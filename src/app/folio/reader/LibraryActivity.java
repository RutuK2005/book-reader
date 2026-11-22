package app.folio.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * Home: today's reading at a glance, the book in progress, and the shelves. Shelves are the
 * reader's own categories; "Reading" and "Finished" are worked out from each book's progress.
 */
public class LibraryActivity extends Activity {

    private static final int REQ_PICK = 1, REQ_COVER = 2;
    private static final String STATE_COVER = "cover_book";
    private static final int DEFAULT_ACCENT = 0xFFB5651D;
    /** The accent of the colour scheme in use; set each time the page is built. */
    private static int ACCENT = DEFAULT_ACCENT;

    /** Home page colour schemes: page, card, text, accent, and 1 if it is a dark scheme. */
    private static final String[] SCHEME_NAMES = {"Match reading theme",
            "Linen", "Snow", "Sage", "Blush", "Sky", "Lavender", "Sand", "Mint", "Peach", "Rose gold", "Lemon", "Stone",
            "Coral", "Teal", "Lilac", "Clay",
            "Midnight", "Charcoal", "Forest", "Plum", "Espresso", "Ocean", "Black", "Wine", "Slate", "Ember", "Aurora", "Graphite"};
    private static final int[][] SCHEMES = {null,
            {0xFFEFEAE0, 0xFFFFFDF8, 0xFF1D1B18, 0xFFB5651D, 0}, {0xFFF2F4F7, 0xFFFFFFFF, 0xFF15171A, 0xFF2F6FED, 0},
            {0xFFE4ECE1, 0xFFF8FBF6, 0xFF1F2A20, 0xFF3F7D4E, 0}, {0xFFF6E7E5, 0xFFFFF8F6, 0xFF2B1D1C, 0xFFC0504D, 0},
            {0xFFE2EDF6, 0xFFF7FBFF, 0xFF16232E, 0xFF2B7BB9, 0}, {0xFFEBE6F5, 0xFFFAF8FF, 0xFF221D30, 0xFF6B4FB3, 0},
            {0xFFEDE0CA, 0xFFFBF4E6, 0xFF33271A, 0xFFA0621E, 0}, {0xFFDDF0EA, 0xFFF5FCFA, 0xFF12302A, 0xFF1E8C73, 0},
            {0xFFFAE5D3, 0xFFFFF7EF, 0xFF3A2416, 0xFFD9742B, 0}, {0xFFF2E0DC, 0xFFFFF6F3, 0xFF3B2422, 0xFFB76E79, 0},
            {0xFFF5EFCB, 0xFFFFFCEB, 0xFF2E2A12, 0xFF9A7B00, 0}, {0xFFE1E1DE, 0xFFF6F6F4, 0xFF1E1E1C, 0xFF555555, 0},
            {0xFFFBE3DC, 0xFFFFF6F3, 0xFF3A1D17, 0xFFE2573E, 0}, {0xFFDCEEEF, 0xFFF4FBFB, 0xFF10292B, 0xFF0F7C86, 0},
            {0xFFF0E4F4, 0xFFFCF7FE, 0xFF2C1B33, 0xFFA153C2, 0}, {0xFFEBDCD2, 0xFFFAF2EC, 0xFF35221A, 0xFF9C4A2F, 0},
            {0xFF0F1420, 0xFF1A2233, 0xFFE6EAF2, 0xFF5B8DEF, 1}, {0xFF17171A, 0xFF242428, 0xFFEDEDED, 0xFFD9902F, 1},
            {0xFF0F1A14, 0xFF1A2A21, 0xFFE2EFE6, 0xFF3FA36B, 1}, {0xFF1A1220, 0xFF2A1D33, 0xFFEFE6F5, 0xFFA868E8, 1},
            {0xFF1C1512, 0xFF2B211C, 0xFFF1E7DF, 0xFFC97F45, 1}, {0xFF0B1A22, 0xFF143040, 0xFFE0F0F6, 0xFF1E9FD6, 1},
            {0xFF000000, 0xFF161616, 0xFFE8E8E8, 0xFFD9902F, 1}, {0xFF1F0F14, 0xFF331A22, 0xFFF5E4E8, 0xFFD4486A, 1},
            {0xFF1B2026, 0xFF282F38, 0xFFE5E9EE, 0xFF6E9CCB, 1}, {0xFF1A1310, 0xFF2A1E18, 0xFFF6E9E0, 0xFFE8622C, 1},
            {0xFF0E1A1B, 0xFF16292B, 0xFFE0F2F1, 0xFF2BB8A8, 1}, {0xFF202124, 0xFF2D2E32, 0xFFE8EAED, 0xFF9AA0A6, 1}};
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** Filters that are not user shelves; a shelf name can never start with '#'. */
    private static final String ALL = "", READING = "#reading", FINISHED = "#finished";
    private static final int[] GOALS = {5, 10, 15, 20, 30, 45, 60, 90};

    private Library lib;
    private SharedPreferences prefs;
    private final HashMap<String, Bitmap> covers = new HashMap<>();
    private TextView busy;
    private int bg, fg, dim, card, lift;
    private float dp;
    private boolean importing;
    /** Id of the book a cover picture is being picked for, or null. */
    private String coverFor;
    private String filter = ALL;
    private ScrollView scroll;
    private LinearLayout listBox;
    private String query = "";
    private boolean searching, focusSearch;
    private int scrollSpeed;
    /** Scrolls the page while a dragged book is held near the top or bottom edge. */
    private final Runnable edgeScroller = new Runnable() {
        @Override
        public void run() {
            if (scrollSpeed == 0 || scroll == null) return;
            scroll.scrollBy(0, scrollSpeed);
            scroll.postDelayed(this, 16);
        }
    };

