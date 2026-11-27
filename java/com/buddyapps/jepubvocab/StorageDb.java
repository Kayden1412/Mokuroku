package com.buddyapps.jepubvocab;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite store for parsed books. Three kinds of rows are kept apart:
 *
 * <ul>
 * <li>{@code books}: small metadata rows (title, stats, cover, sizes);</li>
 * <li>{@code chunks}: the full parsed book, split into 256 KB pieces because
 * Android's CursorWindow rejects single rows larger than about 2 MB;</li>
 * <li>{@code profiles}: precomputed similarity fingerprints and shortlists, so
 * ordering the library and opening a book never needs the full text.</li>
 * </ul>
 */
final class StorageDb extends SQLiteOpenHelper {

    private static final String BOOKS = "books";
    private static final String CHUNKS = "chunks";
    private static final String PROFILES = "profiles";
    private static final String DB_NAME = "mokuroku.db";
    // Bump together with Library.CACHE_VERSION when the stored format changes.
    private static final int DB_VERSION = 11;
    private static final int CHUNK_BYTES = 256 * 1024;

    private final Context appContext;

    StorageDb(Context c) {
        super(c, DB_NAME, null, DB_VERSION);
        Context app = c.getApplicationContext();
        this.appContext = app != null ? app : c;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createBooks(db);
        createChunks(db);
        createProfiles(db);
    }

