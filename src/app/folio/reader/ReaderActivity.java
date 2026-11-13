package app.folio.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;

public class ReaderActivity extends Activity implements ReaderView.Listener {

    static final String EXTRA_ID = "id";

    private static final int PANEL = 0xFF1E1E20, TEXT = 0xFFECECEC, DIM = 0xFFA0A0A0, ACCENT = 0xFFE8A33D;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** The screen is held on while reading, but only this long after the last touch or page turn. */
    private static final long AWAKE_MS = 10 * 60 * 1000L;
    private static final long SAVE_DELAY_MS = 2000L;

    private Library lib;
    private Library.Book book;
    private PdfEngine engine;
    private ReaderView view;
    private SharedPreferences prefs;
    private float dp;

    private FrameLayout root;
    private View topBar, bottomPanel;
    private TextView titleView, pageLabel, starBtn;
    private SeekBar pageSeek, brightSeek;
    private final TextView[] swatches = new TextView[Themes.COUNT];
    private final TextView[] animBtns = new TextView[3];
    private boolean menuVisible, ready;
    private long lastTurnAt;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean awake, unsaved;
    private final Runnable letScreenSleep = () -> {
        awake = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    };
    /**
     * Your place is written once you have settled on a page, so a crash or a dead battery loses
     * nothing. It waits for a quiet moment: never during a page turn, where it could cost a frame.
     */
    private final Runnable saveProgress = new Runnable() {
        @Override
        public void run() {
            if (!unsaved) return;
            if (view.isTurning()) {
                handler.postDelayed(this, SAVE_DELAY_MS);
                return;
            }
            unsaved = false;
            new Thread(() -> lib.save()).start();
        }
    };
    private final BroadcastReceiver minuteTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateStatus();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        lib = Library.get(this);
        prefs = P.get(this);
        dp = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        view = new ReaderView(this);
        view.listener = this;
        view.setAnimMode(prefs.getInt(P.ANIM, ReaderView.ANIM_CURL));
        root.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        buildMenu();
        setContentView(root);
        applyTheme();
        applyBrightness();
        immersive();

