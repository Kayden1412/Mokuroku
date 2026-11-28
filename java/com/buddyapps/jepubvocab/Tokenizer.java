package com.buddyapps.jepubvocab;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rank-scored, POS-aware beam tokenizer over a compact flat-array trie of
 * JMdict and Jiten frequency words, with stem-gated deinflection, colloquial
 * contraction expansion and furigana hints. Contiguous Japanese runs are
 * segmented globally rather than greedily, so punctuation, quotes and middle
 * dots remain hard boundaries and conjugated words collapse to base lemmas.
 */
final class Tokenizer {

    interface TokenConsumer {
        void onToken(String word, int startOffset);
    }

    private static final byte FLAG_WORD = 1;
    private static final byte FLAG_STEM = 2;

    // Coarse POS classes stored in dict.bin. Must match gen_dict_bin.py.
    private static final byte POS_UNKNOWN = 0;
    private static final byte POS_NOUN = 1;
    private static final byte POS_VERB = 2;
    private static final byte POS_ADJ = 3;
    private static final byte POS_PARTICLE = 4;
    private static final byte POS_AUX = 5;
    private static final byte POS_ADVERB = 6;
    private static final byte POS_CONJ = 7;
    private static final byte POS_PRONOUN = 8;
    private static final byte POS_AFFIX = 9;
    private static final byte POS_INTERJ = 10;
    private static final byte POS_NUM = 11;
    private static final byte POS_EXPR = 12;
    private static final int POS_CLASSES = 13;

    // Fallback for words without a Jiten rank (full list peaks around 412k).
    private static final int DEFAULT_RANK = 413000;
    private static final int MAX_WORD_LEN = 16;
    private static final int MAX_KANJI_FALLBACK = 8;
    private static final int MAX_INFLECT_CACHE = 32768;
    private static final int BEAM = 2;

    private static final double BASE_WORD_COST = 4.5;
    private static final double UNK_KANJI_BASE = 22.0;
    private static final double UNK_KANJI_STEP = 6.0;
    private static final double UNK_KATA_BASE = 20.0;
    private static final double UNK_KATA_STEP = 2.0;
    private static final double UNK_CHAR_COST = 24.0;
    private static final double CONTRACTION_COST = 0.8;
    private static final double ELONGATION_COST = 0.6;
    private static final double HINT_DIRECT_BONUS = 1.2;
    private static final double HINT_INFLECT_BONUS = 0.5;

    static final Set<String> STOP = new HashSet<String>(Arrays.asList(
            "は", "が", "を", "に", "で", "と", "も", "の", "へ", "や", "か", "ね", "よ",
            "わ", "お", "ご", "この", "その", "あの", "これ", "それ", "あれ", "ここ",
            "そこ", "どこ", "だれ", "なん", "なに", "です", "ます", "ました", "でした",
            "する", "した", "して", "ある", "いる", "なる", "なった", "いう", "ない",
            "そして", "しかし", "また", "それで", "だから", "ので", "から", "まで",
            "より", "など", "という", "そう", "こう", "どう", "どんな", "まず", "もし",
            "ほか", "場合", "私", "彼女", "彼", "たち", "もの", "こと", "とき",
            "って", "んだ", "てる", "てた", "じゃ", "では", "には", "とは", "にも",
            "だけ", "ばかり", "ほど", "くらい", "ぐらい", "のに", "けど", "けれど",
            "けれども", "だが", "でも", "ため", "よう", "はず", "わけ", "一"));

    // Longest suffixes first; expansion is fed back through the deinflector.
    private static final String[] CONT_SUFFIX = {
            "ちゃった", "じゃった", "ちゃう", "じゃう", "てる", "てた", "でる", "でた",
            "なきゃ", "なくちゃ", "なくては", "とった", "どいた", "とく", "どく",
            "っす", "すか"
    };
    private static final String[] CONT_EXPAND = {
            "てしまった", "でしまった", "てしまう", "でしまう", "ている", "ていた",
            "でいる", "でいた", "ない", "ない", "ない", "ておいた", "でおいた",
            "ておく", "でおく", "です", "ですか"
    };

