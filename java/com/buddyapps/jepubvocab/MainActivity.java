package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int PICK_EPUBS = 42;
    private static final String[] VIEW_NAMES = {"Card", "List", "Grid"};
    private static final String[] SORT_NAMES = {
            "Name", "Time added", "Characters", "Words", "Dialogue", "Avg sentence length"};

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
    private LinearLayout libraryView;
    private View storageView;
    private LinearLayout storageList;
    private TextView storageSummary;
    private Button libraryTab;
    private Button storageTab;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Mokuroku");
        prefs = getSharedPreferences("mokuroku", MODE_PRIVATE);
        viewMode = prefs.getInt("view", Ui.MODE_LIST);
        sortMode = prefs.getInt("sort", 0);
        sortDescending = prefs.getBoolean("desc", sortMode != 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        libraryView = new LinearLayout(this);
        libraryView.setOrientation(LinearLayout.VERTICAL);
        libraryView.setPadding(dp(20), dp(16), dp(20), dp(8));

        info = Ui.label(this, "", 12, Ui.secondary(this), false);
        libraryView.addView(info);

        search = new EditText(this);
        search.setHint("Search title");
        search.setTextSize(14);
        search.setSingleLine(true);
        libraryView.addView(search);

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
        libraryView.addView(bar);

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
        libraryView.addView(holder);

        storageView = buildStorageView();
        root.addView(libraryView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(storageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildTabs());

        setContentView(root);
        showTab(true);

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
                final Throwable[] failure = new Throwable[1];
                try {
                    JMDict.load(MainActivity.this);
                    JitenRank.load(MainActivity.this);
                    JMNedict.load(MainActivity.this);
                    Library.load(MainActivity.this);
                } catch (Throwable e) {
                    failure[0] = e;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        loading.dismiss();
                        refresh();
                        if (failure[0] != null) {
                            Toast.makeText(MainActivity.this,
                                    "Could not load library: " + failure[0].getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private int dp(int value) { return Ui.dp(this, value); }

    // ------------------------------------------------------------- bottom tabs

    private View buildTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        View top = new View(this);
        top.setBackgroundColor(Ui.dividerColor(this));
        top.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        libraryTab = tabButton("Library", R.drawable.ic_view_list, new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(true); }
        });
        storageTab = tabButton("Storage", R.drawable.ic_delete, new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(false); }
        });
        libraryTab.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        storageTab.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(libraryTab);
        tabs.addView(storageTab);
        // The divider is drawn by the top of this bar, not by tab buttons.
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(top);
        wrapper.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    private Button tabButton(String label, int icon, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(listener);
        return b;
    }

    private void showTab(boolean library) {
        libraryView.setVisibility(library ? View.VISIBLE : View.GONE);
        storageView.setVisibility(library ? View.GONE : View.VISIBLE);
        libraryTab.setTextColor(library ? Ui.blue(this) : Ui.secondary(this));
        storageTab.setTextColor(library ? Ui.secondary(this) : Ui.blue(this));
        if (!library) refreshStorage();
    }

    // ----------------------------------------------------------------- storage

    private View buildStorageView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));

        box.addView(Ui.label(this, "Storage", 18, Ui.primary(this), true));
        storageSummary = Ui.label(this, "", 12, Ui.secondary(this), false);
        box.addView(storageSummary);

        box.addView(smallButton("Delete all computed data",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmDelete("Delete all computed data?",
                        "Every parsed book will be removed. Copied EPUBs are kept.",
                        new Runnable() {
                    @Override public void run() {
                        Library.clearComputed(MainActivity.this);
                        refresh();
                        Toast.makeText(MainActivity.this, "Computed data deleted",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }));
        box.addView(smallButton("Delete all copied EPUBs",
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmDelete("Delete all copied EPUBs?",
                        "Only old EPUB copies inside the app are removed. "
                                + "Computed books stay available.",
                        new Runnable() {
                    @Override public void run() {
                        int n = Library.deleteAllEpubs(MainActivity.this);
                        refreshStorage();
                        Toast.makeText(MainActivity.this,
                                "Deleted " + Ui.num(n) + " EPUB copy/copies",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }));
        box.addView(Ui.label(this,
                "New imports keep only extracted text, word data and a small cover. "
                        + "The original EPUB is never stored.",
                12, Ui.faint(this), false));

        storageList = new LinearLayout(this);
        storageList.setOrientation(LinearLayout.VERTICAL);
        box.addView(storageList);
        scroll.addView(box);
        return scroll;
    }

    private Button smallButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(Ui.primary(this));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setOnClickListener(listener);
        return b;
    }

    private void confirmDelete(String title, String message, final Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { action.run(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshStorage() {
        if (storageSummary == null || storageList == null) return;
        File dir = Library.booksDir(this);
        File[] files = dir.listFiles();
        long epubBytes = 0;
        int epubCount = 0;
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".epub")) {
                    epubBytes += f.length();
                    epubCount++;
                }
            }
        }
        storageSummary.setText(Ui.num(Library.computedCount(this)) + " parsed books  ·  "
                + Ui.size(Library.computedBytes(this)) + " computed  ·  "
                + Ui.num(epubCount) + " copied EPUBs, " + Ui.size(epubBytes)
                + "  ·  database " + Ui.size(Library.databaseBytes(this)));

        storageList.removeAllViews();
        HashSet<String> shown = new HashSet<String>();
        for (Library.BookInfo book : Library.books) {
            shown.add(book.fileName);
            storageList.add(storageRow(book));
            storageList.add(divider());
        }
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : files) {
                if (!f.isFile() || !f.getName().toLowerCase().endsWith(".epub")) continue;
                if (shown.contains(f.getName())) continue;
                storageList.add(epubRow(f));
                storageList.add(divider());
            }
        }
    }

    private View storageRow(final Library.BookInfo book) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        row.addView(Ui.label(this, book.title, 14, Ui.primary(this), true));

        File copy = new File(Library.booksDir(this), book.fileName);
        String detail = book.fileName + "  ·  "
                + Ui.size(Library.bookBytes(this, book.fileName)) + " computed";
        if (copy.isFile()) detail += "  ·  " + Ui.size(copy.length()) + " EPUB copy";
        row.addView(Ui.label(this, detail, 11, Ui.secondary(this), false));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button deleteData = smallButton("Delete data", new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmDelete("Delete computed data?",
                        "Remove the parsed text, vocabulary and cover for “"
                                + book.title + "”?",
                        new Runnable() {
                    @Override public void run() {
                        Library.deleteComputed(MainActivity.this, book);
                        refresh();
                        Toast.makeText(MainActivity.this, "Computed data deleted",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
        deleteData.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(deleteData);
        if (copy.isFile()) {
            Button deleteEpub = smallButton("Delete EPUB", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    deleteEpubFile(book.fileName);
                }
            });
            deleteEpub.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            actions.addView(deleteEpub);
        }
        row.addView(actions);
        return row;
    }

    private View epubRow(final File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));
        row.addView(Ui.label(this, file.getName(), 14, Ui.primary(this), true));
        row.addView(Ui.label(this, Ui.size(file.length())
                + " copied EPUB  ·  no computed data", 11, Ui.secondary(this), false));
        Button deleteEpub = smallButton("Delete EPUB", new View.OnClickListener() {
            @Override public void onClick(View v) { deleteEpubFile(file.getName()); }
        });
        deleteEpub.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(deleteEpub);
        return row;
    }

    private void deleteEpubFile(final String fileName) {
        confirmDelete("Delete copied EPUB?", "Remove “" + fileName
                + "” from the app's private storage?", new Runnable() {
            @Override public void run() {
                Library.deleteEpub(MainActivity.this, fileName);
                refreshStorage();
                Toast.makeText(MainActivity.this, "EPUB copy deleted",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.dividerColor(this));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

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
                else if (sort == 4) result = Double.compare(
                        a.dialoguePercent, b.dialoguePercent);
                else if (sort == 5) result = Double.compare(
                        a.avgSentenceLength, b.avgSentenceLength);
                else result = a.title.compareToIgnoreCase(b.title);
                return sortDescending ? -result : result;
            }
        });
        listAdapter.notifyDataSetChanged();
        gridAdapter.notifyDataSetChanged();
        info.setText(Ui.num(Library.books.size()) + " books  ·  "
                + Ui.num(Library.totalVocab()) + " unique words  ·  showing "
                + Ui.num(displayed.size()));
        refreshStorage();
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
                "Parsing and storing " + Ui.num(uris.size()) + " EPUB file(s)…", true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final int[] added = {0};
                final Throwable[] failure = new Throwable[1];
                try {
                    added[0] = Library.importUris(MainActivity.this, uris);
                } catch (Throwable e) {
                    failure[0] = e;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        progress.dismiss();
                        refresh();
                        if (failure[0] != null) {
                            Toast.makeText(MainActivity.this,
                                    "Import failed: " + failure[0].getMessage(),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "Imported " + Ui.num(added[0]) + " new EPUB(s)",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void rebuild() {
        final ProgressDialog progress = ProgressDialog.show(this, "Reloading",
                "Loading stored library…", true, false);
        displayed.clear();
        listAdapter.notifyDataSetChanged();
        gridAdapter.notifyDataSetChanged();
        Ui.clearCovers();
        new Thread(new Runnable() {
            @Override public void run() {
                final Throwable[] failure = new Throwable[1];
                try {
                    Library.rebuild(MainActivity.this);
                } catch (Throwable e) {
                    failure[0] = e;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        progress.dismiss();
                        refresh();
                        if (failure[0] != null) {
                            Toast.makeText(MainActivity.this,
                                    "Reload failed: " + failure[0].getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }
}
