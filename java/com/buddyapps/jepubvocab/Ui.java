package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.content.res.TypedValue;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

final class Ui {

    static final int MODE_CARD = 0;
    static final int MODE_LIST = 1;
    static final int MODE_GRID = 2;

    private static final LruCache<Integer, Bitmap> COVERS =
            new LruCache<Integer, Bitmap>(24);

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

    static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    /** A compact Lucide icon button, tinted for the current theme. */
    static ImageButton iconButton(Context c, int icon, String description,
                                  View.OnClickListener listener) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(primary(c)));
        b.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        b.setContentDescription(description);
        b.setTooltipText(description);
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
                tv, true)) b.setBackgroundResource(tv.resourceId);
        b.setOnClickListener(listener);
        return b;
    }

    static Bitmap cover(Library.BookInfo book) {
        if (book.cover == null || book.cover.length == 0) return null;
        Bitmap bm = COVERS.get(book.index);
        if (bm == null) {
            try {
                bm = BitmapFactory.decodeByteArray(book.cover, 0, book.cover.length);
                if (bm != null) COVERS.put(book.index, bm);
            } catch (Exception e) {
                return null;
            }
        }
        return bm;
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

    static ImageView coverView(Context c, Library.BookInfo book, int wDp, int hDp) {
        ImageView iv = new ImageView(c);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(dp(c, wDp), dp(c, hDp));
        lp.rightMargin = dp(c, 10);
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setAdjustViewBounds(false);
        Bitmap bm = cover(book);
        if (bm != null) iv.setImageBitmap(bm);
        else iv.setImageResource(android.R.drawable.ic_menu_gallery);
        return iv;
    }

    private static void selectable(Context c, View v) {
        TypedArray ta = c.getTheme().obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
        int background = 0;
        try { background = ta.getResourceId(0, 0); }
        catch (Exception ignored) { }
        finally { ta.recycle(); }
        if (background != 0) v.setBackgroundResource(background);
        else v.setBackgroundResource(android.R.drawable.list_selector_background);
    }

    /** Builds one library item for card, list or grid display. */
    static View bookView(Context c, Library.BookInfo book, String subtitle, int mode) {
        String sub = book.summary();
        if (subtitle != null && !subtitle.isEmpty()) sub = subtitle + "  ·  " + sub;

        if (mode == MODE_GRID) {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 12));
            selectable(c, box);
            ImageView iv = coverView(c, book, 140, 190);
            LinearLayout.LayoutParams ilp =
                    new LinearLayout.LayoutParams(dp(c, 140), dp(c, 190));
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            iv.setLayoutParams(ilp);
            box.addView(iv);
            TextView title = label(c, book.title, 13, primary(c), true);
            title.setMaxLines(2);
            box.addView(title);
            TextView stats = label(c, sub, 10, secondary(c), false);
            stats.setMaxLines(2);
            box.addView(stats);
            return box;
        }

        int imageW = mode == MODE_CARD ? 84 : 52;
        int imageH = mode == MODE_CARD ? 116 : 72;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(c, mode == MODE_CARD ? 14 : 10), dp(c, 10),
                dp(c, 10), dp(c, 10));
        selectable(c, row);
        row.addView(coverView(c, book, imageW, imageH));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(c, book.title, mode == MODE_CARD ? 16 : 15,
                primary(c), true);
        title.setMaxLines(2);
        texts.addView(title);
        TextView stats = label(c, sub, 11, secondary(c), false);
        stats.setMaxLines(3);
        texts.addView(stats);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    static LinearLayout bookRow(Context c, Library.BookInfo book, String subtitle) {
        // Used by the "similar books" lists.
        LinearLayout row = (LinearLayout) bookView(c, book, subtitle, MODE_LIST);
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
