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

    private static final int REQ_PICK = 1;
    private static final int ACCENT = 0xFFB5651D;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** Filters that are not user shelves; a shelf name can never start with '#'. */
    private static final String ALL = "", READING = "#reading", FINISHED = "#finished";
    private static final int[] GOALS = {5, 10, 15, 20, 30, 45, 60, 90};

    private Library lib;
    private SharedPreferences prefs;
    private final HashMap<String, Bitmap> covers = new HashMap<>();
    private TextView busy;
    private int bg, fg, dim, card;
    private float dp;
    private boolean importing;
    private String filter = ALL;
    private ScrollView scroll;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuilt on every return so the page follows the reading theme and shows new progress.
        build();
    }

    // ---------------------------------------------------------------- page

    @SuppressWarnings("deprecation")
    private void build() {
        int theme = P.theme(prefs);
        bg = theme == 5 ? 0xFF121212 : Themes.BG[theme];
        fg = Themes.FG[theme];
        dim = (fg & 0x00FFFFFF) | 0x99000000;
        card = Themes.blend(bg, fg, Themes.isDark(theme) ? 0.10f : 0.055f);
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        w.getDecorView().setSystemUiVisibility(Themes.isDark(theme) ? 0
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
        for (Library.Book b : all) {
            if (b.started() && !b.finished() && (current == null || b.opened > current.opened)) current = b;
        }
        if (open && current != null) page.addView(continueCard(current), top(MATCH, WRAP, 14));

        if (all.isEmpty()) {
            page.addView(message("Your library is empty.\n\nTap “Add books” and choose the PDFs you want to read. "
                    + "They are copied into MyBookSpace, so they stay here even if you move the originals."), top(MATCH, WRAP, 60));
        } else {
            page.addView(chips(all, shelves), top(MATCH, WRAP, 22));
            ArrayList<Library.Book> shown = new ArrayList<>();
            for (Library.Book b : all) if (matches(b)) shown.add(b);
            TextView heading = text(filterTitle() + "   " + shown.size(), 13, dim, false);
            heading.setAllCaps(true);
            heading.setLetterSpacing(0.08f);
            page.addView(heading, top(WRAP, WRAP, 18));
            if (shown.isEmpty()) {
                page.addView(message(filter.equals(READING) ? "Nothing in progress. Open a book to start reading."
                        : filter.equals(FINISHED) ? "No finished books yet."
                        : "Nothing on this shelf yet.\nLong-press a book and choose “Move to shelf”."), top(MATCH, WRAP, 40));
            } else {
                grid(page, shown);
            }
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
        titles.addView(text("by Rutuparn K", 12, dim, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView add = text("+  Add books", 15, Color.WHITE, false);
        add.setPadding(d(20), d(11), d(20), d(11));
        add.setBackground(round(ACCENT, 24));
        add.setOnClickListener(v -> pick());
        head.addView(add);
        return head;
    }

    /** One line that unfolds into the daily goal and the book in progress. Folded by default. */
    private View summaryRow(final boolean open) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(d(18), d(12), d(18), d(12));
        row.setBackground(round(card, 18));
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
        c.setBackground(round(card, 18));
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
        c.setBackground(round(card, 18));
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
        resume.setBackground(round(ACCENT, 20));
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
        row.addView(chip("All", all.size(), ALL, false));
        row.addView(chip("Reading", reading, READING, false));
        row.addView(chip("Finished", finished, FINISHED, false));
        for (String s : shelves) {
            Integer n = counts.get(s);
            row.addView(chip(s, n == null ? 0 : n, s, true));
        }
        TextView add = text("+  New shelf", 14, ACCENT, false);
        add.setPadding(d(16), d(8), d(16), d(8));
        GradientDrawable outline = round(Color.TRANSPARENT, 20);
        outline.setStroke(d(1), ACCENT);
        add.setBackground(outline);
        add.setOnClickListener(v -> newShelfDialog(null));
        row.addView(add);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    private View chip(String name, int count, final String key, boolean custom) {
        boolean on = filter.equals(key);
        TextView t = text(name + "  " + count, 14, on ? Color.WHITE : fg, false);
        t.setPadding(d(16), d(8), d(16), d(8));
        t.setBackground(round(on ? ACCENT : card, 20));
        t.setOnClickListener(v -> {
            setFilter(key);
            build();
        });
        if (custom) {
            t.setOnLongClickListener(v -> {
                shelfMenu(key);
                return true;
            });
        }
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
        CoverView cover = coverView(b);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH, WRAP);
        clp.setMargins(d(2), d(2), d(2), d(8));
        c.addView(cover, clp);
        int pct = percent(b);
        ProgressLine line = new ProgressLine(this);
        line.set(b.started() ? pct / 100f : 0f, fg);
        c.addView(line, new LinearLayout.LayoutParams(MATCH, d(3)));
        TextView title = text(b.title, 14, fg, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(title, top(MATCH, WRAP, 8));
        String sub = b.finished() ? "Finished" : b.started() ? pct + "% read" : b.pages + " pages";
        if (!b.shelf.isEmpty() && !filter.equals(b.shelf)) sub += "  ·  " + b.shelf;
        TextView s = text(sub, 12, dim, false);
        s.setMaxLines(2);
        c.addView(s, top(MATCH, WRAP, 2));
        c.setOnClickListener(v -> openBook(b));
        // Hold a book and drag it onto another to move it there. Letting go where it started
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

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
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
                .setItems(new String[]{"Move to shelf…", "Rename", "Remove from library"}, (dlg, which) -> {
                    if (which == 0) shelfPicker(b);
                    else if (which == 1) rename(b);
                    else confirmRemove(b);
                })
                .show();
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

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, Math.round(w * 1.45f));
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