    private static final class Trie {
        int[] root;
        char[] ch;
        int[] first;
        int[] next;
        byte[] flags;
        byte[] pos;
        int[] rank;
        int size = 1; // node 0 is unused sentinel

        Trie(int capacity) {
            root = new int[65536];
            Arrays.fill(root, -1);
            ch = new char[capacity];
            first = new int[capacity];
            next = new int[capacity];
            flags = new byte[capacity];
            pos = new byte[capacity];
            rank = new int[capacity];
            Arrays.fill(first, -1);
            Arrays.fill(next, -1);
        }

        /** Zero-copy view over the precompiled trie in assets/dict.bin. */
        Trie(DictBin d) {
            root = d.root;
            ch = d.ch;
            first = d.firstChild;
            next = d.nextSibling;
            flags = d.flags;
            pos = d.pos;
            rank = d.rank;
        }

        private void ensureCapacity(int needed) {
            if (needed <= ch.length) return;
            int cap = ch.length + (ch.length >> 1);
            if (cap < needed) cap = needed;
            int oldLen = ch.length;
            ch = Arrays.copyOf(ch, cap);
            first = Arrays.copyOf(first, cap);
            next = Arrays.copyOf(next, cap);
            flags = Arrays.copyOf(flags, cap);
            pos = Arrays.copyOf(pos, cap);
            rank = Arrays.copyOf(rank, cap);
            Arrays.fill(first, oldLen, cap, -1);
            Arrays.fill(next, oldLen, cap, -1);
        }

        void insert(String word, int wordRank, boolean isWord, boolean isStem) {
            int len = word.length();
            if (len == 0) return;
            char c0 = word.charAt(0);
            int node = root[c0];
            if (node < 0) {
                node = alloc(c0);
                root[c0] = node;
            }
            for (int i = 1; i < len; i++) {
                char c = word.charAt(i);
                int child = first[node];
                while (child >= 0 && ch[child] != c) child = next[child];
                if (child < 0) {
                    child = alloc(c);
                    next[child] = first[node];
                    first[node] = child;
                }
                node = child;
            }
            if (isWord) {
                flags[node] |= FLAG_WORD;
                if (rank[node] == 0 || wordRank < rank[node]) {
                    rank[node] = wordRank;
                }
            }
            if (isStem) flags[node] |= FLAG_STEM;
        }

        private int alloc(char c) {
            ensureCapacity(size + 1);
            int id = size++;
            ch[id] = c;
            first[id] = -1;
            next[id] = -1;
            flags[id] = 0;
            pos[id] = 0;
            rank[id] = 0;
            return id;
        }

        void trim() {
            ch = Arrays.copyOf(ch, size);
            first = Arrays.copyOf(first, size);
            next = Arrays.copyOf(next, size);
            flags = Arrays.copyOf(flags, size);
            pos = Arrays.copyOf(pos, size);
            rank = Arrays.copyOf(rank, size);
        }

        int lookup(String word) {
            if (word == null || word.isEmpty()) return -1;
            int node = root[word.charAt(0)];
            if (node < 0) return -1;
            for (int i = 1; i < word.length(); i++) {
                char c = word.charAt(i);
                int child = first[node];
                while (child >= 0 && ch[child] != c) child = next[child];
                if (child < 0) return -1;
                node = child;
            }
            return node;
        }
    }

    private static volatile Trie trie;
    private static final HashMap<String, JMDict.InflectHit> INFLECT_CACHE =
            new HashMap<String, JMDict.InflectHit>();
    private static final JMDict.InflectHit NO_HIT = new JMDict.InflectHit(null, 0, 0);

    private static final double[][] TRANS = new double[POS_CLASSES][POS_CLASSES];
    private static final HashMap<Character, int[]> CONT_RULES =
            new HashMap<Character, int[]>();

