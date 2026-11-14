package app.folio.reader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.util.HashSet;

/**
 * The page surface. It keeps the current screen and its two neighbours as ready-made bitmaps, so
 * a page turn only has to composite; nothing is drawn while the reader is idle, which is what
 * keeps battery use low.
 *
 * Page turns are driven by one point T. For a forward turn the page's right-hand corner C is
 * lifted and carried to T; the crease is the perpendicular bisector of C-T. Everything on C's
 * side of the crease reveals the next page, and the lifted part is drawn mirrored across the
 * crease as the back of the sheet. T runs from the right edge (x = W, page flat) to x = -W
 * (crease at the spine, page fully turned). The slide animation reuses the same T: the crease
 * position is simply the turning page's right edge.
 */
final class ReaderView extends View implements PdfEngine.Callback {

    interface Listener {
        void onCenterTap();

        /** dir is +1/-1 for a page turn, 0 for a jump. */
        void onPageChanged(int page, int dir);

        void onEdge(int dir);

        void onUserActivity();

        /** A page has started to turn (fired once per turn, before onPageChanged). */
        void onTurn();

        /** A highlight was added or removed. */
        void onMarksChanged();
    }

    static final int ANIM_CURL = 0, ANIM_SLIDE = 1, ANIM_NONE = 2;
    private static final int IDLE = 0, DRAG = 1, SETTLE = 2;
    private static final float MAX_ZOOM = 6f;
    private static final float PI = (float) Math.PI;
    /** Bands the roll is drawn in: its face, then its back. */
    private static final int ROLL_FRONT = 5, ROLL_BACK = 12;

    Listener listener;
    boolean menuOpen;

    private PdfEngine engine;
    private Library.Book book;
    private PdfEngine.Layout layout;
    private int pageCount, cur, curPage, gen;
    private boolean cropOn, dualPref;
    private final SparseArray<Bitmap> cache = new SparseArray<>();
    /** One shader per cached page, so a page can be drawn as a polygon fill during a turn. */
    private final SparseArray<BitmapShader> shaders = new SparseArray<>();
    private final HashSet<Integer> pending = new HashSet<>();
    private final SparseArray<String[]> footers = new SparseArray<>();

    private int animMode = ANIM_CURL;
    private int bg, fg;
    private boolean showStatus = true;
    private String status = "";

    private final float dp, animScale, flingMin;
    private final int slop, footerH, pad;
    private final Paint pagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bgPaint = new Paint();
    /** Page image as a polygon fill. No anti-aliasing: adjacent polygons must meet without a seam. */
    private final Paint fillPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint flapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint flapTint = new Paint();
    private final Paint foldShadow = new Paint();
    private final Paint edgeShadowH = new Paint();
    private final Paint edgeShadowV = new Paint();
    private final Paint flapShade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader edgeGradH, edgeGradV, flapGrad, rollGrad;
    private final Matrix shaderMatrix = new Matrix();
    private final float[] quad = new float[8], poly = new float[16], poly2 = new float[16];
    private final Path pathA = new Path(), pathB = new Path();
    /** The back of the sheet on the roll; no anti-aliasing, the bands must meet without seams. */
    private final Paint rollBackPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint();
    /** Crease frame of the turn being drawn: origin and unit x axis. */
    private float fMx, fMy, fNx, fNy;
    private float maxRadius;
    private final Paint footer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ribbon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ribbonPath = new Path();
    private final Paint notePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path notePath = new Path();

    // Reading ruler and highlighter. Both are plain rectangles drawn with the page, so they cost
    // nothing while the page sits still.
    boolean rulerOn, marking;
    private float rulerY = 0.4f, markX, markY;
    private boolean rulerMove, markMoved;
    private final Paint dimPaint = new Paint(), markPaint = new Paint();

    // Page turn state.
    private int tState = IDLE, tDir;
    private float tx, ty, cy;
    private boolean straight, animComplete;
    private ValueAnimator anim;

    // Touch state.
    private final GestureDetector taps;
    private final ScaleGestureDetector scaler;
    private VelocityTracker vt;
    private float downX, downY, lastX, lastY;
    private boolean dragging, multi, tapUsed;
    private int swipeDir;

    // Holding a screen edge riffles through the pages, as fast as they can be rendered.
    private int riffleDir;
    private boolean riffled;
    private final Runnable riffle = new Runnable() {
        @Override
        public void run() {
            if (layout == null || riffleDir == 0) return;
            int target = cur + riffleDir;
            if (target < 0 || target >= layout.screenCount()) {
                riffleDir = 0;
                return;
            }
            if (tState == IDLE && cache.get(target) != null) {
                riffled = true;
                if (listener != null) listener.onTurn();
                setCur(target, riffleDir);
                postDelayed(this, 70);
            } else {
                postDelayed(this, 16);
            }
        }
    };

    // Zoom state. zoomBmp is a sharp render of the viewport at (zbZ, zbX, zbY).
    private float zoom = 1f, panX, panY;
    private Bitmap zoomBmp;
    private float zbZ, zbX, zbY, reqZ, reqX, reqY;
    private int zoomSeq;

    ReaderView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        footerH = Math.round(26 * dp);
        pad = Math.round(14 * dp);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        flingMin = vc.getScaledMinimumFlingVelocity() * 4f;
        float scale = 1f;
        try {
            scale = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable ignored) {
            // Default speed.
        }
        animScale = scale;

