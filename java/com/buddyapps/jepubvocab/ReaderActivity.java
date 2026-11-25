package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;

public class ReaderActivity extends Activity {

    private int bookIndex;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bookIndex = getIntent().getIntExtra("book", -1);
        if (bookIndex < 0 || bookIndex >= Library.books.size()) { finish(); return; }
        final Library.BookInfo book = Library.books.get(bookIndex);
        setTitle(book.title + " · pages");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 8);
        root.addView(Ui.label(this, Ui.num(book.pages.size()) + " pages. Tap a page to read it, "
                + "see its vocabulary and find books with similar content.",
                13, Ui.secondary(this), false));

        ListView list = new ListView(this);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list.setAdapter(new ArrayAdapter<Library.PageInfo>(this,
                android.R.layout.simple_list_item_1, book.pages) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                Library.PageInfo page = getItem(position);
                LinearLayout row = new LinearLayout(ReaderActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(16, 12, 16, 12);
                row.addView(Ui.label(ReaderActivity.this, "Page " + Ui.num(page.number),
                        15, Ui.primary(ReaderActivity.this), true));
                row.addView(Ui.label(ReaderActivity.this, page.preview(),
                        12, Ui.secondary(ReaderActivity.this), false));
                return row;
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Ui.openPage(ReaderActivity.this, bookIndex, pos);
            }
        });
        root.addView(list);
        setContentView(root);
    }
}
