package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class VocabActivity extends Activity {

    // Existing modes keep their saved values; new ascending/reverse modes are
    // appended so older preferences still mean what they did before.
    private static final int SORT_BOOK_FREQ = 0;      // most frequent first
    private static final int SORT_JITEN_FREQ = 1;     // most common in Japanese first
    private static final int SORT_CHRONO = 2;         // first occurrence in book
    private static final int SORT_BOOK_FREQ_ASC = 3;  // least frequent first
    private static final int SORT_JITEN_FREQ_ASC = 4; // rarest first
    private static final int SORT_CHRONO_LAST = 5;    // last occurrence in book
    private static final int SORT_WORD_ASC = 6;       // A -> Z
    private static final int SORT_WORD_DESC = 7;      // Z -> A

    private static final int[] BOOK_MODES = {
            SORT_BOOK_FREQ, SORT_BOOK_FREQ_ASC,
            SORT_JITEN_FREQ, SORT_JITEN_FREQ_ASC,
            SORT_CHRONO, SORT_CHRONO_LAST,
            SORT_WORD_ASC, SORT_WORD_DESC
    };
    private static final String[] BOOK_LABELS = {
            "Book freq: high → low", "Book freq: low → high",
            "Jiten rank: common → rare", "Jiten rank: rare → common",
            "First in book", "Last in book",
            "Word A → Z", "Word Z → A"
    };
    private static final int[] ALL_MODES = {
            SORT_BOOK_FREQ, SORT_BOOK_FREQ_ASC,
            SORT_JITEN_FREQ, SORT_JITEN_FREQ_ASC,
            SORT_WORD_ASC, SORT_WORD_DESC
    };
    private static final String[] ALL_LABELS = {
            "Book freq: high → low", "Book freq: low → high",
            "Jiten rank: common → rare", "Jiten rank: rare → common",
            "Word A → Z", "Word Z → A"
    };

    private final ArrayList<Library.VocabEntry> entries =
            new ArrayList<Library.VocabEntry>(); // filtered rows shown in the list
    private ArrayList<Library.VocabEntry> allEntries =
            new ArrayList<Library.VocabEntry>(); // complete, sorted word list
    private ArrayAdapter<Library.VocabEntry> adapter;
    private TextView countLabel;
    private EditText search;
    private Spinner sortSpinner;
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
        if (oneBook) Library.ensureBookLoaded(this, bookIndex);
        book = oneBook ? Library.books.get(bookIndex) : null;
        sortMode = getSharedPreferences("mokuroku", MODE_PRIVATE)
                .getInt("vocabSort", SORT_BOOK_FREQ);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 12, 16, 8);

        search = new EditText(this);
        search.setHint("Search words");
        search.setTextSize(14);
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });
        root.addView(search);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        countLabel = Ui.label(this, "", 12, Ui.secondary(this), false);
        countLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sortSpinner = new Spinner(this);
        sortSpinner.setPrompt("Sort vocabulary");
        final int[] modes = oneBook ? BOOK_MODES : ALL_MODES;
        final String[] labels = oneBook ? BOOK_LABELS : ALL_LABELS;
        ArrayAdapter<String> sortAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels);
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sortSpinner.setAdapter(sortAdapter);
        sortSpinner.setSelection(positionOf(modes, sortMode), false);
        sortSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                int selected = modes[position];
                if (selected == sortMode) return;
                sortMode = selected;
                getSharedPreferences("mokuroku", MODE_PRIVATE).edit()
                        .putInt("vocabSort", sortMode).apply();
                applySort();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        top.addView(countLabel);
        top.addView(sortSpinner);
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
            setAllEntries(book.vocab);
            applySort();
        } else {
            setTitle("Vocabulary · all books");
            countLabel.setText("Building vocabulary…");
            if (sortMode == SORT_CHRONO || sortMode == SORT_CHRONO_LAST) {
                sortMode = SORT_BOOK_FREQ;
                sortSpinner.setSelection(positionOf(ALL_MODES, sortMode), false);
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    final List<Library.VocabEntry> all;
                    try {
                        all = Library.allVocabulary(VocabActivity.this);
                    } catch (Throwable e) {
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            setAllEntries(all);
                            applySort();
                        }
                    });
                }
            }).start();
        }
    }

    private static int positionOf(int[] modes, int mode) {
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == mode) return i;
        }
        return 0;
    }

    /** Keeps the full list; the adapter shows only rows matching the search. */
    private void setAllEntries(List<Library.VocabEntry> list) {
        allEntries = new ArrayList<Library.VocabEntry>(list);
        applyFilter();
    }

    private void applyFilter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        entries.clear();
        if (query.isEmpty()) {
            entries.addAll(allEntries);
        } else {
            for (Library.VocabEntry e : allEntries) {
                if (e.word != null && e.word.toLowerCase(Locale.ROOT).contains(query)) {
                    entries.add(e);
                }
            }
        }
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void applySort() {
        if (oneBook && (sortMode == SORT_CHRONO || sortMode == SORT_CHRONO_LAST)) {
            final boolean reverse = sortMode == SORT_CHRONO_LAST;
            if (chronoCache == null) {
                countLabel.setText("Building chronological order…");
                final int generation = ++sortGeneration;
                new Thread(new Runnable() {
                    @Override public void run() {
                        final ArrayList<Library.VocabEntry> result =
                                Library.chronologicalVocab(VocabActivity.this, book);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (isFinishing() || generation != sortGeneration) return;
                                chronoCache = result;
                                setAllEntries(reverse ? reversed(result) : result);
                            }
                        });
                    }
                }).start();
            } else {
                setAllEntries(reverse ? reversed(chronoCache) : chronoCache);
            }
            return;
        }

        final int mode = sortMode;
        if ((mode == SORT_JITEN_FREQ || mode == SORT_JITEN_FREQ_ASC) && !JMDict.isLoaded()) {
            // The full Jiten trie takes a moment to read; never do that on the UI
            // thread or a cold sort can trip Android's ANR watchdog.
            countLabel.setText("Loading Jiten ranks…");
            final int generation = ++sortGeneration;
            new Thread(new Runnable() {
                @Override public void run() {
                    JitenRank.load(getApplicationContext());
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing() || generation != sortGeneration) return;
                            applySort();
                        }
                    });
                }
            }).start();
            return;
        }
        ArrayList<Library.VocabEntry> sorted = new ArrayList<Library.VocabEntry>(allEntries);
        Collections.sort(sorted, new Comparator<Library.VocabEntry>() {
            @Override public int compare(Library.VocabEntry a, Library.VocabEntry b) {
                String wa = a.word == null ? "" : a.word;
                String wb = b.word == null ? "" : b.word;
                if (mode == SORT_BOOK_FREQ || mode == SORT_BOOK_FREQ_ASC) {
                    int c = mode == SORT_BOOK_FREQ
                            ? Integer.compare(b.count, a.count)
                            : Integer.compare(a.count, b.count);
                    if (c != 0) return c;
                    return wa.compareTo(wb);
                }
                if (mode == SORT_JITEN_FREQ || mode == SORT_JITEN_FREQ_ASC) {
                    int ra = JitenRank.rank(wa);
                    int rb = JitenRank.rank(wb);
                    int c = mode == SORT_JITEN_FREQ
                            ? Integer.compare(ra, rb) : Integer.compare(rb, ra);
                    if (c != 0) return c;
                    return wa.compareTo(wb);
                }
                return mode == SORT_WORD_ASC ? wa.compareTo(wb) : wb.compareTo(wa);
            }
        });
        setAllEntries(sorted);
    }

    private static List<Library.VocabEntry> reversed(List<Library.VocabEntry> list) {
        ArrayList<Library.VocabEntry> out = new ArrayList<Library.VocabEntry>(list);
        Collections.reverse(out);
        return out;
    }

    private void updateCount() {
        String label;
        if (sortMode == SORT_JITEN_FREQ || sortMode == SORT_JITEN_FREQ_ASC) {
            label = sortMode == SORT_JITEN_FREQ
                    ? "Jiten rank (common → rare)" : "Jiten rank (rare → common)";
        } else if (sortMode == SORT_CHRONO || sortMode == SORT_CHRONO_LAST) {
            label = sortMode == SORT_CHRONO ? "first occurrence" : "last occurrence";
        } else if (sortMode == SORT_WORD_ASC || sortMode == SORT_WORD_DESC) {
            label = sortMode == SORT_WORD_ASC ? "word A → Z" : "word Z → A";
        } else if (!oneBook) {
            label = sortMode == SORT_BOOK_FREQ
                    ? "frequency (high → low)" : "frequency (low → high)";
        } else {
            label = sortMode == SORT_BOOK_FREQ
                    ? "book freq (high → low)" : "book freq (low → high)";
        }
        String shown = search.getText().toString().trim().isEmpty()
                ? Ui.num(entries.size())
                : Ui.num(entries.size()) + " of " + Ui.num(allEntries.size());
        countLabel.setText(shown + " words  ·  " + label
                + ". Tap to copy or look up a meaning.");
    }
}
