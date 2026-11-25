package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int PICK_EPUBS = 42;
    private static final String[] VIEW_NAMES = {"Card", "List", "Grid"};
    private static final String[] SORT_NAMES = {"Name", "Time added", "Characters", "Words"};

    private SharedPreferences prefs;
    private int viewMode;
    private int sortMode;
    private boolean sortDescending;
    private TextView info;
    private EditText search;
    private ImageButton viewButton;
    private ImageButton sortButton;
    private ImageButton orderButton;
    private ListView list;
    private GridView grid;
    private FrameLayout holder;
    private TextView emptyView;
    private ArrayAdapter<Library.BookInfo> listAdapter;
    private ArrayAdapter<Library.BookInfo> gridAdapter;
    private final ArrayList<Library.BookInfo> displayed = new ArrayList<Library.BookInfo>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Mokuroku");
        prefs = getSharedPreferences("mokuroku", MODE_PRIVATE);
        viewMode = prefs.getInt("view", Ui.MODE_LIST);
        sortMode = prefs.getInt("sort", 0);
        sortDescending = prefs.getBoolean("desc", sortMode != 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(8));

        info = Ui.label(this, "", 12, Ui.secondary(this), false);
        root.addView(info);

        search = new EditText(this);
        search.setHint("Search title");
        search.setTextSize(14);
        search.setSingleLine(true);
        root.addView(search);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(iconButton(R.drawable.ic_add, "Add EPUBs", new View.OnClickListener() {
            @Override public void onClick(View v) { pick(); }
        }));
        viewButton = iconButton(R.drawable.ic_view_list, "View", new View.OnClickListener() {
            @Override public void onClick(View v) { chooseView(); }
        });
        bar.addView(viewButton);
        sortButton = iconButton(R.drawable.ic_sort, "Sort", new View.OnClickListener() {
            @Override public void onClick(View v) { chooseSort(); }
        });
        bar.addView(sortButton);
        bar.addView(iconButton(R.drawable.ic_vocab, "Vocabulary", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, VocabActivity.class));
            }
        }));
        bar.addView(iconButton(R.drawable.ic_reload, "Reload", new View.OnClickListener() {
            @Override public void onClick(View v) { rebuild(); }
        }));
        orderButton = iconButton(R.drawable.ic_down, "Sort descending",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                sortDescending = !sortDescending;
                prefs.edit().putBoolean("desc", sortDescending).apply();
                updateButtons();
                refresh();
            }
        });
        bar.addView(orderButton);
        root.addView(bar);

        holder = new FrameLayout(this);
        holder.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list = new ListView(this);
        list.setDividerHeight(1);
        grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(6));
        grid.setVerticalSpacing(dp(6));
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));
        grid.setGravity(Gravity.CENTER);
        holder.addView(list);
        holder.addView(grid);

        emptyView = Ui.label(this,
                "No books match. Add EPUBs or clear your search.", 14, Ui.faint(this), false);
        emptyView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        emptyView.setLayoutParams(elp);
        list.setEmptyView(emptyView);
        grid.setEmptyView(emptyView);
        root.addView(holder);

        setContentView(root);

        listAdapter = new ArrayAdapter<Library.BookInfo>(this,
                android.R.layout.simple_list_item_1, displayed) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return Ui.bookView(MainActivity.this, getItem(position), null, viewMode);
            }
        };
        gridAdapter = new ArrayAdapter<Library.BookInfo>(this,
                android.R.layout.simple_list_item_1, displayed) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return Ui.bookView(MainActivity.this, getItem(position), null, Ui.MODE_GRID);
            }
        };
        list.setAdapter(listAdapter);
        grid.setAdapter(gridAdapter);

        AdapterView.OnItemClickListener click = new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View v,
                                             int position, long id) {
                Library.BookInfo book = (Library.BookInfo) parent.getItemAtPosition(position);
                Ui.openBook(MainActivity.this, book.index);
            }
        };
        list.setOnItemClickListener(click);
        grid.setOnItemClickListener(click);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });

        updateButtons();
        switchViews();
        info.setText("Loading library…");

        // Parsing many EPUBs can take seconds. Do it off the main thread so the
        // app never appears frozen on its launch screen.
        final ProgressDialog loading = ProgressDialog.show(this, "Mokuroku",
                "Loading library…", true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                JMDict.load(MainActivity.this);
                Library.load(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        loading.dismiss();
                        refresh();
                    }
                });
            }
        }).start();
    }

    private int dp(int value) { return Ui.dp(this, value); }

    private ImageButton iconButton(int icon, String description,
                                   View.OnClickListener listener) {
        ImageButton b = Ui.iconButton(this, icon, description, listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(1), dp(4), dp(1), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private void describe(ImageButton b, String description) {
        b.setContentDescription(description);
        b.setTooltipText(description);
    }

    private void updateButtons() {
        int viewIcon = viewMode == Ui.MODE_GRID ? R.drawable.ic_view_grid
                : viewMode == Ui.MODE_LIST ? R.drawable.ic_view_list
                : R.drawable.ic_view_card;
        viewButton.setImageResource(viewIcon);
        describe(viewButton, "View: " + VIEW_NAMES[viewMode]);
        describe(sortButton, "Sort: " + SORT_NAMES[sortMode]);
        orderButton.setImageResource(sortDescending ? R.drawable.ic_down : R.drawable.ic_up);
        describe(orderButton, sortDescending ? "Sort descending" : "Sort ascending");
    }

    private void switchViews() {
        // AdapterView forces itself VISIBLE whenever its data changes, so merely
        // setting GONE is not enough. Detach the inactive adapter view instead.
        boolean asGrid = viewMode == Ui.MODE_GRID;
        holder.removeAllViews();
        holder.addView(asGrid ? grid : list);
        holder.addView(emptyView);
        list.setEmptyView(emptyView);
        grid.setEmptyView(emptyView);
    }

    private void chooseView() {
        new AlertDialog.Builder(this)
                .setTitle("Display books as")
                .setSingleChoiceItems(VIEW_NAMES, viewMode,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                viewMode = which;
                                prefs.edit().putInt("view", which).apply();
                                d.dismiss();
                                updateButtons();
                                switchViews();
                                refresh();
                            }
                        })
                .show();
    }

    private void chooseSort() {
        new AlertDialog.Builder(this)
                .setTitle("Sort books by")
                .setSingleChoiceItems(SORT_NAMES, sortMode,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                sortMode = which;
                                sortDescending = which != 0;
                                prefs.edit().putInt("sort", which)
                                        .putBoolean("desc", sortDescending).apply();
                                d.dismiss();
                                updateButtons();
                                refresh();
                            }
                        })
                .show();
    }

    // Ignore whitespace, unify full/half-width characters, and treat hiragana and
    // katakana as the same so "べ" finds a title written as "ベアー".
    private static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (c >= 0x3041 && c <= 0x3096) c += 0x60; // hiragana -> katakana
            out.append(c);
        }
        return out.toString();
    }

    private boolean matches(Library.BookInfo book, String query) {
        String q = normalize(query);
        if (q.isEmpty()) return true;
        String title = normalize(book.title);
        if (title.contains(q)) return true;
        // "くま ベアー" should find a title with other words in between.
        String[] tokens = query.trim().split("\\s+");
        if (tokens.length < 2) return false;
        for (String token : tokens) {
            if (!title.contains(normalize(token))) return false;
        }
        return true;
    }

    private void refresh() {
        String query = search.getText().toString().trim();
        displayed.clear();
        for (Library.BookInfo book : Library.books) {
            if (!query.isEmpty() && !matches(book, query)) continue;
            displayed.add(book);
        }
        final int sort = sortMode;
        Collections.sort(displayed, new Comparator<Library.BookInfo>() {
            @Override public int compare(Library.BookInfo a, Library.BookInfo b) {
                int result;
                if (sort == 1) result = Long.compare(a.addedAt, b.addedAt);
                else if (sort == 2) result = Long.compare(a.charCount, b.charCount);
                else if (sort == 3) result = Integer.compare(
                        a.vocab.size(), b.vocab.size());
                else result = a.title.compareToIgnoreCase(b.title);
                return sortDescending ? -result : result;
            }
        });
        listAdapter.notifyDataSetChanged();
        gridAdapter.notifyDataSetChanged();
        info.setText(Library.books.size() + " books  ·  " + Library.totalVocab()
                + " unique words  ·  showing " + displayed.size());
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory("android.intent.category.OPEN_DOCUMENT");
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            // Do not wrap this in createChooser(): on some phones the chooser
            // resolves to nothing and shows "No apps to share to".
            startActivityForResult(i, PICK_EPUBS);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("*/*");
                fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(fallback, PICK_EPUBS);
            } catch (Exception e2) {
                Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_EPUBS || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<Uri>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) importUris(uris);
    }

    private void importUris(final List<Uri> uris) {
        final ProgressDialog progress = ProgressDialog.show(this, "Importing",
                "Copying and parsing " + uris.size() + " EPUB file(s)…", true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final int[] added = {0};
                for (Uri uri : uris) {
                    if (copyUri(uri)) added[0]++;
                }
                Library.rebuild(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        progress.dismiss();
                        refresh();
                        Toast.makeText(MainActivity.this,
                                "Imported " + added[0] + " new EPUB(s)", Toast.LENGTH_SHORT)
                                .show();
                    }
                });
            }
        }).start();
    }

    private void rebuild() {
        final ProgressDialog progress = ProgressDialog.show(this, "Reloading",
                "Parsing your EPUB library…", true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                Library.rebuild(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        progress.dismiss();
                        refresh();
                    }
                });
            }
        }).start();
    }

    private boolean copyUri(Uri uri) {
        ContentResolver resolver = getContentResolver();
        String name = null;
        Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) name = cursor.getString(0);
            } catch (Exception e) {
                name = null;
            } finally {
                cursor.close();
            }
        }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "book.epub";
        name = sanitize(name);
        if (!name.toLowerCase().endsWith(".epub")) name += ".epub";

        File dir = Library.booksDir(this);
        if (!dir.exists()) dir.mkdirs();
        File dst = new File(dir, name);
        if (dst.exists() && dst.length() > 0) return false;

        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = resolver.openInputStream(uri);
            if (in == null) return false;
            out = new FileOutputStream(dst);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            if (dst.exists()) dst.delete();
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._\\u3000-\\u30ff\\u4e00-\\u9fff-]", "_");
    }
}
