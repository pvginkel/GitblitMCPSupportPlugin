/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.search.AutomatonQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.apache.lucene.util.automaton.LevenshteinAutomata;

/**
 * Finds the line of a text that best matches a query, by tokenizing the text
 * with the analyzer it was indexed with and matching the query's terms,
 * phrases and wildcard terms against those tokens.
 *
 * The best line is the one matching the most distinct parts of the query,
 * the earliest of those on a tie. "a AND b" thus prefers a line holding both.
 */
public final class MatchLocator {

    private final List<Matcher> matchers = new ArrayList<>();

    /**
     * A locator for the parts of query on field. Prohibited clauses are
     * ignored, as are parts on other fields and query types it does not know.
     */
    public MatchLocator(Query query, String field) {
        collect(query, field);
    }

    /**
     * The 0-based line of text that best matches the query, or -1 when no
     * line matches any part of it.
     */
    public int findLine(String text, Analyzer analyzer, String field) throws IOException {
        if (matchers.isEmpty()) {
            return -1;
        }

        Tokens tokens = Tokens.of(text, analyzer, field);
        int[] lineStarts = lineStarts(text);

        // Per line, the matchers it satisfies, as a bit per matcher.
        Map<Integer, Integer> hitsByLine = new HashMap<>();
        for (int m = 0; m < matchers.size(); m++) {
            for (int t : matchers.get(m).matches(tokens)) {
                int line = lineOf(lineStarts, tokens.startOffsets[t]);
                Integer bits = hitsByLine.get(line);
                hitsByLine.put(line, (bits == null ? 0 : bits) | (1 << Math.min(m, 30)));
            }
        }

        int best = -1;
        int bestCount = 0;
        for (Map.Entry<Integer, Integer> entry : hitsByLine.entrySet()) {
            int count = Integer.bitCount(entry.getValue());
            int line = entry.getKey();
            if (count > bestCount || (count == bestCount && line < best)) {
                best = line;
                bestCount = count;
            }
        }
        return best;
    }

