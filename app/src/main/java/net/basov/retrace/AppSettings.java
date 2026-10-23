package net.basov.retrace;

import android.content.Context;
import android.content.SharedPreferences;

// ----------------------------------------------------------------------
// The settings of the user
// ----------------------------------------------------------------------
//
// SettingsActivity changes them. The other classes read them with the
// static methods of this class. One preference file holds all settings.
//
// The stored values are text ids, not numbers. A later release can then
// add a value and still read the old file.
final class AppSettings {

    static final int THEME_AUTO = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_DARK = 2;

    private static final String FILE = "settings";
    private static final String KEY_VIBRATE = "vibrate";
    private static final String KEY_THEME = "theme";
    private static final String[] THEME_IDS = {"auto", "light", "dark"};

    private AppSettings() {
    }

    // The device vibrates at the finish of a trip.
    static boolean vibrate(Context context) {
        return prefs(context).getBoolean(KEY_VIBRATE, true);
    }

    static void setVibrate(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_VIBRATE, on).apply();
    }

    // One of the THEME values. THEME_AUTO follows the night mode of the
    // system. Android has the system night mode from Android 10.
    static int theme(Context context) {
        String id = prefs(context).getString(KEY_THEME, THEME_IDS[THEME_AUTO]);
        for (int i = 0; i < THEME_IDS.length; i++) {
            if (THEME_IDS[i].equals(id)) {
                return i;
            }
        }
        return THEME_AUTO;
    }

    static void setTheme(Context context, int theme) {
        prefs(context).edit().putString(KEY_THEME, THEME_IDS[theme]).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
