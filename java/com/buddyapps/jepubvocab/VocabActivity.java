package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class VocabActivity extends Activity {

    private static final int SORT_BOOK_FREQ = 0;
    private static final int SORT_JITEN_FREQ = 1;
    private static final int SORT_CHRONO = 2;

    private final ArrayList<Library.VocabEntry> entries =
            new ArrayList<Library.VocabEntry>();
    private ArrayAdapter<Library.VocabEntry> adapter;
    private TextView countLabel;
    private ImageButton sortButton;
    private boolean oneBook;
    private Library.BookInfo book;
    private int sortMode;
    private int sortGeneration;
    private ArrayList<Library.VocabEntry> chronoCache;

    private static final class Row {
        TextView word;
        TextView snippet;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int bookIndex = getIntent().getIntExtra("book", -1);
        oneBook = bookIndex >= 0 && bookIndex < Library.books.size();
        book = oneBook ? Library.books.get(bookIndex) : null;
        sortMode = getSharedPreferences("mokuroku", MODE_PRIVATE)
                .getInt("vocabSort", SORT_BOOK_FREQ);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 12, 16, 8);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        countLabel = Ui.label(this, "", 13, Ui.secondary(this), false);
        countLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sortButton = Ui.iconButton(this, R.drawable.ic_sort, "Sort vocabulary",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        sortMode = (sortMode + 1) % 3;
                        getSharedPreferences("mokuroku", MODE_PRIVATE).edit()
                                .putInt("vocabSort", sortMode).apply();
                        applySort();
                    }
                });
        top.addView(countLabel);
        top.addView(sortButton);
        root.addView(top);

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
            setTitle("Vocabulary · " + book.title);
            entries.addAll(book.vocab);
            applySort();
        } else {
            setTitle("Vocabulary · all books");
            sortButton.setVisibility(View.GONE);
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
                            showEntries(all);
                        }
                    });
                }
            }).start();
        }
    }

    private void applySort() {
        if (!oneBook || book == null) return;
        if (sortMode == SORT_CHRONO) {
            if (chronoCache == null) {
                countLabel.setText("Building chronological order…");
                final int generation = ++sortGeneration;
                new Thread(new Runnable() {
                    @Override public void run() {
                        final ArrayList<Library.VocabEntry> result =
                                Library.chronologicalVocab(book);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (isFinishing() || generation != sortGeneration) return;
                                chronoCache = result;
                                showEntries(result);
                            }
                        });
                    }
                }).start();
            } else {
                showEntries(chronoCache);
            }
            return;
        }
        final int mode = sortMode;
        Collections.sort(entries, new Comparator<Library.VocabEntry>() {
            @Override public int compare(Library.VocabEntry a, Library.VocabEntry b) {
                if (mode == SORT_JITEN_FREQ) {
                    int j = Integer.compare(JitenRank.rank(a.word), JitenRank.rank(b.word));
                    if (j != 0) return j;
                }
                int c = Integer.compare(b.count, a.count);
                return c != 0 ? c : a.word.compareTo(b.word);
            }
        });
        showEntries(entries);
    }

    private void showEntries(List<Library.VocabEntry> list) {
        if (list != entries) {
            entries.clear();
            entries.addAll(list);
        }
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        String label;
        if (!oneBook) label = "frequency";
        else if (sortMode == SORT_JITEN_FREQ) label = "Jiten frequency";
        else if (sortMode == SORT_CHRONO) label = "chronological order";
        else label = "book frequency";
        countLabel.setText(Ui.num(entries.size()) + " words  ·  sorted by " + label
                + ". Tap to copy or look up a meaning.");
        sortButton.setContentDescription("Sort vocabulary: " + label);
        sortButton.setTooltipText("Sort: " + label + " (tap to change)");
    }
}
