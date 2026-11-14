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
    static final String SOUND = "page_sound";
    static final String AUTO_NIGHT = "auto_night";
    static final String AUTO_SEC = "auto_turn_sec";
    static final String GOAL_MIN = "goal_min";
    static final String SHELF = "shelf_filter";

    /** The theme to read in right now: Night after dark if the reader asked for that. */
    static int theme(SharedPreferences p) {
        int chosen = p.getInt(THEME, 1);
        if (!p.getBoolean(AUTO_NIGHT, false)) return chosen;
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return (hour >= 21 || hour < 6) ? 5 : chosen;
    }

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("folio", Context.MODE_PRIVATE);
    }

    private P() {}
}
