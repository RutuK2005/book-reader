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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.view.animation.DecelerateInterpolator;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
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
    private PageSound pageSound;
    private int shownTheme = -1;

    // Reading time, credited to today's total and to the book.
    private long lastCredit, creditMs;

    // Hands-free reading and the bedtime timer. Both last only for this reading session.
    private static final int[] AUTO_CHOICES = {0, 15, 30, 45, 60, 90, 120};
    private static final int[] SLEEP_CHOICES = {0, 15, 30, 45, 60};
    private int autoSec, sleepMin;
    private long sleepAt;
    private View sleepCover;
    private TextView autoBtn, sleepBtn, noteBtn, rulerBtn, markBtn, dictBtn;
    private View markBar;

    // Preview of the page the slider is on.
    private LinearLayout thumbBox;
    private ImageView thumbImage;
    private TextView thumbText;
    private int thumbSeq;

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
            flushReadingTime();
            new Thread(() -> lib.save()).start();
        }
    };
    private final BroadcastReceiver minuteTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateStatus();
            // The night theme switches itself on and off at its hours.
            if (P.theme(prefs) != shownTheme) applyTheme();
        }
    };
    /** Turns the page every autoSec seconds until the reader touches the screen or the book ends. */
    private final Runnable autoTurn = new Runnable() {
        @Override
        public void run() {
            if (autoSec <= 0 || !ready) return;
            if (!menuVisible && sleepCover == null && !view.marking && hasWindowFocus()) view.turn(1, true);
            handler.postDelayed(this, autoSec * 1000L);
        }
    };
    private final Runnable sleepNow = this::showSleepCover;

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
        view.rulerOn = prefs.getBoolean(P.RULER, false);
        view.rulerBand = prefs.getFloat(P.RULER_H, 96f);
        applySaver();

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

    /** "Open with MyBookSpace" from a file manager: add the PDF to the shelf, then read it. */
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
        lastTurnAt = lastCredit = SystemClock.elapsedRealtime();
        if (P.theme(prefs) != shownTheme) applyTheme();
        updateStatus();
        keepAwake();
        if (autoSec > 0) handler.postDelayed(autoTurn, autoSec * 1000L);
        if (sleepAt > 0) handler.postDelayed(sleepNow, Math.max(0, sleepAt - SystemClock.elapsedRealtime()));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(minuteTick);
        handler.removeCallbacks(letScreenSleep);
        handler.removeCallbacks(saveProgress);
        handler.removeCallbacks(autoTurn);
        handler.removeCallbacks(sleepNow);
        awake = false;
        unsaved = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        credit();
        lastCredit = 0;
        if (ready) {
            flushReadingTime();
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
        if (pageSound != null) pageSound.release();
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

    /** Counts the time since the last sign of life as reading, up to three minutes of it. */
    private void credit() {
        long now = SystemClock.elapsedRealtime();
        if (lastCredit > 0) creditMs += Math.min(now - lastCredit, 180_000L);
        lastCredit = now;
    }

    private void flushReadingTime() {
        if (book == null || creditMs < 1000) return;
        lib.addReading(book, (int) (creditMs / 1000));
        creditMs %= 1000;
    }

    private void keepAwake() {
        if (sleepCover != null || saver()) return;
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
        credit();
        book.page = page;
        if (dir == 1 && !book.celebrated && page + view.pagesOnScreen() >= book.pages) {
            book.celebrated = true;
            celebrate();
        }
        unsaved = true;
        handler.removeCallbacks(saveProgress);
        handler.postDelayed(saveProgress, SAVE_DELAY_MS);
        keepAwake();
        if (menuVisible) refreshMenu();
    }

    @Override
    public void onMarksChanged() {
        new Thread(() -> lib.save()).start();
    }

    @Override
    public void onTurn() {
        if (pageSound != null) pageSound.play();
    }

    @Override
    public void onEdge(int dir) {
        if (dir > 0 && autoSec > 0) setAuto(0);
        toast(dir > 0 ? "End of the book" : "This is the first page");
    }

    @Override
    public void onUserActivity() {
        keepAwake();
        credit();
        if (autoSec > 0) {
            // A touch means the reader is not ready for the next page: start the wait again.
            handler.removeCallbacks(autoTurn);
            handler.postDelayed(autoTurn, autoSec * 1000L);
        }
        if (unsaved) {
            // Still flipping: push the save back until the reader has stopped touching the screen.
            handler.removeCallbacks(saveProgress);
            handler.postDelayed(saveProgress, SAVE_DELAY_MS);
        }
    }

    // ---------------------------------------------------------------- settings

    private void applyTheme() {
        int chosen = prefs.getInt(P.THEME, 1);
        int t = P.theme(prefs);
        shownTheme = t;
        float warmth = prefs.getFloat(P.WARMTH, 0f);
        view.applyTheme(t, warmth);
        // The reader view paints every pixel itself; a window background would only be drawn
        // underneath it on every frame.
        getWindow().setBackgroundDrawable(null);
        for (int i = 0; i < swatches.length; i++) styleSwatch(swatches[i], i, i == chosen);
    }

    private void applyBrightness() {
        float b = prefs.getFloat(P.BRIGHT, -1f);
        if (saver()) b = b < 0f ? 0.15f : Math.min(b, 0.15f);
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
        boolean show = prefs.getBoolean(P.CLOCK, true) && !saver();
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
        if (autoSec > 0) s = "auto-turn " + autoSec + " s" + (s.isEmpty() ? "" : "   ·   " + s);
        view.setStatus(s, show || autoSec > 0);
    }

    // ---------------------------------------------------------------- hands-free and bedtime

    private void setAuto(int seconds) {
        autoSec = seconds;
        handler.removeCallbacks(autoTurn);
        if (seconds > 0) handler.postDelayed(autoTurn, seconds * 1000L);
        updateStatus();
        refreshPills();
    }

    private void setSleep(int minutes) {
        sleepMin = minutes;
        handler.removeCallbacks(sleepNow);
        sleepAt = minutes > 0 ? SystemClock.elapsedRealtime() + minutes * 60_000L : 0;
        if (minutes > 0) handler.postDelayed(sleepNow, minutes * 60_000L);
        refreshPills();
    }

    /** Bedtime: dim right down, stop holding the screen on, and wait for a tap to carry on. */
    private void showSleepCover() {
        if (sleepCover != null || isFinishing()) return;
        setSleep(0);
        setAuto(0);
        showMenu(false);
        TextView cover = label(this, "Sleep timer ended\n\nTap to keep reading", 18, 0xFFBBBBBB);
        cover.setGravity(Gravity.CENTER);
        cover.setBackgroundColor(0xF2000000);
        cover.setOnClickListener(v -> {
            root.removeView(sleepCover);
            sleepCover = null;
            applyBrightness();
            keepAwake();
        });
        sleepCover = cover;
        root.addView(cover, new FrameLayout.LayoutParams(MATCH, MATCH));
        handler.removeCallbacks(letScreenSleep);
        letScreenSleep.run();
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.01f;
        getWindow().setAttributes(lp);
    }

    private void refreshPills() {
        if (autoBtn == null) return;
        autoBtn.setText(autoSec > 0 ? "Auto-turn: " + autoSec + " s" : "Auto-turn: off");
        stylePill(autoBtn, autoSec > 0);
        long left = sleepAt > 0 ? Math.max(1, (sleepAt - SystemClock.elapsedRealtime() + 59_000L) / 60_000L) : 0;
        sleepBtn.setText(sleepAt > 0 ? "Sleep in " + left + " min" : "Sleep timer: off");
        stylePill(sleepBtn, sleepAt > 0);
        boolean noted = ready && noteKey() >= 0;
        noteBtn.setText(noted ? "Edit note" : "Add note");
        stylePill(noteBtn, noted);
        if (rulerBtn == null) return;
        rulerBtn.setText(view.rulerOn ? "Ruler: on" : "Reading ruler");
        stylePill(rulerBtn, view.rulerOn);
        markBtn.setText(view.marking ? "Highlighting: on" : "Highlight");
        stylePill(markBtn, view.marking);
        dictBtn.setText("Dictionary");
        stylePill(dictBtn, false);
    }

    private void stylePill(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(d(14));
        g.setColor(on ? ACCENT : 0xFF2F2F33);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), g, null));
        t.setTextColor(on ? 0xFF1B1B1D : TEXT);
    }

    private static int next(int[] choices, int current) {
        for (int i = 0; i < choices.length; i++) if (choices[i] == current) return choices[(i + 1) % choices.length];
        return choices[0];
    }

    private void celebrate() {
        if (saver()) {
            toast("You finished “" + book.title + "”");
            return;
        }
        ConfettiView confetti = new ConfettiView(this);
        root.addView(confetti, new FrameLayout.LayoutParams(MATCH, MATCH));
        confetti.start();
        Toast.makeText(this, "You finished “" + book.title + "”", Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- battery saver and dictionary

    private boolean saver() {
        return prefs.getBoolean(P.SAVER, false);
    }

    /**
     * Extreme battery saver: pages change instantly with no animation or sound, the screen is
     * held dim, and the tablet's own screen timeout applies instead of the reader holding it on.
     * The backlight is what drains an LCD tablet, so dimness and an early screen-off matter most.
     */
    private void applySaver() {
        boolean on = saver();
        view.setAnimMode(on ? ReaderView.ANIM_NONE : prefs.getInt(P.ANIM, ReaderView.ANIM_CURL));
        if (pageSound != null) pageSound.release();
        pageSound = !on && prefs.getBoolean(P.SOUND, true) ? new PageSound() : null;
        if (on) {
            if (autoSec > 0) setAuto(0);
            handler.removeCallbacks(letScreenSleep);
            letScreenSleep.run();
        } else {
            keepAwake();
        }
        applyBrightness();
        updateStatus();
    }

    private void dictionaryDialog() {
        final EditText input = new EditText(new ContextThemeWrapper(this, android.R.style.Theme_Material_Dialog_Alert));
        input.setSingleLine(true);
        input.setHint("Type a word");
        input.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(d(22), d(8), d(22), 0);
        wrap.addView(input);
        present(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Dictionary")
                .setView(wrap)
                .setPositiveButton("Look up", (dlg, which) -> lookUp(input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .create(), input);
    }

    /** Fetches a definition over the network; nothing is requested until the reader asks. */
    private void lookUp(final String word) {
        if (word.isEmpty()) return;
        toast("Looking up “" + word + "”…");
        new Thread(() -> {
            String result;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                        "https://api.dictionaryapi.dev/api/v2/entries/en/" + java.net.URLEncoder.encode(word, "UTF-8")).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) {
                    result = "No entry found for “" + word + "”.";
                } else {
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    try (java.io.InputStream in = c.getInputStream()) {
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                    }
                    org.json.JSONArray meanings = new org.json.JSONArray(buf.toString("UTF-8")).getJSONObject(0).getJSONArray("meanings");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(3, meanings.length()); i++) {
                        org.json.JSONObject m = meanings.getJSONObject(i);
                        org.json.JSONArray defs = m.getJSONArray("definitions");
                        if (sb.length() > 0) sb.append("\n\n");
                        sb.append(m.optString("partOfSpeech"));
                        for (int k = 0; k < Math.min(2, defs.length()); k++) {
                            sb.append("\n").append(k + 1).append(". ").append(defs.getJSONObject(k).optString("definition"));
                        }
                    }
                    result = sb.length() == 0 ? "No entry found for “" + word + "”." : sb.toString();
                }
            } catch (Exception e) {
                result = "Couldn't reach the dictionary. Check the Wi-Fi connection.";
            }
            final String text = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                present(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(word)
                        .setMessage(text)
                        .setPositiveButton("Close", null)
                        .create(), null);
            });
        }).start();
    }

    // ---------------------------------------------------------------- notes

    /** The page on screen that has a note, or -1. */
    private int noteKey() {
        int page = view.getPage();
        for (int i = 0; i < view.pagesOnScreen(); i++) if (book.notes.containsKey(page + i)) return page + i;
        return -1;
    }

    private void noteDialog() {
        if (!ready) return;
        final int existing = noteKey();
        final int page = existing >= 0 ? existing : view.getPage();
        final EditText input = new EditText(new ContextThemeWrapper(this, android.R.style.Theme_Material_Dialog_Alert));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP);
        input.setHint("A thought about this page");
        input.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        if (existing >= 0) input.setText(book.notes.get(existing));
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(d(22), d(8), d(22), 0);
        wrap.addView(input);
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Note on page " + (page + 1))
                .setView(wrap)
                .setPositiveButton("Save", (dlg, which) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) book.notes.remove(page);
                    else book.notes.put(page, text);
                    notesChanged();
                })
                .setNegativeButton("Cancel", null);
        if (existing >= 0) {
            b.setNeutralButton("Delete", (dlg, which) -> {
                book.notes.remove(page);
                notesChanged();
            });
        }
        present(b.create(), input);
    }

    private void notesChanged() {
        lib.save();
        view.invalidate();
        refreshPills();
    }

    // ---------------------------------------------------------------- menu

    private void showMenu(boolean show) {
        menuVisible = show;
        view.menuOpen = show;
        if (show) refreshMenu();
        slide(topBar, show, -1);
        slide(bottomPanel, show, 1);
        markBar.setVisibility(view.marking && !show ? View.VISIBLE : View.GONE);
    }

    private void refreshMenu() {
        int page = view.getPage();
        pageSeek.setProgress(page);
        pageLabel.setText(pageText(page));
        starBtn.setText(view.isCurrentBookmarked() ? "★" : "☆");
        starBtn.setTextColor(view.isCurrentBookmarked() ? ACCENT : TEXT);
        refreshPills();
    }

    private String pageText(int page) {
        return "Page " + (page + 1) + " of " + book.pages + "   ·   " + ((page + 1) * 100 / book.pages) + "%";
    }

    private void buildMenu() {
        Context mc = new ContextThemeWrapper(this, android.R.style.Theme_Material);
        // Sized to the longest row label at the user's font scale, so labels never wrap.
        final int labelW = (int) Math.ceil(label(mc, "", 14, DIM).getPaint().measureText("Brightness")) + d(16);

        // ---- top bar: back, title, marks list, bookmark
        LinearLayout top = new LinearLayout(mc);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(PANEL);
        top.setPadding(d(4), d(2), d(8), d(2));
        top.setClickable(true);
        top.setElevation(d(6));
        top.addView(new Icon(mc, Icon.BACK, v -> finish()), new LinearLayout.LayoutParams(d(48), d(48)));
        titleView = new TextView(mc);
        titleView.setTextColor(TEXT);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setPadding(d(4), 0, d(8), 0);
        top.addView(titleView, new LinearLayout.LayoutParams(0, WRAP, 1f));
        top.addView(new Icon(mc, Icon.LIST, v -> bookmarksDialog()), new LinearLayout.LayoutParams(d(48), d(48)));
        starBtn = barButton(mc, "☆", v -> toggleBookmark());
        starBtn.setTextSize(24);
        top.addView(starBtn);
        root.addView(top, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP));
        topBar = top;

        // ---- bottom sheet
        LinearLayout bot = new LinearLayout(mc);
        bot.setOrientation(LinearLayout.VERTICAL);
        bot.setPadding(d(20), d(12), d(20), d(18));
        View handle = new View(mc);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xFF4C4C50);
        hb.setCornerRadius(d(2));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(d(36), d(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = d(12);
        bot.addView(handle, hlp);

        LinearLayout pageRow = row(mc);
        pageLabel = label(mc, "", 15, TEXT);
        pageLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pageRow.addView(pageLabel, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView go = pill(mc, v -> goToDialog());
        go.setText("Go to page");
        go.setPadding(d(16), d(7), d(16), d(7));
        stylePill(go, false);
        pageRow.addView(go, new LinearLayout.LayoutParams(WRAP, WRAP));
        bot.addView(pageRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        pageSeek = seek(mc);
        pageSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser || !ready) return;
                int page = Math.min(p, book.pages - 1);
                pageLabel.setText(pageText(page));
                showThumb(page);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                thumbBox.setVisibility(View.GONE);
                if (ready) view.goToPage(s.getProgress());
            }
        });
        bot.addView(pageSeek, rowParams(6));

        // ---- tabs: the settings are grouped so only a handful show at a time
        final String[] names = {"Display", "Layout", "Tools", "More"};
        final TextView[] tabs = new TextView[names.length];
        final LinearLayout[] panels = new LinearLayout[names.length];
        LinearLayout tabRow = row(mc);
        GradientDrawable tabBg = new GradientDrawable();
        tabBg.setColor(0xFF2B2B2E);
        tabBg.setCornerRadius(d(14));
        tabRow.setBackground(tabBg);
        tabRow.setPadding(d(3), d(3), d(3), d(3));
        FrameLayout content = new FrameLayout(mc);
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            tabs[i] = label(mc, names[i], 14, DIM);
            tabs[i].setGravity(Gravity.CENTER);
            tabs[i].setPadding(0, d(9), 0, d(9));
            tabs[i].setOnClickListener(v -> selectTab(tabs, panels, index));
            tabRow.addView(tabs[i], new LinearLayout.LayoutParams(0, WRAP, 1f));
            panels[i] = new LinearLayout(mc);
            panels[i].setOrientation(LinearLayout.VERTICAL);
            content.addView(panels[i], new FrameLayout.LayoutParams(MATCH, WRAP));
        }
        bot.addView(tabRow, rowParams(12));
        bot.addView(content, rowParams(6));
        LinearLayout display = panels[0], layoutTab = panels[1], tools = panels[2], more = panels[3];

        // ---- Display: theme, page turn, brightness, warmth
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
        display.addView(themes, rowParams(10));

        LinearLayout turn = row(mc);
        turn.addView(label(mc, "Page turn", 14, DIM), new LinearLayout.LayoutParams(labelW, WRAP));
        String[] modes = {"Curl", "Slide", "None"};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            TextView b = label(mc, modes[i], 14, TEXT);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, d(8), 0, d(8));
            b.setOnClickListener(v -> {
                prefs.edit().putInt(P.ANIM, mode).apply();
                if (!saver()) view.setAnimMode(mode);
                styleAnimButtons();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            lp.setMargins(d(4), 0, d(4), 0);
            turn.addView(b, lp);
            animBtns[i] = b;
        }
        styleAnimButtons();
        display.addView(turn, rowParams(16));

        LinearLayout bright = row(mc);
        bright.addView(label(mc, "Brightness", 14, DIM), new LinearLayout.LayoutParams(labelW, WRAP));
        brightSeek = seek(mc);
        brightSeek.setMax(100);
        float saved = prefs.getFloat(P.BRIGHT, -1f);
        brightSeek.setProgress(saved < 0f ? 50 : Math.round(saved * 100));
        brightSeek.setEnabled(saved >= 0f);
        brightSeek.setAlpha(saved >= 0f ? 1f : 0.35f);
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
        bright.addView(brightSeek, new LinearLayout.LayoutParams(0, WRAP, 1f));
        display.addView(bright, rowParams(14));
        Switch auto = toggle(mc, "Use the tablet's brightness", saved < 0f);
        auto.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putFloat(P.BRIGHT, on ? -1f : brightSeek.getProgress() / 100f).apply();
            brightSeek.setEnabled(!on);
            brightSeek.setAlpha(on ? 0.35f : 1f);
            applyBrightness();
        });
        display.addView(auto, rowParams(2));

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
        display.addView(warm, rowParams(8));

        // ---- Layout
        Switch crop = toggle(mc, "Crop margins", prefs.getBoolean(P.CROP, true));
        crop.setOnCheckedChangeListener((v, on) -> setCrop(on));
        Switch dual = toggle(mc, "Two pages in landscape", prefs.getBoolean(P.DUAL, true));
        dual.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(P.DUAL, on).apply();
            if (ready) view.setDual(on);
        });
        Switch vol = toggle(mc, "Volume keys turn pages", prefs.getBoolean(P.VOLKEYS, true));
        vol.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(P.VOLKEYS, on).apply());
        Switch clock = toggle(mc, "Clock and battery in the footer", prefs.getBoolean(P.CLOCK, true));
        clock.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(P.CLOCK, on).apply();
            updateStatus();
        });
        for (Switch s : new Switch[]{crop, dual, vol, clock}) layoutTab.addView(s, rowParams(4));

        // ---- Tools
        rulerBtn = pill(mc, v -> {
            view.rulerOn = !view.rulerOn;
            prefs.edit().putBoolean(P.RULER, view.rulerOn).apply();
            view.invalidate();
            refreshPills();
            if (view.rulerOn) toast("Drag up or down on the page to move the ruler");
        });
        markBtn = pill(mc, v -> {
            view.marking = !view.marking;
            refreshPills();
            markBar.setVisibility(View.GONE);
            if (view.marking) {
                showMenu(false);
                toast("Drag over a passage to highlight it. Tap a highlight to remove it.");
            }
        });
        noteBtn = pill(mc, v -> noteDialog());
        dictBtn = pill(mc, v -> dictionaryDialog());
        tools.addView(pair(mc, rulerBtn, markBtn), rowParams(10));
        tools.addView(pair(mc, noteBtn, dictBtn), rowParams(10));
        LinearLayout rulerRow = row(mc);
        rulerRow.addView(label(mc, "Ruler size", 14, DIM), new LinearLayout.LayoutParams(labelW, WRAP));
        SeekBar rulerSeek = seek(mc);
        rulerSeek.setMax(100);
        rulerSeek.setProgress(Math.round((view.rulerBand - 40f) / 3.2f));
        rulerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                view.rulerBand = 40f + p * 3.2f;
                prefs.edit().putFloat(P.RULER_H, view.rulerBand).apply();
                view.invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        rulerRow.addView(rulerSeek, new LinearLayout.LayoutParams(0, WRAP, 1f));
        tools.addView(rulerRow, rowParams(14));

        // ---- More
        autoBtn = pill(mc, v -> setAuto(next(AUTO_CHOICES, autoSec)));
        sleepBtn = pill(mc, v -> setSleep(next(SLEEP_CHOICES, sleepMin)));
        more.addView(pair(mc, autoBtn, sleepBtn), rowParams(10));
        Switch sound = toggle(mc, "Page-turn sound", prefs.getBoolean(P.SOUND, true));
        sound.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(P.SOUND, on).apply();
            if (pageSound != null) pageSound.release();
            pageSound = on && !saver() ? new PageSound() : null;
            if (pageSound != null) pageSound.play();
        });
        Switch night = toggle(mc, "Night theme from 9 pm to 6 am", prefs.getBoolean(P.AUTO_NIGHT, false));
        night.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(P.AUTO_NIGHT, on).apply();
            applyTheme();
        });
        Switch saverBox = toggle(mc, "Extreme battery saver", prefs.getBoolean(P.SAVER, false));
        saverBox.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(P.SAVER, on).apply();
            applySaver();
        });
        more.addView(sound, rowParams(10));
        more.addView(night, rowParams(4));
        more.addView(saverBox, rowParams(4));

        refreshPills();
        selectTab(tabs, panels, Math.min(names.length - 1, prefs.getInt("menu_tab", 0)));

        // The sheet scrolls if it is taller than a landscape screen at large font sizes, and
        // never takes more than three quarters of the height.
        ScrollView scroller = new ScrollView(mc) {
            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.76f);
                super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        GradientDrawable sheet = new GradientDrawable();
        sheet.setColor(PANEL);
        float r = d(22);
        sheet.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        scroller.setBackground(sheet);
        scroller.setClipToOutline(true);
        scroller.setElevation(d(12));
        scroller.setClickable(true);
        scroller.addView(bot, new FrameLayout.LayoutParams(MATCH, WRAP));
        root.addView(scroller, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
        bottomPanel = scroller;

        // Shown while highlighting: take back the last highlight, or finish.
        LinearLayout bar = new LinearLayout(mc);
        bar.setPadding(d(6), d(4), d(6), d(4));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(PANEL);
        barBg.setCornerRadius(d(26));
        bar.setBackground(barBg);
        bar.setElevation(d(8));
        bar.addView(barButton(mc, "Undo highlight", v -> {
            if (!view.undoMark()) toast("Nothing to undo. Tap a highlight to remove it.");
        }));
        TextView done = barButton(mc, "Done", v -> {
            view.marking = false;
            markBar.setVisibility(View.GONE);
            refreshPills();
        });
        done.setTextColor(ACCENT);
        bar.addView(done);
        bar.setVisibility(View.GONE);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        barLp.topMargin = d(14);
        root.addView(bar, barLp);
        markBar = bar;

        // Preview of the page under the slider, shown only while it is being dragged.
        thumbBox = new LinearLayout(mc);
        thumbBox.setOrientation(LinearLayout.VERTICAL);
        thumbBox.setGravity(Gravity.CENTER_HORIZONTAL);
        thumbBox.setPadding(d(10), d(10), d(10), d(8));
        GradientDrawable box = new GradientDrawable();
        box.setColor(PANEL);
        box.setCornerRadius(d(14));
        thumbBox.setBackground(box);
        thumbBox.setElevation(d(10));
        thumbImage = new ImageView(mc);
        thumbImage.setAdjustViewBounds(true);
        thumbImage.setBackgroundColor(0xFFFFFFFF);
        thumbBox.addView(thumbImage, new LinearLayout.LayoutParams(d(170), d(240)));
        thumbText = label(mc, "", 13, TEXT);
        thumbText.setPadding(0, d(6), 0, 0);
        thumbBox.addView(thumbText);
        thumbBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tlp.topMargin = d(90);
        root.addView(thumbBox, tlp);

        top.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.GONE);
    }

    private void selectTab(TextView[] tabs, View[] panels, int index) {
        prefs.edit().putInt("menu_tab", index).apply();
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == index;
            panels[i].setVisibility(on ? View.VISIBLE : View.GONE);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(d(11));
            g.setColor(on ? ACCENT : 0x00000000);
            tabs[i].setBackground(g);
            tabs[i].setTextColor(on ? 0xFF1B1B1D : DIM);
            tabs[i].setTypeface(Typeface.create(on ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        }
    }

    /** Two buttons side by side, sharing the width. */
    private LinearLayout pair(Context c, View a, View b) {
        LinearLayout l = row(c);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, WRAP, 1f);
        la.rightMargin = d(5);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, WRAP, 1f);
        lb.leftMargin = d(5);
        l.addView(a, la);
        l.addView(b, lb);
        return l;
    }

    private Switch toggle(Context c, String text, boolean on) {
        Switch s = new Switch(c);
        s.setText(text);
        s.setTextColor(TEXT);
        s.setTextSize(14);
        s.setChecked(on);
        s.setPadding(0, d(7), 0, d(7));
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, 0xFFC4C4C4}));
        s.setTrackTintList(new ColorStateList(states, new int[]{0x99E8A33D, 0x55FFFFFF}));
        return s;
    }

    /** Slides a bar in from its edge, or back out. dir is -1 for the top edge, +1 for the bottom. */
    private void slide(final View v, boolean show, int dir) {
        v.animate().cancel();
        if (show) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setVisibility(View.VISIBLE);
                v.setAlpha(0f);
                v.setTranslationY(dir * d(60));
            }
            v.animate().translationY(0f).alpha(1f).setDuration(240).setInterpolator(new DecelerateInterpolator(2f)).withEndAction(null);
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().translationY(dir * d(60)).alpha(0f).setDuration(180).withEndAction(() -> v.setVisibility(View.GONE));
        }
    }

    /** Small line icons for the top bar, drawn rather than shipped as images. */
    private static final class Icon extends View {
        static final int BACK = 0, LIST = 1;
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(Context c, int kind, OnClickListener l) {
            super(c);
            this.kind = kind;
            paint.setColor(TEXT);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            android.util.TypedValue tv = new android.util.TypedValue();
            c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
            setBackgroundResource(tv.resourceId);
            setContentDescription(kind == BACK ? "Back to library" : "Bookmarks and notes");
            setOnClickListener(l);
        }

        @Override
        protected void onDraw(Canvas c) {
            float u = Math.min(getWidth(), getHeight()) / 24f, cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setStrokeWidth(1.9f * u);
            path.rewind();
            if (kind == BACK) {
                path.moveTo(cx + 2.5f * u, cy - 5.5f * u);
                path.lineTo(cx - 3f * u, cy);
                path.lineTo(cx + 2.5f * u, cy + 5.5f * u);
            } else {
                for (int i = -1; i <= 1; i++) {
                    path.moveTo(cx - 2.5f * u, cy + i * 4.5f * u);
                    path.lineTo(cx + 6.5f * u, cy + i * 4.5f * u);
                    path.moveTo(cx - 6.5f * u, cy + i * 4.5f * u);
                    path.lineTo(cx - 6.4f * u, cy + i * 4.5f * u);
                }
            }
            c.drawPath(path, paint);
        }
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
            g.setCornerRadius(d(14));
            g.setColor(i == mode ? ACCENT : 0xFF2F2F33);
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
        if (book.bookmarks.isEmpty() && book.notes.isEmpty()) {
            toast("Nothing marked yet. Tap ☆ to bookmark this page, or add a note.");
            return;
        }
        // Bookmarks and notes share one list, in page order.
        java.util.TreeSet<Integer> marked = new java.util.TreeSet<>(book.bookmarks);
        marked.addAll(book.notes.keySet());
        final ArrayList<Integer> pages = new ArrayList<>(marked);
        String[] items = new String[pages.size()];
        for (int i = 0; i < items.length; i++) {
            int p = pages.get(i);
            String note = book.notes.get(p);
            if (note != null && note.length() > 60) note = note.substring(0, 60).trim() + "…";
            items[i] = "Page " + (p + 1) + (book.bookmarks.contains(p) ? "  ★" : "") + (note == null ? "" : "\n" + note);
        }
        present(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Bookmarks and notes")
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
        if (dialog.getWindow() != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF2A2A2D);
            bg.setCornerRadius(d(22));
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(bg, d(16)));
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

    private TextView pill(Context c, View.OnClickListener l) {
        TextView t = label(c, "", 13, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(d(6), d(12), d(6), d(12));
        t.setOnClickListener(l);
        return t;
    }

    private void showThumb(final int page) {
        thumbText.setText("Page " + (page + 1));
        thumbBox.setVisibility(View.VISIBLE);
        final int seq = ++thumbSeq;
        engine.thumbSeq = seq;
        engine.requestThumb(seq, page, d(170), d(240), (s, bmp) -> {
            if (s == thumbSeq && bmp != null && !isDestroyed()) thumbImage.setImageBitmap(bmp);
        });
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
