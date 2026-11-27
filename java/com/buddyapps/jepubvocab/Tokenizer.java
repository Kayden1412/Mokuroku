package com.buddyapps.jepubvocab;

import android.content.Context;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Rank-scored Viterbi Japanese tokenizer over a compact flat-array trie of
 * JMdict and Jiten frequency words, with stem-gated deinflection for verbs and
 * adjectives. Contiguous Japanese runs are segmented globally rather than
 * greedily, so punctuation, quotes and middle dots remain hard boundaries and
 * conjugated words collapse cleanly to their base lemmas.
 */
final class Tokenizer {

    interface TokenConsumer {
        void onToken(String word, int startOffset);
    }

    private static final byte FLAG_WORD = 1;
    private static final byte FLAG_STEM = 2;

    private static final int DEFAULT_RANK = 100000;
    private static final int MAX_WORD_LEN = 16;
    private static final int MAX_KANJI_FALLBACK = 8;
    private static final int MAX_INFLECT_CACHE = 32768;

    private static final double BASE_WORD_COST = 4.5;
    private static final double UNK_KANJI_BASE = 22.0;
    private static final double UNK_KANJI_STEP = 6.0;
    private static final double UNK_KATA_BASE = 20.0;
    private static final double UNK_KATA_STEP = 2.0;
    private static final double UNK_CHAR_COST = 24.0;

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

    private static final class Trie {
        final int[] root = new int[65536];
        char[] ch;
        int[] first;
        int[] next;
        byte[] flags;
        int[] rank;
        int size = 1; // node 0 is unused sentinel

        Trie(int capacity) {
            Arrays.fill(root, -1);
            ch = new char[capacity];
            first = new int[capacity];
            next = new int[capacity];
            flags = new byte[capacity];
            rank = new int[capacity];
            Arrays.fill(first, -1);
            Arrays.fill(next, -1);
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
            rank[id] = 0;
            return id;
        }

        void trim() {
            ch = Arrays.copyOf(ch, size);
            first = Arrays.copyOf(first, size);
            next = Arrays.copyOf(next, size);
            flags = Arrays.copyOf(flags, size);
            rank = Arrays.copyOf(rank, size);
        }
    }

    private static volatile Trie trie;
    private static final HashMap<String, JMDict.InflectHit> INFLECT_CACHE =
            new HashMap<String, JMDict.InflectHit>();
    private static final JMDict.InflectHit NO_HIT = new JMDict.InflectHit(null, 0, 0);

    private Tokenizer() { }

