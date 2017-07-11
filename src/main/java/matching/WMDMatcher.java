package matching;

import com.crtomirmajer.wmd4j.WordMovers;
import edu.stanford.nlp.ling.CoreLabel;
import org.deeplearning4j.models.embeddings.loader.WordVectorSerializer;
import org.deeplearning4j.models.embeddings.wordvectors.WordVectors;
import org.deeplearning4j.models.embeddings.wordvectors.WordVectorsImpl;
import org.toradocu.extractor.DocumentedMethod;
import org.toradocu.extractor.Tag;
import org.toradocu.translator.StanfordParser;
import util.OutputUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by arianna on 10/07/17.
 */
public class WMDMatcher extends SemanticMatcher {
    public WMDMatcher(String className, boolean stopwordsRemoval, boolean posSelect, boolean tfid, float distanceThreshold) {
        super(className, stopwordsRemoval, posSelect, tfid, distanceThreshold);
    }

    public void runWmdMatch(File goalFile, Set<SimpleMethodCodeElement> codeElements){
        Set<DocumentedMethod> methods = readMethodsFromJson(goalFile);

        ClassLoader classLoader = getClass().getClassLoader();
//        File file = new File(classLoader.getResource("GoogleNews-vectors-negative300.bin.gz").getFile());
//        WordVectors vectors = WordVectorSerializer.loadGoogleModel(file, true);
        File file = new File("/home/arianna/Scaricati/glove-master/target/glove.6B.300d.txt");
        WordVectors vectors = null;
        try {
            vectors = WordVectorSerializer.loadTxtVectors(file);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        WordMovers wm = WordMovers.Builder().wordVectors(vectors).build();

        for(DocumentedMethod m : methods){
            HashSet<SimpleMethodCodeElement> referredCodeElements = codeElements
                    .stream()
                    .filter(forMethod -> forMethod.getForMethod().equals(m.getSignature()))
                    .collect(Collectors.toCollection(HashSet::new));

            if(m.returnTag() != null){
                String condition = m.returnTag().getCondition().get();
                if(!condition.equals("")) {
                    wmdMatch(wm, m.returnTag(), m, referredCodeElements);
                }
            }
            if(!m.throwsTags().isEmpty()){
                for(Tag throwTag : m.throwsTags()){
                    String condition = throwTag.getCondition().get();
                    if(!condition.equals("")) {
                        wmdMatch(wm, throwTag, m, referredCodeElements);
                    }
                }
            }
        }
        try {
            OutputUtil.exportTojson(true, false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    void wmdMatch(WordMovers wm, Tag tag, DocumentedMethod method, Set<SimpleMethodCodeElement> codeElements){
        Map<SimpleMethodCodeElement, Double> distances = new HashMap<SimpleMethodCodeElement, Double>();
        Set<String> commentWordSet = super.parseComment(tag, method);
        String parsedComment = String.join(" ", commentWordSet).replaceAll("\\s+", " ").trim();
        if (codeElements != null && !codeElements.isEmpty()) {
            for(SimpleMethodCodeElement codeElement : codeElements){
                Set<String> ids = codeElement.getCodeElementIds();
                for (String id : ids) {
                    String[] camelId = id.split("(?<!^)(?=[A-Z])");
                    String joinedId = String.join(" ", camelId).replaceAll("\\s+", " ").trim().toLowerCase();
                    int index = 0;
                    for (CoreLabel lemma : StanfordParser.lemmatize(joinedId)) {
                        if (lemma != null) camelId[index] = lemma.lemma();
                        index++;
                    }
                    Set<String> codeElementWordSet = removeStopWords(camelId);
                    joinedId = String.join(" ", codeElementWordSet).replaceAll("\\s+", " ").trim().toLowerCase();
                    double dist = 10;
                    try{
                        dist = wm.distance(parsedComment, joinedId);
                    }catch(NoSuchElementException e){
                        //do nothing
                    }
                    distances.put(codeElement, dist);
                }
            }
        }
        retainMatches(parsedComment, method.getName(), tag, distances);
    }
}
