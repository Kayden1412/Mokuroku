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
 * SQLite store for parsed books. The extracted text, vocabulary, word profiles
 * and resized cover are stored here, so the original EPUB is never needed again
 * and nothing has to be reparsed at launch.
 *
 * Each book is split into 256 KB chunks: Android's CursorWindow rejects single
 * rows larger than about 2 MB, which a whole book regularly exceeds.
 */
final class StorageDb extends SQLiteOpenHelper {

    private static final String BOOKS = "books";
    private static final String CHUNKS = "chunks";
    private static final String DB_NAME = "mokuroku.db";
    // Bump together with Library.CACHE_VERSION when the stored format changes.
    private static final int DB_VERSION = 9;
    private static final int CHUNK_BYTES = 256 * 1024;

    StorageDb(Context c) {
        super(c, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + BOOKS
                + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, size INTEGER)");
        db.execSQL("CREATE TABLE " + CHUNKS
                + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, book_id INTEGER, seq INTEGER,"
                + " data BLOB)");
        db.execSQL("CREATE INDEX chunk_lookup ON " + CHUNKS + " (book_id, seq)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + CHUNKS);
        db.execSQL("DROP TABLE IF EXISTS " + BOOKS);
        onCreate(db);
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
     * are rebuilt from the page text on load. Tokens are also canonicalised so
     * repeated words are written once per book.
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

    private static Library.BookInfo deserialize(byte[] data) {
        try {
            ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data));
            try {
                Object o = in.readObject();
                if (!(o instanceof Library.BookInfo)) return null;
                Library.BookInfo book = (Library.BookInfo) o;
                Library.rebuildDerived(book);
                return book;
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return null;
        }
    }

    /** Replaces the stored library in one transaction so a crash cannot leave half of it. */
    synchronized void saveBooks(List<Library.BookInfo> books) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(CHUNKS, null, null);
            db.delete(BOOKS, null, null);
            for (Library.BookInfo book : books) {
                byte[] data = serialize(book);
                if (data == null) continue;
                ContentValues cv = new ContentValues();
                cv.put("name", book.fileName);
                cv.put("size", data.length);
                long id = db.insert(BOOKS, null, cv);
                if (id < 0) continue;
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
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized ArrayList<Library.BookInfo> loadBooks() {
        ArrayList<Library.BookInfo> out = new ArrayList<Library.BookInfo>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(BOOKS, new String[]{"_id", "name"}, null, null, null, null, "_id");
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                Cursor cc = db.query(CHUNKS, new String[]{"data"}, "book_id=?",
                        new String[]{String.valueOf(id)}, null, null, "seq");
                try {
                    while (cc.moveToNext()) {
                        byte[] part = cc.getBlob(0);
                        if (part != null) bytes.write(part, 0, part.length);
                    }
                } finally {
                    cc.close();
                }
                Library.BookInfo book = deserialize(bytes.toByteArray());
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
        db.execSQL("DELETE FROM " + CHUNKS + " WHERE book_id IN "
                + "(SELECT _id FROM " + BOOKS + " WHERE name=?)", new Object[]{name});
        db.delete(BOOKS, "name=?", new String[]{name});
    }

    synchronized void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(CHUNKS, null, null);
        db.delete(BOOKS, null, null);
    }
}
