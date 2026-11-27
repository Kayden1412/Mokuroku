package com.buddyapps.jepubvocab;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Offline lookup over a compact subset of JMdict. Forms, POS tags, glosses and
 * deinflection conditions come from the precompiled {@code assets/dict.bin}
 * trie, so no text file is parsed and no large form→entry HashMap is allocated
 * at startup. Inflected forms are handled by the Yomitan-derived deinflector.
 */
final class JMDict {

    static final class Entry {
        final String word;
        final String reading;
        final List<String> partsOfSpeech;
        final List<String> glosses;
        final int conditions;
        final boolean contentWord;

        Entry(String word, String reading, String[] pos, String[] glosses) {
            this.word = word;
            this.reading = reading;
            this.partsOfSpeech = Arrays.asList(pos);
            this.glosses = Arrays.asList(glosses);
            this.conditions = conditionsFor(pos);
            this.contentWord = hasContentPos(pos);
        }
    }

    static final class Hit {
        final Entry entry;
        final String matched;
        final List<String> trace;

        Hit(Entry entry, String matched, List<String> trace) {
            this.entry = entry;
            this.matched = matched;
            this.trace = trace;
        }
    }

    static final class InflectHit {
        final String lemma;
        final int steps;
        final int rank;

        InflectHit(String lemma, int steps, int rank) {
            this.lemma = lemma;
            this.steps = steps;
            this.rank = rank;
        }
    }

    private static DictBin dict;
    private static boolean attempted;

    private JMDict() { }

    static synchronized void load(Context c) {
        if (dict != null || attempted) return;
        attempted = true;
        dict = DictBin.load(c);
    }

    static DictBin dict() {
        return dict;
    }

    static boolean contains(String form) {
        if (dict == null || form == null) return false;
        return dict.isWord(dict.lookup(form));
    }

    static boolean isContentWord(String form) {
        if (dict == null || form == null) return false;
        int node = dict.lookup(form);
        if (node < 0) return false;
        return dict.isContentEntry(dict.entryFor(node));
    }

    /**
     * Resolves an inflected surface form to its lowest-cost dictionary base form
     * (preferring fewer deinflection steps, then higher Jiten frequency), or
     * returns null when no valid inflected verb/adjective matches.
     */
    static InflectHit bestInflection(String surface) {
        if (dict == null || surface == null || surface.length() < 2) return null;
        final DictBin d = dict;
        final InflectHit[] best = new InflectHit[1];
        final double[] bestScore = new double[]{Double.MAX_VALUE};
        Deinflector.deinflectSteps(surface, new Deinflector.StepVisitor() {
            @Override public void visit(String candidate, int conditions, int steps) {
                int node = d.lookup(candidate);
                if (node < 0) return;
                int eid = d.entryFor(node);
                if (eid < 0 || (d.entryConditions(eid) & conditions) == 0) return;
                int rank = JitenRank.rank(candidate);
                double score = steps * 2.5 + Math.log(rank + 2.0);
                if (score < bestScore[0]) {
                    bestScore[0] = score;
                    best[0] = new InflectHit(candidate, steps, rank);
                }
            }
        });
        return best[0];
    }

    /**
     * Resolves a surface token to its dictionary base form for similarity
     * comparison, or returns null if JMdict classifies it purely as a
     * non-content word (pronoun, particle, conjunction, interjection,
     * auxiliary, copula, prefix/suffix, numeric). Unknown tokens stay as-is.
     */
    static String similarityForm(String word) {
        if (dict == null || word == null || word.isEmpty()) return word;
        List<Hit> hits = exact(word);
        if (hits.isEmpty()) return word;
        for (Hit h : hits) {
            if (h.entry.contentWord) return h.entry.word;
        }
        return null;
    }

    private static boolean hasContentPos(String[] pos) {
        if (pos == null || pos.length == 0) return true;
        for (String p : pos) {
            if (p.equals("particle") || p.equals("conjunction")
                    || p.equals("interjection (kandoushi)") || p.equals("copula")
                    || p.equals("pronoun") || p.equals("prefix") || p.equals("suffix")
                    || p.equals("numeric") || p.startsWith("auxiliary")
                    || p.startsWith(" ")) {
                continue;
            }
            return true;
        }
        return false;
    }

    static List<Hit> lookup(String text) {
        if (dict == null || text == null || text.isEmpty())
            return new ArrayList<Hit>();
        List<Hit> hits = exact(text);
        if (hits.isEmpty() && text.codePointCount(0, text.length()) > 2) {
            int[] cps = text.codePoints().toArray();
            int start = Math.min(cps.length - 1, 12);
            for (int len = start; len >= 2 && hits.isEmpty(); len--) {
                String candidate = new String(cps, 0, len);
                hits = exact(candidate);
            }
        }
        return hits;
    }

    private static List<Hit> exact(String text) {
        ArrayList<Hit> hits = new ArrayList<Hit>();
        HashSet<String> seen = new HashSet<String>();
        for (Deinflector.Result r : Deinflector.deinflect(text)) {
            Entry entry = entryFor(r.text);
            if (entry == null) continue;
            if (r.conditions != DeinflectorRules.NONE
                    && (entry.conditions & r.conditions) == 0) continue;
            if (!seen.add(entry.word + "\u0000" + r.text)) continue;
            hits.add(new Hit(entry, r.text, r.trace));
        }
        return hits;
    }

    /** Decodes one packed JMdict entry from the binary trie table. */
    private static Entry entryFor(String form) {
        if (dict == null || form == null) return null;
        int node = dict.lookup(form);
        if (node < 0) return null;
        String[] fields = dict.entryFields(dict.entryFor(node));
        if (fields == null || fields.length < 4) return null;
        String[] pos = fields[2].isEmpty() ? new String[0] : fields[2].split(",");
        String[] glosses = fields[3].split("\u001f", -1);
        return new Entry(fields[0], fields[1], pos, glosses);
    }

    private static int conditionsFor(String[] pos) {
        int c = 0;
        for (String p : pos) {
            if (p.startsWith("Ichidan verb - zuru verb")) c |= DeinflectorRules.VZ;
            else if (p.startsWith("Ichidan verb")) c |= DeinflectorRules.V1;
            else if (p.startsWith("Godan verb")) c |= DeinflectorRules.V5;
            else if (p.startsWith("Kuru verb")) c |= DeinflectorRules.VK;
            else if (p.startsWith("suru verb") || p.startsWith("su verb")
                    || p.contains("aux. verb suru") || p.equals("copula")) c |= DeinflectorRules.VS;
            else if (p.startsWith("adjective (keiyoushi)")) c |= DeinflectorRules.ADJ_I;
            else if (p.contains("verb")) c |= DeinflectorRules.V;
        }
        return c;
    }
}
