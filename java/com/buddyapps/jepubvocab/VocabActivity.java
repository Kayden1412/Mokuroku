package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class VocabActivity extends Activity {

    private final ArrayList<Library.VocabEntry> entries =
            new ArrayList<Library.VocabEntry>();
    private ArrayAdapter<Library.VocabEntry> adapter;
    private TextView countLabel;

    private static final class Row {
        TextView word;
        TextView snippet;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int bookIndex = getIntent().getIntExtra("book", -1);
        final boolean oneBook = bookIndex >= 0 && bookIndex < Library.books.size();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 12, 16, 8);

        countLabel = Ui.label(this, "", 13, Ui.secondary(this), false);
        root.addView(countLabel);

        ListView list = new ListView(this);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new ArrayAdapter<Library.VocabEntry>(this,
                android.R.layout.simple_list_item_1, entries) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                Library.VocabEntry v = getItem(position);
                LinearLayout row;
                Row holder;
                if (convertView instanceof LinearLayout
                        && convertView.getTag() instanceof Row) {
                    row = (LinearLayout) convertView;
                    holder = (Row) row.getTag();
                } else {
                    row = new LinearLayout(VocabActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(16, 10, 16, 10);
                    holder = new Row();
                    holder.word = Ui.label(VocabActivity.this, "",
                            16, Ui.primary(VocabActivity.this), true);
                    holder.snippet = Ui.label(VocabActivity.this, "",
                            12, Ui.faint(VocabActivity.this), false);
                    row.addView(holder.word);
                    row.addView(holder.snippet);
                    row.setTag(holder);
                }
                holder.word.setText(v.word + "    ×" + Ui.num(v.count));
                if (v.snippet == null || v.snippet.isEmpty()) {
                    holder.snippet.setVisibility(View.GONE);
                } else {
                    holder.snippet.setText(v.snippet);
                    holder.snippet.setVisibility(View.VISIBLE);
                }
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Library.VocabEntry entry = entries.get(pos);
                Ui.wordDialog(VocabActivity.this, entry.word, entry.snippet);
            }
        });
        root.addView(list);
        setContentView(root);

        if (oneBook) {
            Library.BookInfo book = Library.books.get(bookIndex);
            setTitle("Vocabulary · " + book.title);
            entries.addAll(book.vocab);
            adapter.notifyDataSetChanged();
            updateCount();
        } else {
            setTitle("Vocabulary · all books");
            countLabel.setText("Building vocabulary…");
            new Thread(new Runnable() {
                @Override public void run() {
                    final List<Library.VocabEntry> all;
                    try {
                        all = Library.allVocabulary();
                    } catch (Throwable e) {
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            entries.addAll(all);
                            adapter.notifyDataSetChanged();
                            updateCount();
                        }
                    });
                }
            }).start();
        }
    }

    private void updateCount() {
        countLabel.setText(Ui.num(entries.size()) + " words  ·  sorted by frequency. "
                + "Tap to copy or look up a meaning.");
    }
}
