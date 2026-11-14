package app.folio.reader;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.util.ArrayList;

/**
 * Owns the PdfRenderer and a single worker thread. PdfRenderer is not thread-safe and allows one
 * open page at a time, so every PDF operation is funnelled through that thread; results come
 * back on the UI thread. Screen-sized bitmaps are pooled so page turns do not allocate.
 */
final class PdfEngine {

    interface Callback {
        /** bmp is null when the request was dropped as stale. */
        void onScreen(int gen, int screen, Bitmap bmp);

        void onZoom(int seq, Bitmap bmp, float zoom, float panX, float panY);
    }

    interface OpenResult {
        void done(int pages, String error);
    }

    interface CropResult {
        void done(float[] even, float[] odd, boolean cover);
    }

    /**
     * Immutable description of how pages map to screens. A "screen" is what one page turn shows:
     * one page, or (two-page mode) the cover alone followed by left/right spreads.
     */
    static final class Layout {
        final int w, h, footer, pad, pageCount;
        final boolean dual, cropCover;
        final float[] cropEven, cropOdd;

        Layout(int w, int h, int footer, int pad, int pageCount, boolean dual,
               float[] cropEven, float[] cropOdd, boolean cropCover) {
            this.w = w;
            this.h = h;
            this.footer = footer;
            this.pad = pad;
            this.pageCount = pageCount;
            this.dual = dual;
            this.cropEven = cropEven;
            this.cropOdd = cropOdd;
            this.cropCover = cropCover;
        }

        int screenCount() {
            return dual ? pageCount / 2 + 1 : pageCount;
        }

        int screenOf(int page) {
            return dual ? (page + 1) / 2 : page;
        }

        int firstPage(int screen) {
            return dual ? (screen == 0 ? 0 : 2 * screen - 1) : screen;
        }

        int pagesOn(int screen) {
            return (dual && screen > 0 && 2 * screen < pageCount) ? 2 : 1;
        }

        float[] cropFor(int page) {
            if (page == 0 && !cropCover) return null;
            return page % 2 == 0 ? cropEven : cropOdd;
        }
    }

    private final HandlerThread thread = new HandlerThread("folio-pdf");
    private final Handler worker;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<Bitmap> pool = new ArrayList<>();
    private PdfRenderer renderer;
    private boolean closed;

    /** Set by the view; requests that no longer match are skipped instead of rendered. */
    volatile int gen, center, zoomSeq;
    volatile Callback cb;

