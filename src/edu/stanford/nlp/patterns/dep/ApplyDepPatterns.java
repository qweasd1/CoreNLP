package edu.stanford.nlp.patterns.dep;

import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.tokensregex.TokenSequenceMatcher;
import edu.stanford.nlp.ling.tokensregex.TokenSequencePattern;
import edu.stanford.nlp.patterns.*;
import edu.stanford.nlp.patterns.surface.SurfacePattern;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.semgrex.SemgrexMatcher;
import edu.stanford.nlp.semgraph.semgrex.SemgrexPattern;
import edu.stanford.nlp.stats.TwoDimensionalCounter;
import edu.stanford.nlp.util.CollectionValuedMap;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.Triple;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * Created by sonalg on 11/1/14.
 */
public class ApplyDepPatterns <E extends Pattern>  implements Callable<Pair<TwoDimensionalCounter<Pair<String, String>, E>, CollectionValuedMap<E, Triple<String, Integer, Integer>>>> {
    String label;
    Map<SemgrexPattern, E> patterns;
    List<String> sentids;
    boolean removeStopWordsFromSelectedPhrases;
    boolean removePhrasesWithStopWords;
    ConstantsAndVariables<E> constVars;
    Map<String, DataInstance> sents = null;


    public ApplyDepPatterns(Map<String, DataInstance> sents, List<String> sentids, Map<SemgrexPattern, E> patterns, String label, boolean removeStopWordsFromSelectedPhrases, boolean removePhrasesWithStopWords, ConstantsAndVariables cv) {
      this.sents = sents;
      this.patterns = patterns;
      this.sentids = sentids;
      this.label = label;
      this.removeStopWordsFromSelectedPhrases = removeStopWordsFromSelectedPhrases;
      this.removePhrasesWithStopWords = removePhrasesWithStopWords;
      this.constVars = cv;
    }

