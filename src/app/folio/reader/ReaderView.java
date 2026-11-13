package app.folio.reader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
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
import android.view.animation.AccelerateDecelerateInterpolator;
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
    }

    static final int ANIM_CURL = 0, ANIM_SLIDE = 1, ANIM_NONE = 2;
    private static final int IDLE = 0, DRAG = 1, SETTLE = 2;
    private static final float MAX_ZOOM = 6f;

    Listener listener;
    boolean menuOpen;

    private PdfEngine engine;
    private Library.Book book;
    private PdfEngine.Layout layout;
    private int pageCount, cur, curPage, gen;
    private boolean cropOn, dualPref;
    private final SparseArray<Bitmap> cache = new SparseArray<>();
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
    private final Paint flapTint = new Paint();
    private final Paint foldShadow = new Paint();
    private final Paint edgeShadowH = new Paint();
    private final Paint edgeShadowV = new Paint();
    private final Paint flapShade = new Paint();
    private final Paint footer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ribbon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ribbonPath = new Path();

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
        // Unit-length gradients; each is stretched with canvas.scale() when drawn.
        foldShadow.setShader(new LinearGradient(0, 0, -1, 0, 0x66000000, 0x00000000, Shader.TileMode.CLAMP));
        edgeShadowH.setShader(new LinearGradient(0, 0, 1, 0, 0x48000000, 0x00000000, Shader.TileMode.CLAMP));
        edgeShadowV.setShader(new LinearGradient(0, 0, 0, 1, 0x48000000, 0x00000000, Shader.TileMode.CLAMP));
        flapShade.setShader(new LinearGradient(0, 0, 1, 0,
                new int[]{0x5A000000, 0x14000000, 0x00000000, 0x26000000},
                new float[]{0f, 0.28f, 0.72f, 1f}, Shader.TileMode.CLAMP));

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
    }

    void applyTheme(int theme, float warmth) {
        bg = Themes.tint(Themes.BG[theme], warmth);
        fg = Themes.tint(Themes.FG[theme], warmth);
        pagePaint.setColorFilter(Themes.filter(theme, warmth));
        bgPaint.setColor(bg);
        flapTint.setColor((Themes.blend(bg, fg, 0.10f) & 0x00FFFFFF) | 0xE2000000);
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
        startSettle(from, cy, -from, cy, !straight, true, animMode == ANIM_SLIDE ? 280 : 420);
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
        invalidate();
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
        c.drawColor(bg);
        if (layout == null) return;
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
        drawFooter(c, screen);
    }

    private void drawSlide(Canvas c, int top, int bottom) {
        float w = getWidth(), h = getHeight();
        float edge = (w + tx) / 2f;
        drawPage(c, bottom);
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

    private void drawCurl(Canvas c, int top, int bottom) {
        final float w = getWidth(), h = getHeight();
        final float dx = tx - w, dy = ty - cy;
        final float d = (float) Math.hypot(dx, dy);
        if (d < 0.5f) {
            drawPage(c, top);
            return;
        }
        // Crease-local frame: origin at the midpoint of C-T, +x pointing from the corner to T.
        final float mx = (w + tx) / 2f, my = (cy + ty) / 2f;
        final float ang = (float) Math.toDegrees(Math.atan2(dy, dx));
        final float big = (w + h) * 2f;
        final Bitmap sheet = cache.get(top);

        drawPage(c, bottom);

        // Shadow the lifted sheet casts on the page underneath, along the crease.
        c.save();
        c.translate(mx, my);
        c.rotate(ang);
        c.scale(clamp(d * 0.20f, 8 * dp, 56 * dp), 1f);
        c.drawRect(-1f, -big, 0f, big, foldShadow);
        c.restore();

        c.save();
        // The part of the sheet still lying flat is the far side of the crease.
        c.translate(mx, my);
        c.rotate(ang);
        c.clipRect(0f, -big, big, big);
        c.rotate(-ang);
        c.translate(-mx, -my);
        drawPage(c, top);

        // Mirror across the crease: from here on, page coordinates land on the folded-over flap.
        c.translate(mx, my);
        c.rotate(ang);
        c.scale(-1f, 1f);
        c.rotate(-ang);
        c.translate(-mx, -my);

        // Soft shadow just outside the flap's free edges.
        float es = clamp(d * 0.10f, 4 * dp, 28 * dp);
        c.save();
        c.translate(w, 0);
        c.scale(es, 1f);
        c.drawRect(0f, -big, 1f, big, edgeShadowH);
        c.restore();
        if (!straight) {
            c.save();
            if (cy > h / 2f) {
                c.translate(0, h);
                c.scale(1f, es);
            } else {
                c.scale(1f, -es);
            }
            c.drawRect(-big, 0f, big, 1f, edgeShadowV);
            c.restore();
        }

        // The back of the sheet: the page's own content mirrored, washed out to paper colour.
        c.clipRect(0f, 0f, w, h);
        if (sheet != null) c.drawBitmap(sheet, 0, 0, pagePaint);
        c.drawRect(0f, 0f, w, h, flapTint);

        // Undo the mirror to get back to the crease frame, then shade the flap like a curve.
        c.translate(mx, my);
        c.rotate(ang);
        c.scale(-1f, 1f);
        c.scale(Math.max(1f, d / 2f), 1f);
        c.drawRect(0f, -big, 1f, big, flapShade);
        c.restore();
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
        if (book == null || book.samples < 4) return null;
        int remaining = pageCount - 1 - lastPage;
        if (remaining <= 0) return null;
        int min = Math.round(remaining * book.secPerPage / 60f);
        if (min < 1) return "under a minute left";
        if (min < 60) return min + " min left";
        return (min / 60) + " h " + (min % 60) + " min left";
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
        anim.setInterpolator(arc ? new AccelerateDecelerateInterpolator() : new DecelerateInterpolator(1.5f));
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
            dragging = multi = tapUsed = false;
            swipeDir = 0;
            if (vt == null) vt = VelocityTracker.obtain();
            else vt.clear();
        }
        scaler.onTouchEvent(e);
        taps.onTouchEvent(e);
        if (vt != null) vt.addMovement(e);

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