    /** screenY is where the dragged book is; a negative value stops the scrolling. */
    private void edgeScroll(float screenY) {
        int h = getResources().getDisplayMetrics().heightPixels, zone = d(130), was = scrollSpeed;
        if (screenY < 0) scrollSpeed = 0;
        else if (screenY < zone) scrollSpeed = -Math.round(d(4) + d(14) * (zone - screenY) / zone);
        else if (screenY > h - zone) scrollSpeed = Math.round(d(4) + d(14) * (screenY - (h - zone)) / zone);
        else scrollSpeed = 0;
        if (was == 0 && scrollSpeed != 0 && scroll != null) scroll.post(edgeScroller);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        lib = Library.get(this);
        prefs = P.get(this);
        dp = getResources().getDisplayMetrics().density;
        filter = prefs.getString(P.SHELF, ALL);
        if (state != null) coverFor = state.getString(STATE_COVER);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // The picker can push this page out of memory; the book being covered has to survive that.
        out.putString(STATE_COVER, coverFor);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuilt on every return so the page follows the reading theme and shows new progress.
        covers.clear();
        build();
    }

    // ---------------------------------------------------------------- page

    @SuppressWarnings("deprecation")
    private void build() {
        int theme = P.theme(prefs);
        // A lightly tinted page with paper-white cards raised off it; in the dark themes the
        // cards are simply a lighter grey, since shadows do not show on black.
        int paper = theme == 5 ? 0xFF121212 : Themes.BG[theme];
        boolean darkTheme = Themes.isDark(theme);
        fg = Themes.FG[theme];
        dim = (fg & 0x00FFFFFF) | 0x99000000;
        bg = darkTheme ? paper : Themes.blend(paper, fg, 0.055f);
        card = darkTheme ? Themes.blend(paper, fg, 0.10f) : Themes.blend(paper, 0xFFFFFFFF, 0.65f);
        lift = darkTheme ? 0 : d(2);
        ACCENT = DEFAULT_ACCENT;
        int scheme = prefs.getInt("home_scheme", 0);
        if (scheme > 0 && scheme < SCHEMES.length) {
            // The home page has its own colours, separate from the reading theme.
            int[] sc = SCHEMES[scheme];
            bg = sc[0];
            card = sc[1];
            fg = sc[2];
            ACCENT = sc[3];
            darkTheme = sc[4] == 1;
            dim = (fg & 0x00FFFFFF) | 0x99000000;
            lift = darkTheme ? 0 : d(2);
        }
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        w.getDecorView().setSystemUiVisibility(darkTheme ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        ArrayList<Library.Book> all = lib.list();
        ArrayList<String> shelves = lib.shelves();
        if (!filter.startsWith("#") && !filter.isEmpty() && !shelves.contains(filter)) setFilter(ALL);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(d(24), d(18), d(24), d(40));

        page.addView(header());
        final boolean open = prefs.getBoolean(P.HOME_OPEN, false);
        page.addView(summaryRow(open), top(MATCH, WRAP, 14));
        if (open) page.addView(statsCard(), top(MATCH, WRAP, 10));

        Library.Book current = null;
        for (Library.Book b : lib.all()) {
            if (b.started() && !b.finished() && (current == null || b.opened > current.opened)) current = b;
        }
        if (open && current != null) page.addView(continueCard(current), top(MATCH, WRAP, 14));
        if (open) page.addView(coloursRow(), top(MATCH, WRAP, 14));

        if (all.isEmpty()) {
            page.addView(message("Your library is empty.\n\nTap “Add books” and choose the books you want to read (PDF, EPUB or TXT). "
                    + "They are copied into MyBookSpace, so they stay here even if you move the originals."), top(MATCH, WRAP, 60));
        } else {
            page.addView(chips(all, shelves), top(MATCH, WRAP, 22));
            if (searching) page.addView(searchField(), top(MATCH, WRAP, 14));
            listBox = new LinearLayout(this);
            listBox.setOrientation(LinearLayout.VERTICAL);
            page.addView(listBox, new LinearLayout.LayoutParams(MATCH, WRAP));
            fillList();
        }

        final int keepY = scroll == null ? 0 : scroll.getScrollY();
        scrollSpeed = 0;
        scroll = new ScrollView(this);
        scroll.post(() -> scroll.scrollTo(0, keepY));
        // Catches a dragged book held over the header or the gaps, where no book is underneath.
        scroll.setOnDragListener((v, e) -> {
            if (e.getAction() == DragEvent.ACTION_DRAG_LOCATION) {
                int[] at = new int[2];
                v.getLocationOnScreen(at);
                edgeScroll(at[1] + e.getY());
            } else if (e.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                edgeScroll(-1);
            }
            return e.getAction() != DragEvent.ACTION_DROP;
        });
        scroll.setBackgroundColor(bg);
        scroll.setFillViewport(true);
        scroll.addView(page, new FrameLayout.LayoutParams(MATCH, WRAP));
        FrameLayout root = new FrameLayout(this);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        busy = text("Adding books…", 15, Color.WHITE, false);
        busy.setPadding(d(22), d(12), d(22), d(12));
        busy.setBackground(round(0xE0202020, 24));
        busy.setVisibility(importing ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = d(36);
        root.addView(busy, blp);
        setContentView(root);
    }

    /** The heading and the books under it. Kept separate so typing in the search box only redraws this. */
    private void fillList() {
        listBox.removeAllViews();
        ArrayList<Library.Book> shown = new ArrayList<>();
        String q = query.toLowerCase();
        for (Library.Book b : lib.list()) {
            if (matches(b) && (q.isEmpty() || b.title.toLowerCase().contains(q))) shown.add(b);
        }
        TextView heading = text(filterTitle() + "   " + shown.size(), 13, dim, false);
        heading.setAllCaps(true);
        heading.setLetterSpacing(0.08f);
        LinearLayout headRow = new LinearLayout(this);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.addView(heading, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView find = text(searching ? "Close search" : "Search", 13, ACCENT, false);
        find.setPadding(d(10), d(6), d(10), d(6));
        find.setOnClickListener(v -> {
            searching = !searching;
            query = "";
            focusSearch = searching;
            build();
        });
        headRow.addView(find);
        // Covers only: hides every book's title and details for a cleaner shelf.
        final boolean coversOnly = prefs.getBoolean("covers_only", false);
        TextView mode = text(coversOnly ? "Show titles" : "Covers only", 13, ACCENT, false);
        mode.setPadding(d(10), d(6), 0, d(6));
        mode.setOnClickListener(v -> {
            prefs.edit().putBoolean("covers_only", !coversOnly).apply();
            build();
        });
        headRow.addView(mode);
        listBox.addView(headRow, top(MATCH, WRAP, 12));
        if (shown.isEmpty()) {
            listBox.addView(message(!q.isEmpty() ? "No book matches “" + query + "”."
                    : filter.equals(READING) ? "Nothing in progress. Open a book to start reading."
                    : filter.equals(FINISHED) ? "No finished books yet."
                    : "Nothing on this shelf yet.\nDrag a book onto this shelf's tab, or long-press a book and choose “Move to shelf”."), top(MATCH, WRAP, 40));
        } else {
            grid(listBox, shown);
        }
    }

    private View searchField() {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Search your books");
        field.setText(query);
        field.setTextSize(15);
        field.setTextColor(fg);
        field.setHintTextColor(dim);
        field.setPadding(d(18), d(11), d(18), d(11));
        field.setBackground(round(card, 22));
        field.setElevation(lift);
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                query = s.toString().trim();
                fillList();
            }
        });
        if (focusSearch) {
            focusSearch = false;
            field.post(() -> {
                field.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(field, 0);
            });
        }
        return field;
    }

