package com.buddyapps.jepubvocab;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int PICK_EPUBS = 42;
    private ArrayAdapter<Library.BookInfo> adapter;
    private TextView info;
    private ListView list;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("EPUB Vocab");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 24, 20, 16);

        TextView title = Ui.label(this, "Japanese EPUB vocabulary", 20, 0xFF20202A, true);
        root.addView(title);
        info = Ui.label(this, "", 13, 0xFF666678, false);
        root.addView(info);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Add EPUBs", new View.OnClickListener() {
            @Override public void onClick(View v) { pick(); }
        }));
        buttons.addView(button("Vocab", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, VocabActivity.class));
            }
        }));
        buttons.addView(button("Reload", new View.OnClickListener() {
            @Override public void onClick(View v) { rebuild(); }
        }));
        root.addView(buttons);

        list = new ListView(this);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list.setDividerHeight(1);
        root.addView(list);

        TextView empty = Ui.label(this,
                "No books yet. Tap Add EPUBs to choose Japanese .epub files.",
                15, 0xFF777788, false);
        empty.setPadding(24, 60, 24, 24);
        empty.setGravity(android.view.Gravity.CENTER);
        ((ViewGroup) root).addView(empty);
        list.setEmptyView(empty);

        setContentView(root);

        Library.load(this);
        adapter = new ArrayAdapter<Library.BookInfo>(this,
                android.R.layout.simple_list_item_1, Library.books) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return Ui.bookRow(MainActivity.this, getItem(position), null);
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Ui.openBook(MainActivity.this, pos);
            }
        });
        updateInfo();
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(2, 12, 2, 12);
        b.setLayoutParams(lp);
        return b;
    }

    private void updateInfo() {
        info.setText(Library.books.size() + " books  ·  " + Library.totalVocab()
                + " unique words  ·  tap a book for similar content");
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
                        adapter.notifyDataSetChanged();
                        updateInfo();
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
                        adapter.notifyDataSetChanged();
                        updateInfo();
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
