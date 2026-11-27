package com.buddyapps.jepubvocab;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Holds the whole library in memory: books, their pages, extracted vocabulary and
 * character-ngram profiles used to find books with similar content.
 */
public final class Library {

    public static final ArrayList<BookInfo> books = new ArrayList<BookInfo>();

    private static final int PAGE_CHARS = 1400;
    private static final int MAX_PAGES = 400;
    private static final int MAX_GRAMS = 180;
    // Bump when parsing changes so stored books are rebuilt once. StorageDb
    // uses this as its schema version, so old rows are dropped automatically.
    static final int CACHE_VERSION = 7;
    private static final long serialVersionUID = 1L;

    private static StorageDb db;
    private static HashMap<String, Integer> df = new HashMap<String, Integer>();
    private static HashMap<String, Integer> wordDf = new HashMap<String, Integer>();
    private static double[] bookWordNorm = new double[0];
    private static double[] bookGramNorm = new double[0];
    private static List<Map<String, Integer>> bookSimVocab = new ArrayList<Map<String, Integer>>();
    private static final HashMap<Integer, List<SimilarInfo>> bookSimilarCache =
            new HashMap<Integer, List<SimilarInfo>>();
    private static int totalVocabCount = -1;
    private static ArrayList<VocabEntry> allVocab;
    // Pairwise scores and each book's strongest neighbours. Built lazily so
    // opening a book does not scan the whole library on the UI thread.
    private static volatile double[][] similarityMatrix;
    private static int[][] similarTopK;
    private static final int SIMILAR_TOP_K = 12;

    private Library() { }

    private static void ensureDb(Context c) {
        if (db == null) db = new StorageDb(c);
    }

    private static void clearCaches() {
        Ui.clearCovers();
        allVocab = null;
        totalVocabCount = -1;
        bookSimilarCache.clear();
        similarityMatrix = null;
        similarTopK = null;
        df.clear();
        wordDf.clear();
        bookSimVocab.clear();
    }

    /** Loads the already parsed library from SQLite; no EPUB is opened at launch. */
    public static void load(Context c) {
        JMDict.load(c);
        JMNedict.load(c);
        ensureDb(c);
        books.clear();
        ArrayList<BookInfo> stored = db.loadBooks();
        if (!stored.isEmpty()) {
            books.addAll(stored);
        } else if (!migrated(c)) {
            // One-time upgrade from the old library.bin + copied EPUBs layout.
            migrateLegacy(c);
            markMigrated(c);
        }
        prepare();
    }

    private static File migrationMarker(Context c) {
        return new File(c.getFilesDir(), "storage.migrated");
    }

    private static boolean migrated(Context c) {
        return migrationMarker(c).exists();
    }

    private static void markMigrated(Context c) {
        try {
            migrationMarker(c).createNewFile();
        } catch (Throwable ignored) { }
    }

