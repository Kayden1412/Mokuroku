package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VocabActivity extends Activity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int bookIndex = getIntent().getIntExtra("book", -1);
        boolean oneBook = bookIndex >= 0 && bookIndex < Library.books.size();
        final List<Library.VocabEntry> entries = new ArrayList<Library.VocabEntry>();

        if (oneBook) {
            Library.BookInfo book = Library.books.get(bookIndex);
            entries.addAll(book.vocab);
            setTitle("Vocabulary · " + book.title);
        } else {
            HashMap<String, Integer> counts = new HashMap<String, Integer>();
            HashMap<String, String> snippets = new HashMap<String, String>();
            for (Library.BookInfo b : Library.books) {
                for (Library.VocabEntry v : b.vocab) {
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
            for (String w : words) {
                String s = snippets.get(w);
                entries.add(new Library.VocabEntry(w, c.get(w), s == null ? "" : s));
            }
            setTitle("Vocabulary · all books");
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 12, 16, 8);
        root.addView(Ui.label(this, Ui.num(entries.size()) + " words  ·  sorted by frequency. "
                + "Tap to copy or look up a meaning.", 13, Ui.secondary(this), false));

        ListView list = new ListView(this);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list.setAdapter(new ArrayAdapter<Library.VocabEntry>(this,
                android.R.layout.simple_list_item_1, entries) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                Library.VocabEntry v = getItem(position);
                LinearLayout row = new LinearLayout(VocabActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(16, 10, 16, 10);
                row.addView(Ui.label(VocabActivity.this, v.word + "    ×" + Ui.num(v.count),
                        16, Ui.primary(VocabActivity.this), true));
                if (v.snippet != null && !v.snippet.isEmpty()) {
                    row.addView(Ui.label(VocabActivity.this, v.snippet,
                            12, Ui.faint(VocabActivity.this), false));
                }
                return row;
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Library.VocabEntry entry = entries.get(pos);
                Ui.wordDialog(VocabActivity.this, entry.word, entry.snippet);
            }
        });
        root.addView(list);
        setContentView(root);
    }
}
