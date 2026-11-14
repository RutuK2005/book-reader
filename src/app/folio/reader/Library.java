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
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The bookshelf. Imported PDFs are copied into app-private storage, so a book keeps working even
 * if the original file is moved or deleted, and no storage permission is ever needed. Also keeps
 * the user's shelves and a per-day reading log for the streak and daily goal.
 */
final class Library {

    static final class Book {
        String id, title, src;
        /** Name of the shelf this book is filed on, or "" for none. */
        String shelf = "";
        int pages, page;
        long opened, added, size;
        /** Margin crop, as fractions of the page (l, t, r, b), for even and odd page indices. */
        float[] cropEven, cropOdd;
        boolean cropDone, cropCover;
        final ArrayList<Integer> bookmarks = new ArrayList<>();
        /** Short notes attached to pages (page index -> text). */
        final TreeMap<Integer, String> notes = new TreeMap<>();
        /** Highlighted areas per page, each as fractions of the page (l, t, r, b). */
        final HashMap<Integer, ArrayList<float[]>> highlights = new HashMap<>();
        /** Running average of reading speed, for the "time left" estimate. */
        float secPerPage;
        int samples;
        /** Total time spent reading this book. */
        long readSec;
        /** True for books laid out from an EPUB or text file; textSize is what they were set in. */
        boolean converted;
        float textSize;
        /** Set once the "you finished it" moment has been shown. */
        boolean celebrated;

        boolean started() {
            return opened > 0 && page > 0;
        }

        boolean finished() {
            return pages > 0 && page >= pages - 1;
        }
    }

    private static Library inst;

