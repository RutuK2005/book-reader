package app.folio.reader;

import android.content.Context;
import android.content.SharedPreferences;

/** Preference keys shared by the library and the reader. */
final class P {
    static final String THEME = "theme";
    static final String ANIM = "anim";
    static final String CROP = "crop";
    static final String DUAL = "dual";
    static final String BRIGHT = "bright";
    static final String WARMTH = "warmth";
    static final String VOLKEYS = "volkeys";
    static final String CLOCK = "clock";
    static final String HINT = "hint_shown";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("folio", Context.MODE_PRIVATE);
    }

    private P() {}
}
