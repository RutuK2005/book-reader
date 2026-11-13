package app.folio.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;

/** The bookshelf: a grid of covers, most recently read first. */
public class LibraryActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int ACCENT = 0xFFB5651D;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Library lib;
    private final ArrayList<Library.Book> items = new ArrayList<>();
    private final HashMap<String, Bitmap> covers = new HashMap<>();
    private final BookAdapter adapter = new BookAdapter();
    private TextView empty, busy;
    private int bg, fg;
    private float dp;
    private boolean importing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        lib = Library.get(this);
        dp = getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuilt on every return so the shelf follows the reading theme and shows new progress.
        buildUi();
        reload();
    }

    @SuppressWarnings("deprecation")
    private void buildUi() {
        int theme = P.get(this).getInt(P.THEME, 1);
        bg = theme == 5 ? 0xFF121212 : Themes.BG[theme];
        fg = Themes.FG[theme];
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        w.getDecorView().setSystemUiVisibility(Themes.isDark(theme) ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(d(26), d(22), d(26), d(6));
        TextView title = new TextView(this);
        title.setText("Library");
        title.setTextSize(30);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        title.setTextColor(fg);
        head.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView add = new TextView(this);
        add.setText("+  Add books");
        add.setTextSize(15);
        add.setTextColor(Color.WHITE);
        add.setPadding(d(20), d(11), d(20), d(11));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(ACCENT);
        pill.setCornerRadius(d(24));
        add.setBackground(pill);
        add.setOnClickListener(v -> pick());
        head.addView(add);
        root.addView(head);

        FrameLayout body = new FrameLayout(this);
        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(d(156));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(d(22));
        grid.setVerticalSpacing(d(26));
        grid.setPadding(d(26), d(18), d(26), d(26));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> openBook(items.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            bookMenu(items.get(pos));
            return true;
        });
        body.addView(grid, new FrameLayout.LayoutParams(MATCH, MATCH));

        empty = new TextView(this);
        empty.setText("Your shelf is empty.\n\nTap “Add books” and choose the PDFs you want to read.\nThey are copied into Folio, so they stay here even if you move the originals.");
        empty.setTextSize(16);
        empty.setTextColor((fg & 0x00FFFFFF) | 0xB0000000);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(0, 1.25f);
        empty.setPadding(d(40), 0, d(40), d(60));
        body.addView(empty, new FrameLayout.LayoutParams(MATCH, MATCH));

        busy = new TextView(this);
        busy.setText("Adding books…");
        busy.setTextSize(15);
        busy.setTextColor(Color.WHITE);
        busy.setPadding(d(22), d(12), d(22), d(12));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0xE0202020);
        chip.setCornerRadius(d(24));
        busy.setBackground(chip);
        busy.setVisibility(importing ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = d(36);
        body.addView(busy, blp);

        root.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setContentView(root);
    }

    private void reload() {
        items.clear();
        items.addAll(lib.list());
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

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
        new Thread(() -> {
            String error = null;
            for (Uri u : uris) {
                try {
                    lib.importUri(u);
                } catch (Throwable t) {
                    error = t.getMessage() == null ? "One file couldn't be opened" : t.getMessage();
                }
            }
            final String err = error;
            runOnUiThread(() -> {
                importing = false;
                if (busy != null) busy.setVisibility(View.GONE);
                reload();
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void bookMenu(final Library.Book b) {
        new AlertDialog.Builder(this)
                .setTitle(b.title)
                .setItems(new String[]{"Rename", "Remove from library"}, (dlg, which) -> {
                    if (which == 0) rename(b);
                    else confirmRemove(b);
                })
                .show();
    }

    private void rename(final Library.Book b) {
        final EditText input = new EditText(this);
        input.setText(b.title);
        input.setSelection(b.title.length());
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(d(22), d(8), d(22), 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(wrap)
                .setPositiveButton("Save", (dlg, which) -> {
                    String t = input.getText().toString().trim();
                    if (!t.isEmpty()) {
                        b.title = t;
                        lib.save();
                        reload();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemove(final Library.Book b) {
        new AlertDialog.Builder(this)
                .setTitle("Remove “" + b.title + "”?")
                .setMessage("This deletes Folio's copy, your place and your bookmarks. The original PDF is not touched.")
                .setPositiveButton("Remove", (dlg, which) -> {
                    covers.remove(b.id);
                    lib.remove(b);
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int d(float v) {
        return Math.round(v * dp);
    }

    private final class BookAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Cell cell = convert instanceof Cell ? (Cell) convert : new Cell(LibraryActivity.this);
            cell.bind(items.get(pos));
            return cell;
        }
    }

    private final class Cell extends LinearLayout {
        final CoverView cover;
        final ProgressLine progress;
        final TextView title, sub;

        Cell(Context c) {
            super(c);
            setOrientation(VERTICAL);
            cover = new CoverView(c);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(Color.WHITE);
            cover.setElevation(d(5));
            LayoutParams clp = new LayoutParams(MATCH, WRAP);
            clp.setMargins(d(2), d(2), d(2), d(6));
            addView(cover, clp);
            progress = new ProgressLine(c);
            LayoutParams plp = new LayoutParams(MATCH, d(3));
            plp.setMargins(d(2), d(4), d(2), 0);
            addView(progress, plp);
            title = new TextView(c);
            title.setTextSize(14);
            title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(d(2), d(8), d(2), 0);
            addView(title);
            sub = new TextView(c);
            sub.setTextSize(12);
            sub.setPadding(d(2), d(2), d(2), 0);
            addView(sub);
        }

        void bind(Library.Book b) {
            title.setText(b.title);
            title.setTextColor(fg);
            sub.setTextColor((fg & 0x00FFFFFF) | 0x99000000);
            int pct = b.pages > 0 ? Math.min(100, (b.page + 1) * 100 / b.pages) : 0;
            boolean started = b.opened > 0 && b.page > 0;
            sub.setText(!started ? b.pages + " pages" : pct >= 100 ? "Finished" : "Page " + (b.page + 1) + " of " + b.pages + "  ·  " + pct + "%");
            progress.set(started ? pct / 100f : 0f, fg);
            Bitmap bmp = covers.get(b.id);
            if (bmp == null) {
                bmp = BitmapFactory.decodeFile(lib.cover(b).getPath());
                if (bmp != null) covers.put(b.id, bmp);
            }
            cover.setImageBitmap(bmp);
        }
    }

    /** A cover slot with book proportions, whatever the column width turns out to be. */
    private static final class CoverView extends ImageView {
        CoverView(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, Math.round(w * 1.45f));
        }
    }

    private static final class ProgressLine extends View {
        private final Paint paint = new Paint();
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
            paint.setColor((color & 0x00FFFFFF) | 0x26000000);
            c.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setColor(ACCENT);
            c.drawRect(0, 0, getWidth() * fraction, getHeight(), paint);
        }
    }
}