    private View header() {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView date = text(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(new Date()), 12, dim, false);
        date.setAllCaps(true);
        date.setLetterSpacing(0.08f);
        titles.addView(date);
        titles.addView(text("MyBookSpace", 30, fg, true));
        TextView by = text("by Rutuparn K   ·   github.com/RutuK2005", 12, dim, false);
        by.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RutuK2005")));
            } catch (Exception e) {
                Toast.makeText(this, "No browser to open the link", Toast.LENGTH_SHORT).show();
            }
        });
        titles.addView(by);
        head.addView(titles, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView add = text("+  Add books", 15, Color.WHITE, false);
        add.setPadding(d(20), d(11), d(20), d(11));
        add.setBackground(press(round(ACCENT, 24)));
        add.setOnClickListener(v -> pick());
        head.addView(add);
        return head;
    }

    /** One line that unfolds into the daily goal and the book in progress. Folded by default. */
    private View summaryRow(final boolean open) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(d(18), d(12), d(18), d(12));
        row.setBackground(press(round(card, 18)));
        row.setElevation(lift);
        int streak = lib.streak();
        TextView t = text("Today  " + minutes(lib.secondsOn(0)) + "   ·   " + streak + (streak == 1 ? " day" : " days") + " streak", 14, fg, false);
        t.setSingleLine(true);
        row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(text(open ? "Hide  ▴" : "Goal and current book  ▾", 13, ACCENT, false));
        row.setOnClickListener(v -> {
            prefs.edit().putBoolean(P.HOME_OPEN, !open).apply();
            build();
        });
        return row;
    }

    /** Today's minutes against the daily goal, the streak, and the week. Tap to change the goal. */
    private View statsCard() {
        int goal = prefs.getInt(P.GOAL_MIN, 20);
        int today = lib.secondsOn(0), streak = lib.streak();
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(d(18), d(14), d(18), d(14));
        c.setBackground(press(round(card, 18)));
        c.setElevation(lift);
        Ring ring = new Ring(this);
        ring.set(Math.min(1f, today / (goal * 60f)), today / 60, fg, Themes.blend(card, fg, 0.14f));
        c.addView(ring, new LinearLayout.LayoutParams(d(74), d(74)));
        c.addView(stat(minutes(today), today >= goal * 60 ? "Goal reached" : "of " + goal + " min today"), statParams());
        c.addView(stat(streak + (streak == 1 ? " day" : " days"), "Streak"), statParams());
        c.addView(stat(minutes(lib.weekSeconds()), "This week"), statParams());
        c.setOnClickListener(v -> goalDialog());
        return c;
    }

    private LinearLayout.LayoutParams statParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        lp.leftMargin = d(16);
        return lp;
    }

    private View stat(String value, String label) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView v = text(value, 17, fg, true);
        v.setSingleLine(true);
        col.addView(v);
        TextView l = text(label, 11, dim, false);
        l.setMaxLines(2);
        col.addView(l);
        return col;
    }

    private View continueCard(final Library.Book b) {
        LinearLayout c = new LinearLayout(this);
        c.setPadding(d(16), d(16), d(18), d(16));
        c.setBackground(press(round(card, 18)));
        c.setElevation(lift);
        c.setGravity(Gravity.CENTER_VERTICAL);
        CoverView cover = coverView(b);
        c.addView(cover, new LinearLayout.LayoutParams(d(92), WRAP));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView label = text("Continue reading", 11, ACCENT, false);
        label.setAllCaps(true);
        label.setLetterSpacing(0.10f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(label);
        TextView title = text(b.title, 19, fg, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title, top(MATCH, WRAP, 2));
        int pct = percent(b);
        String where = "Page " + (b.page + 1) + " of " + b.pages + "  ·  " + pct + "%";
        if (b.samples >= 5 && b.secPerPage > 0) {
            where += "  ·  about " + minutes(Math.round((b.pages - 1 - b.page) * b.secPerPage)) + " left";
        }
        col.addView(text(where, 12, dim, false), top(MATCH, WRAP, 4));
        ProgressLine line = new ProgressLine(this);
        line.set(pct / 100f, fg);
        col.addView(line, top(MATCH, d(4), 10));
        TextView resume = text("Resume", 14, Color.WHITE, false);
        resume.setPadding(d(18), d(8), d(18), d(8));
        resume.setBackground(press(round(ACCENT, 20)));
        LinearLayout.LayoutParams rlp = top(WRAP, WRAP, 12);
        col.addView(resume, rlp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        lp.leftMargin = d(18);
        c.addView(col, lp);
        c.setOnClickListener(v -> openBook(b));
        return c;
    }

    private View chips(ArrayList<Library.Book> all, ArrayList<String> shelves) {
        LinearLayout row = new LinearLayout(this);
        int reading = 0, finished = 0;
        HashMap<String, Integer> counts = new HashMap<>();
        for (Library.Book b : all) {
            if (b.finished()) finished++;
            else if (b.started()) reading++;
            if (!b.shelf.isEmpty()) {
                Integer n = counts.get(b.shelf);
                counts.put(b.shelf, n == null ? 1 : n + 1);
            }
        }
        for (String key : tabOrder(shelves)) {
            if (key.equals(ALL)) row.addView(chip("All", all.size(), ALL, false));
            else if (key.equals(READING)) row.addView(chip("Reading", reading, READING, false));
            else if (key.equals(FINISHED)) row.addView(chip("Finished", finished, FINISHED, false));
            else {
                Integer n = counts.get(key);
                row.addView(chip(key, n == null ? 0 : n, key, true));
            }
        }
        TextView add = text("+  New shelf", 14, ACCENT, false);
        add.setPadding(d(16), d(8), d(16), d(8));
        GradientDrawable outline = round(Color.TRANSPARENT, 20);
        outline.setStroke(d(1), ACCENT);
        add.setBackground(outline);
        add.setOnClickListener(v -> newShelfDialog(null));
        row.addView(add);
        row.setPadding(d(2), d(2), d(2), d(6));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    /** The tabs in the reader's chosen order; anything new goes at the end. */
    private ArrayList<String> tabOrder(ArrayList<String> shelves) {
        ArrayList<String> valid = new ArrayList<>();
        valid.add(ALL);
        valid.add(READING);
        valid.add(FINISHED);
        valid.addAll(shelves);
        ArrayList<String> out = new ArrayList<>();
        String saved = prefs.getString("tab_order", null);
        if (saved != null) {
            for (String k : saved.split("\n", -1)) if (valid.contains(k) && !out.contains(k)) out.add(k);
        }
        for (String k : valid) if (!out.contains(k)) out.add(k);
        return out;
    }

    private void moveTab(String key, String onto) {
        ArrayList<String> order = tabOrder(lib.shelves());
        int to = order.indexOf(onto);
        if (to < 0 || !order.remove(key)) return;
        order.add(to, key);
        prefs.edit().putString("tab_order", TextUtils.join("\n", order)).apply();
    }

    private View chip(String name, int count, final String key, final boolean custom) {
        boolean on = filter.equals(key);
        TextView t = text(name + "  " + count, 14, on ? Color.WHITE : fg, false);
        t.setPadding(d(16), d(8), d(16), d(8));
        t.setBackground(press(round(on ? ACCENT : card, 20)));
        t.setElevation(on ? 0 : lift);
        t.setOnClickListener(v -> {
            setFilter(key);
            build();
        });
        // Hold a tab and drag it onto another to rearrange them. Letting go where it started
        // opens the shelf's menu. A book dropped on a shelf's tab is filed on that shelf.
        t.setOnLongClickListener(v -> {
            v.startDragAndDrop(null, new View.DragShadowBuilder(v), "tab:" + key, 0);
            return true;
        });
        t.setOnDragListener((v, e) -> {
            Object what = e.getLocalState();
            boolean tab = what instanceof String;
            boolean book = what instanceof Library.Book && custom;
            if (!tab && !book) return false;
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_ENTERED:
                    v.setAlpha(0.45f);
                    break;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    v.setAlpha(1f);
                    break;
                case DragEvent.ACTION_DROP:
                    v.setAlpha(1f);
                    if (book) {
                        ((Library.Book) what).shelf = key;
                        lib.save();
                        Toast.makeText(this, "Moved to " + key, Toast.LENGTH_SHORT).show();
                        build();
                    } else if (what.equals("tab:" + key)) {
                        if (custom) shelfMenu(key);
                    } else {
                        moveTab(((String) what).substring(4), key);
                        build();
                    }
                    break;
                default:
                    break;
            }
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.rightMargin = d(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** Lays the books out as rows of covers; the column count follows the screen width. */
    private void grid(LinearLayout page, ArrayList<Library.Book> books) {
        int gap = d(20);
        int width = getResources().getDisplayMetrics().widthPixels - d(48);
        int cols = Math.max(2, (width + gap) / (d(150) + gap));
        for (int i = 0; i < books.size(); i += cols) {
            LinearLayout row = new LinearLayout(this);
            for (int k = 0; k < cols; k++) {
                boolean filled = i + k < books.size();
                // An empty slot gets a fixed height: a bare View asked to wrap takes all the room
                // it is offered, which would stretch the row to the height of the screen.
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, filled ? WRAP : 1, 1f);
                if (k > 0) lp.leftMargin = gap;
                row.addView(filled ? cell(books.get(i + k)) : new View(this), lp);
            }
            page.addView(row, top(MATCH, WRAP, i == 0 ? 12 : 22));
        }
    }

    private View cell(final Library.Book b) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        // Each cover keeps its own proportions and stands on the bottom of an equal-height slot,
        // so a row lines up along its base like books on a shelf and nothing is cropped away.
        CoverView cover = coverView(b);
        CoverSlot slot = new CoverSlot(this);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
        clp.setMargins(d(2), 0, d(2), d(8));
        slot.addView(cover, clp);
        c.addView(slot, new LinearLayout.LayoutParams(MATCH, WRAP));
        int pct = percent(b);
        ProgressLine line = new ProgressLine(this);
        line.set(b.started() ? pct / 100f : 0f, fg);
        c.addView(line, new LinearLayout.LayoutParams(MATCH, d(3)));
        TextView title = text(b.title, 14, fg, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(title, top(MATCH, WRAP, 8));
        String sub = b.finished() ? "Finished" : b.started() ? pct + "% read" : b.pages + " pages";
        if (!b.partner.isEmpty()) sub += "  ·  2 versions";
        if (!b.shelf.isEmpty() && !filter.equals(b.shelf)) sub += "  ·  " + b.shelf;
        TextView s = text(sub, 12, dim, false);
        s.setMaxLines(2);
        c.addView(s, top(MATCH, WRAP, 2));
        c.setContentDescription(b.title);
        if (prefs.getBoolean("covers_only", false)) {
            line.setVisibility(View.GONE);
            title.setVisibility(View.GONE);
            s.setVisibility(View.GONE);
        }
        c.setOnClickListener(v -> chooseVersion(b));
        // Hold a book and drag it onto the edge of another to move it there, or onto the middle
        // of its cover to merge the two as versions of one book. Letting go where it started
        // opens its menu instead.
        c.setOnLongClickListener(v -> {
            v.startDragAndDrop(null, new View.DragShadowBuilder(v), b, 0);
            return true;
        });
        c.setOnDragListener((v, e) -> {
            if (!(e.getLocalState() instanceof Library.Book)) return false;
            Library.Book moving = (Library.Book) e.getLocalState();
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_LOCATION:
                    int[] at = new int[2];
                    v.getLocationOnScreen(at);
                    edgeScroll(at[1] + e.getY());
                    break;
                case DragEvent.ACTION_DRAG_ENTERED:
                    if (moving != b) v.setAlpha(0.4f);
                    break;
                case DragEvent.ACTION_DRAG_ENDED:
                    edgeScroll(-1);
                    v.setAlpha(1f);
                    break;
                case DragEvent.ACTION_DRAG_EXITED:
                    v.setAlpha(1f);
                    break;
                case DragEvent.ACTION_DROP:
                    v.setAlpha(1f);
                    if (moving == b) {
                        bookMenu(b);
                    } else if (e.getX() > v.getWidth() * 0.25f && e.getX() < v.getWidth() * 0.75f) {
                        confirmMerge(moving, b);
                    } else {
                        lib.move(moving, b);
                        build();
                    }
                    break;
                default:
                    break;
            }
            return true;
        });
        return c;
    }

    private CoverView coverView(Library.Book b) {
        CoverView cover = new CoverView(this, d(7));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(Color.WHITE);
        cover.setElevation(d(5));
        Bitmap bmp = covers.get(b.id);
        if (bmp == null) {
            bmp = BitmapFactory.decodeFile(lib.cover(b).getPath());
            if (bmp != null) covers.put(b.id, bmp);
        }
        cover.setImageBitmap(bmp);
        return cover;
    }

    private boolean matches(Library.Book b) {
        if (filter.equals(ALL)) return true;
        if (filter.equals(READING)) return b.started() && !b.finished();
        if (filter.equals(FINISHED)) return b.finished();
        return filter.equals(b.shelf);
    }

    private String filterTitle() {
        return filter.equals(ALL) ? "All books" : filter.equals(READING) ? "Reading"
                : filter.equals(FINISHED) ? "Finished" : filter;
    }

    private void setFilter(String f) {
        filter = f;
        prefs.edit().putString(P.SHELF, f).apply();
    }

    private static int percent(Library.Book b) {
        return b.pages > 0 ? Math.min(100, (b.page + 1) * 100 / b.pages) : 0;
    }

    private static String minutes(int seconds) {
        int min = seconds / 60;
        return min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
    }

    // ---------------------------------------------------------------- actions

    private void openBook(Library.Book b) {
        startActivity(new Intent(this, ReaderActivity.class).putExtra(ReaderActivity.EXTRA_ID, b.id));
    }

    /** A merged book asks which version to open; any other book just opens. */
    private void chooseVersion(final Library.Book b) {
        final Library.Book other = b.partner.isEmpty() ? null : lib.find(b.partner);
        if (other == null) {
            openBook(b);
            return;
        }
        final Library.Book[] versions = {b, other};
        String[] items = new String[2];
        for (int i = 0; i < 2; i++) {
            Library.Book v = versions[i];
            items[i] = v.kind() + "   ·   " + (v.started() ? "page " + (v.page + 1) + " of " + v.pages : v.pages + " pages");
        }
        new AlertDialog.Builder(this)
                .setTitle(b.title)
                .setItems(items, (dlg, which) -> openBook(versions[which]))
                .show();
    }

    private void details(Library.Book b) {
        StringBuilder s = new StringBuilder();
        s.append(b.kind()).append("  ·  ").append(b.pages).append(" pages");
        s.append("\n\n").append(b.finished() ? "Finished" : b.started() ? "On page " + (b.page + 1) + "  (" + percent(b) + "%)" : "Not started");
        s.append("\nTime spent reading: ").append(minutes((int) Math.min(Integer.MAX_VALUE, b.readSec)));
        if (b.samples >= 5 && b.secPerPage > 0 && !b.finished()) {
            s.append("\nAbout ").append(minutes(Math.round((b.pages - 1 - b.page) * b.secPerPage))).append(" left at your pace");
        }
        int marks = 0;
        for (ArrayList<float[]> list : b.highlights.values()) marks += list.size();
        s.append("\n\n").append(b.bookmarks.size()).append(" bookmarks  ·  ").append(b.notes.size())
                .append(" notes  ·  ").append(marks).append(" highlights");
        s.append("\nShelf: ").append(b.shelf.isEmpty() ? "none" : b.shelf);
        if (b.added > 0) s.append("\nAdded ").append(new SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(new Date(b.added)));
        s.append("\n\nFile: ").append(b.src);
        new AlertDialog.Builder(this).setTitle(b.title).setMessage(s.toString()).setPositiveButton("Close", null).show();
    }

    private void confirmMerge(final Library.Book dragged, final Library.Book target) {
        new AlertDialog.Builder(this)
                .setTitle("Merge these two?")
                .setMessage("“" + dragged.title + "” and “" + target.title + "” become one book with two versions. "
                        + "Tapping it asks which to open. You can separate them again from the book's menu.")
                .setPositiveButton("Merge", (dlg, which) -> {
                    if (!lib.merge(dragged, target)) {
                        Toast.makeText(this, "One of these already has a second version", Toast.LENGTH_LONG).show();
                    }
                    build();
                })
                .setNegativeButton("Just move it", (dlg, which) -> {
                    lib.move(dragged, target);
                    build();
                })
                .show();
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // Many file apps report an EPUB as a generic file, so the picker has to allow those too.
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "application/epub+zip", "text/plain", "application/octet-stream"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker is available on this device", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_COVER) {
            Library.Book b = coverFor == null ? null : lib.find(coverFor);
            coverFor = null;
            if (result == RESULT_OK && data != null && data.getData() != null && b != null) applyCover(b, data.getData());
            return;
        }
        if (req != REQ_PICK || result != RESULT_OK || data == null) return;
        final ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        importing = true;
        if (busy != null) busy.setVisibility(View.VISIBLE);
        // New books land on the shelf being looked at, if it is one of the reader's own.
        final String shelf = filter.startsWith("#") ? "" : filter;
        new Thread(() -> {
            String error = null;
            for (Uri u : uris) {
                try {
                    Library.Book b = lib.importUri(u);
                    if (!shelf.isEmpty() && b.shelf.isEmpty()) b.shelf = shelf;
                } catch (Throwable t) {
                    error = t.getMessage() == null ? "One file couldn't be opened" : t.getMessage();
                }
            }
            lib.save();
            final String err = error;
            runOnUiThread(() -> {
                importing = false;
                build();
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void bookMenu(final Library.Book b) {
        new AlertDialog.Builder(this)
                .setTitle(b.title)
                .setItems(b.partner.isEmpty() ? new String[]{"Move to shelf…", "Rename", "Change cover…", "Details", "Remove from library"}
                        : new String[]{"Move to shelf…", "Rename", "Change cover…", "Details", "Remove from library", "Separate the two versions"}, (dlg, which) -> {
                    if (which == 0) shelfPicker(b);
                    else if (which == 1) rename(b);
                    else if (which == 2) coverMenu(b);
                    else if (which == 3) details(b);
                    else if (which == 4) confirmRemove(b);
                    else {
                        lib.unmerge(b);
                        build();
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- covers

    private void coverMenu(final Library.Book b) {
        final ArrayList<String> items = new ArrayList<>();
        items.add("Choose a picture from this device");
        items.add("Find a cover on the web");
        if (b.ownCover) items.add("Use the first page again");
        new AlertDialog.Builder(this)
                .setTitle("Cover")
                .setItems(items.toArray(new String[0]), (dlg, which) -> {
                    if (which == 0) pickCover(b);
                    else if (which == 1) searchCover(b);
                    else resetCover(b);
                })
                .show();
    }

    private void pickCover(Library.Book b) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            coverFor = b.id;
            startActivityForResult(i, REQ_COVER);
        } catch (Exception e) {
            coverFor = null;
            Toast.makeText(this, "No file picker is available on this device", Toast.LENGTH_LONG).show();
        }
    }

    /** Opens an image search for the book in the browser; the saved picture is then chosen here. */
    private void searchCover(Library.Book b) {
        Uri u = Uri.parse("https://www.google.com/search").buildUpon()
                .appendQueryParameter("tbm", "isch").appendQueryParameter("q", b.title + " book cover").build();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
            Toast.makeText(this, "Save a picture, then come back and choose it as the cover", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No browser is available on this device", Toast.LENGTH_LONG).show();
        }
    }

    private void applyCover(final Library.Book b, final Uri uri) {
        new Thread(() -> {
            String error = null;
            try {
                lib.setCover(b, uri);
            } catch (Throwable t) {
                error = t instanceof OutOfMemoryError || t.getMessage() == null ? "That picture couldn't be used" : t.getMessage();
            }
            coverChanged(b, error);
        }).start();
    }

    private void resetCover(final Library.Book b) {
        new Thread(() -> {
            String error = null;
            try {
                lib.resetCover(b);
            } catch (Throwable t) {
                error = "The first page couldn't be drawn";
            }
            coverChanged(b, error);
        }).start();
    }

    private void coverChanged(final Library.Book b, final String error) {
        runOnUiThread(() -> {
            if (isFinishing()) return;
            covers.remove(b.id);
            build();
            if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        });
    }

    private void shelfPicker(final Library.Book b) {
        final ArrayList<String> shelves = lib.shelves();
        final String[] items = new String[shelves.size() + 2];
        for (int i = 0; i < shelves.size(); i++) items[i] = shelves.get(i) + (shelves.get(i).equals(b.shelf) ? "   ✓" : "");
        items[shelves.size()] = "No shelf" + (b.shelf.isEmpty() ? "   ✓" : "");
        items[shelves.size() + 1] = "New shelf…";
        new AlertDialog.Builder(this)
                .setTitle("Move to shelf")
                .setItems(items, (dlg, which) -> {
                    if (which == shelves.size() + 1) {
                        newShelfDialog(b);
                        return;
                    }
                    b.shelf = which < shelves.size() ? shelves.get(which) : "";
                    lib.save();
                    build();
                })
                .show();
    }

    /** Creates a shelf; if a book is given it is moved there, otherwise the new shelf is opened. */
    private void newShelfDialog(final Library.Book b) {
        final EditText input = field("", "Work, Study, Novels…");
        ask("New shelf", input, "Create", () -> {
            String name = input.getText().toString().replace("#", "");
            String made = lib.addShelf(name);
            if (made == null) return;
            if (b != null) {
                b.shelf = made;
                lib.save();
            } else {
                setFilter(made);
            }
            build();
        });
    }

    private void shelfMenu(final String shelf) {
        new AlertDialog.Builder(this)
                .setTitle(shelf)
                .setItems(new String[]{"Rename shelf", "Delete shelf"}, (dlg, which) -> {
                    if (which == 0) {
                        final EditText input = field(shelf, "");
                        ask("Rename shelf", input, "Save", () -> {
                            String to = input.getText().toString().replace("#", "").trim();
                            lib.renameShelf(shelf, to);
                            if (lib.shelves().contains(to)) setFilter(to);
                            build();
                        });
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("Delete “" + shelf + "”?")
                                .setMessage("The books on it stay in your library.")
                                .setPositiveButton("Delete", (d2, w2) -> {
                                    lib.deleteShelf(shelf);
                                    setFilter(ALL);
                                    build();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .show();
    }

    /** The colour setting, shown in the unfolded section: the scheme in use and a way to change it. */
    private View coloursRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(d(18), d(12), d(18), d(12));
        row.setBackground(press(round(card, 18)));
        row.setElevation(lift);
        View dot = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(ACCENT);
        dot.setBackground(ring);
        row.addView(dot, new LinearLayout.LayoutParams(d(20), d(20)));
        int scheme = prefs.getInt("home_scheme", 0);
        TextView name = text("Home page colours   ·   " + SCHEME_NAMES[scheme < SCHEME_NAMES.length ? scheme : 0], 14, fg, false);
        name.setSingleLine(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        nlp.leftMargin = d(12);
        row.addView(name, nlp);
        row.addView(text("Change", 13, ACCENT, false));
        row.setOnClickListener(v -> colourPicker());
        return row;
    }

    /** A grid of colour schemes for the home page, each tile drawn in its own colours. */
    private void colourPicker() {
        final int current = prefs.getInt("home_scheme", 0);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(d(16), d(8), d(16), d(8));
        final AlertDialog[] dialog = new AlertDialog[1];
        int cols = 3;
        for (int i = 0; i < SCHEMES.length; i += cols) {
            LinearLayout row = new LinearLayout(this);
            for (int k = 0; k < cols; k++) {
                final int index = i + k;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, index < SCHEMES.length ? WRAP : 1, 1f);
                lp.setMargins(d(4), d(4), d(4), d(4));
                if (index >= SCHEMES.length) {
                    row.addView(new View(this), lp);
                    continue;
                }
                int[] sc = SCHEMES[index];
                int tileBg = sc == null ? 0xFFFFFFFF : sc[0], tileFg = sc == null ? 0xFF222222 : sc[2];
                int tileAccent = sc == null ? DEFAULT_ACCENT : sc[3], tileCard = sc == null ? 0xFFEFEFEF : sc[1];
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER_HORIZONTAL);
                tile.setPadding(d(6), d(10), d(6), d(8));
                GradientDrawable tb = new GradientDrawable();
                tb.setColor(tileBg);
                tb.setCornerRadius(d(14));
                tb.setStroke(d(index == current ? 3 : 1), index == current ? tileAccent : 0x33000000);
                tile.setBackground(tb);
                // A miniature of the page: a card with a line of text and an accent button.
                LinearLayout mini = new LinearLayout(this);
                mini.setGravity(Gravity.CENTER_VERTICAL);
                mini.setPadding(d(8), d(6), d(8), d(6));
                GradientDrawable mb = new GradientDrawable();
                mb.setColor(tileCard);
                mb.setCornerRadius(d(8));
                mini.setBackground(mb);
                TextView aa = new TextView(this);
                aa.setText("Aa");
                aa.setTextSize(14);
                aa.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                aa.setTextColor(tileFg);
                mini.addView(aa, new LinearLayout.LayoutParams(0, WRAP, 1f));
                View pill = new View(this);
                GradientDrawable pb = new GradientDrawable();
                pb.setColor(tileAccent);
                pb.setCornerRadius(d(8));
                pill.setBackground(pb);
                mini.addView(pill, new LinearLayout.LayoutParams(d(22), d(14)));
                tile.addView(mini, new LinearLayout.LayoutParams(MATCH, WRAP));
                TextView name = new TextView(this);
                name.setText(SCHEME_NAMES[index]);
                name.setTextSize(11);
                name.setTextColor(tileFg);
                name.setGravity(Gravity.CENTER);
                name.setMaxLines(2);
                name.setPadding(0, d(6), 0, 0);
                tile.addView(name);
                tile.setOnClickListener(v -> {
                    prefs.edit().putInt("home_scheme", index).apply();
                    if (dialog[0] != null) dialog[0].dismiss();
                    build();
                });
                row.addView(tile, lp);
            }
            grid.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(grid);
        dialog[0] = new AlertDialog.Builder(this).setTitle("Home page colours").setView(sv).setNegativeButton("Close", null).create();
        dialog[0].show();
    }

    private void goalDialog() {
        final String[] items = new String[GOALS.length];
        int current = prefs.getInt(P.GOAL_MIN, 20), checked = -1;
        for (int i = 0; i < GOALS.length; i++) {
            items[i] = GOALS[i] + " minutes a day";
            if (GOALS[i] == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Daily reading goal")
                .setSingleChoiceItems(items, checked, (dlg, which) -> {
                    prefs.edit().putInt(P.GOAL_MIN, GOALS[which]).apply();
                    dlg.dismiss();
                    build();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void rename(final Library.Book b) {
        final EditText input = field(b.title, "");
        ask("Rename", input, "Save", () -> {
            String t = input.getText().toString().trim();
            if (!t.isEmpty()) {
                b.title = t;
                lib.save();
                build();
            }
        });
    }

    private void confirmRemove(final Library.Book b) {
        new AlertDialog.Builder(this)
                .setTitle("Remove “" + b.title + "”?")
                .setMessage("This deletes MyBookSpace's copy, your place, your bookmarks and notes. The original PDF is not touched.")
                .setPositiveButton("Remove", (dlg, which) -> {
                    covers.remove(b.id);
                    lib.remove(b);
                    build();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText field(String value, String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setHint(hint);
        input.setSelection(value.length());
        return input;
    }

    /** A one-field dialog that comes up with the keyboard. */
    private void ask(String title, EditText input, String ok, final Runnable onOk) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(d(22), d(8), d(22), 0);
        wrap.addView(input);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton(ok, (d1, which) -> onOk.run())
                .setNegativeButton("Cancel", null)
                .create();
        if (dlg.getWindow() != null) dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dlg.show();
        input.requestFocus();
    }

    // ---------------------------------------------------------------- small view helpers

    private TextView text(String s, int sp, int color, boolean serifBold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (serifBold) t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        return t;
    }

    private TextView message(String s) {
        TextView t = text(s, 15, dim, false);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(d(20), 0, d(20), 0);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(d(radiusDp));
        return g;
    }

    /** Adds a touch ripple to a rounded background. */
    private android.graphics.drawable.Drawable press(GradientDrawable g) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22000000), g, null);
    }

    private LinearLayout.LayoutParams top(int w, int h, int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = d(marginDp);
        return lp;
    }

    private int d(float v) {
        return Math.round(v * dp);
    }

    /** A cover slot with book proportions and rounded corners, whatever width it is given. */
    private static final class CoverView extends ImageView {
        CoverView(Context c, final int radius) {
            super(c);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            setClipToOutline(true);
        }

        /** Height over width. Follows the picture, within the range real books come in. */
        private float aspect = 1.45f;

        @Override
        public void setImageBitmap(Bitmap b) {
            super.setImageBitmap(b);
            if (b != null && b.getWidth() > 0) aspect = Math.max(1.15f, Math.min(1.46f, b.getHeight() / (float) b.getWidth()));
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, Math.round(w * aspect));
        }
    }

    /** A fixed-height place for one cover, tall enough for the tallest book. */
    private static final class CoverSlot extends FrameLayout {
        CoverSlot(Context c) {
            super(c);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(Math.round(w * 1.5f), MeasureSpec.EXACTLY));
        }
    }

    private static final class ProgressLine extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float fraction;
        private int color;

        ProgressLine(Context c) {
            super(c);
        }

        void set(float f, int fg) {
            fraction = f;
            color = fg;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight();
            paint.setColor((color & 0x00FFFFFF) | 0x26000000);
            r.set(0, 0, getWidth(), h);
            c.drawRoundRect(r, h / 2, h / 2, paint);
            if (fraction <= 0f) return;
            paint.setColor(ACCENT);
            r.set(0, 0, Math.max(h, getWidth() * fraction), h);
            c.drawRoundRect(r, h / 2, h / 2, paint);
        }
    }

    /** The daily-goal ring: an arc for progress with today's minutes in the middle. */
    private static final class Ring extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float fraction;
        private int minutes, fg, track;

        Ring(Context c) {
            super(c);
        }

        void set(float f, int min, int fgColor, int trackColor) {
            fraction = f;
            minutes = min;
            fg = fgColor;
            track = trackColor;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), stroke = w * 0.11f;
            r.set(stroke / 2, stroke / 2, w - stroke / 2, w - stroke / 2);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(track);
            c.drawOval(r, paint);
            if (fraction > 0f) {
                paint.setColor(ACCENT);
                c.drawArc(r, -90, 360 * fraction, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fg);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(w * 0.30f);
            c.drawText(String.valueOf(minutes), w / 2, w / 2 + w * 0.06f, paint);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(w * 0.15f);
            c.drawText("min", w / 2, w / 2 + w * 0.24f, paint);
        }
    }
}
