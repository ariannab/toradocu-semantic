package matching;

import com.google.gson.reflect.TypeToken;
import de.jungblut.distance.CosineDistance;
import de.jungblut.glove.GloveRandomAccessReader;
import de.jungblut.math.DoubleVector;
import edu.stanford.nlp.ling.CoreLabel;
import org.toradocu.extractor.DocumentedMethod;
import org.toradocu.extractor.Tag;
import org.toradocu.translator.StanfordParser;
import org.toradocu.util.GsonInstance;
import util.OutputUtil;
import util.SimpleMethodCodeElement;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Created by arianna on 29/05/17.
 *
 * Main component. Contains all the methods to compute the {@code SemantichMatch}es for a given class.
 * This implements the "basic" semantic matching, i.e. the one that uses plain vector sums.
 * Other kinds of matcher will extend this class.
 *
 */
public class SemanticMatcher {

    static boolean stopwordsRemoval;
    static boolean posSelect;
    static boolean tfid;
    static float distanceThreshold;
    static List<String> stopwords;
    public static String className;
    public static String fileName;
    /** Stores all the {@code SemanticMatch}es collected during a test. */
    public static Set<SemanticMatch> semanticMatches;

    SemanticMatcher(
            String className,
            boolean stopwordsRemoval,
            boolean posSelect,
            boolean tfid,
            float distanceThreshold) {

        this.tfid = tfid;
        this.stopwordsRemoval = stopwordsRemoval;
        this.posSelect = posSelect;
        this.distanceThreshold = distanceThreshold;
        this.className = className;
        semanticMatches = new HashSet<SemanticMatch>();

        //TODO very naive list. Not the best to use.
        stopwords =
                new ArrayList<>(
                        Arrays.asList(
                                "true", "false", "the", "a", "if", "for", "be", "this", "do",
                                "not", "of", "only", "already", "specify"));

        if (stopwordsRemoval) fileName = "semantic_" + className;
        else fileName = "semantic_noSW_" + className;

        File file = new File(fileName);
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    Set<DocumentedMethod> readMethodsFromJson(File goalFile){
        try (BufferedReader reader =
                     Files.newBufferedReader(goalFile.toPath())){

            Set<DocumentedMethod> methods = new HashSet<>();
            methods.addAll(
                    GsonInstance.gson()
                            .fromJson(reader, new TypeToken<Set<DocumentedMethod>>() {}.getType()));
            return methods;
        } catch (IOException e) {
            System.exit(1);
        }
        return null;
    }

    /**
     * Takes a goal file of a certain class in order to extract all its {@code DocumentedMethod}s and
     * the list of Java code elements that can be used in the translation.
     *
     * @param db
     * @param goalFile the class goal file
     * @param codeElements the list of Java code elements for the translation
     */
    void runVectorMatch(GloveRandomAccessReader db, File goalFile, Set<SimpleMethodCodeElement> codeElements) throws IOException {
        Set<DocumentedMethod> methods = this.readMethodsFromJson(goalFile);

        for(DocumentedMethod m : methods){
            HashSet<SimpleMethodCodeElement> referredCodeElements = codeElements
                    .stream()
                    .filter(forMethod -> forMethod.getForMethod().equals(m.getSignature()))
                    .collect(Collectors.toCollection(HashSet::new));

            if(m.returnTag() != null){
                String condition = m.returnTag().getCondition().get();
                if(!condition.equals("")) {
                    try {
                        vectorsMatch(db, m.returnTag(), m, referredCodeElements);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            if(!m.throwsTags().isEmpty()){
                for(Tag throwTag : m.throwsTags()){
                    String condition = throwTag.getCondition().get();
                    if(!condition.equals("")) {
                        try {
                            vectorsMatch(db, throwTag, m, referredCodeElements);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
        OutputUtil.exportTojson(false, false);
    }



    /**
     * Computes semantic matching through GloVe vectors.
     *
     * @param tag the tag for which we want to produce a condition translation
     * @param method the method the tag belongs to
     * @param codeElements the code elements that are possible candidates to use in the translation
     * @throws IOException if the GloVe database couldn't be read
     */
    void vectorsMatch(GloveRandomAccessReader db, Tag tag, DocumentedMethod method, Set<SimpleMethodCodeElement> codeElements) throws IOException {
        Set<String> commentWordSet = this.parseComment(tag, method);
        String parsedComment = String.join(" ", commentWordSet).replaceAll("\\s+", " ").trim();
        Map<String, Double> freq = new HashMap<String, Double>();
        CosineDistance cos = new CosineDistance();

        DoubleVector commentVector = getCommentVector(commentWordSet, db);

//      Map<MethodCodeElement, Double> distances = new HashMap<MethodCodeElement, Double>();
        Map<SimpleMethodCodeElement, Double> distances = new HashMap<SimpleMethodCodeElement, Double>();

 //     Set<CodeElement<?>> codeElements = Matcher.codeElementsMatch(method, subject, predicate);
//      Set<CodeElement<?>> codeElements = JavaElementsCollector.collect(method);

        // For each code element, I want to take the vectors of its identifiers (like words componing the method name)
        // and compute the semantic similarity with the predicate (or the whole comment, we'll see)

        if (codeElements != null && !codeElements.isEmpty()) {
            if (tfid) freq = TFIDUtils.computeTFIDF(freq, codeElements);
//            for (CodeElement<?> codeElement : codeElements) {
            for(SimpleMethodCodeElement codeElement : codeElements){
//                if (codeElement instanceof MethodCodeElement) {
                DoubleVector methodVector = getCodeElementVector(db, freq, codeElement);

                if (methodVector != null && commentVector != null) {
                    double dist = cos.measureDistance(methodVector, commentVector);
                    distances.put(codeElement, dist);
                }
            }
            retainMatches(parsedComment, method.getSignature(), tag, distances);
        }
    }


    /**
     * Build the vector representing a code element, made by its IDs camel case-splitted
     *
     * @param db gloVe database
     * @param freq TFID map
     * @param codeElement the code element
     * @return a {@code DoubleVector} representing the code element vector
     * @throws IOException if the database couldn't be read
     */
    private static DoubleVector getCodeElementVector(GloveRandomAccessReader db, Map<String, Double> freq, SimpleMethodCodeElement codeElement) throws IOException {
        int index;
        Set<String> ids = codeElement.getCodeElementIds();
        DoubleVector codeElementVector = null;
        for (String id : ids) {
            String[] camelId = id.split("(?<!^)(?=[A-Z])");
            String joinedId = String.join(" ", camelId).replaceAll("\\s+", " ").toLowerCase().trim();
            index = 0;
            for (CoreLabel lemma : StanfordParser.lemmatize(joinedId)) {
                if (lemma != null) camelId[index] = lemma.lemma();

                index++;
            }
            for (int i = 0; i != camelId.length; i++) {
                if (!tfid || (tfid && freq.get(camelId[i].toLowerCase()) < 0.5)) {
                    DoubleVector v = db.get(camelId[i].toLowerCase());
                    if (stopwordsRemoval && stopwords.contains(camelId[i].toLowerCase())) continue;
                    if (v != null) {
                        if (codeElementVector == null) codeElementVector = v;
                        else codeElementVector = codeElementVector.add(v);
                    }
                }
            }
        }
        return codeElementVector;
    }

    private static DoubleVector getCommentVector(Set<String> wordComment, GloveRandomAccessReader db) throws IOException {
        DoubleVector commentVector = null;
        Iterator<String> wordIterator = wordComment.iterator();
        while(wordIterator.hasNext()){
            String word = wordIterator.next();
            if(word!=null){
                DoubleVector v = db.get(word.toLowerCase());
                if (v != null) {
                    if (commentVector == null) commentVector = v;
                    else commentVector = commentVector.add(v);
                }
            }
        }
        return commentVector;
    }

    protected double computeSim(GloveRandomAccessReader db, String commentT, String codeElemT) throws IOException {
        DoubleVector ctVector = db.get(commentT);
        DoubleVector cetVector = db.get(codeElemT);
        CosineDistance cos = new CosineDistance();
        if(ctVector!=null && cetVector!=null)
            return (1+cos.measureDistance(ctVector, cetVector))/2;

        return 1;
    }


    /**
     * Parse the original tag comment according to the configuration parameters.
     *
     * @param tag the {@code Tag} the comment belongs to
     * @param method the {@code DocumentedMethod} containing the tag
     * @return the parsed comment in form of array of strings (words)
     */
    Set<String> parseComment(Tag tag, DocumentedMethod method) {
        String comment = "";
        if (posSelect) comment = POSUtils.findSubjectPredicate(tag.getComment(), method);
        else comment = tag.getComment();
        comment = comment.replaceAll("[^A-Za-z0-9! ]", "");

        String[] wordComment = comment.split(" ");
        int index = 0;
        List<CoreLabel> lemmas = StanfordParser.lemmatize(comment);
        if (wordComment.length != lemmas.size()) System.out.println("?");
        for (CoreLabel lemma : lemmas) {
            if (lemma != null) wordComment[index] = lemma.lemma();
            index++;
        }

        return removeStopWords(wordComment);
    }

    static Set<String> removeStopWords(String[] words) {
        // Subject often is not useful at all (usually it's the target). Try removing it
        String simpleClassName = className.substring(className.lastIndexOf(".")+1, className.length()).toLowerCase();

        if (stopwordsRemoval) {
            for (int i = 0; i != words.length; i++) {
                if (words[i].equals(simpleClassName) || stopwords.contains(words[i].toLowerCase()))
                    words[i] = "";
            }
        }
        Set<String> wordList = new HashSet<>(Arrays.asList(words));
        wordList.removeAll(Arrays.asList(""));
        return wordList;
    }


    /**
     * Compute and instantiate the {@code SemantiMatch} for a tag.
     *
     * @param parsedComment the parse tag comment
     * @param methodName name of the method the tag belongs to
     * @param tag the {@code Tag}
     * @param distances the computed distance, for every possible code element candidate, from the parsed comment
     */
    void retainMatches(String parsedComment, String methodName, Tag tag, Map<SimpleMethodCodeElement, Double> distances){
        SemanticMatch aMatch = new SemanticMatch(tag, methodName, parsedComment, distanceThreshold);

        // Select as candidates only code elements that have a semantic distance below the chosen threshold.
        if(distanceThreshold!=-1) {
            distances.values().removeIf(new Predicate<Double>() {
                @Override
                public boolean test(Double aDouble) {
                    return aDouble > distanceThreshold;
                }
            });
        }

        LinkedHashMap<SimpleMethodCodeElement, Double> orderedDistances;
        if(this instanceof ConceptualMatcher){
            orderedDistances = distances.entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByValue(Collections.reverseOrder()))
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (e1, e2) -> e1,
                            LinkedHashMap::new
                    ));

        }else
            orderedDistances = distances.entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByValue())
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (e1, e2) -> e1,
                            LinkedHashMap::new
                    ));

        aMatch.setCandidates(orderedDistances);

        if(!aMatch.candidates.isEmpty()) {
            aMatch.computeCorrectness();
            aMatch.computePartialCorrectness();
            semanticMatches.add(aMatch);
        }
    }

}
