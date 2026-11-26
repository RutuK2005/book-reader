package app.folio.reader;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
        /** Id of a second version of this book merged under it (say its EPUB), or "". */
        String partner = "";
        /** True for a version tucked under another book; it is not shown on its own. */
        boolean hidden;
        /** True when the reader chose the cover picture; it is then never redrawn from page one. */
        boolean ownCover;

        String kind() {
            return !converted ? "PDF" : src.toLowerCase().endsWith(".txt") ? "Text" : "EPUB";
        }

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
        ArrayList<Book> out = new ArrayList<>();
        for (Book b : books) if (!b.hidden) out.add(b);
        return out;
    }

    /** Every book, including versions merged under another. */
    synchronized ArrayList<Book> all() {
        return new ArrayList<>(books);
    }

    /**
     * Makes two books one entry with two versions. The PDF stays on show, since a PDF usually has
     * the better cover; otherwise the book that was dropped onto does. Returns false if either
     * already has a second version.
     */
    synchronized boolean merge(Book dragged, Book target) {
        if (dragged == target || dragged.hidden || target.hidden || !dragged.partner.isEmpty() || !target.partner.isEmpty()) return false;
        Book shown = !dragged.converted && target.converted ? dragged : target;
        Book tucked = shown == target ? dragged : target;
        if (shown == dragged) {
            // Keep the entry where the reader dropped it.
            books.remove(dragged);
            books.add(books.indexOf(target), dragged);
        }
        shown.partner = tucked.id;
        tucked.hidden = true;
        if (shown.shelf.isEmpty()) shown.shelf = tucked.shelf;
        save();
        return true;
    }

    synchronized void unmerge(Book b) {
        Book other = find(b.partner);
        b.partner = "";
        if (other != null) {
            other.hidden = false;
            other.shelf = b.shelf;
            books.remove(other);
            books.add(books.indexOf(b) + 1, other);
        }
        save();
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
        // Removing one version leaves the other in the library on its own.
        if (!b.partner.isEmpty()) unmerge(b);
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
                drawCover(b, r);
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

    /** Lays a converted book out again at a new text size. Slow: call off the UI thread. */
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
                // Page numbers change, so everything tied to a page moves to the same point in
                // the book. Highlights mark an area of a page and cannot be carried over.
                float k = b.pages > 0 ? pages / (float) b.pages : 1f;
                b.page = Math.min(pages - 1, Math.round(b.page * k));
                ArrayList<Integer> marks = new ArrayList<>();
                for (int p : b.bookmarks) {
                    int q = Math.min(pages - 1, Math.round(p * k));
                    if (!marks.contains(q)) marks.add(q);
                }
                b.bookmarks.clear();
                b.bookmarks.addAll(marks);
                TreeMap<Integer, String> notes = new TreeMap<>();
                for (Map.Entry<Integer, String> e : b.notes.entrySet()) {
                    int q = Math.min(pages - 1, Math.round(e.getKey() * k));
                    String had = notes.get(q);
                    notes.put(q, had == null ? e.getValue() : had + "\n" + e.getValue());
                }
                b.notes.clear();
                b.notes.putAll(notes);
                b.highlights.clear();
                b.pages = pages;
                b.textSize = size;
                if (!b.ownCover) {
                    try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file(b), ParcelFileDescriptor.MODE_READ_ONLY);
                         PdfRenderer r = new PdfRenderer(pfd)) {
                        drawCover(b, r);
                    } catch (Exception ignored) {
                        // The old cover picture stays.
                    }
                }
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

    // ---------------------------------------------------------------- moving the library

    /*
     * A library file is a zip the PC and Android versions both read and write. manifest.json comes
     * first and lists the books with their place, bookmarks, notes and highlights; then each
     * book's original file (the PDF, or the EPUB or text file it was laid out from) and its cover.
     */
    static final String TRANSFER_FORMAT = "mybookspace-library";
    static final String TRANSFER_NAME = "manifest.json";

    interface Progress {
        void at(int done, int total);
    }

    private static String kindOf(Book b) {
        return !b.converted ? "pdf" : b.src.toLowerCase(Locale.US).endsWith(".txt") ? "txt" : "epub";
    }

    private JSONObject manifestOf(Book b) throws Exception {
        JSONObject o = new JSONObject();
        String kind = kindOf(b);
        o.put("id", b.id).put("title", b.title).put("src", b.src).put("shelf", b.shelf).put("kind", kind);
        o.put("file", "books/" + b.id + "." + kind);
        if (cover(b).exists()) o.put("cover", "covers/" + b.id + ".jpg");
        o.put("ownCover", b.ownCover);
        o.put("pages", b.pages).put("page", b.page);
        o.put("opened", b.opened).put("added", b.added).put("size", b.size);
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
        o.put("textSize", b.textSize);
        o.put("partner", b.partner).put("hidden", b.hidden);
        return o;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void zipFile(ZipOutputStream zip, String name, File f) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            copy(in, zip);
        }
        zip.closeEntry();
    }

    /**
     * Writes the chosen books, with everything the reader has done in them, into one file.
     * A book's second version goes along with it. Slow: call off the UI thread. Returns how
     * many books were written.
     */
    int exportTo(Uri uri, java.util.Collection<Book> chosen, int goalMin, Progress progress) throws Exception {
        ArrayList<Book> out = new ArrayList<>();
        JSONObject root = new JSONObject();
        synchronized (this) {
            for (Book b : books) {
                boolean take = chosen.contains(b);
                for (Book c : chosen) take |= c.partner.equals(b.id);
                if (take && (b.converted ? source(b) : file(b)).exists()) out.add(b);
            }
            JSONArray arr = new JSONArray();
            for (Book b : out) arr.put(manifestOf(b));
            root.put("format", TRANSFER_FORMAT).put("version", 1).put("app", "android");
            root.put("exportedAt", System.currentTimeMillis());
            root.put("books", arr);
            root.put("shelves", new JSONArray(shelves));
            root.put("daily", new JSONObject(daily));
            root.put("goalMin", goalMin);
        }
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) throw new IOException("Can't write to that place");
            try (ZipOutputStream zip = new ZipOutputStream(new java.io.BufferedOutputStream(os, 1 << 16))) {
                // Books are already compressed; squeezing them again only costs time on a slow tablet.
                zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
                zip.putNextEntry(new ZipEntry(TRANSFER_NAME));
                zip.write(root.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                int done = 0;
                for (Book b : out) {
                    zipFile(zip, "books/" + b.id + "." + kindOf(b), b.converted ? source(b) : file(b));
                    if (cover(b).exists()) zipFile(zip, "covers/" + b.id + ".jpg", cover(b));
                    if (progress != null) progress.at(++done, out.size());
                }
            }
        }
        return out.size();
    }

    private static Book fromManifest(JSONObject o, boolean sameApp) {
        Book b = new Book();
        b.id = o.optString("id");
        b.title = o.optString("title", "Untitled");
        b.src = o.optString("src", "");
        b.shelf = clean(o.optString("shelf", ""));
        b.pages = o.optInt("pages");
        b.page = Math.max(0, o.optInt("page"));
        b.opened = o.optLong("opened");
        b.added = o.optLong("added", System.currentTimeMillis());
        b.size = o.optLong("size");
        b.ownCover = o.optBoolean("ownCover");
        readMarks(o, b);
        b.secPerPage = (float) o.optDouble("secPerPage", 0);
        b.samples = o.optInt("samples");
        b.readSec = o.optLong("readSec");
        b.celebrated = o.optBoolean("celebrated");
        b.partner = o.optString("partner", "");
        b.hidden = o.optBoolean("hidden");
        // Text sizes mean different things on a PC and on Android, so only its own are kept.
        b.textSize = sameApp ? (float) o.optDouble("textSize", Convert.DEFAULT_SIZE) : Convert.DEFAULT_SIZE;
        if (b.textSize <= 0) b.textSize = Convert.DEFAULT_SIZE;
        return b;
    }

    private static void readMarks(JSONObject o, Book b) {
        b.bookmarks.clear();
        b.notes.clear();
        b.highlights.clear();
        JSONArray bm = o.optJSONArray("bookmarks");
        if (bm != null) for (int k = 0; k < bm.length(); k++) if (!b.bookmarks.contains(bm.optInt(k))) b.bookmarks.add(bm.optInt(k));
        Collections.sort(b.bookmarks);
        JSONObject notes = o.optJSONObject("notes");
        if (notes != null) {
            for (Iterator<String> it = notes.keys(); it.hasNext(); ) {
                String k = it.next();
                try {
                    b.notes.put(Integer.parseInt(k), notes.optString(k));
                } catch (NumberFormatException ignored) {
                    // A note with no page to hang on is dropped.
                }
            }
        }
        JSONObject hl = o.optJSONObject("highlights");
        if (hl != null) {
            for (Iterator<String> it = hl.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONArray list = hl.optJSONArray(k);
                if (list == null) continue;
                ArrayList<float[]> rects = new ArrayList<>();
                for (int i = 0; i < list.length(); i++) {
                    float[] r = fromJson(list.optJSONArray(i));
                    if (r != null) rects.add(r);
                }
                try {
                    if (!rects.isEmpty()) b.highlights.put(Integer.parseInt(k), rects);
                } catch (NumberFormatException ignored) {
                    // As for notes.
                }
            }
        }
    }

    /**
     * A book laid out from an EPUB or text file has a different number of pages on each device,
     * so everything tied to a page moves to the same point in the book. Highlights mark an area
     * of a page and cannot be carried over.
     */
    private static void fitPages(Book b, int pages) {
        int old = b.pages;
        if (old > 0 && old != pages) {
            float k = pages / (float) old;
            b.page = Math.round(b.page * k);
            ArrayList<Integer> marks = new ArrayList<>();
            for (int p : b.bookmarks) {
                int q = Math.min(pages - 1, Math.round(p * k));
                if (!marks.contains(q)) marks.add(q);
            }
            b.bookmarks.clear();
            b.bookmarks.addAll(marks);
            TreeMap<Integer, String> notes = new TreeMap<>();
            for (Map.Entry<Integer, String> e : b.notes.entrySet()) {
                int q = Math.min(pages - 1, Math.round(e.getKey() * k));
                String had = notes.get(q);
                notes.put(q, had == null ? e.getValue() : had + "\n" + e.getValue());
            }
            b.notes.clear();
            b.notes.putAll(notes);
            b.highlights.clear();
            b.secPerPage = 0;
            b.samples = 0;
        }
        b.pages = pages;
        b.page = Math.max(0, Math.min(pages - 1, b.page));
    }

    /** A book that is already here takes the newer of the two reading positions and keeps both sets of notes. */
    private static void mergeInto(Book have, JSONObject o) {
        Book from = fromManifest(o, false);
        if (have.pages > 0) fitPages(from, have.pages);
        if (from.opened > have.opened) {
            have.page = from.page;
            have.opened = from.opened;
            if (from.samples > 0) {
                have.secPerPage = from.secPerPage;
                have.samples = from.samples;
            }
            if (!from.highlights.isEmpty()) {
                have.highlights.clear();
                have.highlights.putAll(from.highlights);
            }
        }
        for (int p : from.bookmarks) if (!have.bookmarks.contains(p)) have.bookmarks.add(p);
        Collections.sort(have.bookmarks);
        for (Map.Entry<Integer, String> e : from.notes.entrySet()) {
            String had = have.notes.get(e.getKey());
            if (had == null) have.notes.put(e.getKey(), e.getValue());
            else if (!had.contains(e.getValue())) have.notes.put(e.getKey(), had + "\n" + e.getValue());
        }
        have.readSec = Math.max(have.readSec, from.readSec);
        have.celebrated |= from.celebrated;
        if (have.shelf.isEmpty()) have.shelf = from.shelf;
    }

    /** The picked file's name and size, so a message about the wrong file says which file it was. */
    private String picked(Uri uri) {
        String name = null;
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
                // Some providers reject the query; the message then does without the name.
            }
        }
        if (name == null || name.isEmpty()) return "That file";
        String s = size < 0 ? "" : size < 1024 * 1024 ? " (" + Math.max(size > 0 ? 1 : 0, size / 1024) + " KB)" : " (" + size / (1024 * 1024) + " MB)";
        return "“" + name + "”" + s;
    }

    /**
     * Adds the books of a library file to this library, with their places, bookmarks, notes,
     * highlights, covers and shelves. Books already here are not copied again; they are brought
     * up to date instead. Slow: call off the UI thread. Returns {added, updated, failed}.
     */
    int[] importFrom(Uri uri, Progress progress) throws Exception {
        HashMap<String, Book> byFile = new HashMap<>(), byCover = new HashMap<>();
        HashMap<String, String> ids = new HashMap<>();
        ArrayList<Book> order = new ArrayList<>(), fresh = new ArrayList<>(), covered = new ArrayList<>();
        ArrayList<Object[]> merges = new ArrayList<>();
        JSONObject root;
        int failed = 0;
        boolean done = false;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Can't read that file");
            // Leaves the manifest as the entry about to be read.
            ZipInputStream zip = TransferFile.open(in);
            ZipEntry e;
            java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream();
            copy(zip, text);
            root = new JSONObject(new String(text.toByteArray(), StandardCharsets.UTF_8));
            if (!TRANSFER_FORMAT.equals(root.optString("format"))) throw new IOException("That file isn't a MyBookSpace library");
            if (root.optInt("version", 1) > 1) throw new IOException("That library file was made by a newer MyBookSpace. Update this app first.");
            boolean sameApp = "android".equals(root.optString("app"));
            JSONArray arr = root.optJSONArray("books");
            if (arr == null) arr = new JSONArray();
            synchronized (this) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Book have = findBySource(o.optString("src"), o.optLong("size"));
                    if (have != null) {
                        ids.put(o.optString("id"), have.id);
                        merges.add(new Object[]{have, o});
                        continue;
                    }
                    Book b = fromManifest(o, sameApp);
                    String id = b.id;
                    while (id.isEmpty() || find(id) != null || ids.containsValue(id)) {
                        id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 46656), 36);
                    }
                    ids.put(b.id, id);
                    b.id = id;
                    order.add(b);
                    byFile.put(o.optString("file"), b);
                    if (o.has("cover")) byCover.put(o.optString("cover"), b);
                }
            }
            while ((e = zip.getNextEntry()) != null) {
                Book b = byFile.get(e.getName());
                if (b != null) {
                    try {
                        File f = file(b);
                        try (OutputStream out = new FileOutputStream(f)) {
                            copy(zip, out);
                        }
                        if (!Convert.isPdf(f)) {
                            // Not a PDF: keep the original and lay it out into pages.
                            File src = source(b);
                            if (!f.renameTo(src)) throw new IOException("Couldn't store that file");
                            b.converted = true;
                            b.cropDone = true;
                            Convert.toPdf(src, b.src, f, b.textSize);
                        }
                        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                             PdfRenderer r = new PdfRenderer(pfd)) {
                            if (r.getPageCount() < 1) throw new IOException("That PDF has no pages");
                            fitPages(b, r.getPageCount());
                            if (!covered.contains(b)) drawCover(b, r);
                        }
                        fresh.add(b);
                    } catch (Exception | OutOfMemoryError one) {
                        // One unreadable book is left out; the rest of the library still comes over.
                        failed++;
                        file(b).delete();
                        source(b).delete();
                        cover(b).delete();
                    }
                    if (progress != null) progress.at(fresh.size() + failed, order.size());
                } else if ((b = byCover.get(e.getName())) != null) {
                    try (OutputStream out = new FileOutputStream(cover(b))) {
                        copy(zip, out);
                    }
                    covered.add(b);
                }
            }
            synchronized (this) {
                // New books go to the front, in the order they had on the other device.
                ArrayList<Book> add = new ArrayList<>();
                for (Book b : order) if (fresh.contains(b)) add.add(b);
                books.addAll(0, add);
                for (Object[] m : merges) mergeInto((Book) m[0], (JSONObject) m[1]);
                for (Book b : add) {
                    // A second version stays tucked under its book only if it came over in this file too.
                    String partner = b.partner.isEmpty() ? null : ids.get(b.partner);
                    Book other = partner == null || partner.equals(b.id) ? null : find(partner);
                    b.partner = other != null && add.contains(other) ? partner : "";
                }
                for (Book b : add) {
                    if (!covered.contains(b)) b.ownCover = false;
                    boolean owned = false;
                    for (Book o : books) owned |= o.partner.equals(b.id);
                    if (b.hidden && !owned) b.hidden = false;
                }
                JSONArray sh = root.optJSONArray("shelves");
                if (sh != null) {
                    for (int i = 0; i < sh.length(); i++) {
                        String n = clean(sh.optString(i));
                        boolean used = false;
                        for (Book b : books) used |= n.equals(b.shelf);
                        if (!n.isEmpty() && used && !shelves.contains(n)) shelves.add(n);
                    }
                }
                for (Book b : books) if (!b.shelf.isEmpty() && !shelves.contains(b.shelf)) shelves.add(b.shelf);
                // The reading log keeps the larger figure for each day, so importing twice adds nothing.
                JSONObject d = root.optJSONObject("daily");
                if (d != null) {
                    for (Iterator<String> it = d.keys(); it.hasNext(); ) {
                        String k = it.next();
                        Integer have = daily.get(k);
                        daily.put(k, Math.max(have == null ? 0 : have, d.optInt(k)));
                    }
                }
                save();
            }
            done = true;
        } catch (TransferFile.Empty empty) {
            throw new IOException(picked(uri) + " is empty. It was sent before the other device finished writing it. "
                    + "Export again, wait for the “saved to the file” message, then send it again.");
        } catch (TransferFile.NotLibrary other) {
            throw new IOException(picked(uri) + " isn't a MyBookSpace library. Choose the file ending in .mbs, sent as it is.");
        } catch (java.io.EOFException | java.util.zip.ZipException cut) {
            throw new IOException(picked(uri) + " is incomplete: part of it was lost on the way here. Send it again.");
        } finally {
            if (!done) {
                for (Book b : order) {
                    file(b).delete();
                    source(b).delete();
                    cover(b).delete();
                }
            }
        }
        return new int[]{fresh.size(), merges.size(), failed};
    }

    // ---------------------------------------------------------------- covers

    /** Draws the book's first page as its cover picture. */
    private void drawCover(Book b, PdfRenderer r) throws IOException {
        try (PdfRenderer.Page p = r.openPage(0)) {
            int w = 420, h = Math.max(1, Math.round(w * p.getHeight() / (float) p.getWidth()));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            writeCover(b, bmp);
            bmp.recycle();
        }
    }

    /** Written beside the old picture and swapped in, so a failure never leaves a book coverless. */
    private void writeCover(Book b, Bitmap bmp) throws IOException {
        File tmp = new File(cover(b).getPath() + ".tmp");
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException("Couldn't save the cover");
            }
            if (!tmp.renameTo(cover(b))) throw new IOException("Couldn't save the cover");
        } finally {
            tmp.delete();
        }
    }

    /** Makes a picture the reader picked the book's cover. Slow: call off the UI thread. */
    void setCover(Book b, Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Can't read that picture");
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("That file isn't a picture");
        // Decoded at a fraction of its size: a full photo would not fit in memory on a small tablet.
        final int width = 600;
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= width) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            bmp = in == null ? null : BitmapFactory.decodeStream(in, null, o);
        }
        if (bmp == null) throw new IOException("That picture couldn't be opened");
        int turn = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                int orient = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                turn = orient == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : orient == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : orient == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            }
        } catch (Exception ignored) {
            // No orientation tag (PNG, WebP): the picture is used as it is.
        }
        Matrix m = new Matrix();
        if (turn != 0) m.postRotate(turn);
        int shown = turn % 180 == 0 ? bmp.getWidth() : bmp.getHeight();
        if (shown > width) {
            float k = width / (float) shown;
            m.postScale(k, k);
        }
        Bitmap fitted = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        // Transparent pictures get a white page behind them; JPEG would turn the gaps black.
        Bitmap flat = Bitmap.createBitmap(fitted.getWidth(), fitted.getHeight(), Bitmap.Config.ARGB_8888);
        flat.eraseColor(Color.WHITE);
        new Canvas(flat).drawBitmap(fitted, 0, 0, null);
        if (fitted != bmp) fitted.recycle();
        bmp.recycle();
        synchronized (this) {
            writeCover(b, flat);
            b.ownCover = true;
            save();
        }
        flat.recycle();
    }

    /** Goes back to the first page as the cover. Slow: call off the UI thread. */
    void resetCover(Book b) throws Exception {
        synchronized (this) {
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file(b), ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer r = new PdfRenderer(pfd)) {
                drawCover(b, r);
            }
            b.ownCover = false;
            save();
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
                o.put("partner", b.partner).put("hidden", b.hidden);
                o.put("ownCover", b.ownCover);
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
                b.ownCover = o.optBoolean("ownCover");
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
                b.partner = o.optString("partner", "");
                b.hidden = o.optBoolean("hidden");
                b.textSize = (float) o.optDouble("textSize", Convert.DEFAULT_SIZE);
                if (!b.shelf.isEmpty() && !shelves.contains(b.shelf)) shelves.add(b.shelf);
                if (file(b).exists()) books.add(b);
            }
            for (Book b : books) {
                if (!b.partner.isEmpty() && find(b.partner) == null) b.partner = "";
            }
            for (Book b : books) {
                boolean owned = false;
                for (Book o : books) owned |= o.partner.equals(b.id);
                if (b.hidden && !owned) b.hidden = false;
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
