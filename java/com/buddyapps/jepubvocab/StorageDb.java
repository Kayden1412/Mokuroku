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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite store for parsed books. The extracted text, vocabulary, word profiles
 * and resized cover live in one BLOB per book, so the original EPUB is never
 * needed again and nothing has to be reparsed at launch.
 *
 * The database version is tied to Library.CACHE_VERSION: bumping the parser
 * version drops old rows and makes them rebuild once on the next import.
 */
final class StorageDb extends SQLiteOpenHelper {

    private static final String TABLE = "books";
    private static final String DB_NAME = "mokuroku.db";

    StorageDb(Context c) {
        super(c, DB_NAME, null, Library.CACHE_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE
                + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, data BLOB)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    private static byte[] serialize(Library.BookInfo book) {
        try {
            // Tokens are created as fresh String objects for every occurrence.
            // Canonicalising them lets ObjectOutputStream write each distinct
            // word once per book instead of once per page and map.
            Map<String, String> pool = new HashMap<String, String>();
            canonicalize(book, pool);
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

    private static HashMap<String, String> canonicalValues(HashMap<String, String> map,
                                                            Map<String, String> pool) {
        HashMap<String, String> out = new HashMap<String, String>(map.size());
        for (Map.Entry<String, String> e : map.entrySet()) {
            out.put(canonical(e.getKey(), pool), canonical(e.getValue(), pool));
        }
        return out;
    }

    private static void canonicalize(Library.BookInfo book, Map<String, String> pool) {
        book.title = canonical(book.title, pool);
        book.author = canonical(book.author, pool);
        book.fileName = canonical(book.fileName, pool);
        for (Library.PageInfo page : book.pages) {
            page.text = canonical(page.text, pool);
            page.vocab = canonicalKeys(page.vocab, pool);
            page.snippets = canonicalValues(page.snippets, pool);
            page.grams = canonicalKeys(page.grams, pool);
            for (Library.VocabEntry v : page.vocabList) {
                v.word = canonical(v.word, pool);
                v.snippet = canonical(v.snippet, pool);
            }
            page.firstSnippetWord = canonical(page.firstSnippetWord, pool);
            page.firstSnippet = canonical(page.firstSnippet, pool);
        }
        for (Library.VocabEntry v : book.vocab) {
            v.word = canonical(v.word, pool);
            v.snippet = canonical(v.snippet, pool);
        }
        book.vocabMap = canonicalKeys(book.vocabMap, pool);
        book.snippets = canonicalValues(book.snippets, pool);
        book.grams = canonicalKeys(book.grams, pool);
    }

    private static Library.BookInfo deserialize(byte[] data) {
        try {
            ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data));
            try {
                Object o = in.readObject();
                return o instanceof Library.BookInfo ? (Library.BookInfo) o : null;
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
            db.delete(TABLE, null, null);
            for (Library.BookInfo book : books) {
                byte[] data = serialize(book);
                if (data == null) continue;
                ContentValues cv = new ContentValues();
                cv.put("name", book.fileName);
                cv.put("data", data);
                db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    synchronized ArrayList<Library.BookInfo> loadBooks() {
        ArrayList<Library.BookInfo> out = new ArrayList<Library.BookInfo>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, new String[]{"data"}, null, null, null, null, "_id");
        try {
            while (c.moveToNext()) {
                byte[] data = c.getBlob(0);
                if (data == null) continue;
                Library.BookInfo book = deserialize(data);
                if (book != null) out.add(book);
            }
        } finally {
            c.close();
        }
        return out;
    }

    synchronized long bytes(String name) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, new String[]{"LENGTH(data)"}, "name=?",
                new String[]{name}, null, null, null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } finally {
            c.close();
        }
    }

    synchronized long dataBytes() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COALESCE(SUM(LENGTH(data)),0) FROM " + TABLE, null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } finally {
            c.close();
        }
    }

    synchronized int count() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    synchronized void delete(String name) {
        getWritableDatabase().delete(TABLE, "name=?", new String[]{name});
    }

    synchronized void clear() {
        getWritableDatabase().delete(TABLE, null, null);
    }
}
