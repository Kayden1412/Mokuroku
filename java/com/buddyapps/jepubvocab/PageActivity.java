package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public class PageActivity extends Activity {

    private int bookIndex;
    private int pageIndex;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bookIndex = getIntent().getIntExtra("book", -1);
        pageIndex = getIntent().getIntExtra("page", -1);
        if (bookIndex < 0 || bookIndex >= Library.books.size()) { finish(); return; }
        Library.BookInfo book = Library.books.get(bookIndex);
        if (pageIndex < 0 || pageIndex >= book.pages.size()) { finish(); return; }
        final Library.PageInfo page = book.pages.get(pageIndex);
        setTitle(book.title + " · page " + page.number);

        ScrollView scroll = new ScrollView(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 16, 20, 40);
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.addView(navButton("← Previous", new View.OnClickListener() {
            @Override public void onClick(View v) { gotoPage(pageIndex - 1); }
        }));
        nav.addView(navButton("Next →", new View.OnClickListener() {
            @Override public void onClick(View v) { gotoPage(pageIndex + 1); }
        }));
        root.addView(nav);

        root.addView(Ui.label(this, "Page " + page.number + " of " + book.pages.size(),
                18, Ui.primary(this), true));
        TextView text = Ui.label(this, page.text, 16, Ui.primary(this), false);
        text.setTextIsSelectable(true);
        root.addView(text);

        root.addView(Ui.divider(this));
        root.addView(Ui.label(this, "Vocabulary on this page (" + page.vocabList.size() + ")",
                16, Ui.primary(this), true));
        root.addView(Ui.label(this, "Tap a word to copy it or look up its meaning.",
                12, Ui.faint(this), false));

        int limit = Math.min(page.vocabList.size(), 60);
        for (int i = 0; i < limit; i++) {
            final Library.VocabEntry v = page.vocabList.get(i);
            TextView row = Ui.label(this, v.word + "   ×" + v.count, 15, Ui.blue(this), false);
            row.setPadding(14, 6, 14, 6);
            row.setTextColor(Ui.blue(this));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    Ui.wordDialog(PageActivity.this, v.word, v.snippet);
                }
            });
            root.addView(row);
        }
        if (page.vocabList.size() > limit) {
            root.addView(Ui.label(this, "…and " + (page.vocabList.size() - limit)
                    + " more in the book's vocabulary list.", 12, Ui.faint(this), false));
        }

        root.addView(Ui.divider(this));
        root.addView(Ui.label(this, "Books with similar content", 16, Ui.primary(this), true));
        List<Library.SimilarInfo> similar = Library.similarToPage(page, book);
        if (similar.isEmpty()) {
            root.addView(Ui.label(this, "Add more EPUBs to compare.", 13, Ui.faint(this), false));
        }
        for (final Library.SimilarInfo s : similar) {
            Library.BookInfo other = Library.books.get(s.bookIndex);
            String sub = s.percent() + " match";
            if (!s.sharedText().isEmpty()) sub += "  ·  " + s.sharedText();
            TextView row = Ui.label(this, other.title + "  —  " + sub,
                    14, Ui.blue(this), false);
            row.setPadding(14, 8, 14, 8);
            row.setTextColor(Ui.green(this));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    Ui.openBook(PageActivity.this, s.bookIndex);
                }
            });
            root.addView(row);
        }
    }

    private Button navButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        return b;
    }

    private void gotoPage(int target) {
        Library.BookInfo book = Library.books.get(bookIndex);
        if (target < 0 || target >= book.pages.size()) return;
        Intent i = new Intent(this, PageActivity.class);
        i.putExtra("book", bookIndex);
        i.putExtra("page", target);
        startActivity(i);
        finish();
    }
}
