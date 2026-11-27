package com.buddyapps.jepubvocab;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Word frequency ranks from Jiten's global frequency dictionary (CC BY-SA 4.0).
 * Lower rank means more frequent; rank_weight grows with rank so rare words
 * contribute more to the similarity matcher than common ones.
 */
final class JitenRank {

    private static final int DEFAULT_RANK = 100000;
    private static final int OOV_RANK = 30000;

    private static Map<String, Integer> ranks;

    private JitenRank() { }

    static synchronized void load(Context c) {
        if (ranks != null) return;
        HashMap<String, Integer> map = new HashMap<String, Integer>();
        try {
            InputStream in = c.getAssets().open("jiten_rank.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                try {
                    map.put(line.substring(0, tab),
                            Integer.parseInt(line.substring(tab + 1)));
                } catch (NumberFormatException ignored) { }
            }
            reader.close();
        } catch (IOException e) {
            map.clear();
        }
        ranks = map;
    }

    static Map<String, Integer> rankMap() {
        return ranks;
    }

    static int rank(String word) {
        if (ranks == null || word == null) return DEFAULT_RANK;
        Integer r = ranks.get(word);
        if (r != null) return r;
        return JMDict.contains(word) ? DEFAULT_RANK : OOV_RANK;
    }

    static double weight(String word) {
        return Math.log(rank(word) + 2.0);
    }
}
