package com.buddyapps.jepubvocab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final long serialVersionUID = 1L;

    private static File cacheFile;
    private static HashMap<String, Integer> df = new HashMap<String, Integer>();
    private static HashMap<String, Integer> wordDf = new HashMap<String, Integer>();
    private static double[] bookWordNorm = new double[0];
    private static double[] bookGramNorm = new double[0];
    private static final HashMap<Integer, List<SimilarInfo>> bookSimilarCache =
            new HashMap<Integer, List<SimilarInfo>>();

    private Library() { }

    public static void load(Context c) {
        cacheFile = new File(c.getFilesDir(), "library.bin");
        books.clear();
        boolean cacheOk = false;
        if (cacheFile.exists()) {
            try {
                ObjectInputStream in = new ObjectInputStream(
                        new BufferedInputStream(new FileInputStream(cacheFile)));
                try {
                    Object o = in.readObject();
                    if (o instanceof List) {
                        for (Object x : (List<?>) o) {
                            if (x instanceof BookInfo) books.add((BookInfo) x);
                        }
                    }
                    cacheOk = true;
                } finally {
                    in.close();
                }
            } catch (Throwable e) {
                // A cache left half-written by a crash is safe to ignore.
                books.clear();
            }
        }
        // An import can crash after copying its EPUBs but before saving the cache.
        // Parse only the missing files instead of the entire library on next launch.
        SyncResult sync = syncWithDisk(c);
        if (!cacheOk || sync.changed) {
            prepare();
            save();
        } else {
            prepare();
        }
    }

    /** Adds EPUBs that were copied to disk after the cache was last saved. */
    public static int importNew(Context c) {
        if (cacheFile == null) cacheFile = new File(c.getFilesDir(), "library.bin");
        SyncResult sync = syncWithDisk(c);
        if (sync.changed) {
            prepare();
            save();
        }
        return sync.added;
    }

    private static final class SyncResult {
        int added;
        boolean changed;
    }

    /** Reconciles the in-memory library with the EPUB files in private storage. */
    private static SyncResult syncWithDisk(Context c) {
        SyncResult result = new SyncResult();
        File dir = new File(c.getFilesDir(), "books");
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
        for (int i = books.size() - 1; i >= 0; i--) {
            File f = new File(dir, books.get(i).fileName);
            if (!f.isFile() || f.length() == 0) {
                books.remove(i);
                result.changed = true;
            }
        }
        return result;
    }

    public static void rebuild(Context c) {
        cacheFile = new File(c.getFilesDir(), "library.bin");
        books.clear();
        File dir = new File(c.getFilesDir(), "books");
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files, new Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File f : files) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".epub")) {
                        BookInfo book = parseEpub(f);
                        if (book != null) books.add(book);
                    }
                }
            }
        }
        prepare();
        save();
    }

    public static void deleteBook(Context c, BookInfo book) {
        File dir = new File(c.getFilesDir(), "books");
        File f = new File(dir, book.fileName);
        if (f.exists()) f.delete();
        books.remove(book);
        prepare();
        save();
    }

    public static File booksDir(Context c) {
        return new File(c.getFilesDir(), "books");
    }

    public static int totalVocab() {
        HashSet<String> all = new HashSet<String>();
        for (BookInfo b : books) all.addAll(b.vocabMap.keySet());
        return all.size();
    }

    private static void save() {
        if (cacheFile == null) return;
        File tmp = new File(cacheFile.getParentFile(), "library.bin.tmp");
        try {
            ObjectOutputStream out = new ObjectOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp)));
            try {
                out.writeObject(books);
            } finally {
                out.close();
            }
            // Only replace the live cache once the new file is complete: a crash
            // mid-write must never leave a truncated library.bin for next launch.
            if (!tmp.renameTo(cacheFile)) {
                if (cacheFile.exists()) cacheFile.delete();
                if (!tmp.renameTo(cacheFile)) tmp.delete();
            }
        } catch (Throwable e) {
            tmp.delete();
        }
    }

    /** Recomputes inverse document frequencies and book indexes. */
    private static void prepare() {
        df = new HashMap<String, Integer>();
        wordDf = new HashMap<String, Integer>();
        for (int i = 0; i < books.size(); i++) {
            BookInfo b = books.get(i);
            b.index = i;
            for (String g : b.grams.keySet()) {
                Integer old = df.get(g);
                df.put(g, old == null ? 1 : old + 1);
            }
            for (String w : b.vocabMap.keySet()) {
                Integer old = wordDf.get(w);
                wordDf.put(w, old == null ? 1 : old + 1);
            }
        }
        bookWordNorm = new double[books.size()];
        bookGramNorm = new double[books.size()];
        for (int i = 0; i < books.size(); i++) {
            BookInfo b = books.get(i);
            for (Map.Entry<String, Integer> e : b.vocabMap.entrySet()) {
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
    }

    private static double weight(String gram, int count) {
        Integer d = df.get(gram);
        int docFreq = d == null ? 0 : d;
        double idf = Math.log((books.size() + 1.0) / (docFreq + 1.0)) + 1.0;
        return count * idf;
    }

    private static double wordWeight(String word, int count) {
        Integer d = wordDf.get(word);
        int docFreq = d == null ? 0 : d;
        double idf = Math.log((books.size() + 1.0) / (docFreq + 1.0)) + 1.0;
        return count * idf * JitenRank.weight(word);
    }

    private static double cosine(double dot, double na, double nb) {
        if (na <= 0 || nb <= 0) return 0.0;
        double value = dot / Math.sqrt(na * nb);
        if (value < 0) value = 0;
        if (value > 1) value = 1;
        return value;
    }

    /**
     * Blend rank-weighted vocabulary overlap with character-pattern overlap.
     * The other book's norms are cached in prepare(), so this stays fast even
     * when a page is compared against a large library.
     */
    private static double matchScore(Map<String, Integer> vocabA,
                                     Map<String, Integer> gramsA,
                                     BookInfo other) {
        if (books.size() < 2) return 0.0;
        double wordDot = 0.0;
        double wordNorm = 0.0;
        for (Map.Entry<String, Integer> e : vocabA.entrySet()) {
            double w = wordWeight(e.getKey(), e.getValue());
            wordNorm += w * w;
            Integer count = other.vocabMap.get(e.getKey());
            if (count != null) wordDot += w * wordWeight(e.getKey(), count);
        }
        double gramDot = 0.0;
        double gramNorm = 0.0;
        for (Map.Entry<String, Integer> e : gramsA.entrySet()) {
            double w = weight(e.getKey(), e.getValue());
            gramNorm += w * w;
            Integer count = other.grams.get(e.getKey());
            if (count != null) gramDot += w * weight(e.getKey(), count);
        }
        int i = other.index;
        return 0.7 * cosine(wordDot, wordNorm, bookWordNorm[i])
                + 0.3 * cosine(gramDot, gramNorm, bookGramNorm[i]);
    }

    public static List<SimilarInfo> similarToBook(BookInfo source) {
        List<SimilarInfo> cached = bookSimilarCache.get(source.index);
        if (cached != null) return cached;
        ArrayList<SimilarInfo> out = new ArrayList<SimilarInfo>();
        for (BookInfo other : books) {
            if (other.index == source.index) continue;
            double score = matchScore(source.vocabMap, source.grams, other);
            out.add(new SimilarInfo(other.index, score, null));
        }
        Collections.sort(out);
        if (out.size() > 6) out = new ArrayList<SimilarInfo>(out.subList(0, 6));
        // Sorting the source vocabulary is expensive: only do it for the books
        // that actually made the shortlist, not for every title in the library.
        for (SimilarInfo s : out) {
            s.shared = sharedWords(source.vocabMap, books.get(s.bookIndex).vocabMap);
        }
        bookSimilarCache.put(source.index, out);
        return out;
    }

    public static List<SimilarInfo> similarToPage(PageInfo page, BookInfo source) {
        ArrayList<SimilarInfo> out = new ArrayList<SimilarInfo>();
        for (BookInfo other : books) {
            if (other.index == source.index) continue;
            double score = matchScore(page.vocab, page.grams, other);
            out.add(new SimilarInfo(other.index, score, null));
        }
        Collections.sort(out);
        if (out.size() > 5) out = new ArrayList<SimilarInfo>(out.subList(0, 5));
        for (SimilarInfo s : out) {
            s.shared = sharedWords(page.vocab, books.get(s.bookIndex).vocabMap);
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

    private static BookInfo parseEpub(File file) {
        try {
            ZipFile zip = new ZipFile(file);
            try {
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
                if (title.isEmpty()) title = stripExtension(file.getName());

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
                book.fileName = file.getName();
                book.addedAt = file.lastModified();
                book.charCount = countChars(all.toString());
                book.cover = extractCover(zip, opf, opfDir, manifest);
                book.pages = splitPages(all.toString());
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

    private static final Pattern ENTITY = Pattern.compile(
            "&(#x[0-9a-fA-F]+|#[0-9]+|nbsp|amp|lt|gt|quot|apos|mdash|ndash|hellip);",
            Pattern.CASE_INSENSITIVE);

    private static String decodeEntities(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String token = m.group(1);
            String rep = "";
            try {
                if (token.startsWith("#x")) {
                    rep = String.valueOf((char) Integer.parseInt(token.substring(2), 16));
                } else if (token.startsWith("#")) {
                    rep = String.valueOf((char) Integer.parseInt(token.substring(1)));
                } else if (token.equalsIgnoreCase("nbsp")) {
                    rep = " ";
                } else if (token.equalsIgnoreCase("amp")) {
                    rep = "&";
                } else if (token.equalsIgnoreCase("lt")) {
                    rep = "<";
                } else if (token.equalsIgnoreCase("gt")) {
                    rep = ">";
                } else if (token.equalsIgnoreCase("quot")) {
                    rep = "\"";
                } else if (token.equalsIgnoreCase("apos")) {
                    rep = "'";
                } else if (token.equalsIgnoreCase("mdash")) {
                    rep = "—";
                } else if (token.equalsIgnoreCase("ndash")) {
                    rep = "–";
                } else if (token.equalsIgnoreCase("hellip")) {
                    rep = "…";
                }
            } catch (Exception e) {
                rep = m.group(0);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style|head)\\b[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</?(p|div|h[1-6]|li|tr|section|blockquote|article)\\b[^>]*>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = decodeEntities(s);
        s = s.replace('\u3000', ' ');
        s = s.replaceAll("[ \\t\\r\\f]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private static long countChars(String text) {
        long n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) n++;
        }
        return n;
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

    private static HashMap<String, Integer> tokenize(String text, Map<String, String> snippets) {
        HashMap<String, Integer> counts = new HashMap<String, Integer>();
        int i = 0;
        int len = text.length();
        while (i < len) {
            int start = i;
            char c = text.charAt(i);
            String word = null;
            if (isKanji(c)) {
                int j = i;
                while (j < len && isKanji(text.charAt(j)) && j - i < 8) j++;
                int k = j;
                int okuri = 0;
                while (k < len && isHira(text.charAt(k)) && okuri < 3) { k++; okuri++; }
                word = text.substring(i, k);
                i = k;
            } else if (isHira(c)) {
                int j = i;
                while (j < len && isHira(text.charAt(j)) && j - i < 8) j++;
                word = text.substring(i, j);
                i = j;
            } else if (isKata(c)) {
                int j = i;
                while (j < len && isKata(text.charAt(j)) && j - i < 16) j++;
                word = text.substring(i, j);
                i = j;
            } else {
                i++;
            }
            if (word == null) continue;
            // Prefer the longest recognised prefix, as a dictionary scanner does.
            word = normalizeToken(word);
            if (word.length() < 2 || STOP.contains(word)) continue;
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
        public String fileName = "";
        public int index;
        public long addedAt;
        public long charCount;
        public byte[] cover;
        public ArrayList<PageInfo> pages = new ArrayList<PageInfo>();
        public ArrayList<VocabEntry> vocab = new ArrayList<VocabEntry>();
        public HashMap<String, Integer> vocabMap = new HashMap<String, Integer>();
        public HashMap<String, String> snippets = new HashMap<String, String>();
        public HashMap<String, Integer> grams = new HashMap<String, Integer>();

        public String summary() {
            return Ui.num(pages.size()) + " pages  ·  " + Ui.num(charCount)
                    + " chars  ·  " + Ui.num(vocab.size()) + " words";
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
