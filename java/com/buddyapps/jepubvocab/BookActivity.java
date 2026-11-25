package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class BookActivity extends Activity {

    private int bookIndex;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bookIndex = getIntent().getIntExtra("book", -1);
        if (!valid()) { finish(); return; }
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
        heading.addView(Ui.label(this, book.fileName + "  ·  " + book.summary(),
                11, Ui.secondary(this), false));
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
        root.addView(buttons);

        root.addView(Ui.divider(this));
        root.addView(Ui.label(this, "Books with similar content", 16, Ui.primary(this), true));
        root.addView(Ui.label(this,
                "Based on shared words and character patterns across your library.",
                12, Ui.faint(this), false));

        final List<Library.SimilarInfo> similar = Library.similarToBook(book);
        ListView list = new ListView(this);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list.setAdapter(new ArrayAdapter<Library.SimilarInfo>(this,
                android.R.layout.simple_list_item_1, similar) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                Library.SimilarInfo s = getItem(position);
                Library.BookInfo other = Library.books.get(s.bookIndex);
                String subtitle = s.percent() + " match";
                if (!s.sharedText().isEmpty()) subtitle += "  ·  " + s.sharedText();
                return Ui.bookRow(BookActivity.this, other, subtitle);
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Ui.openBook(BookActivity.this, similar.get(pos).bookIndex);
            }
        });
        root.addView(list);

        if (similar.isEmpty()) {
            TextView note = Ui.label(this,
                    "Add more EPUBs to see similar books here.", 14, Ui.faint(this), false);
            note.setPadding(16, 24, 16, 24);
            root.addView(note);
        }
        setContentView(root);
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
