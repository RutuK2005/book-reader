package app.folio.reader;

import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;

/**
 * Reading themes. A PDF page is rendered once as black-on-white; a theme is a per-channel linear
 * map applied at draw time (white becomes BG, black becomes FG), so switching themes never
 * re-renders a page. Dark themes fall out of the same formula with a negative slope.
 */
final class Themes {
    static final String[] NAMES = {"White", "Paper", "Sepia", "Green", "Gray", "Night"};
    static final int[] BG = {0xFFFFFFFF, 0xFFFAF5E9, 0xFFF3E7CC, 0xFFCFE8CF, 0xFF2C2C2E, 0xFF000000};
    static final int[] FG = {0xFF000000, 0xFF1D1B18, 0xFF50392A, 0xFF1D2E1F, 0xFFD0D0D0, 0xFF9A9A9A};
    static final int COUNT = NAMES.length;

    static boolean isDark(int t) {
        return t >= 4;
    }

    /** Warmth 0..1 pulls blue (and a little green) out of the picture, like a night-light filter. */
    private static float warmG(float w) {
        return 1f - 0.10f * w;
    }

    private static float warmB(float w) {
        return 1f - 0.42f * w;
    }

    static ColorMatrixColorFilter filter(int t, float warmth) {
        if (t == 0 && warmth <= 0f) return null;
        int bg = BG[t], fg = FG[t];
        float[] m = new float[20];
        row(m, 0, Color.red(fg), Color.red(bg), 1f);
        row(m, 1, Color.green(fg), Color.green(bg), warmG(warmth));
        row(m, 2, Color.blue(fg), Color.blue(bg), warmB(warmth));
        m[18] = 1f;
        return new ColorMatrixColorFilter(m);
    }

    private static void row(float[] m, int r, int lo, int hi, float w) {
        m[r * 5 + r] = (hi - lo) / 255f * w;
        m[r * 5 + 4] = lo * w;
    }

    static int tint(int color, float warmth) {
        return Color.argb(Color.alpha(color), Color.red(color),
                Math.round(Color.green(color) * warmG(warmth)),
                Math.round(Color.blue(color) * warmB(warmth)));
    }

    static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private Themes() {}
}