        String id = getIntent().getStringExtra(EXTRA_ID);
        Library.Book b = id == null ? null : lib.find(id);
        if (b != null) {
            open(b);
        } else if (getIntent().getData() != null) {
            importAndOpen(getIntent().getData());
        } else {
            finish();
        }
    }

    /** "Open with Folio" from a file manager: add the PDF to the shelf, then read it. */
    private void importAndOpen(final Uri uri) {
        new Thread(() -> {
            Library.Book b = null;
            String err = null;
            try {
                b = lib.importUri(uri);
            } catch (Throwable t) {
                err = t.getMessage();
            }
            final Library.Book fb = b;
            final String fe = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fb == null) {
                    toast(fe == null ? "This PDF can't be opened" : fe);
                    finish();
                } else {
                    open(fb);
                }
            });
        }).start();
    }

    private void open(Library.Book b) {
        book = b;
        titleView.setText(b.title);
        engine = new PdfEngine();
        engine.open(lib.file(b), (pages, err) -> {
            if (isFinishing() || isDestroyed()) return;
            if (err != null || pages < 1) {
                toast(err == null ? "This PDF can't be opened" : err);
                finish();
                return;
            }
            book.pages = pages;
            if (prefs.getBoolean(P.CROP, true) && !book.cropDone) {
                engine.computeCrop((even, odd, cover) -> {
                    storeCrop(even, odd, cover);
                    start();
                });
            } else {
                start();
            }
        });
    }

    private void storeCrop(float[] even, float[] odd, boolean cover) {
        book.cropEven = even;
        book.cropOdd = odd;
        book.cropCover = cover;
        book.cropDone = true;
        lib.save();
    }

    private void start() {
        if (isFinishing() || isDestroyed()) return;
        ready = true;
        book.opened = System.currentTimeMillis();
        pageSeek.setMax(Math.max(1, book.pages - 1));
        view.setBook(engine, book, book.pages, prefs.getBoolean(P.CROP, true), prefs.getBoolean(P.DUAL, true));
        lastTurnAt = SystemClock.elapsedRealtime();
        updateStatus();
        keepAwake();
        if (!prefs.getBoolean(P.HINT, false)) {
            prefs.edit().putBoolean(P.HINT, true).apply();
            Toast.makeText(this, "Tap or swipe the edges to turn pages  ·  tap the middle for settings",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(minuteTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        lastTurnAt = SystemClock.elapsedRealtime();
        updateStatus();
        keepAwake();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(minuteTick);
        handler.removeCallbacks(letScreenSleep);
        handler.removeCallbacks(saveProgress);
        awake = false;
        unsaved = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (ready) {
            book.page = view.getPage();
            book.opened = System.currentTimeMillis();
            lib.save();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        view.restorePages();
    }

    @Override
    protected void onStop() {
        super.onStop();
        view.releasePages();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        view.detach();
        if (engine != null) engine.close();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void keepAwake() {
        // Setting a window flag forces a window re-layout, so only do it when it is not already set.
        if (!awake) {
            awake = true;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        handler.removeCallbacks(letScreenSleep);
        handler.postDelayed(letScreenSleep, AWAKE_MS);
    }

    @Override
    public void onBackPressed() {
        if (menuVisible) showMenu(false);
        else super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        int dir = keyDir(code);
        if (dir != 0 && ready) {
            keepAwake();
            view.turn(dir, true);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        // Swallow the release too, or the volume keys would still beep.
        return (keyDir(code) != 0 && ready) || super.onKeyUp(code, e);
    }

    private int keyDir(int code) {
        if (menuVisible) return 0;
        boolean vol = prefs.getBoolean(P.VOLKEYS, true);
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return vol ? 1 : 0;
            case KeyEvent.KEYCODE_VOLUME_UP:
                return vol ? -1 : 0;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_SPACE:
                return 1;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_PAGE_UP:
                return -1;
            default:
                return 0;
        }
    }

    // ---------------------------------------------------------------- reader callbacks

    @Override
    public void onCenterTap() {
        if (ready) showMenu(!menuVisible);
    }

    @Override
    public void onPageChanged(int page, int dir) {
        long now = SystemClock.elapsedRealtime();
        if (dir == 1) {
            // Only plausible reading times feed the estimate: not skimming, not a coffee break.
            float sec = (now - lastTurnAt) / 1000f;
            if (sec >= 8f && sec <= 600f) {
                float per = sec / view.pagesOnScreen();
                book.secPerPage = book.samples == 0 ? per : book.secPerPage * 0.85f + per * 0.15f;
                book.samples++;
            }
        }
        lastTurnAt = now;
        book.page = page;
        unsaved = true;
        handler.removeCallbacks(saveProgress);
        handler.postDelayed(saveProgress, SAVE_DELAY_MS);
        keepAwake();
        if (menuVisible) refreshMenu();
    }

    @Override
    public void onEdge(int dir) {
        toast(dir > 0 ? "End of the book" : "This is the first page");
    }

    @Override
    public void onUserActivity() {
        keepAwake();
        if (unsaved) {
            // Still flipping: push the save back until the reader has stopped touching the screen.
            handler.removeCallbacks(saveProgress);
            handler.postDelayed(saveProgress, SAVE_DELAY_MS);
        }
    }

    // ---------------------------------------------------------------- settings

    private void applyTheme() {
        int t = prefs.getInt(P.THEME, 1);
        float warmth = prefs.getFloat(P.WARMTH, 0f);
        view.applyTheme(t, warmth);
        // The reader view paints every pixel itself; a window background would only be drawn
        // underneath it on every frame.
        getWindow().setBackgroundDrawable(null);
        for (int i = 0; i < swatches.length; i++) styleSwatch(swatches[i], i, i == t);
    }

    private void applyBrightness() {
        float b = prefs.getFloat(P.BRIGHT, -1f);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b < 0f ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.01f, b);
        getWindow().setAttributes(lp);
    }

    private void setCrop(boolean on) {
        prefs.edit().putBoolean(P.CROP, on).apply();
        if (!ready) return;
        if (on && !book.cropDone) {
            engine.computeCrop((even, odd, cover) -> {
                storeCrop(even, odd, cover);
                view.setCrop(true);
            });
        } else {
            view.setCrop(on);
        }
    }

    private void updateStatus() {
        boolean show = prefs.getBoolean(P.CLOCK, true);
        String s = "";
        if (show) {
            s = DateFormat.getTimeFormat(this).format(new Date());
            Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) {
                int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) s += "   ·   battery " + (level * 100 / scale) + "%";
            }
        }
        view.setStatus(s, show);
    }

    // ---------------------------------------------------------------- menu

    private void showMenu(boolean show) {
        menuVisible = show;
        view.menuOpen = show;
        if (show) refreshMenu();
        topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        bottomPanel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void refreshMenu() {
        int page = view.getPage();
        pageSeek.setProgress(page);
        pageLabel.setText(pageText(page));
        starBtn.setText(view.isCurrentBookmarked() ? "★" : "☆");
        starBtn.setTextColor(view.isCurrentBookmarked() ? ACCENT : TEXT);
    }

    private String pageText(int page) {
        return "Page " + (page + 1) + " of " + book.pages + "   ·   " + ((page + 1) * 100 / book.pages) + "%";
    }

    private void buildMenu() {
        Context mc = new ContextThemeWrapper(this, android.R.style.Theme_Material);
        // Sized to the longest row label at the user's font scale, so labels never wrap.
        final int labelW = (int) Math.ceil(label(mc, "", 14, DIM).getPaint().measureText("Brightness")) + d(16);

        LinearLayout top = new LinearLayout(mc);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(PANEL);
        top.setPadding(d(6), d(4), d(6), d(4));
        top.setClickable(true);
        top.addView(barButton(mc, "‹  Library", v -> finish()));
        titleView = new TextView(mc);
        titleView.setTextColor(TEXT);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER);
        top.addView(titleView, new LinearLayout.LayoutParams(0, WRAP, 1f));
        top.addView(barButton(mc, "Go to page", v -> goToDialog()));
        top.addView(barButton(mc, "Bookmarks", v -> bookmarksDialog()));
        starBtn = barButton(mc, "☆", v -> toggleBookmark());
        starBtn.setTextSize(24);
        top.addView(starBtn);
        root.addView(top, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP));
        topBar = top;

        LinearLayout bot = new LinearLayout(mc);
        bot.setOrientation(LinearLayout.VERTICAL);
        bot.setBackgroundColor(PANEL);
        bot.setPadding(d(22), d(14), d(22), d(16));
        bot.setClickable(true);

        pageLabel = label(mc, "", 14, TEXT);
        pageLabel.setGravity(Gravity.CENTER);
        bot.addView(pageLabel, new LinearLayout.LayoutParams(MATCH, WRAP));
        pageSeek = seek(mc);
        pageSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && ready) pageLabel.setText(pageText(Math.min(p, book.pages - 1)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (ready) view.goToPage(s.getProgress());
            }
        });
        bot.addView(pageSeek, rowParams(6));

        LinearLayout themes = row(mc);
        themes.setGravity(Gravity.CENTER);
        for (int i = 0; i < Themes.COUNT; i++) {
            final int t = i;
            TextView sw = new TextView(mc);
            sw.setText("Aa");
            sw.setTextSize(16);
            sw.setTypeface(Typeface.SERIF);
            sw.setGravity(Gravity.CENTER);
            sw.setContentDescription(Themes.NAMES[i] + " theme");
            sw.setOnClickListener(v -> {
                prefs.edit().putInt(P.THEME, t).apply();
                applyTheme();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(46), d(46));
            lp.setMargins(d(9), 0, d(9), 0);
            themes.addView(sw, lp);
            swatches[i] = sw;
        }
        bot.addView(themes, rowParams(12));

        LinearLayout turn = row(mc);
        turn.addView(label(mc, "Page turn", 14, DIM), new LinearLayout.LayoutParams(labelW, WRAP));
        String[] names = {"Curl", "Slide", "None"};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            TextView b = label(mc, names[i], 14, TEXT);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, d(8), 0, d(8));
            b.setOnClickListener(v -> {
                prefs.edit().putInt(P.ANIM, mode).apply();
                view.setAnimMode(mode);
                styleAnimButtons();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            lp.setMargins(d(4), 0, d(4), 0);
            turn.addView(b, lp);
            animBtns[i] = b;
        }
        styleAnimButtons();
        bot.addView(turn, rowParams(14));

        LinearLayout bright = row(mc);
        bright.addView(label(mc, "Brightness", 14, DIM), new LinearLayout.LayoutParams(labelW, WRAP));
        brightSeek = seek(mc);
        brightSeek.setMax(100);
        float saved = prefs.getFloat(P.BRIGHT, -1f);
        brightSeek.setProgress(saved < 0f ? 50 : Math.round(saved * 100));
        brightSeek.setEnabled(saved >= 0f);
        brightSeek.setAlpha(saved >= 0f ? 1f : 0.35f);
        final CheckBox auto = check(mc, "System", saved < 0f);
        brightSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                prefs.edit().putFloat(P.BRIGHT, p / 100f).apply();
                applyBrightness();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        auto.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putFloat(P.BRIGHT, on ? -1f : brightSeek.getProgress() / 100f).apply();
            brightSeek.setEnabled(!on);
            brightSeek.setAlpha(on ? 0.35f : 1f);
            applyBrightness();
        });
        bright.addView(brightSeek, new LinearLayout.LayoutParams(0, WRAP, 1f));
        bright.addView(auto);
        bot.addView(bright, rowParams(10));

        LinearLayout warm = row(mc);
        warm.addView(label(mc, "Warmth", 14, DIM), new LinearLayout.LayoutParams(labelW, WRAP));
        SeekBar warmSeek = seek(mc);
        warmSeek.setMax(100);
        warmSeek.setProgress(Math.round(prefs.getFloat(P.WARMTH, 0f) * 100));
        warmSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                prefs.edit().putFloat(P.WARMTH, p / 100f).apply();
                applyTheme();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        warm.addView(warmSeek, new LinearLayout.LayoutParams(0, WRAP, 1f));
        bot.addView(warm, rowParams(6));

        LinearLayout checksA = row(mc), checksB = row(mc);
        CheckBox crop = check(mc, "Crop margins", prefs.getBoolean(P.CROP, true));
        crop.setOnCheckedChangeListener((v, on) -> setCrop(on));
        CheckBox dual = check(mc, "Two pages in landscape", prefs.getBoolean(P.DUAL, true));
        dual.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(P.DUAL, on).apply();
            if (ready) view.setDual(on);
        });
        CheckBox vol = check(mc, "Volume keys turn pages", prefs.getBoolean(P.VOLKEYS, true));
        vol.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(P.VOLKEYS, on).apply());
        CheckBox clock = check(mc, "Clock and battery", prefs.getBoolean(P.CLOCK, true));
        clock.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(P.CLOCK, on).apply();
            updateStatus();
        });
        checksA.addView(crop, new LinearLayout.LayoutParams(0, WRAP, 1f));
        checksA.addView(dual, new LinearLayout.LayoutParams(0, WRAP, 1f));
        checksB.addView(vol, new LinearLayout.LayoutParams(0, WRAP, 1f));
        checksB.addView(clock, new LinearLayout.LayoutParams(0, WRAP, 1f));
        bot.addView(checksA, rowParams(10));
        bot.addView(checksB, rowParams(2));

        root.addView(bot, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
        bottomPanel = bot;
        top.setVisibility(View.GONE);
        bot.setVisibility(View.GONE);
    }

    private void styleSwatch(TextView sw, int theme, boolean selected) {
        if (sw == null) return;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Themes.BG[theme]);
        g.setStroke(d(selected ? 3 : 1), selected ? ACCENT : 0xFF6A6A6A);
        sw.setBackground(g);
        sw.setTextColor(Themes.FG[theme]);
    }

    private void styleAnimButtons() {
        int mode = prefs.getInt(P.ANIM, ReaderView.ANIM_CURL);
        for (int i = 0; i < animBtns.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(d(18));
            if (i == mode) g.setColor(ACCENT);
            else g.setStroke(d(1), 0xFF6A6A6A);
            animBtns[i].setBackground(g);
            animBtns[i].setTextColor(i == mode ? 0xFF1B1B1D : TEXT);
        }
    }

    private void toggleBookmark() {
        if (!ready) return;
        Integer page = view.getPage();
        // On a two-page spread either page may carry the bookmark.
        boolean removed = false;
        for (int i = 0; i < view.pagesOnScreen(); i++) removed |= book.bookmarks.remove((Integer) (page + i));
        if (!removed) book.bookmarks.add(page);
        lib.save();
        view.invalidate();
        refreshMenu();
    }

    private void bookmarksDialog() {
        if (!ready) return;
        if (book.bookmarks.isEmpty()) {
            toast("No bookmarks yet. Tap ☆ to mark this page.");
            return;
        }
        final ArrayList<Integer> pages = new ArrayList<>(book.bookmarks);
        Collections.sort(pages);
        String[] items = new String[pages.size()];
        for (int i = 0; i < items.length; i++) items[i] = "Page " + (pages.get(i) + 1);
        present(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Bookmarks")
                .setItems(items, (dlg, which) -> {
                    view.goToPage(pages.get(which));
                    showMenu(false);
                })
                .setNegativeButton("Close", null)
                .create(), null);
    }

    private void goToDialog() {
        if (!ready) return;
        final EditText input = new EditText(new ContextThemeWrapper(this, android.R.style.Theme_Material_Dialog_Alert));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 – " + book.pages);
        input.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(d(22), d(8), d(22), 0);
        wrap.addView(input);
        present(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Go to page")
                .setView(wrap)
                .setPositiveButton("Go", (dlg, which) -> {
                    try {
                        view.goToPage(Integer.parseInt(input.getText().toString().trim()) - 1);
                        showMenu(false);
                    } catch (NumberFormatException e) {
                        toast("Enter a page number");
                    }
                })
                .setNegativeButton("Cancel", null)
                .create(), input);
    }

    /** Shows a dialog in the app's colours; with a text field, the keyboard comes up with it. */
    private void present(AlertDialog dialog, View field) {
        if (field != null && dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        for (int which : new int[]{DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE}) {
            if (dialog.getButton(which) != null) dialog.getButton(which).setTextColor(ACCENT);
        }
        if (field != null) field.requestFocus();
    }

    // ---------------------------------------------------------------- small view helpers

    private TextView barButton(Context c, String text, View.OnClickListener l) {
        TextView b = label(c, text, 15, TEXT);
        b.setPadding(d(14), d(10), d(14), d(10));
        b.setOnClickListener(l);
        return b;
    }

    private TextView label(Context c, String text, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private SeekBar seek(Context c) {
        SeekBar s = new SeekBar(c);
        s.setProgressTintList(ColorStateList.valueOf(ACCENT));
        s.setThumbTintList(ColorStateList.valueOf(ACCENT));
        return s;
    }

    private CheckBox check(Context c, String text, boolean on) {
        CheckBox cb = new CheckBox(c);
        cb.setButtonTintList(ColorStateList.valueOf(ACCENT));
        cb.setText(text);
        cb.setTextColor(TEXT);
        cb.setTextSize(14);
        cb.setChecked(on);
        return cb;
    }

    private LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams rowParams(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = d(topDp);
        return lp;
    }

    private int d(float v) {
        return Math.round(v * dp);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
