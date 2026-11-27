package com.buddyapps.jepubvocab;

import android.content.Context;

/**
 * Word frequency ranks from Jiten's global frequency dictionary (CC BY-SA 4.0).
 * Lower rank means more frequent; rank_weight grows with rank so rare words
 * contribute more to the similarity matcher than common ones. Ranks are read
 * from the same precompiled trie as JMdict—no separate text file or HashMap.
 */
final class JitenRank {

    private static final int DEFAULT_RANK = 100000;
    private static final int OOV_RANK = 30000;

    private JitenRank() { }

    static void load(Context c) {
        JMDict.load(c);
    }

    static int rank(String word) {
        DictBin d = JMDict.dict();
        if (d == null || word == null) return DEFAULT_RANK;
        int node = d.lookup(word);
        if (node < 0 || !d.isWord(node)) return OOV_RANK;
        int r = d.rank[node];
        return r > 0 ? r : DEFAULT_RANK; // JMdict-only word
    }

    static double weight(String word) {
        return Math.log(rank(word) + 2.0);
    }
}
