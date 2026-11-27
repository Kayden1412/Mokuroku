package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class BookActivity extends Activity {

    private int bookIndex;
    private int minMatch;
    private boolean excludeSameAuthor;
    private ImageButton authorButton;
    private List<Library.SimilarInfo> allSimilar = new ArrayList<Library.SimilarInfo>();
    private boolean computingSimilar = true;
    private final ArrayList<Library.SimilarInfo> displayed = new ArrayList<Library.SimilarInfo>();
    private ArrayAdapter<Library.SimilarInfo> similarAdapter;
    private TextView filterLabel;
    private TextView emptyNote;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bookIndex = getIntent().getIntExtra("book", -1);
        if (!valid()) { finish(); return; }
        minMatch = getSharedPreferences("mokuroku", MODE_PRIVATE)
                .getInt("min_match", 0);
        excludeSameAuthor = getSharedPreferences("mokuroku", MODE_PRIVATE)
                .getBoolean("exclude_same_author", false);
        final Library.BookInfo book = Library.books.get(bookIndex);
        setTitle(book.title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 12);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(Ui.coverView(this, book, 72, 100));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.label(this, book.title, 18, Ui.primary(this), true);
        title.setMaxLines(3);
        heading.addView(title);
        String subtitle = book.fileName;
        if (book.author != null && !book.author.trim().isEmpty()) {
            subtitle += "  ·  " + book.author.trim();
        }
        subtitle += "  ·  " + book.summary();
        heading.addView(Ui.label(this, subtitle, 11, Ui.secondary(this), false));
        header.addView(heading, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(action(R.drawable.ic_vocab, "Read pages", new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.openReader(BookActivity.this, bookIndex); }
        }));
        buttons.addView(action(R.drawable.ic_notes, "Vocabulary", new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.content.Intent i = new android.content.Intent(
                        BookActivity.this, VocabActivity.class);
                i.putExtra("book", bookIndex);
                startActivity(i);
            }
        }));
        buttons.addView(action(R.drawable.ic_delete, "Delete book", new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDelete(); }
        }));
        authorButton = action(R.drawable.ic_author, "Exclude same author",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                excludeSameAuthor = !excludeSameAuthor;
                getSharedPreferences("mokuroku", MODE_PRIVATE).edit()
                        .putBoolean("exclude_same_author", excludeSameAuthor).apply();
                updateAuthorButton();
                applyFilter();
            }
        });
        buttons.addView(authorButton);
        updateAuthorButton();
        root.addView(buttons);

        root.addView(Ui.divider(this));
        root.addView(Ui.label(this, "Books with similar content", 16, Ui.primary(this), true));
        root.addView(Ui.label(this,
                "Based on shared words and character patterns across your library.",
                12, Ui.faint(this), false));

        // Similarity scans the whole library; compute it off the UI thread so
        // opening a book stays instant. Results are cached by Library.
        allSimilar = new ArrayList<Library.SimilarInfo>();

        LinearLayout filter = new LinearLayout(this);
        filter.setOrientation(LinearLayout.HORIZONTAL);
        filterLabel = Ui.label(this, "", 13, Ui.secondary(this), false);
        filterLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        SeekBar seek = new SeekBar(this);
        seek.setContentDescription("Minimum match percentage");
        seek.setMax(100);
        seek.setProgress(minMatch);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.blue(this)));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.blue(this)));
        seek.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 180),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                minMatch = progress;
                getSharedPreferences("mokuroku", MODE_PRIVATE).edit()
                        .putInt("min_match", minMatch).apply();
                applyFilter();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        filter.addView(filterLabel);
        filter.addView(seek);
        root.addView(filter);

        ListView list = new ListView(this);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        similarAdapter = new ArrayAdapter<Library.SimilarInfo>(this,
                android.R.layout.simple_list_item_1, displayed) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                Library.SimilarInfo s = getItem(position);
                Library.BookInfo other = Library.books.get(s.bookIndex);
                String subtitle = s.percent() + " match";
                if (!s.sharedText().isEmpty()) subtitle += "  ·  " + s.sharedText();
                return Ui.bookRow(BookActivity.this, other, subtitle);
            }
        };
        list.setAdapter(similarAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Ui.openBook(BookActivity.this, displayed.get(pos).bookIndex);
            }
        });
        root.addView(list);

        emptyNote = Ui.label(this, "", 14, Ui.faint(this), false);
        emptyNote.setPadding(16, 24, 16, 24);
        root.addView(emptyNote);

        applyFilter();
        setContentView(root);

        final Library.BookInfo source = book;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Library.SimilarInfo> found;
                try {
                    found = Library.similarToBook(source);
                } catch (Throwable e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            computingSimilar = false;
                            applyFilter();
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        allSimilar = found;
                        computingSimilar = false;
                        applyFilter();
                    }
                });
            }
        }).start();
    }

    private void applyFilter() {
        filterLabel.setText("Minimum match: " + minMatch + "%");
        displayed.clear();
        Library.BookInfo source = Library.books.get(bookIndex);
        for (Library.SimilarInfo s : allSimilar) {
            if (s.percentValue() < minMatch) continue;
            if (excludeSameAuthor
                    && sameAuthor(source, Library.books.get(s.bookIndex))) continue;
            displayed.add(s);
        }
        if (similarAdapter != null) similarAdapter.notifyDataSetChanged();
        if (emptyNote != null) {
            emptyNote.setVisibility(displayed.isEmpty() ? View.VISIBLE : View.GONE);
            if (computingSimilar) {
                emptyNote.setText("Finding similar books…");
            } else if (allSimilar.isEmpty()) {
                emptyNote.setText("Add more EPUBs to see similar books here.");
            } else {
                String note = "No books match at least " + minMatch + "%.";
                if (excludeSameAuthor) note += " Same-author books are excluded.";
                emptyNote.setText(note);
            }
        }
    }

    private void updateAuthorButton() {
        int color = excludeSameAuthor ? Ui.blue(this) : Ui.secondary(this);
        authorButton.setImageTintList(ColorStateList.valueOf(color));
        String description = (excludeSameAuthor ? "Include" : "Exclude")
                + " books by the same author";
        authorButton.setContentDescription(description);
        authorButton.setTooltipText(description);
    }

    private static boolean sameAuthor(Library.BookInfo a, Library.BookInfo b) {
        if (a.author == null || b.author == null) return false;
        String x = a.author.trim().toLowerCase(java.util.Locale.ROOT);
        String y = b.author.trim().toLowerCase(java.util.Locale.ROOT);
        return !x.isEmpty() && x.equals(y);
    }

    private ImageButton action(int icon, String description, View.OnClickListener listener) {
        ImageButton b = Ui.iconButton(this, icon, description, listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 44), 1f);
        lp.setMargins(Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10));
        b.setLayoutParams(lp);
        return b;
    }

    private boolean valid() {
        return bookIndex >= 0 && bookIndex < Library.books.size();
    }

    private void confirmDelete() {
        final Library.BookInfo book = Library.books.get(bookIndex);
        new AlertDialog.Builder(this)
                .setTitle("Delete " + book.title + "?")
                .setMessage("Removes the imported EPUB and its parsed data from this app.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Library.deleteBook(BookActivity.this, book);
                        Toast.makeText(BookActivity.this, "Deleted", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
