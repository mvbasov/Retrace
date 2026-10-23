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

    static final int WAY_ASK = 0;
    static final int WAY_AUTOMATIC = 1;
    static final int WAY_NEVER = 2;

    private static final String FILE = "settings";
    private static final String KEY_VIBRATE = "vibrate";
    private static final String KEY_THEME = "theme";
    private static final String[] THEME_IDS = {"auto", "light", "dark"};
    private static final String KEY_WAY_UPDATE = "way_update";
    private static final String[] WAY_IDS = {"ask", "automatic", "never"};

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
        return index(prefs(context).getString(KEY_THEME, THEME_IDS[THEME_AUTO]), THEME_IDS,
                THEME_AUTO);
    }

    static void setTheme(Context context, int theme) {
        prefs(context).edit().putString(KEY_THEME, THEME_IDS[theme]).apply();
    }

    // What Retrace does with a finished trip on a way. One of the WAY
    // values.
    static int wayUpdate(Context context) {
        return index(prefs(context).getString(KEY_WAY_UPDATE, WAY_IDS[WAY_ASK]), WAY_IDS, WAY_ASK);
    }

    static void setWayUpdate(Context context, int mode) {
        prefs(context).edit().putString(KEY_WAY_UPDATE, WAY_IDS[mode]).apply();
    }

    private static int index(String id, String[] ids, int fallback) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(id)) {
                return i;
            }
        }
        return fallback;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