    @Override
    public Pair<TwoDimensionalCounter<Pair<String, String>, E>, CollectionValuedMap<E, Triple<String, Integer, Integer>>> call()
      throws Exception {
      // CollectionValuedMap<String, Integer> tokensMatchedPattern = new
      // CollectionValuedMap<String, Integer>();

      TwoDimensionalCounter<Pair<String, String>, E> allFreq = new TwoDimensionalCounter<Pair<String, String>, E>();
      CollectionValuedMap<E, Triple<String, Integer, Integer>> matchedTokensByPat = new CollectionValuedMap<E, Triple<String, Integer, Integer>>();

      for (String sentid : sentids) {
        DataInstance sent = sents.get(sentid);
        List<CoreLabel> tokens = sent.getTokens();
        for (Map.Entry<SemgrexPattern, E> pEn : patterns.entrySet()) {

          if (pEn.getKey() == null)
            throw new RuntimeException("why is the pattern " + pEn + " null?");

          SemanticGraph graph = ((DataInstanceDep) sent).getGraph();
          SemgrexMatcher m = pEn.getKey().matcher(graph);
          //TokenSequenceMatcher m = pEn.getKey().matcher(sent);

//        //Setting this find type can save time in searching - greedy and reluctant quantifiers are not enforced
//        m.setFindType(SequenceMatcher.FindType.FIND_ALL);

          //Higher branch values makes the faster but uses more memory
          //m.setBranchLimit(5);

          while (m.find()) {

            Pair<Integer, Integer> matchedTokensIndex = getMatchedTokensIndex(m, graph);
            int s = matchedTokensIndex.first();
            int e = matchedTokensIndex.second();

            String phrase = "";
            String phraseLemma = "";
            boolean useWordNotLabeled = false;
            boolean doNotUse = false;

            //find if the neighboring words are labeled - if so - club them together
            if(constVars.clubNeighboringLabeledWords) {
              for (int i = s - 1; i >= 0; i--) {
                if (!tokens.get(i).get(constVars.getAnswerClass().get(label)).equals(label)) {
                  s = i + 1;
                  break;
                }
              }
              for (int i = e; i < tokens.size(); i++) {
                if (!tokens.get(i).get(constVars.getAnswerClass().get(label)).equals(label)) {
                  e = i;
                  break;
                }
              }
            }

            //to make sure we discard phrases with stopwords in between, but include the ones in which stop words were removed at the ends if removeStopWordsFromSelectedPhrases is true
            boolean[] addedindices = new boolean[e-s];
            Arrays.fill(addedindices, false);


            for (int i = s; i < e; i++) {
              CoreLabel l = tokens.get(i);
              l.set(PatternsAnnotations.MatchedPattern.class, true);

              if(!l.containsKey(PatternsAnnotations.MatchedPatterns.class) || l.get(PatternsAnnotations.MatchedPatterns.class) == null)
                l.set(PatternsAnnotations.MatchedPatterns.class, new HashSet<Pattern>());

              SurfacePattern pSur = (SurfacePattern) pEn.getValue();
              assert pSur != null : "Why is " + pEn.getValue() + " not present in the index?!";
              assert l.get(PatternsAnnotations.MatchedPatterns.class) != null : "How come MatchedPatterns class is null for the token. The classes in the key set are " + l.keySet();
              l.get(PatternsAnnotations.MatchedPatterns.class).add(pSur);

              for (Map.Entry<Class, Object> ig : constVars.getIgnoreWordswithClassesDuringSelection()
                .get(label).entrySet()) {
                if (l.containsKey(ig.getKey())
                  && l.get(ig.getKey()).equals(ig.getValue())) {
                  doNotUse = true;
                }
              }
              boolean containsStop = containsStopWord(l,
                constVars.getCommonEngWords(), PatternFactory.ignoreWordRegex);
              if (removePhrasesWithStopWords && containsStop) {
                doNotUse = true;
              } else {
                if (!containsStop || !removeStopWordsFromSelectedPhrases) {

                  if (label == null
                    || l.get(constVars.getAnswerClass().get(label)) == null
                    || !l.get(constVars.getAnswerClass().get(label)).equals(
                    label.toString())) {
                    useWordNotLabeled = true;
                  }
                  phrase += " " + l.word();
                  phraseLemma += " " + l.lemma();
                  addedindices[i-s] = true;
                }
              }
            }
            for(int i =0; i < addedindices.length; i++){
              if(i > 0 && i < addedindices.length -1 && addedindices[i-1] == true && addedindices[i] == false && addedindices[i+1] == true){
                doNotUse = true;
                break;
              }
            }
            if (!doNotUse && useWordNotLabeled) {

              matchedTokensByPat.add(pEn.getValue(), new Triple<String, Integer, Integer>(
                sentid, s, e -1 ));
              if (useWordNotLabeled) {
                phrase = phrase.trim();
                phraseLemma = phraseLemma.trim();
                allFreq.incrementCount(new Pair<String, String>(phrase,
                  phraseLemma), pEn.getValue(), 1.0);
              }
            }
          }
        }
      }
      return new Pair<TwoDimensionalCounter<Pair<String, String>, E>, CollectionValuedMap<E, Triple<String, Integer, Integer>>>(allFreq, matchedTokensByPat);


    }

  private Pair<Integer, Integer> getMatchedTokensIndex(SemgrexMatcher m, SemanticGraph graph) {
    //TODO: implement this
    return null;
  }

  boolean  containsStopWord(CoreLabel l, Set<String> commonEngWords, java.util.regex.Pattern ignoreWordRegex) {
      // if(useWordResultCache.containsKey(l.word()))
      // return useWordResultCache.get(l.word());

      if ((commonEngWords.contains(l.lemma()) || commonEngWords.contains(l.word())) || (ignoreWordRegex != null && ignoreWordRegex.matcher(l.lemma()).matches())){
        //|| (ignoreWords !=null && (ignoreWords.contains(l.lemma()) || ignoreWords.contains(l.word())))) {
        // useWordResultCache.putIfAbsent(l.word(), false);
        return true;
      }
      //
      // if (l.word().length() >= minLen4Fuzzy) {
      // try {
      // String matchedFuzzy = NoisyLabelSentences.containsFuzzy(commonEngWords,
      // l.word(), minLen4Fuzzy);
      // if (matchedFuzzy != null) {
      // synchronized (commonEngWords) {
      // commonEngWords.add(l.word());
      // System.out.println("word is " + l.word() + " and matched fuzzy with " +
      // matchedFuzzy);
      // }
      // useWordResultCache.putIfAbsent(l.word(), false);
      // return false;
      // }
      // } catch (Exception e) {
      // e.printStackTrace();
      // System.out.println("Exception " + " while fuzzy matching " + l.word());
      // }
      // }
      // useWordResultCache.putIfAbsent(l.word(), true);
      return false;
    }

  }