        footer.setTextSize(12.5f * c.getResources().getDisplayMetrics().scaledDensity);
        ribbon.setColor(0xFFC0392B);
        notePaint.setColor(0xFFE8A33D);
        dimPaint.setColor(0x73000000);
        markPaint.setColor(0x66FFD400);
        // Unit-length gradients; each is stretched with canvas.scale() when drawn.
        foldShadow.setShader(new LinearGradient(0, 0, -1, 0, 0x66000000, 0x00000000, Shader.TileMode.CLAMP));
        edgeGradH = new LinearGradient(0, 0, 1, 0, 0x48000000, 0x00000000, Shader.TileMode.CLAMP);
        edgeGradV = new LinearGradient(0, 0, 0, 1, 0x48000000, 0x00000000, Shader.TileMode.CLAMP);
        edgeShadowH.setShader(edgeGradH);
        edgeShadowV.setShader(edgeGradV);
        // Across the flat part of the turned sheet, from where it leaves the roll to its free
        // edge: it starts at the roll's last shade and darkens a little again at the edge.
        flapGrad = new LinearGradient(0, 0, 1, 0,
                new int[]{0x26000000, 0x00000000, 0x00000000, 0x1C000000},
                new float[]{0f, 0.35f, 0.80f, 1f}, Shader.TileMode.CLAMP);
        flapShade.setShader(flapGrad);
        int[] light = new int[33];
        float[] at = new float[light.length];
        for (int i = 0; i < light.length; i++) {
            at[i] = i / (float) (light.length - 1);
            light[i] = rollShade(PI * at[i]);
        }
        rollGrad = new LinearGradient(0, 0, 1, 0, light, at, Shader.TileMode.CLAMP);