    private static void createBooks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + BOOKS
                + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE,"
                + " size INTEGER, meta BLOB)");
    }

    private static void createChunks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + CHUNKS
                + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, book_id INTEGER, seq INTEGER,"
                + " data BLOB)");
        db.execSQL("CREATE INDEX chunk_lookup ON " + CHUNKS + " (book_id, seq)");
    }

    private static void createProfiles(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + PROFILES
                + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, book_id INTEGER UNIQUE,"
                + " data BLOB)");
        db.execSQL("CREATE INDEX profile_lookup ON " + PROFILES + " (book_id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion >= 11) return;
        if (oldVersion < 9) {
            db.execSQL("DROP TABLE IF EXISTS " + CHUNKS);
            db.execSQL("DROP TABLE IF EXISTS " + BOOKS);
            db.execSQL("DROP TABLE IF EXISTS " + PROFILES);
            onCreate(db);
            return;
        }
        if (oldVersion < 10) {
            // v9 -> v10 keeps every parsed book and derives the new metadata row
            // from the stored body. One slow upgrade is cheaper than reparsing.
            db.execSQL("ALTER TABLE " + BOOKS + " ADD COLUMN meta BLOB");
            createProfiles(db);
            populateMeta(db);
        }
        if (oldVersion < 11) {
            retokenizeAll(db);
        }
    }

    private void retokenizeAll(SQLiteDatabase db) {
        Library.ensureDictionaries(appContext);
        ArrayList<Long> ids = new ArrayList<Long>();
        ArrayList<String> names = new ArrayList<String>();
        Cursor c = db.query(BOOKS, new String[]{"_id", "name"}, null, null, null, null, "_id");
        try {
            while (c.moveToNext()) {
                ids.add(c.getLong(0));
                names.add(c.getString(1));
            }
        } finally {
            c.close();
        }
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            String name = names.get(i);
            byte[] raw = readChunks(db, id);
            if (raw == null || raw.length == 0) continue;
            Object o = readObject(raw);
            if (!(o instanceof Library.BookInfo)) continue;
            Library.BookInfo book = (Library.BookInfo) o;
            if (book.fileName == null || book.fileName.isEmpty()) book.fileName = name;
            Library.retokenizeBook(book);
            byte[] data = serialize(book);
            byte[] meta = serializeMeta(book);
            if (data == null || meta == null) continue;
            db.delete(CHUNKS, "book_id=?", new String[]{String.valueOf(id)});
            writeChunks(db, id, data);
            ContentValues cv = new ContentValues();
            cv.put("size", data.length);
            cv.put("meta", meta);
            db.update(BOOKS, cv, "_id=?", new String[]{String.valueOf(id)});
        }
        db.delete(PROFILES, null, null);
    }

    private static void populateMeta(SQLiteDatabase db) {
        Cursor c = db.query(BOOKS, new String[]{"_id", "name"}, null, null, null, null, "_id");
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                Library.BookInfo book = deserialize(readChunks(db, id));
                if (book == null) continue;
                if (book.fileName == null || book.fileName.isEmpty()) book.fileName = name;
                ContentValues cv = new ContentValues();
                cv.put("meta", serializeMeta(book));
                db.update(BOOKS, cv, "_id=?", new String[]{String.valueOf(id)});
            }
        } finally {
            c.close();
        }
    }

    private static String canonical(String s, Map<String, String> pool) {
        if (s == null) return null;
        String shared = pool.get(s);
        if (shared != null) return shared;
        pool.put(s, s);
        return s;
    }

    private static <V> HashMap<String, V> canonicalKeys(HashMap<String, V> map,
                                                          Map<String, String> pool) {
        HashMap<String, V> out = new HashMap<String, V>(map.size());
        for (Map.Entry<String, V> e : map.entrySet()) {
            out.put(canonical(e.getKey(), pool), e.getValue());
        }
        return out;
    }

    /**
     * Stores only what cannot be derived cheaply: page text, word counts, gram
     * counts and snippet keys. Vocabulary lists, book totals and snippet strings
     * are rebuilt from the page text when one book is opened. Tokens are also
     * canonicalised so repeated words are written once per book.
     */
    private static byte[] serialize(Library.BookInfo book) {
        ArrayList<Library.VocabEntry> bookVocab = book.vocab;
        HashMap<String, Integer> bookVocabMap = book.vocabMap;
        HashMap<String, String> bookSnippets = book.snippets;
        HashMap<String, Integer> bookGrams = book.grams;
        book.vocab = new ArrayList<Library.VocabEntry>();
        book.vocabMap = new HashMap<String, Integer>();
        book.snippets = new HashMap<String, String>();
        book.grams = new HashMap<String, Integer>();

        ArrayList<ArrayList<Library.VocabEntry>> pageLists =
                new ArrayList<ArrayList<Library.VocabEntry>>();
        ArrayList<HashMap<String, String>> pageSnippets =
                new ArrayList<HashMap<String, String>>();
        ArrayList<String> firstWords = new ArrayList<String>();
        ArrayList<String> firstSnippets = new ArrayList<String>();
        try {
            Map<String, String> pool = new HashMap<String, String>();
            book.title = canonical(book.title, pool);
            book.author = canonical(book.author, pool);
            book.fileName = canonical(book.fileName, pool);
            for (Library.PageInfo page : book.pages) {
                page.text = canonical(page.text, pool);
                page.vocab = canonicalKeys(page.vocab, pool);
                page.grams = canonicalKeys(page.grams, pool);

                pageLists.add(page.vocabList);
                pageSnippets.add(page.snippets);
                firstWords.add(page.firstSnippetWord);
                firstSnippets.add(page.firstSnippet);

                HashMap<String, String> keysOnly = new HashMap<String, String>();
                for (String word : page.snippets.keySet()) {
                    keysOnly.put(canonical(word, pool), "");
                }
                page.snippets = keysOnly;
                page.vocabList = new ArrayList<Library.VocabEntry>();
                page.firstSnippetWord = "";
                page.firstSnippet = "";
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(bytes);
            try {
                out.writeObject(book);
            } finally {
                out.close();
            }
            return bytes.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            book.vocab = bookVocab;
            book.vocabMap = bookVocabMap;
            book.snippets = bookSnippets;
            book.grams = bookGrams;
            for (int i = 0; i < book.pages.size(); i++) {
                Library.PageInfo page = book.pages.get(i);
                if (i < pageLists.size()) page.vocabList = pageLists.get(i);
                if (i < pageSnippets.size()) page.snippets = pageSnippets.get(i);
                if (i < firstWords.size()) page.firstSnippetWord = firstWords.get(i);
                if (i < firstSnippets.size()) page.firstSnippet = firstSnippets.get(i);
            }
        }
    }

    private static byte[] objectBytes(Object object) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(bytes);
            try {
                out.writeObject(object);
            } finally {
                out.close();
            }
            return bytes.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object readObject(byte[] data) {
        try {
            ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data));
            try {
                return in.readObject();
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] serializeMeta(Library.BookInfo book) {
        return objectBytes(Library.metaFor(book));
    }

    private static Library.BookInfo deserializeMeta(byte[] data) {
        Object o = readObject(data);
        return o instanceof Library.BookMeta ? ((Library.BookMeta) o).toBook() : null;
    }

    private static byte[] serializeProfile(Library.BookProfile profile) {
        return objectBytes(profile);
    }

    private static Library.BookProfile deserializeProfile(byte[] data) {
        Object o = readObject(data);
        return o instanceof Library.BookProfile ? (Library.BookProfile) o : null;
    }

    private static Library.BookInfo deserialize(byte[] data) {
        if (data == null || data.length == 0) return null;
        Object o = readObject(data);
        if (!(o instanceof Library.BookInfo)) return null;
        Library.BookInfo book = (Library.BookInfo) o;
        Library.rebuildDerived(book);
        book.loaded = true;
        if (book.pageCount <= 0) book.pageCount = book.pages.size();
        if (book.vocabCount <= 0) book.vocabCount = book.vocab.size();
        return book;
    }

    /** Replaces the stored library in one transaction (used for full restores). */
    synchronized void saveBooks(List<Library.BookInfo> books) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(PROFILES, null, null);
            db.delete(CHUNKS, null, null);
            db.delete(BOOKS, null, null);
            for (Library.BookInfo book : books) insertBookInTx(db, book);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Adds one newly parsed book without touching the other bodies. */
    synchronized void insertBook(Library.BookInfo book) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            insertBookInTx(db, book);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void insertBookInTx(SQLiteDatabase db, Library.BookInfo book) {
        byte[] data = serialize(book);
        byte[] meta = serializeMeta(book);
        if (data == null || meta == null) return;
        ContentValues cv = new ContentValues();
        cv.put("name", book.fileName);
        cv.put("size", data.length);
        cv.put("meta", meta);
        long id = db.insert(BOOKS, null, cv);
        if (id < 0) return;
        writeChunks(db, id, data);
    }

    private static void writeChunks(SQLiteDatabase db, long id, byte[] data) {
        int seq = 0;
        for (int off = 0; off < data.length; off += CHUNK_BYTES) {
            int end = Math.min(data.length, off + CHUNK_BYTES);
            ContentValues chunk = new ContentValues();
            chunk.put("book_id", id);
            chunk.put("seq", seq++);
            chunk.put("data", Arrays.copyOfRange(data, off, end));
            db.insert(CHUNKS, null, chunk);
        }
    }

    /** Loads only the small metadata row for every book. */
    synchronized ArrayList<Library.BookInfo> loadMeta() {
        ArrayList<Library.BookInfo> out = new ArrayList<Library.BookInfo>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(BOOKS, new String[]{"_id", "name", "meta"},
                null, null, null, null, "_id");
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                Library.BookInfo book = c.isNull(2) ? null : deserializeMeta(c.getBlob(2));
                if (book == null) {
                    Library.BookInfo full = deserialize(readChunks(db, id));
                    if (full != null) book = Library.metaFor(full).toBook();
                }
                if (book != null) {
                    if (book.fileName == null || book.fileName.isEmpty()) book.fileName = name;
                    out.add(book);
                }
            }
        } finally {
            c.close();
        }
        return out;
    }

    private static byte[] readChunks(SQLiteDatabase db, long id) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Cursor cc = db.query(CHUNKS, new String[]{"data"}, "book_id=?",
                new String[]{String.valueOf(id)}, null, null, "seq");
        try {
            while (cc.moveToNext()) {
                byte[] part = cc.getBlob(0);
                if (part != null) bytes.write(part, 0, part.length);
            }
        } catch (Throwable e) {
            return null;
        } finally {
            cc.close();
        }
        return bytes.toByteArray();
    }

    private static long bookId(SQLiteDatabase db, String name) {
        Cursor c = db.query(BOOKS, new String[]{"_id"}, "name=?",
                new String[]{name}, null, null, null);
        try {
            return c.moveToFirst() ? c.getLong(0) : -1L;
        } finally {
            c.close();
        }
    }

    /** Loads one full book body, including its page text and vocabulary. */
    synchronized Library.BookInfo loadBook(String name) {
        SQLiteDatabase db = getReadableDatabase();
        long id = bookId(db, name);
        if (id < 0) return null;
        Library.BookInfo book = deserialize(readChunks(db, id));
        if (book != null && (book.fileName == null || book.fileName.isEmpty())) {
            book.fileName = name;
        }
        return book;
    }

    /** Loads similarity profiles in the same order as {@link #loadMeta()}. */
    synchronized ArrayList<Library.BookProfile> loadProfiles() {
        ArrayList<Library.BookProfile> out = new ArrayList<Library.BookProfile>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(BOOKS, new String[]{"_id"}, null, null, null, null, "_id");
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                Library.BookProfile profile = null;
                Cursor pc = db.query(PROFILES, new String[]{"data"}, "book_id=?",
                        new String[]{String.valueOf(id)}, null, null, null);
                try {
                    if (pc.moveToFirst() && !pc.isNull(0)) {
                        profile = deserializeProfile(pc.getBlob(0));
                    }
                } finally {
                    pc.close();
                }
                out.add(profile);
            }
        } finally {
            c.close();
        }
        return out;
    }

    synchronized void saveProfiles(List<Library.BookInfo> books,
                                   List<Library.BookProfile> profiles) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(PROFILES, null, null);
            for (int i = 0; i < books.size(); i++) {
                long id = bookId(db, books.get(i).fileName);
                if (id < 0) continue;
                Library.BookProfile p = i < profiles.size()
                        ? profiles.get(i) : new Library.BookProfile();
                byte[] data = serializeProfile(p);
                if (data == null) continue;
                ContentValues cv = new ContentValues();
                cv.put("book_id", id);
                cv.put("data", data);
                db.insert(PROFILES, null, cv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized void clearProfiles() {
        getWritableDatabase().delete(PROFILES, null, null);
    }

    synchronized long bytes(String name) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(BOOKS, new String[]{"size"}, "name=?",
                new String[]{name}, null, null, null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } finally {
            c.close();
        }
    }

    synchronized long dataBytes() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COALESCE(SUM(size),0) FROM " + BOOKS, null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } finally {
            c.close();
        }
    }

    synchronized int count() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + BOOKS, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    synchronized void delete(String name) {
        SQLiteDatabase db = getWritableDatabase();
        Object[] args = new Object[]{name};
        String where = "book_id IN (SELECT _id FROM " + BOOKS + " WHERE name=?)";
        db.execSQL("DELETE FROM " + CHUNKS + " WHERE " + where, args);
        db.execSQL("DELETE FROM " + PROFILES + " WHERE " + where, args);
        db.delete(BOOKS, "name=?", new String[]{name});
    }

    synchronized void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(CHUNKS, null, null);
        db.delete(PROFILES, null, null);
        db.delete(BOOKS, null, null);
    }
}
