package app.folio.reader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.pdf.PdfDocument;
import android.text.Html;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lays an EPUB or a plain-text file out into book pages and saves them as a PDF, so the reader
 * (which is built around fixed pages) can show it like any other book. The text stays real text
 * in the PDF, so it is sharp at any zoom. The original file is kept, so the book can be laid out
 * again at a different text size.
 */
final class Convert {
    /** Page size and margins in points: a 6 x 9 inch book page. */
    private static final float PAGE_W = 432, PAGE_H = 648, MARGIN_X = 44, MARGIN_Y = 50;
    static final float[] SIZES = {10f, 11.5f, 13f, 15f, 17.5f};
    static final String[] SIZE_NAMES = {"Small", "Medium", "Large", "Extra large", "Huge"};
    static final float DEFAULT_SIZE = 11.5f;

    static boolean isPdf(File f) {
        return startsWith(f, '%', 'P', 'D', 'F');
    }

    private static boolean startsWith(File f, char... sig) {
        try (InputStream in = new FileInputStream(f)) {
            for (char c : sig) if (in.read() != c) return false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Returns the book's own title if the file carries one, otherwise null. */
    static String toPdf(File src, String name, File out, float textSize) throws Exception {
        if (startsWith(src, 'P', 'K')) return epub(src, out, textSize);
        if (name.toLowerCase().endsWith(".txt")) {
            Pages pages = new Pages(out, textSize);
            pages.chapter(new String(readAll(new FileInputStream(src)), StandardCharsets.UTF_8));
            pages.finish();
            return null;
        }
        throw new IOException("Only PDF, EPUB and TXT files can be added");
    }

    private static String epub(File src, File out, float textSize) throws Exception {
        try (ZipFile zip = new ZipFile(src)) {
            String container = read(zip, "META-INF/container.xml");
            Matcher m = container == null ? null : Pattern.compile("full-path\\s*=\\s*[\"']([^\"']+)[\"']").matcher(container);
            if (m == null || !m.find()) throw new IOException("This file isn't a readable EPUB");
            String opfPath = m.group(1);
            String opf = read(zip, opfPath);
            if (opf == null) throw new IOException("This file isn't a readable EPUB");
            String base = dirOf(opfPath);

            // The manifest names every file; the spine lists the chapters in reading order.
            HashMap<String, String> files = new HashMap<>();
            Matcher item = Pattern.compile("<item\\b[^>]*>").matcher(opf);
            while (item.find()) {
                String id = attr(item.group(), "id"), href = attr(item.group(), "href");
                if (id != null && href != null) files.put(id, href);
            }
            ArrayList<String> chapters = new ArrayList<>();
            Matcher ref = Pattern.compile("<itemref\\b[^>]*>").matcher(opf);
            while (ref.find()) {
                String href = files.get(attr(ref.group(), "idref"));
                if (href != null) chapters.add(resolve(base, href));
            }
            String title = null;
            Matcher t = Pattern.compile("<dc:title[^>]*>([^<]+)<").matcher(opf);
            if (t.find()) title = Html.fromHtml(t.group(1), Html.FROM_HTML_MODE_LEGACY).toString().trim();

            Pages pages = new Pages(out, textSize);
            for (final String path : chapters) {
                String html = read(zip, path);
                if (html == null) continue;
                String body = html.replaceAll("(?is)<head.*?</head>", "")
                        .replaceAll("(?is)<(style|script)[^>]*>.*?</\\1>", "")
                        // Covers are often an SVG wrapper around one picture; treat it as a plain image.
                        .replaceAll("(?is)<image\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", "<img src=\"$1\"/>");
                pages.chapter(Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY, source -> picture(zip, dirOf(path), source), null));
            }
            pages.finish();
            return title;
        }
    }

    /** Loads a picture from inside the EPUB, sized to fit within a page. */
    private static BitmapDrawable picture(ZipFile zip, String dir, String source) {
        try {
            ZipEntry e = zip.getEntry(resolve(dir, source));
            if (e == null) return null;
            byte[] data = readAll(zip.getInputStream(e));
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / sample > 1400 || o.outHeight / sample > 2000) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return null;
            float maxW = PAGE_W - 2 * MARGIN_X, maxH = (PAGE_H - 2 * MARGIN_Y) * 0.9f;
            // Small pictures keep roughly their intended size; large ones shrink to the page.
            float scale = Math.min(0.6f * sample, Math.min(maxW / b.getWidth(), maxH / b.getHeight()));
            BitmapDrawable d = new BitmapDrawable(null, b);
            d.setBounds(0, 0, Math.max(1, Math.round(b.getWidth() * scale)), Math.max(1, Math.round(b.getHeight() * scale)));
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Flows text onto as many pages as it needs. */
    private static final class Pages {
        private final PdfDocument doc = new PdfDocument();
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        private final File out;
        private int count;

        Pages(File out, float textSize) {
            this.out = out;
            paint.setTextSize(textSize);
            paint.setTypeface(Typeface.SERIF);
            paint.setColor(Color.BLACK);
        }

        /** Each chapter starts on a fresh page. */
        void chapter(CharSequence text) {
            int start = 0, end = text.length();
            while (start < end && Character.isWhitespace(text.charAt(start))) start++;
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
            if (end <= start) return;
            int width = (int) (PAGE_W - 2 * MARGIN_X);
            float height = PAGE_H - 2 * MARGIN_Y;
            StaticLayout layout = StaticLayout.Builder.obtain(text, start, end, paint, width)
                    .setLineSpacing(0f, 1.32f)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .build();
            int line = 0, lines = layout.getLineCount();
            while (line < lines) {
                int top = layout.getLineTop(line), last = line;
                while (last < lines && layout.getLineBottom(last) - top <= height) last++;
                // A single line taller than the page still has to go somewhere.
                if (last == line) last = line + 1;
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder((int) PAGE_W, (int) PAGE_H, ++count).create());
                Canvas c = page.getCanvas();
                c.translate(MARGIN_X, MARGIN_Y - top);
                c.clipRect(0, top, width, layout.getLineBottom(last - 1));
                layout.draw(c);
                doc.finishPage(page);
                line = last;
            }
        }

        void finish() throws IOException {
            try {
                if (count == 0) throw new IOException("That book has no readable text");
                try (OutputStream o = new FileOutputStream(out)) {
                    doc.writeTo(o);
                }
            } finally {
                doc.close();
            }
        }
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?<![\\w:-])" + name + "\\s*=\\s*[\"']([^\"']*)[\"']").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i + 1);
    }

    /** Resolves a link inside the EPUB against the folder of the file it appears in. */
    private static String resolve(String dir, String href) {
        String h = href;
        int hash = h.indexOf('#');
        if (hash >= 0) h = h.substring(0, hash);
        try {
            h = URLDecoder.decode(h.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
            // Use the link as written.
        }
        ArrayList<String> parts = new ArrayList<>();
        for (String p : (h.startsWith("/") ? h.substring(1) : dir + h).split("/")) {
            if (p.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else if (!p.isEmpty() && !p.equals(".")) {
                parts.add(p);
            }
        }
        return TextUtils.join("/", parts);
    }

    private static String read(ZipFile zip, String path) throws IOException {
        ZipEntry e = zip.getEntry(path);
        return e == null ? null : new String(readAll(zip.getInputStream(e)), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[1 << 15];
            int n;
            while ((n = src.read(b)) > 0) buf.write(b, 0, n);
            return buf.toByteArray();
        }
    }

    private Convert() {}
}