        taps = new GestureDetector(c, new TapListener());
        taps.setIsLongpressEnabled(false);
        scaler = new ScaleGestureDetector(c, new ScaleListener());
        scaler.setQuickScaleEnabled(false);
        applyTheme(1, 0f);
    }

    // ---------------------------------------------------------------- setup

    void setBook(PdfEngine e, Library.Book b, int pages, boolean crop, boolean dual) {
        engine = e;
        book = b;
        pageCount = pages;
        cropOn = crop;
        dualPref = dual;
        curPage = clamp(b.page, 0, pages - 1);
        e.cb = this;
        relayout();
    }

    void detach() {
        cancelTurn();
        if (engine != null) engine.cb = null;
        engine = null;
        layout = null;
        cache.clear();
        shaders.clear();
        zoomBmp = null;
    }

    /**
     * Gives back every rendered page while the reader is not on screen (another app in front, or
     * the screen off). Three screen-sized images are too much to sit on in a 2 GB tablet.
     */
    void releasePages() {
        if (engine == null || layout == null) return;
        cancelTurn();
        resetZoom();
        gen++;
        engine.gen = gen;
        cache.clear();
        shaders.clear();
        pending.clear();
        engine.trim();
    }

    /** Re-renders after releasePages(); a page takes well under a tenth of a second. */
    void restorePages() {
        if (engine == null || layout == null || cache.size() > 0) return;
        requestWindow();
        invalidate();
    }

    void applyTheme(int theme, float warmth) {
        bg = Themes.tint(Themes.BG[theme], warmth);
        fg = Themes.tint(Themes.FG[theme], warmth);
        pagePaint.setColorFilter(Themes.filter(theme, warmth));
        fillPaint.setColorFilter(pagePaint.getColorFilter());
        bgPaint.setColor(bg);
        int flap = Themes.blend(bg, fg, 0.10f);
        flapPaint.setColorFilter(Themes.washed(theme, warmth, flap, 0.88f));
        rollBackPaint.setColorFilter(flapPaint.getColorFilter());
        flapTint.setColor(flap);
        footer.setColor((fg & 0x00FFFFFF) | 0x99000000);
        invalidate();
    }

    void setAnimMode(int mode) {
        finishAnimNow();
        animMode = mode;
    }

    void setCrop(boolean on) {
        cropOn = on;
        relayout();
    }

    void setDual(boolean on) {
        dualPref = on;
        relayout();
    }

    void setStatus(String text, boolean show) {
        status = text;
        showStatus = show;
        invalidate();
    }

    int getPage() {
        return curPage;
    }

    /** True while a page is being dragged or is animating. */
    boolean isTurning() {
        return tState != IDLE;
    }

    int pagesOnScreen() {
        return layout == null ? 1 : layout.pagesOn(cur);
    }

    boolean isCurrentBookmarked() {
        return layout != null && bookmarked(cur);
    }

    void goToPage(int page) {
        if (layout == null) return;
        cancelTurn();
        resetZoom();
        curPage = clamp(page, 0, pageCount - 1);
        cur = layout.screenOf(curPage);
        engine.center = cur;
        footers.clear();
        requestWindow();
        if (listener != null) listener.onPageChanged(curPage, 0);
        invalidate();
    }

    /** Turns one screen forward (+1) or back (-1). */
    void turn(int dir, boolean animated) {
        if (layout == null) return;
        finishAnimNow();
        if (zoom != 1f) resetZoom();
        int target = cur + dir;
        if (target < 0 || target >= layout.screenCount()) {
            if (listener != null) listener.onEdge(dir);
            return;
        }
        if (listener != null) listener.onTurn();
        if (!animated || animMode == ANIM_NONE || cache.get(target) == null || cache.get(cur) == null) {
            setCur(target, dir);
            return;
        }
        float w = getWidth(), h = getHeight();
        tDir = dir;
        straight = animMode == ANIM_SLIDE;
        cy = straight ? h / 2f : h;
        float from = dir > 0 ? w : -w;
        tx = from;
        ty = cy;
        startSettle(from, cy, -from, cy, !straight, true, animMode == ANIM_SLIDE ? 300 : 560);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        ribbonPath.reset();
        float x = w - 54 * dp, rw = 20 * dp, rh = 34 * dp;
        ribbonPath.moveTo(x, 0);
        ribbonPath.lineTo(x + rw, 0);
        ribbonPath.lineTo(x + rw, rh);
        ribbonPath.lineTo(x + rw / 2f, rh - 8 * dp);
        ribbonPath.lineTo(x, rh);
        ribbonPath.close();
        // A page with a note gets a small folded tab at the other top corner.
        notePath.reset();
        float nx0 = 30 * dp, nw = 22 * dp, nh = 26 * dp;
        notePath.moveTo(nx0, 0);
        notePath.lineTo(nx0 + nw, 0);
        notePath.lineTo(nx0 + nw, nh - 8 * dp);
        notePath.lineTo(nx0 + nw - 8 * dp, nh);
        notePath.lineTo(nx0, nh);
        notePath.close();
        maxRadius = 0.10f * Math.min(w, h);
        relayout();
    }

    private void relayout() {
        if (engine == null || getWidth() == 0 || getHeight() == 0) return;
        cancelTurn();
        resetZoom();
        gen++;
        engine.gen = gen;
        for (int i = 0; i < cache.size(); i++) engine.recycle(cache.valueAt(i));
        cache.clear();
        shaders.clear();
        pending.clear();
        footers.clear();
        boolean dual = dualPref && getWidth() > getHeight() && pageCount > 1;
        boolean crop = cropOn && book.cropDone;
        layout = new PdfEngine.Layout(getWidth(), getHeight(), footerH, pad, pageCount, dual,
                crop ? book.cropEven : null, crop ? book.cropOdd : null, crop && book.cropCover);
        cur = layout.screenOf(curPage);
        engine.center = cur;
        requestWindow();
        invalidate();
    }

    private void setCur(int target, int dir) {
        cur = target;
        curPage = layout.firstPage(cur);
        engine.center = cur;
        footers.clear();
        requestWindow();
        if (listener != null) listener.onPageChanged(curPage, dir);
        invalidate();
    }

    /** Keeps the current screen and both neighbours rendered; everything else goes to the pool. */
    private void requestWindow() {
        for (int i = cache.size() - 1; i >= 0; i--) {
            if (Math.abs(cache.keyAt(i) - cur) > 1) {
                engine.recycle(cache.valueAt(i));
                shaders.remove(cache.keyAt(i));
                cache.removeAt(i);
            }
        }
        int n = layout.screenCount();
        int[] order = {cur, cur + 1, cur - 1};
        for (int s : order) {
            if (s >= 0 && s < n && cache.get(s) == null && !pending.contains(s)) {
                pending.add(s);
                engine.requestScreen(gen, layout, s);
            }
        }
    }

    @Override
    public void onScreen(int g, int screen, Bitmap bmp) {
        if (engine == null) return;
        if (g != gen) {
            engine.recycle(bmp);
            return;
        }
        pending.remove(screen);
        if (bmp == null) {
            // Dropped as stale while we were flipping; ask again if it is wanted after all.
            if (Math.abs(screen - cur) <= 1) requestWindow();
            return;
        }
        if (Math.abs(screen - cur) > 1) {
            engine.recycle(bmp);
            return;
        }
        engine.recycle(cache.get(screen));
        cache.put(screen, bmp);
        shaders.put(screen, new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        // Upload the texture now, while idle, instead of on the first frame of the next page turn.
        bmp.prepareToDraw();
        // A neighbour arriving while a page sits still changes nothing on screen: no redraw.
        if (screen == cur || tState != IDLE) invalidate();
    }

    @Override
    public void onZoom(int seq, Bitmap bmp, float z, float px, float py) {
        if (engine == null) return;
        if (seq != zoomSeq || z != zoom) {
            engine.recycle(bmp);
            return;
        }
        engine.recycle(zoomBmp);
        zoomBmp = bmp;
        zbZ = z;
        zbX = px;
        zbY = py;
        invalidate();
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        // Every path below paints the whole view, so there is no background fill to pay for.
        if (layout == null) {
            c.drawColor(bg);
            return;
        }
        if (tState != IDLE) {
            int top = tDir > 0 ? cur : cur - 1, bottom = tDir > 0 ? cur + 1 : cur;
            if (animMode == ANIM_SLIDE) drawSlide(c, top, bottom);
            else drawCurl(c, top, bottom);
            return;
        }
        if (zoom == 1f) {
            drawPage(c, cur);
            return;
        }
        Bitmap b = cache.get(cur);
        if (b != null) {
            c.save();
            c.translate(-panX, -panY);
            c.scale(zoom, zoom);
            c.drawBitmap(b, 0, 0, pagePaint);
            c.restore();
        }
        // The sharp render may lag behind the pan; draw it where it still lines up.
        if (zoomBmp != null && zbZ == zoom) c.drawBitmap(zoomBmp, zbX - panX, zbY - panY, pagePaint);
    }

    private void drawPage(Canvas c, int screen) {
        Bitmap b = cache.get(screen);
        if (b != null) {
            c.drawBitmap(b, 0, 0, pagePaint);
        } else {
            c.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
            if (screen == cur) {
                footer.setTextAlign(Paint.Align.CENTER);
                c.drawText("Opening…", getWidth() / 2f, getHeight() / 2f, footer);
            }
        }
        if (bookmarked(screen)) c.drawPath(ribbonPath, ribbon);
        if (noted(screen)) c.drawPath(notePath, notePaint);
        drawMarks(c, screen);
        if (rulerOn && screen == cur && tState == IDLE) {
            // Dim everything but a band a few lines tall; drag up or down to move it.
            float h = getHeight() - footerH, band = 96 * dp;
            float top = clamp(rulerY * h - band / 2f, 0f, h - band);
            c.drawRect(0, 0, getWidth(), top, dimPaint);
            c.drawRect(0, top + band, getWidth(), h, dimPaint);
        }
        drawFooter(c, screen);
    }

    private void drawMarks(Canvas c, int screen) {
        if (book == null || engine == null || screen < 0 || screen >= layout.screenCount()) return;
        int first = layout.firstPage(screen);
        for (int i = 0; i < layout.pagesOn(screen); i++) {
            java.util.ArrayList<float[]> list = book.highlights.get(first + i);
            float[] p = engine.placed.get(first + i);
            if (list == null || p == null) continue;
            for (float[] r : list) {
                c.drawRect(p[0] + (r[0] * p[7] - p[4]) * p[6], p[1] + (r[1] * p[8] - p[5]) * p[6],
                        p[0] + (r[2] * p[7] - p[4]) * p[6], p[1] + (r[3] * p[8] - p[5]) * p[6], markPaint);
            }
        }
        if (markMoved && screen == cur) {
            c.drawRect(Math.min(downX, markX), Math.min(downY, markY), Math.max(downX, markX), Math.max(downY, markY), markPaint);
        }
    }

    /** Turns the dragged rectangle into a highlight on the page it started on, in page fractions. */
    private void commitMark() {
        float l = Math.min(downX, markX), t = Math.min(downY, markY), r = Math.max(downX, markX), b = Math.max(downY, markY);
        if (r - l < 12 * dp || b - t < 6 * dp) return;
        int first = layout.firstPage(cur);
        for (int i = 0; i < layout.pagesOn(cur); i++) {
            float[] p = engine.placed.get(first + i);
            if (p == null || downX < p[0] || downX > p[0] + p[2]) continue;
            float[] f = {clamp(((l - p[0]) / p[6] + p[4]) / p[7], 0f, 1f), clamp(((t - p[1]) / p[6] + p[5]) / p[8], 0f, 1f),
                    clamp(((r - p[0]) / p[6] + p[4]) / p[7], 0f, 1f), clamp(((b - p[1]) / p[6] + p[5]) / p[8], 0f, 1f)};
            java.util.ArrayList<float[]> list = book.highlights.get(first + i);
            if (list == null) book.highlights.put(first + i, list = new java.util.ArrayList<>());
            list.add(f);
            if (listener != null) listener.onMarksChanged();
            return;
        }
    }

    private boolean eraseMarkAt(float x, float y) {
        int first = layout.firstPage(cur);
        for (int i = 0; i < layout.pagesOn(cur); i++) {
            java.util.ArrayList<float[]> list = book.highlights.get(first + i);
            float[] p = engine.placed.get(first + i);
            if (list == null || p == null) continue;
            float fx = ((x - p[0]) / p[6] + p[4]) / p[7], fy = ((y - p[1]) / p[6] + p[5]) / p[8];
            for (int k = list.size() - 1; k >= 0; k--) {
                float[] r = list.get(k);
                if (fx >= r[0] && fx <= r[2] && fy >= r[1] && fy <= r[3]) {
                    list.remove(k);
                    if (list.isEmpty()) book.highlights.remove(first + i);
                    if (listener != null) listener.onMarksChanged();
                    invalidate();
                    return true;
                }
            }
        }
        return false;
    }

    private void drawSlide(Canvas c, int top, int bottom) {
        float w = getWidth(), h = getHeight();
        float edge = (w + tx) / 2f;
        // Only the strip the sliding page has uncovered needs the page underneath.
        c.save();
        c.clipRect(edge - 1f, 0, w, h);
        drawPage(c, bottom);
        c.restore();
        c.save();
        c.translate(edge, 0);
        c.scale(18 * dp, 1f);
        c.drawRect(0, 0, 1, h, edgeShadowH);
        c.restore();
        c.save();
        c.translate(edge - w, 0);
        drawPage(c, top);
        c.restore();
    }

    /**
     * Draws a turn in progress as a sheet rolling over a cylinder, which is what paper does: the
     * page leaves the book along a straight line, wraps half-way round a roll of radius r, and
     * the rest lies flat on top, face down. Because the roll's axis is straight, everything
     * depends only on the distance u past that line, so the roll is drawn as narrow bands
     * parallel to the axis, each squeezed to the width it shows at its angle (and flipped once
     * past the top). A band is one textured polygon, so the GPU only shades pixels that end up
     * visible; rotated clips would make it shade the whole screen for every layer.
     *
     * In the crease frame (origin at the midpoint of corner C and its target T, +x toward T):
     *   x >= half             the sheet still lies flat
     *   -half <= x <= half    wrapped on the roll, landing at half - r*sin(u/r), u = half - x
     *   x <= -half            flat on top, mirrored about x = 0, which carries C exactly onto T
     * where half = r*pi/2.
     */
    private void drawCurl(Canvas c, int top, int bottom) {
        final float w = getWidth(), h = getHeight();
        final float dx = tx - w, dy = ty - cy;
        final float d = (float) Math.hypot(dx, dy);
        if (d < 0.5f) {
            drawPage(c, top);
            return;
        }
        fNx = dx / d;
        fNy = dy / d;
        fMx = (w + tx) / 2f;
        fMy = (cy + ty) / 2f;
        final float mx = fMx, my = fMy;
        final float ang = (float) Math.toDegrees(Math.atan2(dy, dx));
        final float big = (w + h) * 2f;

        // The roll grows as the corner is pulled, up to a comfortable size, and flattens again
        // as it reaches the spine: the sheet must never lift off along the spine itself.
        float room = Math.min(-mx * fNx - my * fNy, -mx * fNx + (h - my) * fNy);
        float r = Math.min(Math.min(maxRadius, 0.27f * d), 2f * room / PI);
        if (r < 2f) r = 0f;
        final float half = r * PI / 2f;
        final float edge = half - r;

        setQuad(quad, 0, 0, w, 0, w, h, 0, h);

        // The page underneath shows beyond the roll's outline. It is drawn right up to where the
        // sheet lies flat: on a tilted turn the roll does not quite cover that strip at the page's
        // edge, and nothing else would paint there.
        if (toPath(pathA, poly, cut(quad, 4, half + 2f, -1f, poly))) {
            fillPage(c, pathA, bottom);
            drawOverlays(c, bottom, mx + edge * fNx, my + edge * fNy, ang, false);
        }

        // Shadow the roll casts on the page underneath: full strength below the roll itself,
        // fading out beyond its outline.
        float reach = clamp(d * 0.20f, 8 * dp, 56 * dp) + 0.5f * r;
        c.save();
        c.translate(mx, my);
        c.rotate(ang);
        c.translate(edge, 0f);
        c.scale(reach, 1f);
        c.drawRect(-1f, -big, r / reach, big, foldShadow);
        c.restore();

        // The part of the sheet still lying flat.
        if (toPath(pathA, poly, cut(quad, 4, half, 1f, poly))) {
            fillPage(c, pathA, top);
            drawOverlays(c, top, mx + half * fNx, my + half * fNy, ang, true);
        }

        final BitmapShader sheet = shaders.get(top);

        // One gradient shades the whole roll. It is laid out along the sheet before rolling (from
        // where it leaves the page to where it lies flat again), so each band picks up exactly
        // its own stretch of it and the light changes smoothly across band edges.
        if (r > 0f) {
            shaderMatrix.setScale(-2f * half, 1f);
            shaderMatrix.postTranslate(half, 0f);
            shaderMatrix.postRotate(ang);
            shaderMatrix.postTranslate(mx, my);
            rollGrad.setLocalMatrix(shaderMatrix);
            shadePaint.setShader(rollGrad);
        }

        // The roll, lowest band first so each one covers the one beneath it.
        for (int i = 0; r > 0f && i < ROLL_FRONT + ROLL_BACK; i++) {
            boolean back = i >= ROLL_FRONT;
            float a0 = back ? PI / 2f * (1f + (i - ROLL_FRONT) / (float) ROLL_BACK) : PI / 2f * i / ROLL_FRONT;
            float a1 = back ? PI / 2f * (1f + (i - ROLL_FRONT + 1) / (float) ROLL_BACK) : PI / 2f * (i + 1) / ROLL_FRONT;
            float x0 = half - r * a0, x1 = half - r * a1;
            float land0 = half - r * (float) Math.sin(a0), land1 = half - r * (float) Math.sin(a1);
            float k = (land1 - land0) / (x1 - x0);
            // Reach back under the previous band by about a pixel and a half of screen.
            float lap = Math.min(x0 - x1, 1.5f / Math.max(Math.abs(k), 0.05f));
            int n = cut(quad, 4, x0 + lap, -1f, poly2);
            if (!toPath(pathB, poly, cut(poly2, n, x1, 1f, poly))) continue;
            c.save();
            c.translate(mx, my);
            c.rotate(ang);
            c.translate(land0, 0f);
            c.scale(k, 1f);
            c.translate(-x0, 0f);
            c.rotate(-ang);
            c.translate(-mx, -my);
            if (sheet != null) {
                Paint p = back ? rollBackPaint : fillPaint;
                p.setShader(sheet);
                c.drawPath(pathB, p);
            } else {
                c.drawPath(pathB, back ? flapTint : bgPaint);
            }
            c.drawPath(pathB, shadePaint);
            c.restore();
        }

        // The rest of the lifted sheet lies flat on top, face down: page coordinates drawn
        // through a mirror about the crease frame's origin.
        if (!toPath(pathA, poly, cut(quad, 4, -half + (r > 0f ? 1.5f : 0f), -1f, poly))) return;
        c.save();
        c.translate(mx, my);
        c.rotate(ang);
        c.scale(-1f, 1f);
        c.rotate(-ang);
        c.translate(-mx, -my);

        // Soft shadows just outside its free edges, each cut off where the sheet meets the roll.
        // They stop at the corner: overlapping past it would leave a dark square.
        float es = clamp(d * 0.10f, 4 * dp, 28 * dp);
        setQuad(quad, w, 0, w + es, 0, w + es, h, w, h);
        if (toPath(pathB, poly, cut(quad, 4, -half, -1f, poly))) {
            shaderMatrix.setScale(es, 1f);
            shaderMatrix.postTranslate(w, 0f);
            fillGradient(c, pathB, edgeShadowH, edgeGradH);
        }
        if (!straight) {
            if (cy > h / 2f) {
                setQuad(quad, 0, h, w, h, w, h + es, 0, h + es);
                shaderMatrix.setScale(1f, es);
                shaderMatrix.postTranslate(0f, h);
            } else {
                setQuad(quad, 0, -es, w, -es, w, 0, 0, 0);
                shaderMatrix.setScale(1f, -es);
            }
            if (toPath(pathB, poly, cut(quad, 4, -half, -1f, poly))) {
                fillGradient(c, pathB, edgeShadowV, edgeGradV);
            }
        }

        // The back of the sheet: the page's own content mirrored, washed out to paper colour.
        if (sheet != null) {
            flapPaint.setShader(sheet);
            c.drawPath(pathA, flapPaint);
        } else {
            c.drawPath(pathA, flapTint);
        }

        // Light falling off from where it leaves the roll out to its free edge.
        shaderMatrix.setScale(-Math.max(1f, d / 2f - half), 1f);
        shaderMatrix.postTranslate(-half, 0f);
        shaderMatrix.postRotate(ang);
        shaderMatrix.postTranslate(mx, my);
        fillGradient(c, pathA, flapShade, flapGrad);
        c.restore();
    }

    /** Cuts a polygon along the line x = const of the current crease frame; side -1 keeps x <= const. */
    private int cut(float[] in, int n, float x, float side, float[] out) {
        return clipToCrease(in, n, fMx + x * fNx, fMy + x * fNy, fNx, fNy, side, 0f, out);
    }

    /**
     * Light on the roll at angle a (0 where the sheet leaves the page, pi where it lies flat on
     * top): the face darkens as it turns away, the back comes out of shadow through a highlight.
     */
    private static int rollShade(float a) {
        float black, white = 0f;
        if (a <= PI / 2f) {
            float t = 1f - (float) Math.cos(a);
            black = 0.46f * t * (float) Math.sqrt(t);
        } else {
            float t = (a - PI / 2f) / (PI / 2f);
            if (t < 0.30f) {
                black = 0.40f * (1f - t / 0.30f);
            } else if (t < 0.75f) {
                black = 0f;
                white = 0.20f * (float) Math.sin(Math.PI * (t - 0.30f) / 0.45f);
            } else {
                black = 0.15f * (t - 0.75f) / 0.25f;
            }
        }
        return white > 0f ? (Math.round(white * 255f) << 24) | 0xFFFFFF : Math.round(black * 255f) << 24;
    }

    private void fillPage(Canvas c, Path path, int screen) {
        BitmapShader s = shaders.get(screen);
        if (s != null) {
            fillPaint.setShader(s);
            c.drawPath(path, fillPaint);
        } else {
            c.drawPath(path, bgPaint);
        }
    }

    /** Fills a path with a unit gradient placed by shaderMatrix. */
    private void fillGradient(Canvas c, Path path, Paint paint, Shader gradient) {
        gradient.setLocalMatrix(shaderMatrix);
        paint.setShader(gradient);
        c.drawPath(path, paint);
    }

    /** Footer and bookmark ribbon for one side of the crease. These are small, so a clip is cheap. */
    private void drawOverlays(Canvas c, int screen, float mx, float my, float ang, boolean farSide) {
        float big = (getWidth() + getHeight()) * 2f;
        c.save();
        c.translate(mx, my);
        c.rotate(ang);
        if (farSide) c.clipRect(0f, -big, big, big);
        else c.clipRect(-big, -big, 0f, big);
        c.rotate(-ang);
        c.translate(-mx, -my);
        if (bookmarked(screen)) c.drawPath(ribbonPath, ribbon);
        if (noted(screen)) c.drawPath(notePath, notePaint);
        drawFooter(c, screen);
        c.restore();
    }

    private static void setQuad(float[] q, float x0, float y0, float x1, float y1,
                                float x2, float y2, float x3, float y3) {
        q[0] = x0;
        q[1] = y0;
        q[2] = x1;
        q[3] = y1;
        q[4] = x2;
        q[5] = y2;
        q[6] = x3;
        q[7] = y3;
    }

    /**
     * Cuts a convex polygon along the crease, keeping the side where (p - m)·n has the sign of
     * `side`, grown outward by `grow` pixels. Returns the number of vertices written to `out`.
     */
    private static int clipToCrease(float[] in, int n, float mx, float my, float nx, float ny,
                                    float side, float grow, float[] out) {
        int k = 0;
        float px = in[2 * (n - 1)], py = in[2 * (n - 1) + 1];
        float pd = side * ((px - mx) * nx + (py - my) * ny) + grow;
        for (int i = 0; i < n; i++) {
            float qx = in[2 * i], qy = in[2 * i + 1];
            float qd = side * ((qx - mx) * nx + (qy - my) * ny) + grow;
            if ((pd >= 0f) != (qd >= 0f)) {
                float t = pd / (pd - qd);
                out[k++] = px + (qx - px) * t;
                out[k++] = py + (qy - py) * t;
            }
            if (qd >= 0f) {
                out[k++] = qx;
                out[k++] = qy;
            }
            px = qx;
            py = qy;
            pd = qd;
        }
        return k / 2;
    }

    private static boolean toPath(Path path, float[] pts, int n) {
        path.rewind();
        if (n < 3) return false;
        path.moveTo(pts[0], pts[1]);
        for (int i = 1; i < n; i++) path.lineTo(pts[2 * i], pts[2 * i + 1]);
        path.close();
        return true;
    }

    private void drawFooter(Canvas c, int screen) {
        if (screen < 0 || screen >= layout.screenCount()) return;
        String[] f = footers.get(screen);
        if (f == null) {
            int first = layout.firstPage(screen), last = first + layout.pagesOn(screen) - 1;
            String left = last > first
                    ? "Pages " + (first + 1) + "–" + (last + 1) + " of " + pageCount
                    : "Page " + (first + 1) + " of " + pageCount;
            String right = ((last + 1) * 100 / pageCount) + "%";
            String time = timeLeft(last);
            if (time != null) right = time + "   ·   " + right;
            f = new String[]{left, right};
            footers.put(screen, f);
        }
        float y = getHeight() - 9 * dp, m = 18 * dp;
        footer.setTextAlign(Paint.Align.LEFT);
        c.drawText(f[0], m, y, footer);
        footer.setTextAlign(Paint.Align.RIGHT);
        c.drawText(f[1], getWidth() - m, y, footer);
        if (showStatus && !status.isEmpty()) {
            footer.setTextAlign(Paint.Align.CENTER);
            c.drawText(status, getWidth() / 2f, y, footer);
        }
    }

    private String timeLeft(int lastPage) {
        if (book == null || book.samples < 5) return null;
        int remaining = pageCount - 1 - lastPage;
        if (remaining <= 0) return null;
        int min = Math.round(remaining * book.secPerPage / 60f);
        if (min < 1) return "under a minute left";
        if (min < 60) return min + " min left";
        return (min / 60) + " h " + (min % 60) + " min left";
    }

    private boolean noted(int screen) {
        if (book == null || book.notes.isEmpty() || screen < 0 || screen >= layout.screenCount()) return false;
        int first = layout.firstPage(screen);
        for (int i = 0; i < layout.pagesOn(screen); i++) if (book.notes.containsKey(first + i)) return true;
        return false;
    }

    private boolean bookmarked(int screen) {
        if (book == null || book.bookmarks.isEmpty() || screen < 0 || screen >= layout.screenCount()) return false;
        int first = layout.firstPage(screen);
        for (int i = 0; i < layout.pagesOn(screen); i++) if (book.bookmarks.contains(first + i)) return true;
        return false;
    }

    // ---------------------------------------------------------------- page-turn motion

    private void beginDrag(int dir) {
        float w = getWidth(), h = getHeight();
        tState = DRAG;
        tDir = dir;
        if (dir > 0 && animMode == ANIM_CURL && downY > 0.62f * h) {
            straight = false;
            cy = h;
        } else if (dir > 0 && animMode == ANIM_CURL && downY < 0.38f * h) {
            straight = false;
            cy = 0;
        } else {
            straight = true;
            cy = downY;
        }
        tx = dir > 0 ? w : -w;
        ty = cy;
    }

    private void updateDrag(float x, float y) {
        float w = getWidth();
        if (tDir > 0) {
            // Scaled so that a drag starting at the screen edge keeps the crease under the finger.
            tx = w - (downX - x) * 2f * w / Math.max(downX, 0.35f * w);
            ty = straight ? cy : cy + (y - downY);
        } else {
            tx = -w + (x - downX) * 2f * w / Math.max(w - downX, 0.35f * w);
            ty = cy;
        }
        constrain();
        invalidate();
    }

    /** Keeps T where a real sheet could be: never past the spine, never torn off it. */
    private void constrain() {
        float w = getWidth();
        tx = clamp(tx, -w, w);
        if (straight) {
            ty = cy;
            return;
        }
        ty = cy > getHeight() / 2f ? Math.min(ty, cy) : Math.max(ty, cy);
        float ddy = ty - cy;
        float dist = (float) Math.hypot(tx, ddy);
        if (dist > w) {
            tx = tx * w / dist;
            ty = cy + ddy * w / dist;
        }
    }

    private void endDrag(float vx, boolean cancelled) {
        float w = getWidth();
        float progress = tDir > 0 ? (w - tx) / (2f * w) : (tx + w) / (2f * w);
        boolean complete;
        if (cancelled) complete = false;
        else if (tDir > 0) complete = vx < -flingMin || (progress > 0.33f && vx < flingMin);
        else complete = vx > flingMin || (progress > 0.33f && vx > -flingMin);
        float endX = (tDir > 0) == complete ? -w : w;
        if (complete && listener != null) listener.onTurn();
        startSettle(tx, ty, endX, cy, false, complete, (long) (140 + 260 * Math.abs(endX - tx) / (2f * w)));
    }

    private void startSettle(final float sx, final float sy, final float ex, final float ey,
                             final boolean arc, boolean complete, long ms) {
        tState = SETTLE;
        animComplete = complete;
        final float w = getWidth(), h = getHeight();
        anim = ValueAnimator.ofFloat(0f, 1f);
        // The system-wide animation speed setting would otherwise rush the page turn.
        anim.setDuration(animScale > 0.01f ? (long) (ms / animScale) : ms);
        // A tapped turn leaves quickly and lands softly, like a page thrown over by a thumb.
        anim.setInterpolator(arc ? new PathInterpolator(0.30f, 0.05f, 0.20f, 1f) : new DecelerateInterpolator(1.5f));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            tx = sx + (ex - sx) * f;
            ty = sy + (ey - sy) * f;
            // Tap-to-turn lifts the corner in a shallow arc so the crease is not a dead-vertical line.
            if (arc) ty -= (float) Math.sin(Math.PI * (w - tx) / (2f * w)) * 0.14f * h;
            constrain();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                anim = null;
                tState = IDLE;
                if (animComplete) setCur(cur + tDir, tDir);
                else invalidate();
            }
        });
        anim.start();
    }

    private void finishAnimNow() {
        if (anim != null) anim.end();
    }

    private void cancelTurn() {
        if (anim != null) {
            anim.removeAllListeners();
            anim.cancel();
            anim = null;
        }
        tState = IDLE;
    }

    // ---------------------------------------------------------------- zoom

    private void resetZoom() {
        zoom = 1f;
        panX = panY = 0f;
        reqZ = 0f;
        zoomSeq++;
        if (engine != null) {
            engine.zoomSeq = zoomSeq;
            engine.recycle(zoomBmp);
        }
        zoomBmp = null;
        invalidate();
    }

    private void clampPan() {
        panX = clamp(panX, 0f, getWidth() * (zoom - 1f));
        panY = clamp(panY, 0f, getHeight() * (zoom - 1f));
    }

    private void requestZoom() {
        if (zoom <= 1f || layout == null) return;
        if (reqZ == zoom && reqX == panX && reqY == panY) return;
        reqZ = zoom;
        reqX = panX;
        reqY = panY;
        zoomSeq++;
        engine.zoomSeq = zoomSeq;
        engine.requestZoom(zoomSeq, layout, cur, zoom, panX, panY);
    }

    // ---------------------------------------------------------------- touch

    private int zoneOf(float x) {
        float w = getWidth();
        return x < 0.28f * w ? -1 : x > 0.72f * w ? 1 : 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (layout == null) return true;
        int action = e.getActionMasked();
        float x = e.getX(), y = e.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            if (listener != null) listener.onUserActivity();
            finishAnimNow();
            downX = lastX = x;
            downY = lastY = y;
            dragging = multi = tapUsed = riffled = false;
            swipeDir = 0;
            rulerMove = markMoved = false;
            removeCallbacks(riffle);
            riffleDir = (zoom == 1f && !menuOpen) ? zoneOf(x) : 0;
            if (riffleDir != 0) postDelayed(riffle, 500);
            if (vt == null) vt = VelocityTracker.obtain();
            else vt.clear();
        }
        scaler.onTouchEvent(e);
        taps.onTouchEvent(e);
        if (vt != null) vt.addMovement(e);
        // Riffling needs a finger held still on the edge; anything else calls it off.
        if (action != MotionEvent.ACTION_DOWN && riffleDir != 0 && (action != MotionEvent.ACTION_MOVE
                || Math.abs(x - downX) > slop || Math.abs(y - downY) > slop)) {
            removeCallbacks(riffle);
            riffleDir = 0;
        }

        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger means pinch; put a half-dragged page back.
                multi = true;
                if (tState == DRAG) {
                    tState = IDLE;
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (multi || menuOpen) break;
                if (zoom > 1f) {
                    panX -= x - lastX;
                    panY -= y - lastY;
                    clampPan();
                    invalidate();
                } else if (marking) {
                    if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) markMoved = true;
                    markX = x;
                    markY = y;
                    if (markMoved) invalidate();
                } else if (rulerMove || (rulerOn && !dragging && Math.abs(y - downY) > slop
                        && Math.abs(y - downY) > Math.abs(x - downX))) {
                    rulerMove = true;
                    rulerY = clamp(y / (getHeight() - footerH), 0f, 1f);
                    invalidate();
                } else {
                    float dx = x - downX, dy = y - downY;
                    if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        int dir = dx < 0 ? 1 : -1;
                        int target = cur + dir;
                        if (target >= 0 && target < layout.screenCount()) {
                            if (animMode == ANIM_NONE) swipeDir = dir;
                            else beginDrag(dir);
                        }
                    }
                    if (tState == DRAG) updateDrag(x, y);
                }
                lastX = x;
                lastY = y;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean cancelled = action == MotionEvent.ACTION_CANCEL;
                if (markMoved) {
                    if (!cancelled) commitMark();
                    markMoved = false;
                    invalidate();
                    break;
                }
                if (rulerMove) {
                    rulerMove = false;
                    break;
                }
                if (zoom > 1f) {
                    requestZoom();
                } else if (tState == DRAG) {
                    vt.computeCurrentVelocity(1000);
                    endDrag(vt.getXVelocity(), cancelled);
                } else if (swipeDir != 0 && !cancelled && Math.abs(x - downX) > 40 * dp) {
                    turn(swipeDir, false);
                }
                break;
            default:
                break;
        }
        return true;
    }

    private final class TapListener extends GestureDetector.SimpleOnGestureListener {
        // Edge taps turn the page on finger-up, with no double-tap wait, so flipping feels instant.
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (riffled) {
                // Letting go after a riffle is not a tap.
                tapUsed = true;
                return true;
            }
            if (marking && !menuOpen && eraseMarkAt(e.getX(), e.getY())) {
                tapUsed = true;
                return true;
            }
            if (menuOpen) {
                tapUsed = true;
                if (listener != null) listener.onCenterTap();
                return true;
            }
            int zone = zoneOf(e.getX());
            if (zoom == 1f && zone != 0) {
                tapUsed = true;
                turn(zone, true);
                return true;
            }
            return false;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (!tapUsed && listener != null) listener.onCenterTap();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (menuOpen) return true;
            int zone = zoneOf(e.getX());
            if (zoom == 1f && zone != 0) {
                turn(zone, true);
                return true;
            }
            if (zoom > 1f) {
                resetZoom();
            } else {
                zoom = 2.2f;
                panX = e.getX() * zoom - e.getX();
                panY = e.getY() * zoom - e.getY();
                clampPan();
                requestZoom();
                invalidate();
            }
            return true;
        }
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector d) {
            return !menuOpen && tState != SETTLE;
        }

        @Override
        public boolean onScale(ScaleGestureDetector d) {
            float nz = clamp(zoom * d.getScaleFactor(), 1f, MAX_ZOOM);
            // Keep the point under the fingers fixed while the scale changes.
            float ux = (panX + d.getFocusX()) / zoom, uy = (panY + d.getFocusY()) / zoom;
            zoom = nz;
            panX = ux * zoom - d.getFocusX();
            panY = uy * zoom - d.getFocusY();
            clampPan();
            invalidate();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector d) {
            if (zoom < 1.08f) resetZoom();
            else requestZoom();
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