    private void collect(Query query, String field) {
        if (query instanceof BooleanQuery) {
            for (BooleanClause clause : ((BooleanQuery) query).clauses()) {
                if (!clause.isProhibited()) {
                    collect(clause.getQuery(), field);
                }
            }
        } else if (query instanceof BoostQuery) {
            collect(((BoostQuery) query).getQuery(), field);
        } else if (query instanceof ConstantScoreQuery) {
            collect(((ConstantScoreQuery) query).getQuery(), field);
        } else if (query instanceof TermQuery) {
            TermQuery termQuery = (TermQuery) query;
            if (field.equals(termQuery.getTerm().field())) {
                matchers.add(new TermMatcher(termQuery.getTerm().text()));
            }
        } else if (query instanceof PhraseQuery) {
            PhraseQuery phraseQuery = (PhraseQuery) query;
            if (phraseQuery.getTerms().length > 0 && field.equals(phraseQuery.getTerms()[0].field())) {
                matchers.add(new PhraseMatcher(phraseQuery));
            }
        } else if (query instanceof AutomatonQuery) {
            // Prefix, wildcard, regexp and range terms
            AutomatonQuery automatonQuery = (AutomatonQuery) query;
            if (field.equals(automatonQuery.getField())) {
                matchers.add(new AutomatonMatcher(new CharacterRunAutomaton(automatonQuery.getAutomaton())));
            }
        } else if (query instanceof FuzzyQuery) {
            FuzzyQuery fuzzyQuery = (FuzzyQuery) query;
            if (field.equals(fuzzyQuery.getField())) {
                String text = fuzzyQuery.getTerm().text();
                int prefixLength = Math.min(fuzzyQuery.getPrefixLength(), text.length());
                LevenshteinAutomata automata = new LevenshteinAutomata(
                    text.substring(prefixLength), fuzzyQuery.getTranspositions());
                matchers.add(new AutomatonMatcher(new CharacterRunAutomaton(
                    automata.toAutomaton(fuzzyQuery.getMaxEdits(), text.substring(0, prefixLength)))));
            }
        }
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = starts.get(i);
        }
        return result;
    }

    private static int lineOf(int[] lineStarts, int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);
        return index >= 0 ? index : -index - 2;
    }

    /**
     * The tokens of a text: term, position and start offset of each.
     */
    static final class Tokens {
        final String[] terms;
        final int[] positions;
        final int[] startOffsets;

        private Tokens(List<String> terms, List<Integer> positions, List<Integer> startOffsets) {
            int n = terms.size();
            this.terms = terms.toArray(new String[n]);
            this.positions = new int[n];
            this.startOffsets = new int[n];
            for (int i = 0; i < n; i++) {
                this.positions[i] = positions.get(i);
                this.startOffsets[i] = startOffsets.get(i);
            }
        }

        static Tokens of(String text, Analyzer analyzer, String field) throws IOException {
            List<String> terms = new ArrayList<>();
            List<Integer> positions = new ArrayList<>();
            List<Integer> startOffsets = new ArrayList<>();
            try (TokenStream stream = analyzer.tokenStream(field, text)) {
                CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
                PositionIncrementAttribute increment = stream.addAttribute(PositionIncrementAttribute.class);
                OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
                stream.reset();
                int position = -1;
                while (stream.incrementToken()) {
                    position += increment.getPositionIncrement();
                    terms.add(term.toString());
                    positions.add(position);
                    startOffsets.add(offset.startOffset());
                }
                stream.end();
            }
            return new Tokens(terms, positions, startOffsets);
        }
    }

    private interface Matcher {
        /** Indexes of the tokens where a match starts. */
        List<Integer> matches(Tokens tokens);
    }

    private static final class TermMatcher implements Matcher {
        private final String term;

        TermMatcher(String term) {
            this.term = term;
        }

        @Override
        public List<Integer> matches(Tokens tokens) {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < tokens.terms.length; i++) {
                if (tokens.terms[i].equals(term)) {
                    result.add(i);
                }
            }
            return result;
        }
    }

    private static final class AutomatonMatcher implements Matcher {
        private final CharacterRunAutomaton automaton;

        AutomatonMatcher(CharacterRunAutomaton automaton) {
            this.automaton = automaton;
        }

        @Override
        public List<Integer> matches(Tokens tokens) {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < tokens.terms.length; i++) {
                if (automaton.run(tokens.terms[i])) {
                    result.add(i);
                }
            }
            return result;
        }
    }

    /**
     * Terms at their relative positions; positions skipped by stop words stay
     * skipped. With slop, each term may sit up to slop positions from where
     * it is expected, a looser test than Lucene's total slop.
     */
    private static final class PhraseMatcher implements Matcher {
        private final String[] terms;
        private final int[] positions;
        private final int slop;

        PhraseMatcher(PhraseQuery query) {
            this.terms = new String[query.getTerms().length];
            for (int i = 0; i < terms.length; i++) {
                terms[i] = query.getTerms()[i].text();
            }
            this.positions = query.getPositions();
            this.slop = query.getSlop();
        }

        @Override
        public List<Integer> matches(Tokens tokens) {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < tokens.terms.length; i++) {
                if (tokens.terms[i].equals(terms[0]) && matchesFrom(tokens, i)) {
                    result.add(i);
                }
            }
            return result;
        }

        private boolean matchesFrom(Tokens tokens, int first) {
            int base = tokens.positions[first] - positions[0];
            for (int k = 1; k < terms.length; k++) {
                if (!hasTermNear(tokens, first, terms[k], base + positions[k])) {
                    return false;
                }
            }
            return true;
        }

        private boolean hasTermNear(Tokens tokens, int from, String term, int position) {
            // Tokens are in position order; look around the expected position.
            for (int j = from; j < tokens.terms.length && tokens.positions[j] <= position + slop; j++) {
                if (tokens.positions[j] >= position - slop && tokens.terms[j].equals(term)) {
                    return true;
                }
            }
            if (slop > 0) {
                for (int j = from - 1; j >= 0 && tokens.positions[j] >= position - slop; j--) {
                    if (tokens.terms[j].equals(term)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
