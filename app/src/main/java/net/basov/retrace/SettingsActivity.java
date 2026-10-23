package net.basov.retrace;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
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
    private static final int[] WAY_TEXT = {
            R.string.way_update_ask, R.string.way_update_automatic, R.string.way_update_never};

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

        // A new theme needs a new screen. See ThemedActivity.
        RadioGroup themes = choices(THEME_TEXT, AppSettings.theme(this), index -> {
            if (index != AppSettings.theme(this)) {
                AppSettings.setTheme(this, index);
                recreate();
            }
        });

        TextView wayTitle = new TextView(this);
        wayTitle.setText(R.string.settings_way);
        wayTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        wayTitle.setPadding(0, pad, 0, 0);

        RadioGroup ways = choices(WAY_TEXT, AppSettings.wayUpdate(this),
                index -> AppSettings.setWayUpdate(this, index));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);
        column.addView(vibrate);
        column.addView(themeTitle);
        column.addView(themes);
        column.addView(wayTitle);
        column.addView(ways);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
    }

    // The index of the selected button in a group.
    private interface Choice {
        void onSelect(int index);
    }

    // A group with one button for each text. Each button gets its own id.
    // Android keeps the state of a view by its id, and two groups with the
    // same ids get each other's state after recreate(). The buttons also
    // keep no state, because the screen reads each value from AppSettings.
    private RadioGroup choices(int[] texts, int selected, Choice choice) {
        RadioGroup group = new RadioGroup(this);
        group.setSaveEnabled(false);
        int[] ids = new int[texts.length];
        for (int i = 0; i < texts.length; i++) {
            RadioButton b = new RadioButton(this);
            ids[i] = View.generateViewId();
            b.setId(ids[i]);
            b.setText(texts[i]);
            b.setSaveEnabled(false);
            group.addView(b);
        }
        group.check(ids[selected]);
        group.setOnCheckedChangeListener((g, id) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == id) {
                    choice.onSelect(i);
                }
            }
        });
        return group;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }
}