    private static boolean legacyVersionOk(Context c) {
        File marker = new File(c.getFilesDir(), "library.version");
        if (!marker.exists()) return false;
        try {
            DataInputStream in = new DataInputStream(new FileInputStream(marker));
            try {
                return in.readInt() == CACHE_VERSION;
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return false;
        }
    }

    /** Moves an existing v1.34 library into SQLite once, then retires library.bin. */
    private static void migrateLegacy(Context c) {
        File cache = new File(c.getFilesDir(), "library.bin");
        if (cache.exists() && legacyVersionOk(c)) {
            try {
                DataInputStream in = new DataInputStream(
                        new FileInputStream(cache));
                try {
                    Object o = in.readObject();
                    if (o instanceof List) {
                        for (Object x : (List<?>) o) {
                            if (x instanceof BookInfo) books.add((BookInfo) x);
                        }
                    }
                } finally {
                    in.close();
                }
            } catch (Throwable e) {
                books.clear();
            }
        }
        // An import could have copied EPUBs but crashed before writing the cache.
        syncWithDisk(c);
        if (!books.isEmpty()) db.saveBooks(books);
        cache.delete();
        new File(c.getFilesDir(), "library.version").delete();
    }

    private static final class SyncResult {
        int added;
        boolean changed;
    }

    /** Used only by the one-time migration from copied EPUB files. */
    private static SyncResult syncWithDisk(Context c) {
        SyncResult result = new SyncResult();
        File dir = booksDir(c);
        if (!dir.exists()) dir.mkdirs();
        HashSet<String> known = new HashSet<String>();
        for (BookInfo b : books) known.add(b.fileName);
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : files) {
                if (!f.isFile() || !f.getName().toLowerCase().endsWith(".epub")) continue;
                if (known.contains(f.getName())) continue;
                BookInfo book = parseEpub(f);
                if (book != null) {
                    books.add(book);
                    known.add(f.getName());
                    result.added++;
                    result.changed = true;
                }
            }
        }
        return result;
    }

    /**
     * Parses picked EPUBs straight from their content URIs and stores only the
     * extracted text, vocabulary and resized cover. The source file is staged
     * in the cache directory just for the parse, then deleted.
     */
    public static synchronized int importUris(Context c, List<Uri> uris) {
        ensureDb(c);
        HashSet<String> known = new HashSet<String>();
        for (BookInfo b : books) known.add(b.fileName);
        int added = 0;
        for (int i = 0; i < uris.size(); i++) {
            Uri uri = uris.get(i);
            String name = sanitize(displayName(c, uri));
            if (!name.toLowerCase().endsWith(".epub")) name += ".epub";
            if (known.contains(name)) continue;
            File tmp = new File(c.getCacheDir(),
                    "import_" + System.currentTimeMillis() + "_" + i + ".epub");
            try {
                if (!streamToFile(c, uri, tmp)) continue;
                BookInfo book = parseEpub(tmp, name, System.currentTimeMillis());
                if (book != null) {
                    books.add(book);
                    known.add(name);
                    added++;
                }
            } catch (Throwable e) {
                // One bad file must not abort the rest of the import.
            } finally {
                tmp.delete();
            }
        }
        if (added > 0) {
            clearCaches();
            prepare();
            db.saveBooks(books);
            markMigrated(c);
        }
        return added;
    }

    private static String displayName(Context c, Uri uri) {
        String name = null;
        ContentResolver resolver = c.getContentResolver();
        Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) name = cursor.getString(0);
            } catch (Throwable e) {
                name = null;
            } finally {
                cursor.close();
            }
        }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        return name == null || name.isEmpty() ? "book.epub" : name;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._\\u3000-\\u30ff\\u4e00-\\u9fff-]", "_");
    }

    private static boolean streamToFile(Context c, Uri uri, File dst) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            out = new FileOutputStream(dst);
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return dst.length() > 0;
        } catch (Throwable e) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
        }
    }

    /** Reloads the stored library and recomputes derived indexes. */
    public static void rebuild(Context c) {
        JMDict.load(c);
        JMNedict.load(c);
        ensureDb(c);
        clearCaches();
        books.clear();
        books.addAll(db.loadBooks());
        prepare();
    }

    public static void deleteBook(Context c, BookInfo book) {
        ensureDb(c);
        books.remove(book);
        db.delete(book.fileName);
        File f = new File(booksDir(c), book.fileName);
        if (f.exists()) f.delete();
        clearCaches();
        prepare();
    }

    /** Removes computed data for one book but leaves any copied EPUB untouched. */
    public static void deleteComputed(Context c, BookInfo book) {
        ensureDb(c);
        books.remove(book);
        db.delete(book.fileName);
        clearCaches();
        prepare();
    }

    /** Removes all computed data; copied EPUBs can still be imported again. */
    public static void clearComputed(Context c) {
        ensureDb(c);
        books.clear();
        db.clear();
        clearCaches();
        prepare();
    }

    public static boolean deleteEpub(Context c, String fileName) {
        File f = new File(booksDir(c), fileName);
        return f.isFile() && f.delete();
    }

    public static int deleteAllEpubs(Context c) {
        File dir = booksDir(c);
        File[] files = dir.listFiles();
        int deleted = 0;
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".epub") && f.delete()) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    public static File booksDir(Context c) {
        return new File(c.getFilesDir(), "books");
    }

    public static long computedBytes(Context c) {
        ensureDb(c);
        return db.dataBytes();
    }

    public static long bookBytes(Context c, String fileName) {
        ensureDb(c);
        return db.bytes(fileName);
    }

    public static int computedCount(Context c) {
        ensureDb(c);
        return db.count();
    }

    public static long databaseBytes(Context c) {
        return new File(c.getFilesDir(), "mokuroku.db").length();
    }

    /** Unique word count; prepared once per library change, not per screen refresh. */
    public static int totalVocab() {
        return totalVocabCount < 0 ? 0 : totalVocabCount;
    }

    /**
     * Vocabulary merged across every book, sorted by total frequency. Built
     * lazily (it is large) and cached until the library changes.
     */
    public static synchronized ArrayList<VocabEntry> allVocabulary() {
        if (allVocab != null) return allVocab;
        HashMap<String, Integer> counts = new HashMap<String, Integer>();
        HashMap<String, String> snippets = new HashMap<String, String>();
        for (BookInfo b : books) {
            for (VocabEntry v : b.vocab) {
                Integer old = counts.get(v.word);
                counts.put(v.word, old == null ? v.count : old + v.count);
                if (!snippets.containsKey(v.word)) {
                    String s = b.snippets.get(v.word);
                    snippets.put(v.word, s == null ? v.snippet : s);
                }
            }
        }
        final Map<String, Integer> c = counts;
        ArrayList<String> words = new ArrayList<String>(counts.keySet());
        Collections.sort(words, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int n = c.get(b).compareTo(c.get(a));
                return n != 0 ? n : a.compareTo(b);
            }
        });
        ArrayList<VocabEntry> entries = new ArrayList<VocabEntry>(words.size());
        for (String w : words) {
            String s = snippets.get(w);
            entries.add(new VocabEntry(w, c.get(w), s == null ? "" : s));
        }
        allVocab = entries;
        return entries;
    }

    /**
     * Vocabulary in order of first appearance. Uses a temporary word->position
     * map and reuses the existing VocabEntry objects, so no second vocabulary
     * model is kept in memory after the caller drops the returned list.
     */
    public static ArrayList<VocabEntry> chronologicalVocab(final BookInfo book) {
        final HashMap<String, Integer> order = new HashMap<String, Integer>();
        int seq = 0;
        for (PageInfo page : book.pages) {
            String text = page.text;
            int i = 0;
            while (i < text.length()) {
                int end = tokenEnd(text, i);
                if (end < 0) { i++; continue; }
                String word = normalizeToken(text.substring(i, end));
                i = end;
                if (word.length() < 2 || STOP.contains(word) || JMNedict.isName(word)) continue;
                if (!order.containsKey(word)) order.put(word, seq++);
            }
        }
        ArrayList<VocabEntry> out = new ArrayList<VocabEntry>(book.vocab);
        Collections.sort(out, new Comparator<VocabEntry>() {
            @Override public int compare(VocabEntry a, VocabEntry b) {
                Integer pa = order.get(a.word);
                Integer pb = order.get(b.word);
                int c = Integer.compare(pa == null ? Integer.MAX_VALUE : pa,
                        pb == null ? Integer.MAX_VALUE : pb);
                return c != 0 ? c : Integer.compare(b.count, a.count);
            }
        });
        return out;
    }

    /** Recomputes inverse document frequencies and book indexes. */
    private static void prepare() {
        df = new HashMap<String, Integer>();
        wordDf = new HashMap<String, Integer>();
        HashSet<String> rawVocabSet = new HashSet<String>();
        HashMap<String, String> lemmaCache = new HashMap<String, String>();
        bookSimVocab = new ArrayList<Map<String, Integer>>(books.size());
        for (int i = 0; i < books.size(); i++) {
            BookInfo b = books.get(i);
            b.index = i;
            Map<String, Integer> simVocab = toSimilarityVocab(b.vocabMap, lemmaCache);
            bookSimVocab.add(simVocab);
            for (String g : b.grams.keySet()) {
                Integer old = df.get(g);
                df.put(g, old == null ? 1 : old + 1);
            }
            for (String w : simVocab.keySet()) {
                Integer old = wordDf.get(w);
                wordDf.put(w, old == null ? 1 : old + 1);
            }
            rawVocabSet.addAll(b.vocabMap.keySet());
        }
        bookWordNorm = new double[books.size()];
        bookGramNorm = new double[books.size()];
        for (int i = 0; i < books.size(); i++) {
            BookInfo b = books.get(i);
            for (Map.Entry<String, Integer> e : bookSimVocab.get(i).entrySet()) {
                double w = wordWeight(e.getKey(), e.getValue());
                bookWordNorm[i] += w * w;
            }
            for (Map.Entry<String, Integer> e : b.grams.entrySet()) {
                double w = weight(e.getKey(), e.getValue());
                bookGramNorm[i] += w * w;
            }
        }
        // Book indexes can change when books are added or removed.
        Ui.clearCovers();
        bookSimilarCache.clear();
        similarityMatrix = null;
        similarTopK = null;
        totalVocabCount = rawVocabSet.size();
        allVocab = null;
    }

    private static Map<String, Integer> toSimilarityVocab(
            Map<String, Integer> raw, Map<String, String> lemmaCache) {
        HashMap<String, Integer> out = new HashMap<String, Integer>(raw.size());
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            String key = e.getKey();
            String base;
            if (lemmaCache != null && lemmaCache.containsKey(key)) {
                base = lemmaCache.get(key);
            } else {
                base = JMDict.similarityForm(key);
                if (lemmaCache != null) lemmaCache.put(key, base);
            }
            if (base == null || base.length() < 2 || STOP.contains(base) || JMNedict.isName(base)) {
                continue;
            }
            Integer old = out.get(base);
            out.put(base, old == null ? e.getValue() : old + e.getValue());
        }
        return out;
    }

    private static double tf(int count) {
        return count <= 0 ? 0.0 : 1.0 + Math.log(count);
    }

    private static double weight(String gram, int count) {
        Integer d = df.get(gram);
        int docFreq = d == null ? 0 : d;
        double idf = Math.log((books.size() + 1.0) / (docFreq + 1.0)) + 1.0;
        return tf(count) * idf;
    }

    private static double wordWeight(String word, int count) {
        Integer d = wordDf.get(word);
        int docFreq = d == null ? 0 : d;
        double idf = Math.log((books.size() + 1.0) / (docFreq + 1.0)) + 1.0;
        return tf(count) * idf * JitenRank.weight(word);
    }

    private static double cosine(double dot, double na, double nb) {
        if (na <= 0 || nb <= 0) return 0.0;
        double value = dot / Math.sqrt(na * nb);
        if (value < 0) value = 0;
        if (value > 1) value = 1;
        return value;
    }

    /**
     * Blend rank-weighted content-lemma overlap (sublinear TF) with
     * character-pattern overlap. Low shared-word counts are damped so a
     * single coincidental word cannot dominate short-text matches.
     */
    private static double matchScore(Map<String, Integer> vocabA,
                                     Map<String, Integer> gramsA,
                                     BookInfo other) {
        if (books.size() < 2) return 0.0;
        int i = other.index;
        Map<String, Integer> otherVocab = i < bookSimVocab.size()
                ? bookSimVocab.get(i) : other.vocabMap;
        double wordDot = 0.0;
        double wordNorm = 0.0;
        int shared = 0;
        for (Map.Entry<String, Integer> e : vocabA.entrySet()) {
            double w = wordWeight(e.getKey(), e.getValue());
            wordNorm += w * w;
            Integer count = otherVocab.get(e.getKey());
            if (count != null) {
                wordDot += w * wordWeight(e.getKey(), count);
                shared++;
            }
        }
        double wordCos = cosine(wordDot, wordNorm, bookWordNorm[i]);
        if (shared < 3) wordCos *= shared / 3.0;
        double gramDot = 0.0;
        double gramNorm = 0.0;
        for (Map.Entry<String, Integer> e : gramsA.entrySet()) {
            double w = weight(e.getKey(), e.getValue());
            gramNorm += w * w;
            Integer count = other.grams.get(e.getKey());
            if (count != null) gramDot += w * weight(e.getKey(), count);
        }
        return 0.7 * wordCos + 0.3 * cosine(gramDot, gramNorm, bookGramNorm[i]);
    }

    /**
     * Computes every pair once. The score itself is symmetric, so a book that
     * appears in another's shortlist can always appear back in its own.
     */
    private static synchronized void ensureSimilarityMatrix() {
        int n = books.size();
        if (similarityMatrix != null && similarityMatrix.length == n) return;
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) {
            BookInfo a = books.get(i);
            Map<String, Integer> simA = i < bookSimVocab.size()
                    ? bookSimVocab.get(i) : a.vocabMap;
            for (int j = i + 1; j < n; j++) {
                double score = matchScore(simA, a.grams, books.get(j));
                m[i][j] = score;
                m[j][i] = score;
            }
        }
        int[][] top = new int[n][];
        for (int i = 0; i < n; i++) {
            ArrayList<SimilarInfo> row = new ArrayList<SimilarInfo>(n);
            for (int j = 0; j < n; j++) {
                if (j != i) row.add(new SimilarInfo(j, m[i][j], null));
            }
            Collections.sort(row);
            int k = Math.min(SIMILAR_TOP_K, row.size());
            int[] best = new int[k];
            for (int t = 0; t < k; t++) best[t] = row.get(t).bookIndex;
            top[i] = best;
        }
        similarTopK = top;
        similarityMatrix = m;
    }

    public static synchronized List<SimilarInfo> similarToBook(BookInfo source) {
        List<SimilarInfo> cached = bookSimilarCache.get(source.index);
        if (cached != null) return cached;
        ensureSimilarityMatrix();
        double[][] matrix = similarityMatrix;
        int[][] top = similarTopK;
        int n = books.size();
        // Union of this book's strongest matches and every book that lists this
        // one, so similar lists are reciprocal instead of one-directional.
        LinkedHashSet<Integer> candidates = new LinkedHashSet<Integer>();
        for (int j : top[source.index]) candidates.add(j);
        for (int i = 0; i < n; i++) {
            if (i == source.index) continue;
            for (int j : top[i]) {
                if (j == source.index) { candidates.add(i); break; }
            }
        }
        Map<String, Integer> srcSim = source.index < bookSimVocab.size()
                ? bookSimVocab.get(source.index) : source.vocabMap;
        ArrayList<SimilarInfo> out = new ArrayList<SimilarInfo>(candidates.size());
        for (int j : candidates) {
            out.add(new SimilarInfo(j, matrix[source.index][j], null));
        }
        Collections.sort(out);
        for (SimilarInfo s : out) {
            Map<String, Integer> otherSim = s.bookIndex < bookSimVocab.size()
                    ? bookSimVocab.get(s.bookIndex) : books.get(s.bookIndex).vocabMap;
            s.shared = sharedWords(srcSim, otherSim);
        }
        bookSimilarCache.put(source.index, out);
        return out;
    }

    public static List<SimilarInfo> similarToPage(PageInfo page, BookInfo source) {
        Map<String, Integer> pageSim = toSimilarityVocab(page.vocab, null);
        ArrayList<SimilarInfo> out = new ArrayList<SimilarInfo>();
        for (BookInfo other : books) {
            if (other.index == source.index) continue;
            double score = matchScore(pageSim, page.grams, other);
            out.add(new SimilarInfo(other.index, score, null));
        }
        Collections.sort(out);
        if (out.size() > 5) out = new ArrayList<SimilarInfo>(out.subList(0, 5));
        for (SimilarInfo s : out) {
            Map<String, Integer> otherSim = s.bookIndex < bookSimVocab.size()
                    ? bookSimVocab.get(s.bookIndex) : books.get(s.bookIndex).vocabMap;
            s.shared = sharedWords(pageSim, otherSim);
        }
        return out;
    }

    private static List<String> sharedWords(Map<String, Integer> a, Map<String, Integer> b) {
        ArrayList<String> common = new ArrayList<String>();
        ArrayList<String> sorted = new ArrayList<String>(a.keySet());
        final Map<String, Integer> aa = a;
        Collections.sort(sorted, new Comparator<String>() {
            @Override public int compare(String x, String y) {
                int c = Integer.compare(JitenRank.rank(y), JitenRank.rank(x));
                if (c != 0) return c;
                c = aa.get(y).compareTo(aa.get(x));
                return c != 0 ? c : x.compareTo(y);
            }
        });
        for (String w : sorted) {
            if (aa.get(w) < 2 || JitenRank.rank(w) <= 100) continue;
            Integer n = b.get(w);
            if (n != null && n >= 2) {
                common.add(w);
                if (common.size() >= 5) break;
            }
        }
        return common;
    }

    // ------------------------------------------------------------------ EPUB

    private static String findOpfPath(ZipFile zip) throws IOException {
        byte[] container = readEntry(zip, "META-INF/container.xml");
        String opfPath = null;
        if (container != null) {
            Matcher m = Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"").matcher(
                    new String(container, "UTF-8"));
            if (m.find()) opfPath = decodeRef(m.group(1));
        }
        if (opfPath == null) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (name.toLowerCase().endsWith(".opf")) { opfPath = name; break; }
            }
        }
        return opfPath;
    }

    private static String firstCreator(String opf) {
        Matcher m = Pattern.compile("<dc:creator\\b[^>]*>(.*?)</dc:creator>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(opf);
        if (!m.find()) return "";
        String author = htmlToText(m.group(1)).trim();
        // EPUBs sometimes list "Family, Given" or a role suffix.
        int comma = author.indexOf(',');
        if (comma > 0 && comma < author.length() - 1) {
            author = author.substring(comma + 1).trim() + " "
                    + author.substring(0, comma).trim();
        }
        return author.replaceAll("\\s+", " ").trim();
    }

    private static BookInfo parseEpub(File file) {
        return parseEpub(file, file.getName(), file.lastModified());
    }

    private static BookInfo parseEpub(File file, String fileName, long addedAt) {
        try {
            ZipFile zip = new ZipFile(file);
            try {
                String opfPath = findOpfPath(zip);
                if (opfPath == null) return null;

                byte[] opfBytes = readEntry(zip, opfPath);
                if (opfBytes == null) return null;
                String opf = new String(opfBytes, "UTF-8");
                String title = "Untitled";
                Matcher tm = Pattern.compile("<dc:title\\b[^>]*>(.*?)</dc:title>",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(opf);
                if (tm.find()) {
                    title = htmlToText(tm.group(1)).trim();
                }
                if (title.isEmpty()) title = stripExtension(fileName);

                String opfDir = opfPath.contains("/")
                        ? opfPath.substring(0, opfPath.lastIndexOf('/')) : "";
                HashMap<String, String> manifest = new HashMap<String, String>();
                Matcher im = Pattern.compile("<item\\b([^>]*)>", Pattern.CASE_INSENSITIVE)
                        .matcher(opf);
                while (im.find()) {
                    String attrs = im.group(1);
                    String id = attr(attrs, "id");
                    String href = attr(attrs, "href");
                    if (!id.isEmpty() && !href.isEmpty()) manifest.put(id, href);
                }

                ArrayList<String> hrefs = new ArrayList<String>();
                Matcher sm = Pattern.compile("<itemref\\b([^>]*)>", Pattern.CASE_INSENSITIVE)
                        .matcher(opf);
                while (sm.find()) {
                    String href = manifest.get(attr(sm.group(1), "idref"));
                    if (href != null) hrefs.add(href);
                }
                if (hrefs.isEmpty()) {
                    for (String href : manifest.values()) if (isHtml(href)) hrefs.add(href);
                }
                if (hrefs.isEmpty()) {
                    Enumeration<? extends ZipEntry> en = zip.entries();
                    while (en.hasMoreElements()) {
                        String name = en.nextElement().getName();
                        if (isHtml(name)) hrefs.add(name);
                    }
                    Collections.sort(hrefs);
                }

                StringBuilder all = new StringBuilder();
                for (String href : hrefs) {
                    if (!isHtml(href)) continue;
                    String path = resolve(opfDir, href);
                    byte[] data = readEntry(zip, path);
                    if (data == null) continue;
                    String text = htmlToText(new String(data, "UTF-8"));
                    if (text.length() < 15) continue;
                    if (all.length() > 0) all.append("\n\n");
                    all.append(text);
                    if (all.length() > 600000) break;
                }
                if (all.length() < 15) return null;

                BookInfo book = new BookInfo();
                book.title = title;
                book.author = firstCreator(opf);
                book.fileName = fileName;
                book.addedAt = addedAt;
                String fullText = all.toString();
                computeTextStats(fullText, book);
                book.cover = extractCover(zip, opf, opfDir, manifest);
                book.pages = splitPages(fullText);
                for (PageInfo page : book.pages) {
                    for (Map.Entry<String, Integer> e : page.vocab.entrySet()) {
                        Integer old = book.vocabMap.get(e.getKey());
                        book.vocabMap.put(e.getKey(),
                                old == null ? e.getValue() : old + e.getValue());
                    }
                    for (Map.Entry<String, String> e : page.snippets.entrySet()) {
                        if (!book.snippets.containsKey(e.getKey())) {
                            book.snippets.put(e.getKey(), e.getValue());
                        }
                    }
                    for (Map.Entry<String, Integer> e : page.grams.entrySet()) {
                        Integer old = book.grams.get(e.getKey());
                        book.grams.put(e.getKey(),
                                old == null ? e.getValue() : old + e.getValue());
                    }
                }
                book.vocab = toVocabList(book.vocabMap, book.snippets);
                trimGrams(book.grams);
                for (PageInfo page : book.pages) trimGrams(page.grams);
                return book;
            } finally {
                zip.close();
            }
        } catch (Throwable e) {
            // OutOfMemoryError is an Error, not an Exception. A single oversized
            // EPUB must not take down the whole import or the app.
            return null;
        }
    }

    private static byte[] readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            String tail = "/" + name;
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry z = en.nextElement();
                String n = z.getName();
                if (n.endsWith(tail) || n.equals(name)) { entry = z; break; }
            }
        }
        if (entry == null) return null;
        InputStream in = zip.getInputStream(entry);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE).matcher(attrs);
        if (m.find()) return decodeRef(m.group(1));
        m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*'([^']*)'",
                Pattern.CASE_INSENSITIVE).matcher(attrs);
        if (m.find()) return decodeRef(m.group(1));
        return "";
    }

    private static String decodeRef(String s) {
        if (s.indexOf('%') < 0) return s;
        try { return URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String resolve(String dir, String href) {
        String path = href.split("#")[0].split("\\?")[0];
        path = decodeRef(path).replace('\\', '/');
        if (path.startsWith("/")) return path.substring(1);
        ArrayList<String> parts = new ArrayList<String>();
        if (!dir.isEmpty()) Collections.addAll(parts, dir.split("/"));
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(p);
            }
        }
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (out.length() > 0) out.append('/');
            out.append(p);
        }
        return out.toString();
    }

    private static boolean isHtml(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml")
                || n.contains("text/html");
    }

    private static String stripExtension(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static boolean matchesIgnoreCase(String s, int from, String name) {
        if (from < 0 || from + name.length() > s.length()) return false;
        for (int k = 0; k < name.length(); k++) {
            char a = s.charAt(from + k);
            char b = name.charAt(k);
            if (a >= 'A' && a <= 'Z') a = (char) (a + 32);
            if (b >= 'A' && b <= 'Z') b = (char) (b + 32);
            if (a != b) return false;
        }
        return true;
    }

    private static boolean tagIs(String s, int from, int to, String name) {
        return to - from == name.length() && matchesIgnoreCase(s, from, name);
    }

    private static int tagEnd(String s, int from) {
        int limit = Math.min(s.length(), from + 160);
        for (int i = from; i < limit; i++) if (s.charAt(i) == '>') return i;
        return -1;
    }

    private static int skipBlock(String s, int from, String tag) {
        int n = s.length();
        for (int i = from; i < n - 1; i++) {
            if (s.charAt(i) == '<' && s.charAt(i + 1) == '/') {
                int p = i + 2;
                while (p < n && Character.isWhitespace(s.charAt(p))) p++;
                if (matchesIgnoreCase(s, p, tag)) {
                    int q = p + tag.length();
                    while (q < n && s.charAt(q) != '>') q++;
                    if (q < n) return q + 1;
                }
            }
        }
        return n;
    }

    private static boolean isBlockTag(String s, int from, int to) {
        if (tagIs(s, from, to, "p") || tagIs(s, from, to, "div")
                || tagIs(s, from, to, "li") || tagIs(s, from, to, "tr")
                || tagIs(s, from, to, "section") || tagIs(s, from, to, "blockquote")
                || tagIs(s, from, to, "article")) return true;
        return to - from == 2 && (s.charAt(from) == 'h' || s.charAt(from) == 'H')
                && s.charAt(from + 1) >= '1' && s.charAt(from + 1) <= '6';
    }

    private static String entityText(String token) {
        if (token.startsWith("#x") || token.startsWith("#X")) {
            try {
                int v = Integer.parseInt(token.substring(2), 16);
                return v > 0 && v <= 0xFFFF ? String.valueOf((char) v) : null;
            } catch (Exception e) { return null; }
        }
        if (token.startsWith("#")) {
            try {
                int v = Integer.parseInt(token.substring(1));
                return v > 0 && v <= 0xFFFF ? String.valueOf((char) v) : null;
            } catch (Exception e) { return null; }
        }
        if (token.equalsIgnoreCase("nbsp")) return " ";
        if (token.equalsIgnoreCase("amp")) return "&";
        if (token.equalsIgnoreCase("lt")) return "<";
        if (token.equalsIgnoreCase("gt")) return ">";
        if (token.equalsIgnoreCase("quot")) return "\"";
        if (token.equalsIgnoreCase("apos")) return "'";
        if (token.equalsIgnoreCase("mdash")) return "—";
        if (token.equalsIgnoreCase("ndash")) return "–";
        if (token.equalsIgnoreCase("hellip")) return "…";
        return null;
    }

    private static void appendNewline(StringBuilder out) {
        int len = out.length();
        if (len == 0) return;
        if (out.charAt(len - 1) != '\n') {
            out.append('\n');
        } else if (len < 2 || out.charAt(len - 2) != '\n') {
            // Keep at most one blank line, matching the old \n{3,} -> \n\n rule.
            out.append('\n');
        }
    }

    // Single pass: strip tags, decode entities, fold full-width ASCII and
    // collapse whitespace in one buffer. No regex recompilation or intermediate
    // strings for every XHTML file in every EPUB.
    private static String htmlToText(String html) {
        StringBuilder out = new StringBuilder(html.length());
        boolean pendingSpace = false;
        int n = html.length();
        int i = 0;
        while (i < n) {
            char c = html.charAt(i);
            if (c == '<') {
                int gt = tagEnd(html, i + 1);
                if (gt > i + 1) {
                    char first = html.charAt(i + 1);
                    if (Character.isLetter(first) || first == '/' || first == '!') {
                        int ns = i + 1;
                        boolean closing = html.charAt(ns) == '/';
                        if (closing) ns++;
                        while (ns < gt && Character.isWhitespace(html.charAt(ns))) ns++;
                        int ne = ns;
                        while (ne < gt && !Character.isWhitespace(html.charAt(ne))
                                && html.charAt(ne) != '/') ne++;
                        if (!closing) {
                            if (tagIs(html, ns, ne, "script")) {
                                i = skipBlock(html, gt + 1, "script");
                                pendingSpace = true;
                                continue;
                            }
                            if (tagIs(html, ns, ne, "style")) {
                                i = skipBlock(html, gt + 1, "style");
                                pendingSpace = true;
                                continue;
                            }
                            if (tagIs(html, ns, ne, "head")) {
                                i = skipBlock(html, gt + 1, "head");
                                pendingSpace = true;
                                continue;
                            }
                            // Ruby annotations are readings, not prose: without
                            // this 行<rt>い</rt>った becomes the bogus token 行い.
                            if (tagIs(html, ns, ne, "rt")) {
                                i = skipBlock(html, gt + 1, "rt");
                                continue;
                            }
                            if (tagIs(html, ns, ne, "rp")) {
                                i = skipBlock(html, gt + 1, "rp");
                                continue;
                            }
                        }
                        if (tagIs(html, ns, ne, "br") || isBlockTag(html, ns, ne)) {
                            pendingSpace = false;
                            appendNewline(out);
                        }
                        i = gt + 1;
                        continue;
                    }
                }
            }
            if (c == '&') {
                int semi = -1;
                int limit = Math.min(n, i + 14);
                for (int k = i + 1; k < limit; k++) {
                    char e = html.charAt(k);
                    if (e == ';') { semi = k; break; }
                    if (e == '<' || e == '&') break;
                }
                if (semi > i + 1) {
                    String rep = entityText(html.substring(i + 1, semi));
                    if (rep != null) {
                        if (rep.equals(" ")) {
                            pendingSpace = true;
                        } else {
                            if (pendingSpace && out.length() > 0
                                    && out.charAt(out.length() - 1) != '\n') out.append(' ');
                            pendingSpace = false;
                            for (int k = 0; k < rep.length(); k++) {
                                char r = rep.charAt(k);
                                out.append(r >= 0xFF01 && r <= 0xFF5E
                                        ? (char) (r - 0xFEE0) : r);
                            }
                        }
                        i = semi + 1;
                        continue;
                    }
                }
            }
            if (Character.isWhitespace(c)) {
                if (c == '\n') {
                    pendingSpace = false;
                    appendNewline(out);
                } else {
                    pendingSpace = true;
                }
                i++;
                continue;
            }
            if (pendingSpace && out.length() > 0
                    && out.charAt(out.length() - 1) != '\n') out.append(' ');
            pendingSpace = false;
            out.append(c >= 0xFF01 && c <= 0xFF5E ? (char) (c - 0xFEE0) : c);
            i++;
        }
        while (out.length() > 0) {
            char last = out.charAt(out.length() - 1);
            if (last != ' ' && last != '\n') break;
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /** Computes character count, dialogue percentage, and average sentence length. */
    private static void computeTextStats(String text, BookInfo book) {
        long totalChars = 0;
        long dialogueChars = 0;
        long sentenceChars = 0;
        long sentenceCount = 0;
        boolean inDialogue = false;
        boolean hasContent = false;
        boolean prevNewline = false;
        long curSentenceChars = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                // Reset unclosed quotes across block breaks (\n\n) so a missing
                // closing bracket never taints the rest of the book, while
                // keeping single-<br> line wraps inside dialogue intact.
                if (prevNewline) inDialogue = false;
                prevNewline = true;
                if (hasContent && curSentenceChars > 0) {
                    sentenceChars += curSentenceChars;
                    sentenceCount++;
                }
                curSentenceChars = 0;
                hasContent = false;
                continue;
            }
            prevNewline = false;
            if (Character.isWhitespace(c)) continue;

            totalChars++;
            if (c == '\u300c') { // 「
                inDialogue = true;
                dialogueChars++;
            } else if (c == '\u300d') { // 」
                if (inDialogue) dialogueChars++;
                inDialogue = false;
            } else if (inDialogue) {
                dialogueChars++;
            }

            curSentenceChars++;
            if (Character.isLetterOrDigit(c)) hasContent = true;

            if (c == '\u3002' || c == '\uff01' || c == '\uff1f' || c == '!' || c == '?') {
                if (hasContent && curSentenceChars > 0) {
                    sentenceChars += curSentenceChars;
                    sentenceCount++;
                }
                curSentenceChars = 0;
                hasContent = false;
            }
        }
        if (hasContent && curSentenceChars > 0) {
            sentenceChars += curSentenceChars;
            sentenceCount++;
        }

        book.charCount = totalChars;
        book.dialoguePercent = totalChars > 0 ? (dialogueChars * 100.0) / totalChars : 0.0;
        book.avgSentenceLength = sentenceCount > 0 ? (double) sentenceChars / sentenceCount : 0.0;
    }

    /** Finds the EPUB cover image and downsizes it to keep the app small. */
    private static byte[] extractCover(ZipFile zip, String opf, String opfDir,
                                       Map<String, String> manifest) {
        String href = null;
        Matcher m = Pattern.compile("<meta\\b[^>]*name\\s*=\\s*[\"']cover[\"']"
                + "[^>]*content\\s*=\\s*[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(opf);
        if (m.find()) href = manifest.get(m.group(1));
        if (href == null) {
            Matcher im = Pattern.compile("<item\\b([^>]*)>", Pattern.CASE_INSENSITIVE)
                    .matcher(opf);
            while (im.find()) {
                String attrs = im.group(1);
                if (attr(attrs, "properties").toLowerCase().contains("cover-image")) {
                    String candidate = attr(attrs, "href");
                    if (!candidate.isEmpty()) { href = candidate; break; }
                }
            }
        }
        byte[] data = null;
        if (href != null) {
            try { data = readEntry(zip, resolve(opfDir, href)); }
            catch (Exception e) { data = null; }
        }
        if (data == null) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry z = en.nextElement();
                String name = z.getName().toLowerCase();
                if (name.contains("cover") && (name.endsWith(".jpg")
                        || name.endsWith(".jpeg") || name.endsWith(".png"))) {
                    try {
                        data = readEntry(zip, z.getName());
                        if (data != null) break;
                    } catch (Exception e) { data = null; }
                }
            }
        }
        if (data == null) return null;
        return compressCover(data);
    }

    private static byte[] compressCover(byte[] data) {
        try {
            // Decode a downscaled sample first: full-size covers in some EPUBs
            // are large enough to exhaust the heap during BitmapFactory.decode.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 960) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (bm == null) return null;
            int w = bm.getWidth();
            int h = bm.getHeight();
            float scale = Math.min(1f, Math.min(360f / w, 480f / h));
            int nw = Math.max(1, Math.round(w * scale));
            int nh = Math.max(1, Math.round(h * scale));
            Bitmap small = (nw == w && nh == h)
                    ? bm : Bitmap.createScaledBitmap(bm, nw, nh, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 78, out);
            if (small != bm) small.recycle();
            bm.recycle();
            return out.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    // -------------------------------------------------------------- tokenize

    private static boolean isKanji(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    private static boolean isHira(char c) {
        return c >= 0x3040 && c <= 0x309F;
    }

    private static boolean isKata(char c) {
        return (c >= 0x30A0 && c <= 0x30FF) || c == 0x30FC || c == 0x309B || c == 0x309C;
    }

    private static boolean isJapanese(char c) {
        return isKanji(c) || isHira(c) || isKata(c);
    }

    private static final Set<String> STOP = new HashSet<String>(Arrays.asList(
            "は", "が", "を", "に", "で", "と", "も", "の", "へ", "や", "か", "ね", "よ",
            "わ", "お", "ご", "この", "その", "あの", "これ", "それ", "あれ", "ここ",
            "そこ", "どこ", "だれ", "なん", "なに", "です", "ます", "ました", "でした",
            "する", "した", "して", "ある", "いる", "なる", "なった", "いう", "ない",
            "そして", "しかし", "また", "それで", "だから", "ので", "から", "まで",
            "より", "など", "という", "そう", "こう", "どう", "どんな", "まず", "もし",
            "ほか", "場合", "私", "彼女", "彼", "たち", "もの", "こと", "とき"));

    // Hoshidict/Yomitan scan a lookup string from longest prefix to shortest and
    // keep the first form the dictionary recognises. Do the same here so a run
    // like 私の/わたしの/学生です resolves to 私/わたし/学生 instead of carrying
    // the trailing particle or auxiliary.
    private static String normalizeToken(String word) {
        if (word.length() < 2 || JMDict.contains(word) || STOP.contains(word)) return word;
        int maxCut = Math.min(4, word.length() - 1);
        for (int cut = 1; cut <= maxCut; cut++) {
            String prefix = word.substring(0, word.length() - cut);
            if (JMDict.contains(prefix) || STOP.contains(prefix)) return prefix;
        }
        return word;
    }

    private static boolean isPunctuation(char c) {
        return c == '\u300c' || c == '\u300d' || c == '\u300e' || c == '\u300f'
                || c == '\u3001' || c == '\u3002' || c == '\uff01' || c == '\uff1f'
                || c == '!' || c == '?' || c == ',' || c == '.' || c == ':'
                || c == ';' || c == '\u2026' || c == '\u2014';
    }

    private static boolean isAllHira(String text, int from, int to) {
        if (from >= to) return false;
        for (int p = from; p < to; p++) {
            if (!isHira(text.charAt(p))) return false;
        }
        return true;
    }

    /** End index of the next token at i, or -1 when text[i] is a delimiter. */
    private static int tokenEnd(String text, int i) {
        int len = text.length();
        char c = text.charAt(i);
        int scriptEnd = -1;
        if (isKanji(c)) {
            int j = i;
            while (j < len && isKanji(text.charAt(j)) && j - i < 8) j++;
            int k = j;
            int okuri = 0;
            while (k < len && isHira(text.charAt(k)) && okuri < 3) { k++; okuri++; }
            scriptEnd = k;
        } else if (isHira(c)) {
            int j = i;
            while (j < len && isHira(text.charAt(j)) && j - i < 8) j++;
            scriptEnd = j;
        } else if (isKata(c)) {
            int j = i;
            while (j < len && isKata(text.charAt(j)) && j - i < 16) j++;
            scriptEnd = j;
        } else {
            return -1;
        }
        // Dictionary-based longest match: split runs at real word boundaries
        // (学校生活 -> 学校/生活) instead of swallowing the whole run. A
        // following hiragana tail is kept so normalizeToken can strip
        // okurigana/particles from inflected forms.
        // Punctuation and quotes are hard boundaries: never let a
        // dictionary entry span them (e.g. 本。」は stays 本 + は).
        int scanLimit = Math.min(len, i + 16);
        for (int p = i; p < scanLimit; p++) {
            if (isPunctuation(text.charAt(p))) { scanLimit = p; break; }
        }
        int dictEnd = JMDict.longestForm(text, i, scanLimit - i);
        if (dictEnd >= i + 2
                && !(dictEnd < scriptEnd && isAllHira(text, dictEnd, scriptEnd))) {
            return dictEnd;
        }
        return scriptEnd;
    }

    private static HashMap<String, Integer> tokenize(String text, Map<String, String> snippets) {
        HashMap<String, Integer> counts = new HashMap<String, Integer>();
        int i = 0;
        int len = text.length();
        while (i < len) {
            int start = i;
            int end = tokenEnd(text, i);
            if (end < 0) { i++; continue; }
            String word = text.substring(i, end);
            i = end;
            // Prefer the longest recognised prefix, as a dictionary scanner does.
            word = normalizeToken(word);
            if (word.length() < 2 || STOP.contains(word) || JMNedict.isName(word)) continue;
            Integer old = counts.get(word);
            counts.put(word, old == null ? 1 : old + 1);
            if (!snippets.containsKey(word)) {
                int pos = start;
                if (pos < 0 || !text.startsWith(word, pos)) pos = text.indexOf(word);
                if (pos < 0) pos = 0;
                int from = Math.max(0, pos - 18);
                int to = Math.min(len, pos + word.length() + 22);
                snippets.put(word, "…" + text.substring(from, to).replace('\n', ' ').trim() + "…");
            }
        }
        return counts;
    }

    private static HashMap<String, Integer> makeGrams(String text) {
        HashMap<String, Integer> grams = new HashMap<String, Integer>();
        char prev = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isJapanese(c)) {
                if (prev != 0) {
                    String g = "" + prev + c;
                    Integer old = grams.get(g);
                    grams.put(g, old == null ? 1 : old + 1);
                }
                prev = c;
            } else {
                prev = 0;
            }
        }
        return grams;
    }

    private static void trimGrams(HashMap<String, Integer> grams) {
        if (grams.size() <= MAX_GRAMS) return;
        List<Map.Entry<String, Integer>> list =
                new ArrayList<Map.Entry<String, Integer>>(grams.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a,
                                         Map.Entry<String, Integer> b) {
                int c = b.getValue().compareTo(a.getValue());
                return c != 0 ? c : a.getKey().compareTo(b.getKey());
            }
        });
        HashMap<String, Integer> kept = new HashMap<String, Integer>();
        for (int i = 0; i < MAX_GRAMS && i < list.size(); i++) {
            kept.put(list.get(i).getKey(), list.get(i).getValue());
        }
        grams.clear();
        grams.putAll(kept);
    }

    private static ArrayList<VocabEntry> toVocabList(
            final Map<String, Integer> counts, Map<String, String> snippets) {
        ArrayList<String> words = new ArrayList<String>(counts.keySet());
        Collections.sort(words, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int c = counts.get(b).compareTo(counts.get(a));
                return c != 0 ? c : a.compareTo(b);
            }
        });
        ArrayList<VocabEntry> out = new ArrayList<VocabEntry>();
        for (String w : words) {
            String s = snippets.get(w);
            out.add(new VocabEntry(w, counts.get(w), s == null ? "" : s));
        }
        return out;
    }

    private static ArrayList<PageInfo> splitPages(String text) {
        ArrayList<PageInfo> pages = new ArrayList<PageInfo>();
        ArrayList<String> segments = new ArrayList<String>();
        String[] paras = text.split("\\n+");
        for (String para : paras) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            while (p.length() > PAGE_CHARS) {
                segments.add(p.substring(0, PAGE_CHARS));
                p = p.substring(PAGE_CHARS);
            }
            segments.add(p);
        }
        StringBuilder cur = new StringBuilder();
        for (String seg : segments) {
            if (cur.length() > 0 && cur.length() + seg.length() + 1 > PAGE_CHARS) {
                pages.add(makePage(pages.size() + 1, cur.toString()));
                cur.setLength(0);
                if (pages.size() >= MAX_PAGES) break;
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(seg);
        }
        if (cur.length() > 0 && pages.size() < MAX_PAGES) {
            pages.add(makePage(pages.size() + 1, cur.toString()));
        }
        return pages;
    }

    private static PageInfo makePage(int number, String text) {
        PageInfo page = new PageInfo();
        page.number = number;
        page.text = text;
        page.vocab = tokenize(text, page.snippets);
        page.grams = makeGrams(text);
        page.vocabList = toVocabList(page.vocab, page.snippets);
        if (!page.vocabList.isEmpty()) {
            page.firstSnippetWord = page.vocabList.get(0).word;
            page.firstSnippet = page.vocabList.get(0).snippet;
        } else {
            page.firstSnippetWord = "";
            page.firstSnippet = "";
        }
        return page;
    }

    // ----------------------------------------------------------------- model

    public static class BookInfo implements Serializable {
        private static final long serialVersionUID = 2L;
        public String title = "";
        public String author = "";
        public String fileName = "";
        public int index;
        public long addedAt;
        public long charCount;
        public double dialoguePercent;
        public double avgSentenceLength;
        public byte[] cover;
        public ArrayList<PageInfo> pages = new ArrayList<PageInfo>();
        public ArrayList<VocabEntry> vocab = new ArrayList<VocabEntry>();
        public HashMap<String, Integer> vocabMap = new HashMap<String, Integer>();
        public HashMap<String, String> snippets = new HashMap<String, String>();
        public HashMap<String, Integer> grams = new HashMap<String, Integer>();

        public String summary() {
            return Ui.num(pages.size()) + " pages  ·  " + Ui.num(charCount)
                    + " chars  ·  " + Ui.num(vocab.size()) + " words  ·  "
                    + Math.round(dialoguePercent) + "% dialogue  ·  "
                    + Ui.dec(avgSentenceLength) + " chars/sent";
        }
    }

    public static class PageInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        public int number;
        public String text = "";
        public HashMap<String, Integer> vocab = new HashMap<String, Integer>();
        public ArrayList<VocabEntry> vocabList = new ArrayList<VocabEntry>();
        public HashMap<String, String> snippets = new HashMap<String, String>();
        public HashMap<String, Integer> grams = new HashMap<String, Integer>();
        public String firstSnippetWord = "";
        public String firstSnippet = "";

        public String preview() {
            String s = text.replace('\n', ' ').trim();
            return s.length() > 80 ? s.substring(0, 80) + "…" : s;
        }
    }

    public static class VocabEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        public String word;
        public int count;
        public String snippet;

        public VocabEntry(String word, int count, String snippet) {
            this.word = word;
            this.count = count;
            this.snippet = snippet == null ? "" : snippet;
        }
    }

    public static class SimilarInfo implements Serializable, Comparable<SimilarInfo> {
        private static final long serialVersionUID = 1L;
        public int bookIndex;
        public double score;
        public List<String> shared;

        public SimilarInfo(int bookIndex, double score, List<String> shared) {
            this.bookIndex = bookIndex;
            this.score = score;
            this.shared = shared;
        }

        @Override public int compareTo(SimilarInfo other) {
            int c = Double.compare(other.score, this.score);
            return c != 0 ? c : Integer.compare(this.bookIndex, other.bookIndex);
        }

        public int percentValue() {
            return (int) Math.round(score * 100);
        }

        public String percent() {
            return percentValue() + "%";
        }

        public String sharedText() {
            if (shared == null || shared.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < shared.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(shared.get(i));
            }
            return "Shared: " + sb.toString();
        }
    }
}