    PdfEngine() {
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void open(final File f, final OpenResult r) {
        worker.post(() -> {
            int pages = 0;
            String err = null;
            try {
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(pfd);
                pages = renderer.getPageCount();
            } catch (SecurityException e) {
                err = "This PDF is password-protected";
            } catch (Throwable t) {
                err = "This PDF can't be opened";
            }
            final int n = pages;
            final String e = err;
            ui.post(() -> r.done(n, e));
        });
    }

    void requestScreen(final int g, final Layout l, final int screen) {
        worker.post(() -> {
            Bitmap b = null;
            if (g == gen && Math.abs(screen - center) <= 1 && renderer != null) {
                b = obtain(l.w, l.h);
                try {
                    render(l, screen, b, 1f, 0f, 0f);
                } catch (Throwable ignored) {
                    // A page that fails to render shows as blank paper rather than crashing.
                }
            }
            final Bitmap out = b;
            ui.post(() -> {
                Callback c = cb;
                if (c != null) c.onScreen(g, screen, out);
                else recycle(out);
            });
        });
    }

    /** Renders just the visible part of a zoomed screen at full sharpness. */
    void requestZoom(final int seq, final Layout l, final int screen,
                     final float zoom, final float panX, final float panY) {
        worker.post(() -> {
            if (seq != zoomSeq || renderer == null) return;
            final Bitmap b = obtain(l.w, l.h);
            try {
                render(l, screen, b, zoom, panX, panY);
            } catch (Throwable ignored) {
                // Keep the upscaled preview if the sharp pass fails.
            }
            ui.post(() -> {
                Callback c = cb;
                if (c != null) c.onZoom(seq, b, zoom, panX, panY);
                else recycle(b);
            });
        });
    }

    /**
     * Finds the text block so margins can be cropped away. Per-page cropping would make a
     * half-empty page zoom in further than a full one, so this takes the union of the ink bounds
     * over a sample of pages, separately for left- and right-hand pages (their margins mirror).
     */
    void computeCrop(final CropResult r) {
        worker.post(() -> {
            float[] even = null, odd = null;
            boolean cover = false;
            try {
                even = unionCrop(0);
                odd = unionCrop(1);
                float[] first = inkBox(0);
                if (even != null && first != null) {
                    float t = 0.02f;
                    cover = first[0] >= even[0] - t && first[1] >= even[1] - t
                            && first[2] <= even[2] + t && first[3] <= even[3] + t;
                }
            } catch (Throwable ignored) {
                // No crop is a safe fallback.
            }
            final float[] e = even, o = odd;
            final boolean c = cover;
            ui.post(() -> r.done(e, o, c));
        });
    }

    void close() {
        cb = null;
        worker.post(() -> {
            try {
                if (renderer != null) renderer.close();
            } catch (Throwable ignored) {
                // Already closed.
            }
            renderer = null;
            synchronized (pool) {
                closed = true;
                pool.clear();
            }
            thread.quitSafely();
        });
    }

    interface ThumbResult {
        void done(int seq, Bitmap bmp);
    }

    volatile int thumbSeq;

    /** A small picture of one whole page, for previewing where the page slider will land. */
    void requestThumb(final int seq, final int page, final int maxW, final int maxH, final ThumbResult r) {
        worker.post(() -> {
            // Only the newest request matters while the slider is moving.
            if (seq != thumbSeq || renderer == null) return;
            Bitmap b = null;
            try (PdfRenderer.Page p = renderer.openPage(page)) {
                float sc = Math.min(maxW / (float) p.getWidth(), maxH / (float) p.getHeight());
                b = Bitmap.createBitmap(Math.max(1, Math.round(p.getWidth() * sc)),
                        Math.max(1, Math.round(p.getHeight() * sc)), Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                Matrix m = new Matrix();
                m.setScale(sc, sc);
                p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            } catch (Throwable ignored) {
                // No preview for this page.
            }
            final Bitmap out = b;
            ui.post(() -> r.done(seq, out));
        });
    }

    /** Drops the spare bitmaps. */
    void trim() {
        synchronized (pool) {
            pool.clear();
        }
    }

    void recycle(Bitmap b) {
        if (b == null) return;
        synchronized (pool) {
            // Two spares cover a page turn (one evicted, one incoming); more is just held RAM.
            if (!closed && pool.size() < 2) pool.add(b);
        }
    }

    private Bitmap obtain(int w, int h) {
        synchronized (pool) {
            for (int i = pool.size() - 1; i >= 0; i--) {
                Bitmap b = pool.remove(i);
                if (b.getWidth() == w && b.getHeight() == h) return b;
            }
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        // Pages are solid paper; telling the GPU so lets it draw them without blending.
        b.setHasAlpha(false);
        return b;
    }

    private void render(Layout l, int screen, Bitmap bmp, float zoom, float panX, float panY) {
        bmp.eraseColor(Color.WHITE);
        int first = l.firstPage(screen), count = l.pagesOn(screen);
        float areaH = l.h - l.footer;
        Matrix m = new Matrix();
        for (int i = 0; i < count; i++) {
            int pg = first + i;
            if (pg >= renderer.getPageCount()) break;
            // The cell this page sits in, and which side of it the page hugs (the spine).
            float cl = 0, cr = l.w;
            int align = 0;
            if (l.dual && screen > 0) {
                if (i == 0) {
                    cr = l.w / 2f;
                    align = 1;
                } else {
                    cl = l.w / 2f;
                    align = -1;
                }
            }
            try (PdfRenderer.Page p = renderer.openPage(pg)) {
                float pw = p.getWidth(), ph = p.getHeight();
                float sl = 0, st = 0, sr = pw, sb = ph, pad = 0;
                float[] c = l.cropFor(pg);
                if (c != null) {
                    sl = c[0] * pw;
                    st = c[1] * ph;
                    sr = c[2] * pw;
                    sb = c[3] * ph;
                    pad = l.pad;
                }
                float aw = cr - cl - 2 * pad, ah = areaH - 2 * pad;
                float sc = Math.min(aw / (sr - sl), ah / (sb - st));
                float dw = (sr - sl) * sc, dh = (sb - st) * sc;
                float dx = align == 0 ? cl + pad + (aw - dw) / 2f : align > 0 ? cr - pad - dw : cl + pad;
                float dy = pad + (ah - dh) / 2f;
                m.setTranslate(-sl, -st);
                m.postScale(sc, sc);
                m.postTranslate(dx, dy);
                m.postScale(zoom, zoom);
                m.postTranslate(-panX, -panY);
                Rect clip = new Rect(
                        (int) Math.floor(dx * zoom - panX), (int) Math.floor(dy * zoom - panY),
                        (int) Math.ceil((dx + dw) * zoom - panX), (int) Math.ceil((dy + dh) * zoom - panY));
                if (clip.intersect(0, 0, bmp.getWidth(), bmp.getHeight())) {
                    p.render(bmp, clip, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
            }
        }
    }

    private float[] unionCrop(int parity) {
        int n = renderer.getPageCount();
        ArrayList<Integer> idx = new ArrayList<>();
        for (int i = 1; i < n; i++) if (i % 2 == parity) idx.add(i);
        if (idx.isEmpty()) return null;
        int samples = Math.min(14, idx.size());
        float l = 1, t = 1, r = 0, b = 0;
        boolean any = false;
        for (int k = 0; k < samples; k++) {
            float[] box = inkBox(idx.get((int) ((long) k * idx.size() / samples)));
            if (box == null) continue;
            any = true;
            l = Math.min(l, box[0]);
            t = Math.min(t, box[1]);
            r = Math.max(r, box[2]);
            b = Math.max(b, box[3]);
        }
        if (!any) return null;
        float margin = 0.012f;
        l = Math.max(0, l - margin);
        t = Math.max(0, t - margin);
        r = Math.min(1, r + margin);
        b = Math.min(1, b + margin);
        if (r - l > 0.95f && b - t > 0.95f) return null;
        return new float[]{l, t, r, b};
    }

    /** Bounds of everything that is not blank paper, as fractions of the page. */
    private float[] inkBox(int pg) {
        try (PdfRenderer.Page p = renderer.openPage(pg)) {
            int w = 240;
            int h = Math.max(1, Math.min(480, Math.round(w * p.getHeight() / (float) p.getWidth())));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            Matrix m = new Matrix();
            m.setScale(w / (float) p.getWidth(), h / (float) p.getHeight());
            p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            b.recycle();
            int[] cols = new int[w], rows = new int[h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = px[y * w + x];
                    if (((c >> 16) & 255) < 200 || ((c >> 8) & 255) < 200 || (c & 255) < 200) {
                        cols[x]++;
                        rows[y]++;
                    }
                }
            }
            // A row or column needs two dark pixels to count, so single specks are ignored.
            int l = 0, r = w - 1, t = 0, bt = h - 1;
            while (l < w && cols[l] < 2) l++;
            while (r > l && cols[r] < 2) r--;
            while (t < h && rows[t] < 2) t++;
            while (bt > t && rows[bt] < 2) bt--;
            if (l >= w || t >= h) return null;
            return new float[]{l / (float) w, t / (float) h, (r + 1) / (float) w, (bt + 1) / (float) h};
        }
    }
}
