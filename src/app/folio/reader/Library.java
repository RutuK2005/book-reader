package app.folio.reader;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/**
 * The bookshelf. Imported PDFs are copied into app-private storage, so a book keeps working even
 * if the original file is moved or deleted, and no storage permission is ever needed.
 */
final class Library {

    static final class Book {
        String id, title, src;
        int pages, page;
        long opened, added, size;
        /** Margin crop, as fractions of the page (l, t, r, b), for even and odd page indices. */
        float[] cropEven, cropOdd;
        boolean cropDone, cropCover;
        final ArrayList<Integer> bookmarks = new ArrayList<>();
        /** Running average of reading speed, for the "time left" estimate. */
        float secPerPage;
        int samples;
    }

    private static Library inst;

    static synchronized Library get(Context c) {
        if (inst == null) inst = new Library(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final File index;
    private final ArrayList<Book> books = new ArrayList<>();

    private Library(Context c) {
        ctx = c;
        index = new File(c.getFilesDir(), "library.json");
        new File(c.getFilesDir(), "books").mkdirs();
        new File(c.getFilesDir(), "covers").mkdirs();
        load();
    }

    File file(Book b) {
        return new File(ctx.getFilesDir(), "books/" + b.id + ".pdf");
    }

    File cover(Book b) {
        return new File(ctx.getFilesDir(), "covers/" + b.id + ".jpg");
    }

    /** Most recently opened first. */
    synchronized ArrayList<Book> list() {
        ArrayList<Book> out = new ArrayList<>(books);
        Collections.sort(out, (a, b) -> Long.compare(Math.max(b.opened, b.added), Math.max(a.opened, a.added)));
        return out;
    }

    synchronized Book find(String id) {
        for (Book b : books) if (b.id.equals(id)) return b;
        return null;
    }

    synchronized void remove(Book b) {
        books.remove(b);
        file(b).delete();
        cover(b).delete();
        save();
    }

    /** Copies a PDF into the library. Slow (file copy + cover render): call off the UI thread. */
    Book importUri(Uri uri) throws Exception {
        String name = uri.getLastPathSegment();
        long size = -1;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (i >= 0 && !c.isNull(i)) name = c.getString(i);
                    i = c.getColumnIndex(OpenableColumns.SIZE);
                    if (i >= 0 && !c.isNull(i)) size = c.getLong(i);
                }
            } catch (Exception ignored) {
                // Some providers reject the query; the file name is only cosmetic.
            }
        }
        if (name == null || name.isEmpty()) name = "Book.pdf";
        Book dup = findBySource(name, size);
        if (dup != null) return dup;

        Book b = new Book();
        b.id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        b.src = name;
        b.title = titleFrom(name);
        b.added = System.currentTimeMillis();
        File f = file(b);
        try {
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(f)) {
                if (in == null) throw new IOException("Can't read that file");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            b.size = f.length();
            dup = findBySource(name, b.size);
            if (dup != null) {
                f.delete();
                return dup;
            }
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer r = new PdfRenderer(pfd)) {
                b.pages = r.getPageCount();
                if (b.pages < 1) throw new IOException("That PDF has no pages");
                try (PdfRenderer.Page p = r.openPage(0)) {
                    int w = 420, h = Math.max(1, Math.round(w * p.getHeight() / (float) p.getWidth()));
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    try (OutputStream out = new FileOutputStream(cover(b))) {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
                    }
                    bmp.recycle();
                }
            } catch (SecurityException e) {
                throw new IOException("That PDF is password-protected");
            }
        } catch (Exception e) {
            f.delete();
            cover(b).delete();
            throw e;
        }
        synchronized (this) {
            books.add(b);
            save();
        }
        return b;
    }

    private synchronized Book findBySource(String name, long size) {
        if (size <= 0) return null;
        for (Book b : books) if (b.size == size && name.equals(b.src)) return b;
        return null;
    }

    private static String titleFrom(String name) {
        String t = name;
        if (t.toLowerCase().endsWith(".pdf")) t = t.substring(0, t.length() - 4);
        t = t.replace('_', ' ').trim();
        return t.isEmpty() ? "Untitled" : t;
    }

    synchronized void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Book b : books) {
                JSONObject o = new JSONObject();
                o.put("id", b.id).put("title", b.title).put("src", b.src);
                o.put("pages", b.pages).put("page", b.page);
                o.put("opened", b.opened).put("added", b.added).put("size", b.size);
                o.put("cropDone", b.cropDone).put("cropCover", b.cropCover);
                if (b.cropEven != null) o.put("cropEven", toJson(b.cropEven));
                if (b.cropOdd != null) o.put("cropOdd", toJson(b.cropOdd));
                o.put("bookmarks", new JSONArray(b.bookmarks));
                o.put("secPerPage", b.secPerPage).put("samples", b.samples);
                arr.put(o);
            }
            File tmp = new File(index.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(index)) tmp.delete();
        } catch (Exception ignored) {
            // Losing a progress update is better than crashing the reader.
        }
    }

    private void load() {
        if (!index.exists()) return;
        try {
            byte[] data = new byte[(int) index.length()];
            try (InputStream in = new FileInputStream(index)) {
                int off = 0, n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            }
            JSONArray arr = new JSONArray(new String(data, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Book b = new Book();
                b.id = o.getString("id");
                b.title = o.optString("title", "Untitled");
                b.src = o.optString("src", "");
                b.pages = o.optInt("pages");
                b.page = o.optInt("page");
                b.opened = o.optLong("opened");
                b.added = o.optLong("added");
                b.size = o.optLong("size");
                b.cropDone = o.optBoolean("cropDone");
                b.cropCover = o.optBoolean("cropCover");
                b.cropEven = fromJson(o.optJSONArray("cropEven"));
                b.cropOdd = fromJson(o.optJSONArray("cropOdd"));
                JSONArray bm = o.optJSONArray("bookmarks");
                if (bm != null) for (int k = 0; k < bm.length(); k++) b.bookmarks.add(bm.getInt(k));
                b.secPerPage = (float) o.optDouble("secPerPage", 0);
                b.samples = o.optInt("samples");
                if (file(b).exists()) books.add(b);
            }
        } catch (Exception ignored) {
            // A corrupt index just means an empty shelf; the PDFs can be re-added.
        }
    }

    private static JSONArray toJson(float[] f) throws Exception {
        JSONArray a = new JSONArray();
        for (float v : f) a.put((double) v);
        return a;
    }

    private static float[] fromJson(JSONArray a) {
        if (a == null || a.length() != 4) return null;
        float[] f = new float[4];
        for (int i = 0; i < 4; i++) f[i] = (float) a.optDouble(i, i < 2 ? 0 : 1);
        return f;
    }
}
