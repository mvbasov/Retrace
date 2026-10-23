package net.basov.retrace;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

// ----------------------------------------------------------------------
// The base class of each screen: the color theme
// ----------------------------------------------------------------------
//
// The resources have a light set in values/ and a dark set in
// values-night/. Android selects the set from the night mode of the
// configuration. For the light theme and the dark theme of the settings,
// this class overrides the night mode of the screen. Then the theme and
// each color follow the setting. The automatic theme changes nothing.
//
// A screen reads the setting once, before Android makes its resources. A
// change of the setting thus needs a new screen. onResume makes it.
abstract class ThemedActivity extends Activity {

    private int appliedTheme;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        appliedTheme = AppSettings.theme(base);
        if (appliedTheme != AppSettings.THEME_AUTO) {
            // An empty configuration changes no other value. The font scale
            // of a new configuration is 1, thus 0 keeps the font scale of
            // the device.
            Configuration override = new Configuration();
            override.fontScale = 0;
            override.uiMode = appliedTheme == AppSettings.THEME_DARK
                    ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
            applyOverrideConfiguration(override);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (AppSettings.theme(this) != appliedTheme) {
            recreate();
        }
    }
}