    static synchronized Library get(Context c) {
        if (inst == null) inst = new Library(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final File index;
    private final ArrayList<Book> books = new ArrayList<>();
    private final ArrayList<String> shelves = new ArrayList<>();
    /** Seconds read per day, keyed "yyyy-MM-dd". */
    private final HashMap<String, Integer> daily = new HashMap<>();

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

    /** The original EPUB or text file of a converted book, kept so it can be laid out again. */
    File source(Book b) {
        return new File(ctx.getFilesDir(), "books/" + b.id + ".src");
    }

    File cover(Book b) {
        return new File(ctx.getFilesDir(), "covers/" + b.id + ".jpg");
    }

    /** The books in the order the reader has arranged them; new books go to the front. */
    synchronized ArrayList<Book> list() {
        return new ArrayList<>(books);
    }

    /** Moves a book to where another one sits; the books in between shift along. */
    synchronized void move(Book book, Book onto) {
        int to = books.indexOf(onto);
        if (to < 0 || !books.remove(book)) return;
        books.add(to, book);
        save();
    }

    synchronized Book find(String id) {
        for (Book b : books) if (b.id.equals(id)) return b;
        return null;
    }

    synchronized void remove(Book b) {
        books.remove(b);
        file(b).delete();
        source(b).delete();
        cover(b).delete();
        save();
    }

    // ---------------------------------------------------------------- shelves

    synchronized ArrayList<String> shelves() {
        return new ArrayList<>(shelves);
    }

    /** Returns the stored name, or null if the name is empty. Adding an existing shelf is a no-op. */
    synchronized String addShelf(String name) {
        String n = clean(name);
        if (n.isEmpty()) return null;
        for (String s : shelves) if (s.equalsIgnoreCase(n)) return s;
        shelves.add(n);
        save();
        return n;
    }

    synchronized void renameShelf(String from, String to) {
        String n = clean(to);
        int i = shelves.indexOf(from);
        if (i < 0 || n.isEmpty() || shelves.contains(n)) return;
        shelves.set(i, n);
        for (Book b : books) if (from.equals(b.shelf)) b.shelf = n;
        save();
    }

    /** Removes the shelf; its books stay in the library, just unfiled. */
    synchronized void deleteShelf(String name) {
        if (!shelves.remove(name)) return;
        for (Book b : books) if (name.equals(b.shelf)) b.shelf = "";
        save();
    }

    private static String clean(String s) {
        String n = s == null ? "" : s.trim();
        return n.length() > 24 ? n.substring(0, 24).trim() : n;
    }

    // ---------------------------------------------------------------- reading log

    private static String dayKey(int daysAgo) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Credits reading time to today and to the book. The caller saves. */
    synchronized void addReading(Book b, int seconds) {
        if (seconds <= 0) return;
        if (b != null) b.readSec += seconds;
        String key = dayKey(0);
        Integer have = daily.get(key);
        daily.put(key, (have == null ? 0 : have) + seconds);
        if (daily.size() > 400) {
            // Keep about a year: anything older can no longer affect a streak or a weekly total.
            String oldest = dayKey(370);
            for (Iterator<Map.Entry<String, Integer>> it = daily.entrySet().iterator(); it.hasNext(); ) {
                if (it.next().getKey().compareTo(oldest) < 0) it.remove();
            }
        }
    }

    synchronized int secondsOn(int daysAgo) {
        Integer v = daily.get(dayKey(daysAgo));
        return v == null ? 0 : v;
    }

    synchronized int weekSeconds() {
        int sum = 0;
        for (int i = 0; i < 7; i++) sum += secondsOn(i);
        return sum;
    }

    /** Consecutive days with at least a minute of reading. Today not having happened yet does not break it. */
    synchronized int streak() {
        int n = 0;
        for (int i = secondsOn(0) >= 60 ? 0 : 1; secondsOn(i) >= 60; i++) n++;
        return n;
    }

    // ---------------------------------------------------------------- import

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
        b.title = niceTitle(name);
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
            if (!Convert.isPdf(f)) {
                // Not a PDF: keep the original and lay it out into pages.
                File src = source(b);
                if (!f.renameTo(src)) throw new IOException("Couldn't store that file");
                b.converted = true;
                b.textSize = Convert.DEFAULT_SIZE;
                // These pages are set with their own margins; cropping would push text to the screen edge.
                b.cropDone = true;
                String own = Convert.toPdf(src, name, f, b.textSize);
                if (own != null && !own.isEmpty()) b.title = own;
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
            source(b).delete();
            cover(b).delete();
            throw e;
        }
        synchronized (this) {
            books.add(0, b);
            save();
        }
        return b;
    }

    /**
     * Lays a converted book out again at a new text size. Page numbers change, so the place is
     * kept as a fraction of the book and page-bound marks are dropped. Slow: call off the UI thread.
     */
    void reconvert(Book b, float size) throws Exception {
        File tmp = new File(file(b).getPath() + ".new");
        try {
            Convert.toPdf(source(b), b.src, tmp, size);
            int pages;
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer r = new PdfRenderer(pfd)) {
                pages = r.getPageCount();
            }
            synchronized (this) {
                if (!tmp.renameTo(file(b))) throw new IOException("Couldn't save the new layout");
                b.page = b.pages > 0 ? Math.min(pages - 1, Math.round(b.page * (float) pages / b.pages)) : 0;
                b.pages = pages;
                b.textSize = size;
                b.bookmarks.clear();
                b.notes.clear();
                b.highlights.clear();
                b.cropDone = true;
                b.cropEven = b.cropOdd = null;
                b.secPerPage = 0;
                b.samples = 0;
                save();
            }
        } finally {
            tmp.delete();
        }
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

    /** A readable title from a file name: no download-site tags, separators turned into spaces. */
    private static String niceTitle(String name) {
        String t = name;
        t = t.replaceAll("(?i)\\.(pdf|epub|txt)$", "");
        t = t.replaceAll("(?i)\\(?\\s*pdf\\s*drive(\\.com)?\\s*\\)?", " ");
        t = t.replaceAll("(?i)\\(?\\s*z-lib(\\.org)?\\s*\\)?", " ");
        t = t.replaceAll("(?i)_?ocean\\s*of\\s*pdf(\\.com)?_?", " ");
        t = t.replaceAll("[_-]+", " ").replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? "Untitled" : t;
    }

    // ---------------------------------------------------------------- storage

    synchronized void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Book b : books) {
                JSONObject o = new JSONObject();
                o.put("id", b.id).put("title", b.title).put("src", b.src).put("shelf", b.shelf);
                o.put("pages", b.pages).put("page", b.page);
                o.put("opened", b.opened).put("added", b.added).put("size", b.size);
                o.put("cropDone", b.cropDone).put("cropCover", b.cropCover);
                if (b.cropEven != null) o.put("cropEven", toJson(b.cropEven));
                if (b.cropOdd != null) o.put("cropOdd", toJson(b.cropOdd));
                o.put("bookmarks", new JSONArray(b.bookmarks));
                JSONObject notes = new JSONObject();
                for (Map.Entry<Integer, String> e : b.notes.entrySet()) notes.put(String.valueOf(e.getKey()), e.getValue());
                o.put("notes", notes);
                JSONObject hl = new JSONObject();
                for (Map.Entry<Integer, ArrayList<float[]>> e : b.highlights.entrySet()) {
                    JSONArray list = new JSONArray();
                    for (float[] r : e.getValue()) list.put(toJson(r));
                    hl.put(String.valueOf(e.getKey()), list);
                }
                o.put("highlights", hl);
                o.put("secPerPage", b.secPerPage).put("samples", b.samples);
                o.put("readSec", b.readSec).put("celebrated", b.celebrated);
                o.put("converted", b.converted).put("textSize", b.textSize);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("books", arr);
            root.put("shelves", new JSONArray(shelves));
            root.put("daily", new JSONObject(daily));
            File tmp = new File(index.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
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
            String text = new String(data, StandardCharsets.UTF_8).trim();
            JSONArray arr;
            if (text.startsWith("[")) {
                // The first version stored just the list of books.
                arr = new JSONArray(text);
            } else {
                JSONObject root = new JSONObject(text);
                arr = root.optJSONArray("books");
                JSONArray sh = root.optJSONArray("shelves");
                if (sh != null) for (int i = 0; i < sh.length(); i++) shelves.add(sh.getString(i));
                JSONObject d = root.optJSONObject("daily");
                if (d != null) {
                    for (Iterator<String> it = d.keys(); it.hasNext(); ) {
                        String k = it.next();
                        daily.put(k, d.optInt(k));
                    }
                }
            }
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Book b = new Book();
                b.id = o.getString("id");
                b.title = o.optString("title", "Untitled");
                b.src = o.optString("src", "");
                b.shelf = o.optString("shelf", "");
                // Titles the reader never renamed get the tidier form.
                if (!b.src.isEmpty() && (b.title.equals(titleFrom(b.src)) || b.title.toLowerCase().contains("oceanofpdf"))) b.title = niceTitle(b.src);
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
                JSONObject notes = o.optJSONObject("notes");
                if (notes != null) {
                    for (Iterator<String> it = notes.keys(); it.hasNext(); ) {
                        String k = it.next();
                        b.notes.put(Integer.parseInt(k), notes.optString(k));
                    }
                }
                JSONObject hl = o.optJSONObject("highlights");
                if (hl != null) {
                    for (Iterator<String> it = hl.keys(); it.hasNext(); ) {
                        String k = it.next();
                        JSONArray list = hl.optJSONArray(k);
                        ArrayList<float[]> rects = new ArrayList<>();
                        for (int q = 0; list != null && q < list.length(); q++) {
                            float[] r = fromJson(list.optJSONArray(q));
                            if (r != null) rects.add(r);
                        }
                        if (!rects.isEmpty()) b.highlights.put(Integer.parseInt(k), rects);
                    }
                }
                b.secPerPage = (float) o.optDouble("secPerPage", 0);
                b.samples = o.optInt("samples");
                b.readSec = o.optLong("readSec");
                b.celebrated = o.optBoolean("celebrated");
                b.converted = o.optBoolean("converted");
                b.textSize = (float) o.optDouble("textSize", Convert.DEFAULT_SIZE);
                if (!b.shelf.isEmpty() && !shelves.contains(b.shelf)) shelves.add(b.shelf);
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
        if (a == null || a.length() < 4) return null;
        float[] f = new float[a.length()];
        for (int i = 0; i < f.length; i++) f[i] = (float) a.optDouble(i, i < 2 ? 0 : 1);
        return f;
    }
}
