package net.basov.retrace;

import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

// ----------------------------------------------------------------------
// The settings screen
// ----------------------------------------------------------------------
//
// The screen has framework widgets only. A preference screen of the
// framework is deprecated, and the AndroidX preference library is a
// dependency. Each change goes to AppSettings at once.
public final class SettingsActivity extends ThemedActivity {

    private static final int[] THEME_TEXT = {
            R.string.theme_auto, R.string.theme_light, R.string.theme_dark};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(16);

        Switch vibrate = new Switch(this);
        vibrate.setText(R.string.settings_vibrate);
        vibrate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        vibrate.setChecked(AppSettings.vibrate(this));
        vibrate.setOnCheckedChangeListener((v, on) -> AppSettings.setVibrate(this, on));

        TextView themeTitle = new TextView(this);
        themeTitle.setText(R.string.settings_theme);
        themeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        themeTitle.setPadding(0, pad, 0, 0);

        RadioGroup themes = new RadioGroup(this);
        int current = AppSettings.theme(this);
        for (int i = 0; i < THEME_TEXT.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(i + 1);
            b.setText(THEME_TEXT[i]);
            themes.addView(b);
        }
        themes.check(current + 1);
        // A new theme needs a new screen. See ThemedActivity.
        themes.setOnCheckedChangeListener((g, id) -> {
            AppSettings.setTheme(this, id - 1);
            recreate();
        });

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);
        column.addView(vibrate);
        column.addView(themeTitle);
        column.addView(themes);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }
}
