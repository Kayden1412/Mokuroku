package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.TypedArray;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

final class Ui {

    private Ui() { }

    /** True when the system (and therefore the DayNight theme) is in dark mode. */
    static boolean dark(Context c) {
        return (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static int primary(Context c) {
        return dark(c) ? Color.rgb(238, 238, 245) : Color.rgb(30, 30, 40);
    }

    static int secondary(Context c) {
        return dark(c) ? Color.rgb(172, 172, 188) : Color.rgb(105, 105, 120);
    }

    static int faint(Context c) {
        return dark(c) ? Color.rgb(145, 145, 162) : Color.rgb(136, 136, 153);
    }

    static int blue(Context c) {
        return dark(c) ? Color.rgb(140, 190, 255) : Color.rgb(36, 82, 140);
    }

    static int green(Context c) {
        return dark(c) ? Color.rgb(120, 225, 195) : Color.rgb(24, 100, 90);
    }

    static int dividerColor(Context c) {
        return dark(c) ? Color.rgb(80, 82, 92) : Color.rgb(220, 222, 228);
    }

    static TextView label(Context c, String text, int sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(4, 6, 4, 6);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    static LinearLayout bookRow(Context c, Library.BookInfo book, String subtitle) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(16, 12, 16, 12);
        TypedArray ta = c.getTheme().obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
        int background = 0;
        try { background = ta.getResourceId(0, 0); }
        catch (Exception ignored) { }
        finally { ta.recycle(); }
        if (background != 0) row.setBackgroundResource(background);
        else row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.addView(label(c, book.title, 16, primary(c), true));
        String sub = book.summary();
        if (subtitle != null && !subtitle.isEmpty()) sub = subtitle + "  ·  " + sub;
        row.addView(label(c, sub, 12, secondary(c), false));
        return row;
    }

    static void openBook(Context c, int bookIndex) {
        Intent i = new Intent(c, BookActivity.class);
        i.putExtra("book", bookIndex);
        c.startActivity(i);
    }

    static void openPage(Context c, int bookIndex, int pageIndex) {
        Intent i = new Intent(c, PageActivity.class);
        i.putExtra("book", bookIndex);
        i.putExtra("page", pageIndex);
        c.startActivity(i);
    }

    static void openReader(Context c, int bookIndex) {
        Intent i = new Intent(c, ReaderActivity.class);
        i.putExtra("book", bookIndex);
        c.startActivity(i);
    }

    static void wordDialog(final Activity a, final String word, String snippet) {
        String message = (snippet == null || snippet.isEmpty())
                ? "Saved from your books."
                : snippet;
        new AlertDialog.Builder(a)
                .setTitle(word)
                .setMessage(message)
                .setPositiveButton("Look up on Jisho", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        try {
                            a.startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://jisho.org/search/" + Uri.encode(word))));
                        } catch (Exception e) {
                            Toast.makeText(a, "No browser available", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNeutralButton("Copy", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        ClipboardManager cm = (ClipboardManager)
                                a.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("word", word));
                        Toast.makeText(a, "Copied: " + word, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(dividerColor(c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = 10;
        lp.bottomMargin = 10;
        v.setLayoutParams(lp);
        return v;
    }
}