    static {
        // Positive = discouraged transition, negative = preferred.
        t(POS_NOUN, POS_PARTICLE, -0.35);
        t(POS_VERB, POS_PARTICLE, -0.35);
        t(POS_ADJ, POS_PARTICLE, -0.35);
        t(POS_PRONOUN, POS_PARTICLE, -0.30);
        t(POS_NUM, POS_PARTICLE, -0.20);
        t(POS_PARTICLE, POS_PARTICLE, 0.25);

        t(POS_VERB, POS_AUX, -0.50);
        t(POS_ADJ, POS_AUX, -0.45);
        t(POS_NOUN, POS_AUX, 0.20);
        t(POS_AUX, POS_AUX, -0.10);

        t(POS_PARTICLE, POS_VERB, -0.20);
        t(POS_PARTICLE, POS_ADJ, -0.15);
        t(POS_PARTICLE, POS_NOUN, -0.10);

        t(POS_VERB, POS_NOUN, 0.15);
        t(POS_ADJ, POS_NOUN, -0.15);
        t(POS_ADVERB, POS_VERB, -0.30);
        t(POS_ADVERB, POS_ADJ, -0.30);
        t(POS_ADVERB, POS_NOUN, -0.10);

        t(POS_AFFIX, POS_NOUN, -0.25);
        t(POS_AFFIX, POS_VERB, -0.20);
        t(POS_NOUN, POS_AFFIX, -0.25);
        t(POS_VERB, POS_AFFIX, -0.20);

        t(POS_NUM, POS_NOUN, -0.20);
        t(POS_NUM, POS_NUM, -0.10);
        t(POS_PRONOUN, POS_NOUN, 0.10);

        t(POS_CONJ, POS_NOUN, -0.10);
        t(POS_CONJ, POS_VERB, -0.10);
        t(POS_INTERJ, POS_NOUN, 0.10);
        t(POS_INTERJ, POS_VERB, 0.10);

        HashMap<Character, ArrayList<Integer>> grouped =
                new HashMap<Character, ArrayList<Integer>>();
        for (int i = 0; i < CONT_SUFFIX.length; i++) {
            String s = CONT_SUFFIX[i];
            char tail = s.charAt(s.length() - 1);
            ArrayList<Integer> list = grouped.get(tail);
            if (list == null) {
                list = new ArrayList<Integer>();
                grouped.put(tail, list);
            }
            list.add(i);
        }
        for (Map.Entry<Character, ArrayList<Integer>> e : grouped.entrySet()) {
            ArrayList<Integer> list = e.getValue();
            int[] arr = new int[list.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
            CONT_RULES.put(e.getKey(), arr);
        }
    }

    private static void t(int prev, int cur, double cost) {
        TRANS[prev][cur] = cost;
    }

    private Tokenizer() { }

    static void ensureReady(Context c) {
        if (trie != null) return;
        synchronized (Tokenizer.class) {
            if (trie != null) return;
            JMDict.load(c);
            JMNedict.load(c);
            JitenRank.load(c);
            DictBin d = JMDict.dict();
            if (d != null) {
                trie = new Trie(d);
            } else {
                // No precompiled dictionary: leave the tokenizer idle rather
                // than spending seconds parsing text files on the UI thread.
                Trie empty = new Trie(1);
                empty.trim();
                trie = empty;
            }
        }
    }

    static boolean isKanjiStart(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || c == '\u3006' || c == '\u3007';
    }

    static boolean isKanji(char c) {
        return isKanjiStart(c) || c == '\u3005';
    }

    static boolean isHira(char c) {
        return c >= 0x3040 && c <= 0x309F;
    }

    static boolean isKata(char c) {
        return ((c >= 0x30A1 && c <= 0x30FF) && c != '\u30FB')
                || c == '\u30FC' || c == '\u309B' || c == '\u309C';
    }

    static boolean isJapanese(char c) {
        return isKanji(c) || isHira(c) || isKata(c);
    }

    static boolean isKeepableWord(String word) {
        if (word == null || word.isEmpty()) return false;
        if (STOP.contains(word) || JMNedict.isName(word)) return false;
        if (word.length() >= 2) return true;
        char c = word.charAt(0);
        return isKanjiStart(c) && JMDict.isContentWord(word);
    }

    /**
     * Furigana hints collected from EPUB ruby markup. The base string is the
     * surface text shown in the book; the reading helps choose between
     * homographs and keeps ruby bases from being split or glued.
     */
    static final class RubyHints {
        private final HashMap<Character, String[]> byFirst;
        private final HashMap<String, String> readings;

        private RubyHints(HashMap<Character, String[]> byFirst,
                          HashMap<String, String> readings) {
            this.byFirst = byFirst;
            this.readings = readings;
        }

        static RubyHints of(Map<String, String> map) {
            if (map == null || map.isEmpty()) return null;
            HashMap<Character, ArrayList<String>> grouped =
                    new HashMap<Character, ArrayList<String>>();
            HashMap<String, String> readings = new HashMap<String, String>();
            for (Map.Entry<String, String> e : map.entrySet()) {
                String base = e.getKey();
                if (base == null || base.isEmpty()) continue;
                char c = base.charAt(0);
                ArrayList<String> list = grouped.get(c);
                if (list == null) {
                    list = new ArrayList<String>();
                    grouped.put(c, list);
                }
                list.add(base);
                String reading = e.getValue();
                if (reading != null && !reading.isEmpty()) readings.put(base, reading);
            }
            if (grouped.isEmpty()) return null;
            HashMap<Character, String[]> byFirst = new HashMap<Character, String[]>();
            for (Map.Entry<Character, ArrayList<String>> e : grouped.entrySet()) {
                byFirst.put(e.getKey(), e.getValue().toArray(new String[0]));
            }
            return new RubyHints(byFirst, readings);
        }

        /** Fills per-position longest matching base length and its reading. */
        void fill(String text, int start, int end, int[] lens, String[] reads) {
            for (int i = start; i < end; i++) {
                String[] cands = byFirst.get(text.charAt(i));
                if (cands == null) continue;
                int best = 0;
                String bestReading = null;
                for (String base : cands) {
                    int len = base.length();
                    if (len <= best || i + len > end) continue;
                    if (!text.regionMatches(i, base, 0, len)) continue;
                    best = len;
                    bestReading = readings.get(base);
                }
                if (best > 0) {
                    lens[i - start] = best;
                    reads[i - start] = bestReading;
                }
            }
        }
    }

    static void tokenize(String text, TokenConsumer consumer) {
        tokenize(text, null, consumer);
    }

    static void tokenize(String text, RubyHints hints, TokenConsumer consumer) {
        if (text == null || text.isEmpty()) return;
        Trie t = trie;
        if (t == null) return;
        int len = text.length();
        int i = 0;
        while (i < len) {
            if (!isJapanese(text.charAt(i))) {
                i++;
                continue;
            }
            int j = i + 1;
            while (j < len && isJapanese(text.charAt(j))) j++;
            segmentSpan(t, text, i, j, hints, consumer);
            i = j;
        }
    }

    private static void segmentSpan(Trie t, String text, int start, int end,
                                    RubyHints hints, TokenConsumer consumer) {
        int n = end - start;
        if (n <= 0) return;

        int[] hintLen = new int[n];
        String[] hintRead = new String[n];
        if (hints != null) hints.fill(text, start, end, hintLen, hintRead);

        // Prefix count of emphatic marks, so elongation spans are found in O(1).
        int[] elongPrefix = new int[n + 1];
        for (int k = 0; k < n; k++) {
            char c = text.charAt(start + k);
            boolean mark = c == '\u30FC'
                    || (c == '\u3063' && k + 1 < n && text.charAt(start + k + 1) == '\u3063');
            elongPrefix[k + 1] = elongPrefix[k] + (mark ? 1 : 0);
        }

        double[][] dp = new double[BEAM][n + 1];
        byte[][] cls = new byte[BEAM][n + 1];
        int[][] prevPos = new int[BEAM][n + 1];
        byte[][] prevSlot = new byte[BEAM][n + 1];
        String[][] lemmaTok = new String[BEAM][n + 1];
        for (int s = 0; s < BEAM; s++) {
            Arrays.fill(dp[s], Double.MAX_VALUE);
            Arrays.fill(prevPos[s], -1);
            Arrays.fill(prevSlot[s], (byte) -1);
        }
        dp[0][0] = 0.0;
        cls[0][0] = -1;

        for (int i = 0; i < n; i++) {
            for (int slot = 0; slot < BEAM; slot++) {
                double baseCost = dp[slot][i];
                if (baseCost >= Double.MAX_VALUE / 2) continue;
                byte prevClass = cls[slot][i];
                int absI = start + i;
                char ch0 = text.charAt(absI);
                int maxEnd = Math.min(n, i + MAX_WORD_LEN);

                // 1. Direct dictionary edges via the flat primitive trie.
                int maxStemRel = -1;
                int node = t.root[ch0];
                for (int j = i + 1; node >= 0; ) {
                    byte f = t.flags[node];
                    if ((f & FLAG_STEM) != 0) maxStemRel = j;
                    if ((f & FLAG_WORD) != 0) {
                        byte edgeClass = t.pos[node];
                        int r = t.rank[node];
                        if (r <= 0) r = DEFAULT_RANK;
                        double c = baseCost + BASE_WORD_COST + Math.log(r + 2.0)
                                + transition(prevClass, edgeClass);
                        if (hintLen[i] == j - i) c -= HINT_DIRECT_BONUS;
                        relax(dp, cls, prevPos, prevSlot, lemmaTok, j, c, i, slot,
                                edgeClass, null);
                    }
                    if (j >= maxEnd) break;
                    char nextChar = text.charAt(start + j);
                    int child = t.first[node];
                    while (child >= 0 && t.ch[child] != nextChar) child = t.next[child];
                    node = child;
                    j++;
                }

                // 2. Stem-gated inflected edges via cached Deinflector.
                if (maxStemRel >= 0) {
                    int hiraLimit = Math.min(maxEnd, maxStemRel + 10);
                    int hiraEnd = maxStemRel;
                    while (hiraEnd < hiraLimit && isHira(text.charAt(start + hiraEnd))) {
                        hiraEnd++;
                    }
                    for (int j = hiraEnd; j >= i + 2; j--) {
                        if (!isHira(text.charAt(start + j - 1))) continue;
                        JMDict.InflectHit hit = cachedInflection(text.substring(absI, start + j));
                        if (hit != null) {
                            byte edgeClass = JMDict.posClassOf(hit.lemma);
                            double c = baseCost + BASE_WORD_COST + Math.log(hit.rank + 2.0)
                                    + 1.5 + 1.0 * hit.steps
                                    + transition(prevClass, edgeClass);
                            if (hintLen[i] > 0 && hintRead[i] != null
                                    && j - i >= hintLen[i]
                                    && JMDict.readingMatches(hit.lemma, hintRead[i])) {
                                c -= HINT_INFLECT_BONUS;
                            }
                            relax(dp, cls, prevPos, prevSlot, lemmaTok, j, c, i, slot,
                                    edgeClass, hit.lemma);
                        }
                    }
                }

                // 3. Colloquial contraction edges (食べてる -> 食べる, 行かなきゃ -> 行く).
                // Only after a real dictionary stem: contractions follow verbs.
                if (maxStemRel >= 0) {
                    int contEnd = Math.min(n, i + MAX_WORD_LEN + 4);
                    for (int j = i + 2; j <= contEnd; j++) {
                        int[] rules = CONT_RULES.get(text.charAt(start + j - 1));
                        if (rules == null) continue;
                        for (int ri = 0; ri < rules.length; ri++) {
                            int r = rules[ri];
                            String suffix = CONT_SUFFIX[r];
                            int sl = suffix.length();
                            int prefixEnd = start + j - sl;
                            if (prefixEnd - start < Math.max(1, maxStemRel)) continue;
                            if (!text.regionMatches(prefixEnd, suffix, 0, sl)) continue;
                            String expanded = text.substring(absI, prefixEnd) + CONT_EXPAND[r];
                            JMDict.InflectHit hit = cachedInflection(expanded);
                            if (hit == null) continue;
                            byte edgeClass = JMDict.posClassOf(hit.lemma);
                            double c = baseCost + BASE_WORD_COST + Math.log(hit.rank + 2.0)
                                    + CONTRACTION_COST + 1.0 * hit.steps
                                    + transition(prevClass, edgeClass);
                            if (hintLen[i] > 0 && hintRead[i] != null
                                    && j - i >= hintLen[i]
                                    && JMDict.readingMatches(hit.lemma, hintRead[i])) {
                                c -= HINT_INFLECT_BONUS;
                            }
                            relax(dp, cls, prevPos, prevSlot, lemmaTok, j, c, i, slot,
                                    edgeClass, hit.lemma);
                        }
                    }
                }

                // 4. Emphatic elongation fallback (すごーい -> すごい).
                for (int j = i + 2; j <= maxEnd; j++) {
                    if (elongPrefix[j] - elongPrefix[i] == 0) continue;
                    String surface = text.substring(absI, start + j);
                    String norm = normalizeElongation(surface);
                    if (norm.equals(surface)) continue;
                    String lemma = null;
                    byte edgeClass = POS_UNKNOWN;
                    int r = DEFAULT_RANK;
                    int node2 = t.lookup(norm);
                    if (node2 >= 0 && (t.flags[node2] & FLAG_WORD) != 0) {
                        lemma = norm;
                        edgeClass = t.pos[node2];
                        r = t.rank[node2] > 0 ? t.rank[node2] : DEFAULT_RANK;
                    } else {
                        JMDict.InflectHit hit = cachedInflection(norm);
                        if (hit == null) continue;
                        lemma = hit.lemma;
                        edgeClass = JMDict.posClassOf(hit.lemma);
                        r = hit.rank;
                    }
                    double c = baseCost + BASE_WORD_COST + Math.log(r + 2.0)
                            + ELONGATION_COST + transition(prevClass, edgeClass);
                    if (hintLen[i] == j - i) c -= HINT_DIRECT_BONUS;
                    relax(dp, cls, prevPos, prevSlot, lemmaTok, j, c, i, slot,
                            edgeClass, lemma);
                }

                // 5. Unknown / OOV fallback edges.
                if (isKanjiStart(ch0)) {
                    double c1 = baseCost + UNK_KANJI_BASE
                            + transition(prevClass, POS_NOUN);
                    relax(dp, cls, prevPos, prevSlot, lemmaTok, i + 1, c1, i, slot,
                            POS_NOUN, null);
                    int kMax = Math.min(n, i + MAX_KANJI_FALLBACK);
                    for (int j = i + 2; j <= kMax; j++) {
                        if (!isKanji(text.charAt(start + j - 1))) break;
                        double ck = baseCost + UNK_KANJI_BASE + UNK_KANJI_STEP * (j - i - 1)
                                + transition(prevClass, POS_NOUN);
                        relax(dp, cls, prevPos, prevSlot, lemmaTok, j, ck, i, slot,
                                POS_NOUN, null);
                    }
                } else if (isKata(ch0)) {
                    double c1 = baseCost + UNK_CHAR_COST
                            + transition(prevClass, POS_NOUN);
                    relax(dp, cls, prevPos, prevSlot, lemmaTok, i + 1, c1, i, slot,
                            POS_NOUN, null);
                    for (int j = i + 2; j <= maxEnd; j++) {
                        if (!isKata(text.charAt(start + j - 1))) break;
                        double ck = baseCost + UNK_KATA_BASE + UNK_KATA_STEP * (j - i - 1)
                                + transition(prevClass, POS_NOUN);
                        relax(dp, cls, prevPos, prevSlot, lemmaTok, j, ck, i, slot,
                                POS_NOUN, null);
                    }
                } else {
                    double c1 = baseCost + UNK_CHAR_COST
                            + transition(prevClass, POS_UNKNOWN);
                    relax(dp, cls, prevPos, prevSlot, lemmaTok, i + 1, c1, i, slot,
                            POS_UNKNOWN, null);
                }
            }
        }

        int bestSlot = dp[1][n] < dp[0][n] ? 1 : 0;
        if (dp[bestSlot][n] >= Double.MAX_VALUE / 2) bestSlot = 0;

        // Reconstruct the winning path in reverse, then emit in forward order.
        int count = 0;
        int[] edgeStarts = new int[n];
        int[] edgeEnds = new int[n];
        String[] edgeLemmas = new String[n];
        int cur = n;
        int slot = bestSlot;
        while (cur > 0) {
            int p = prevPos[slot][cur];
            if (p < 0) break;
            edgeStarts[count] = p;
            edgeEnds[count] = cur;
            edgeLemmas[count] = lemmaTok[slot][cur];
            int ps = prevSlot[slot][cur];
            cur = p;
            slot = ps;
            count++;
        }
        for (int idx = count - 1; idx >= 0; idx--) {
            int s = edgeStarts[idx];
            int e = edgeEnds[idx];
            String word = edgeLemmas[idx] != null
                    ? edgeLemmas[idx]
                    : text.substring(start + s, start + e);
            if (!isKeepableWord(word)) continue;
            consumer.onToken(word, start + s);
        }
    }

    private static void relax(double[][] dp, byte[][] cls, int[][] prevPos,
                              byte[][] prevSlot, String[][] lemmaTok,
                              int j, double cost, int i, int slot,
                              byte edgeClass, String lemma) {
        if (cost < dp[0][j]) {
            dp[1][j] = dp[0][j];
            cls[1][j] = cls[0][j];
            prevPos[1][j] = prevPos[0][j];
            prevSlot[1][j] = prevSlot[0][j];
            lemmaTok[1][j] = lemmaTok[0][j];
            dp[0][j] = cost;
            cls[0][j] = edgeClass;
            prevPos[0][j] = i;
            prevSlot[0][j] = (byte) slot;
            lemmaTok[0][j] = lemma;
        } else if (cost < dp[1][j]) {
            dp[1][j] = cost;
            cls[1][j] = edgeClass;
            prevPos[1][j] = i;
            prevSlot[1][j] = (byte) slot;
            lemmaTok[1][j] = lemma;
        }
    }

    private static double transition(byte prev, byte cur) {
        if (prev < 0) return startCost(cur);
        return TRANS[prev][cur];
    }

    private static double startCost(byte cur) {
        if (cur == POS_PARTICLE || cur == POS_AUX) return 0.3;
        if (cur == POS_CONJ || cur == POS_INTERJ) return -0.1;
        return 0.0;
    }

    private static String normalizeElongation(String s) {
        StringBuilder out = new StringBuilder(s.length());
        char prev = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u30FC') continue;              // drop ー
            if (c == '\u3063' && prev == '\u3063') continue; // collapse っっ
            out.append(c);
            prev = c;
        }
        return out.toString();
    }

    private static JMDict.InflectHit cachedInflection(String surface) {
        synchronized (INFLECT_CACHE) {
            JMDict.InflectHit hit = INFLECT_CACHE.get(surface);
            if (hit != null) return hit == NO_HIT ? null : hit;
        }
        JMDict.InflectHit computed = JMDict.bestInflection(surface);
        synchronized (INFLECT_CACHE) {
            if (INFLECT_CACHE.size() >= MAX_INFLECT_CACHE) INFLECT_CACHE.clear();
            INFLECT_CACHE.put(surface, computed != null ? computed : NO_HIT);
        }
        return computed;
    }
}
