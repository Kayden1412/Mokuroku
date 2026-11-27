package com.buddyapps.jepubvocab;

import android.content.Context;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Loads the precompiled flat-array trie and JMdict entry table from
 * {@code assets/dict.bin}. This replaces the runtime parsing of
 * {@code jmdict.txt} and {@code jiten_rank.txt} and eliminates ~30 MB
 * of HashMap allocation on cold start.
 *
 * <p>The trie structure is identical to the one previously built at runtime
 * in {@link Tokenizer}: {@code root[65536]}, {@code ch[]}, {@code firstChild[]},
 * {@code nextSibling[]}, {@code flags[]}, {@code rank[]}.
 *
 * <p>The JMDict entry table stores conditions, content-word flags, and a
 * packed blob of UTF-8 entry data (word, reading, POS, glosses).
 */
final class DictBin {

    private static final int MAGIC = 0x4D4B4432; // "MKD2"
    static final byte FLAG_WORD = 1;
    static final byte FLAG_STEM = 2;

    // --- Trie arrays ---
    int[] root;        // root[65536]: first trie node for each UTF-16 char
    char[] ch;         // ch[nodeCount]: character label of each node
    int[] firstChild;  // firstChild[nodeCount]: linked-list first child
    int[] nextSibling; // nextSibling[nodeCount]: linked-list next sibling
    byte[] flags;      // flags[nodeCount]: FLAG_WORD | FLAG_STEM
    int[] rank;        // rank[nodeCount]: Jiten frequency rank

    // --- JMDict entry table ---
    int[] formNodeId;      // trie node id for each JMdict form
    int[] formToEntry;     // entry id for each JMdict form
    int[] entryConds;      // deinflection conditions bitmask per entry
    boolean[] entryContent;// true if entry has a content POS
    int[] entryOffsets;    // byte offsets into entryBlob
    byte[] entryBlob;      // packed UTF-8: word\0reading\0pos\0glosses

    int nodeCount;
    int formCount;
    int entryCount;

    private DictBin() { }

    /**
     * Loads {@code assets/dict.bin}. Returns null on failure rather than
     * crashing, so the app can fall back to text-file loading.
     */
    static DictBin load(Context c) {
        try {
            InputStream is = c.getAssets().open("dict.bin");
            DataInputStream in = new DataInputStream(is);
            try {
                return read(in);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static DictBin read(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) throw new IOException("Bad dict.bin magic");

        DictBin d = new DictBin();

        // -- Section 1: Trie --
        d.nodeCount = in.readInt();
        int rootSize = in.readInt(); // always 65536

        d.root = new int[rootSize];
        for (int i = 0; i < rootSize; i++) d.root[i] = in.readInt();

        d.ch = new char[d.nodeCount];
        for (int i = 0; i < d.nodeCount; i++) d.ch[i] = in.readChar();

        d.firstChild = new int[d.nodeCount];
        for (int i = 0; i < d.nodeCount; i++) d.firstChild[i] = in.readInt();

        d.nextSibling = new int[d.nodeCount];
        for (int i = 0; i < d.nodeCount; i++) d.nextSibling[i] = in.readInt();

        d.flags = new byte[d.nodeCount];
        in.readFully(d.flags);

        d.rank = new int[d.nodeCount];
        for (int i = 0; i < d.nodeCount; i++) d.rank[i] = in.readInt();

        // -- Section 2: JMDict entry table --
        d.formCount = in.readInt();

        d.formNodeId = new int[d.formCount];
        for (int i = 0; i < d.formCount; i++) d.formNodeId[i] = in.readInt();

        d.formToEntry = new int[d.formCount];
        for (int i = 0; i < d.formCount; i++) d.formToEntry[i] = in.readUnsignedShort();

        d.entryCount = in.readInt();

        d.entryConds = new int[d.entryCount];
        for (int i = 0; i < d.entryCount; i++) d.entryConds[i] = in.readInt();

        d.entryContent = new boolean[d.entryCount];
        byte[] contentBytes = new byte[d.entryCount];
        in.readFully(contentBytes);
        for (int i = 0; i < d.entryCount; i++) d.entryContent[i] = contentBytes[i] != 0;

        d.entryOffsets = new int[d.entryCount + 1];
        for (int i = 0; i <= d.entryCount; i++) d.entryOffsets[i] = in.readInt();

        int blobLen = in.readInt();
        d.entryBlob = new byte[blobLen];
        in.readFully(d.entryBlob);

        return d;
    }

    // ---- Trie queries ----

    /** Returns the trie node for the full string, or -1 if not in the trie. */
    int lookup(String word) {
        if (word == null || word.isEmpty()) return -1;
        int node = root[word.charAt(0)];
        if (node < 0) return -1;
        for (int i = 1; i < word.length(); i++) {
            char c = word.charAt(i);
            int child = firstChild[node];
            while (child >= 0 && ch[child] != c) child = nextSibling[child];
            if (child < 0) return -1;
            node = child;
        }
        return node;
    }

    boolean isWord(int node) {
        return node > 0 && (flags[node] & FLAG_WORD) != 0;
    }

    boolean isStem(int node) {
        return node > 0 && (flags[node] & FLAG_STEM) != 0;
    }

    // ---- JMDict queries ----

    /**
     * Returns the JMDict entry index for a trie node, or -1 if the node
     * has no associated entry. formNodeId[] is sorted by the generator.
     */
    int entryFor(int node) {
        int idx = Arrays.binarySearch(formNodeId, node);
        return idx >= 0 ? formToEntry[idx] : -1;
    }

    boolean isContentEntry(int eid) {
        return eid >= 0 && eid < entryCount && entryContent[eid];
    }

    int entryConditions(int eid) {
        return eid >= 0 && eid < entryCount ? entryConds[eid] : 0;
    }

    /**
     * Decodes the entry blob into 4 strings: [word, reading, pos, glosses].
     * Returns null if the entry id is invalid.
     */
    String[] entryFields(int eid) {
        if (eid < 0 || eid >= entryCount) return null;
        int from = entryOffsets[eid];
        int to = entryOffsets[eid + 1];
        if (from >= to || to > entryBlob.length) return null;
        try {
            String raw = new String(entryBlob, from, to - from, "UTF-8");
            return raw.split("\0", -1);
        } catch (Exception e) {
            return null;
        }
    }
}
