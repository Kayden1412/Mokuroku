package com.buddyapps.jepubvocab;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;

/**
 * Bloom filter over the kanji forms of JMnedict person names. Tokens that are
 * probably names (安達の, 山田, ...) are left out of the vocabulary and of the
 * similarity weighting, so rare names do not dominate a book's fingerprint.
 * Forms JMdict knows as ordinary words are never treated as names.
 */
final class JMNedict {

    private static byte[] bits;
    private static int hashCount;

    private JMNedict() { }

    static synchronized void load(Context c) {
        if (bits != null) return;
        try {
            InputStream raw = c.getAssets().open("jmnedict.bloom");
            DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 65536));
            try {
                byte[] magic = new byte[4];
                in.readFully(magic);
                if (magic[0] != 'M' || magic[1] != 'N' || magic[2] != 'B' || magic[3] != 'L') {
                    return;
                }
                int sizeBits = in.readInt();
                int hashes = in.readInt();
                if (sizeBits <= 0 || (sizeBits & 7) != 0 || hashes <= 0 || hashes > 16) {
                    return;
                }
                byte[] b = new byte[sizeBits / 8];
                in.readFully(b);
                hashCount = hashes;
                bits = b;
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            bits = null;
        }
    }

    static boolean isName(String word) {
        if (bits == null || word == null || word.length() < 2 || !hasKanji(word)) return false;
        if (JMDict.contains(word)) return false;
        if (mightContain(word)) return true;
        // The tokenizer glues trailing particles onto a kanji run (安達の), so
        // test prefixes formed by stripping trailing hiragana as well; a name
        // followed by kana is still a name.
        int len = word.length();
        while (len > 2 && isHira(word.charAt(len - 1))) {
            len--;
            String prefix = word.substring(0, len);
            if (!hasKanji(prefix)) break;
            if (JMDict.contains(prefix)) return false;
            if (mightContain(prefix)) return true;
        }
        return false;
    }

    private static boolean hasKanji(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)) return true;
        }
        return false;
    }

    private static boolean isHira(char c) {
        return c >= 0x3040 && c <= 0x309F;
    }

    private static boolean mightContain(String word) {
        byte[] b = bits;
        if (b == null) return false;
        int mask = b.length * 8 - 1;
        int a = hash1(word);
        int step = hash2(word);
        for (int i = 0; i < hashCount; i++) {
            int bit = (a + i * step) & mask;
            if ((b[bit >> 3] & (1 << (bit & 7))) == 0) return false;
        }
        return true;
    }

    private static int hash1(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }

    private static int hash2(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = 131 * h + s.charAt(i);
        return h | 1;
    }
}