    static void ensureReady(Context c) {
        if (trie != null) return;
        synchronized (Tokenizer.class) {
            if (trie != null) return;
            JMDict.load(c);
            JMNedict.load(c);
            JitenRank.load(c);

            Trie built = new Trie(230000);
            Map<String, JMDict.Entry> jm = JMDict.formMap();
            if (jm != null) {
                for (Map.Entry<String, JMDict.Entry> e : jm.entrySet()) {
                    String form = e.getKey();
                    JMDict.Entry entry = e.getValue();
                    if (form == null || form.isEmpty()) continue;
                    int r = JitenRank.rank(form);
                    built.insert(form, r, true, false);
                    if (entry != null && entry.conditions != 0) {
                        String stem = form.length() >= 2
                                ? form.substring(0, form.length() - 1) : form;
                        built.insert(stem, 0, false, true);
                    }
                }
            }
            Map<String, Integer> jr = JitenRank.rankMap();
            if (jr != null) {
                for (Map.Entry<String, Integer> e : jr.entrySet()) {
                    String w = e.getKey();
                    if (w == null || w.isEmpty()) continue;
                    built.insert(w, e.getValue(), true, false);
                }
            }
            // Irregular verb/adjective stems (来る, する, いい/よい).
            String[] irregularStems = {"来", "き", "こ", "く", "し", "さ", "せ", "す", "よ", "い"};
            for (String s : irregularStems) {
                built.insert(s, 0, false, true);
            }
            built.trim();
            trie = built;
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

    static void tokenize(String text, TokenConsumer consumer) {
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
            segmentSpan(t, text, i, j, consumer);
            i = j;
        }
    }

    private static void segmentSpan(Trie t, String text, int start, int end,
                                    TokenConsumer consumer) {
        int n = end - start;
        if (n <= 0) return;

        double[] dp = new double[n + 1];
        Arrays.fill(dp, Double.MAX_VALUE);
        dp[0] = 0.0;
        int[] prev = new int[n + 1];
        Arrays.fill(prev, -1);
        String[] lemmaTok = new String[n + 1];

        for (int i = 0; i < n; i++) {
            double baseCost = dp[i];
            if (baseCost >= Double.MAX_VALUE / 2) continue;
            int absI = start + i;
            char ch0 = text.charAt(absI);

            // 1. Direct dictionary edges via flat primitive trie (zero allocation).
            int maxEnd = Math.min(n, i + MAX_WORD_LEN);
            int maxStemRel = -1;
            int node = t.root[ch0];
            for (int j = i + 1; node >= 0; ) {
                byte f = t.flags[node];
                if ((f & FLAG_STEM) != 0) maxStemRel = j;
                if ((f & FLAG_WORD) != 0) {
                    int r = t.rank[node];
                    if (r <= 0) r = DEFAULT_RANK;
                    double c = baseCost + BASE_WORD_COST + Math.log(r + 2.0);
                    if (c < dp[j]) {
                        dp[j] = c;
                        prev[j] = i;
                        lemmaTok[j] = null;
                    }
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
                        double c = baseCost + BASE_WORD_COST + Math.log(hit.rank + 2.0)
                                + 1.5 + 1.0 * hit.steps;
                        if (c < dp[j]) {
                            dp[j] = c;
                            prev[j] = i;
                            lemmaTok[j] = hit.lemma;
                        }
                    }
                }
            }

            // 3. Unknown / OOV fallback edges.
            if (isKanjiStart(ch0)) {
                double c1 = baseCost + UNK_KANJI_BASE;
                if (c1 < dp[i + 1]) {
                    dp[i + 1] = c1;
                    prev[i + 1] = i;
                    lemmaTok[i + 1] = null;
                }
                int kMax = Math.min(n, i + MAX_KANJI_FALLBACK);
                for (int j = i + 2; j <= kMax; j++) {
                    if (!isKanji(text.charAt(start + j - 1))) break;
                    double ck = baseCost + UNK_KANJI_BASE + UNK_KANJI_STEP * (j - i - 1);
                    if (ck < dp[j]) {
                        dp[j] = ck;
                        prev[j] = i;
                        lemmaTok[j] = null;
                    }
                }
            } else if (isKata(ch0)) {
                double c1 = baseCost + UNK_CHAR_COST;
                if (c1 < dp[i + 1]) {
                    dp[i + 1] = c1;
                    prev[i + 1] = i;
                    lemmaTok[i + 1] = null;
                }
                for (int j = i + 2; j <= maxEnd; j++) {
                    if (!isKata(text.charAt(start + j - 1))) break;
                    double ck = baseCost + UNK_KATA_BASE + UNK_KATA_STEP * (j - i - 1);
                    if (ck < dp[j]) {
                        dp[j] = ck;
                        prev[j] = i;
                        lemmaTok[j] = null;
                    }
                }
            } else {
                double c1 = baseCost + UNK_CHAR_COST;
                if (c1 < dp[i + 1]) {
                    dp[i + 1] = c1;
                    prev[i + 1] = i;
                    lemmaTok[i + 1] = null;
                }
            }
        }

        // Reconstruct winning path in reverse, then emit in forward order.
        int count = 0;
        int[] edgeStarts = new int[n];
        int[] edgeEnds = new int[n];
        String[] edgeLemmas = new String[n];
        int cur = n;
        while (cur > 0) {
            int p = prev[cur];
            if (p < 0) break;
            edgeStarts[count] = p;
            edgeEnds[count] = cur;
            edgeLemmas[count] = lemmaTok[cur];
            count++;
            cur = p;
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
