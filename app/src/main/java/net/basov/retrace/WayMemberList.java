package net.basov.retrace;

import android.app.Activity;
import android.content.Intent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.basov.retrace.core.TimeText;
import net.basov.retrace.core.Track;
import net.basov.retrace.core.TrackPoint;

// ----------------------------------------------------------------------
// The list of the tracks of a way
// ----------------------------------------------------------------------
//
// The track screen of a way shows the tracks that WayBuilder used for it.
// Each line has the color of the track on the map. A push on a line opens
// the track screen of that track. The back key then goes back to the way.
//
// The way keeps the file names of its tracks. A rename changes the name in
// the file, not the file name. Thus the link stays correct. A track that the
// user removed after the build of the way shows "not found", and a push on
// it does nothing.
//
// The tracks are hidden at first: the lines on the map and the list. The
// check box above the list shows them. The map of a way with many tracks is
// hard to read, and the line of the way is the main content.
//
// The list is at most MAX_HEIGHT_DP high, and at most 30 percent of the
// screen. A longer list scrolls. Thus the map stays large.
final class WayMemberList {

    // Tells the screen that the user showed or hid the tracks.
    interface ShownListener {
        void onShown(boolean shown);
    }

    private static final int MAX_HEIGHT_DP = 168;

    // One track of a way. The track is null if Retrace cannot read it.
    static final class Member {
        final String fileName;
        final File file;
        final Track track;

        Member(String fileName, File file, Track track) {
            this.fileName = fileName;
            this.file = file;
            this.track = track;
        }
    }

    private WayMemberList() {
    }

    // Reads the tracks of the way. Call it on a background thread.
    static List<Member> read(TrackStore store, Track way) {
        List<Member> members = new ArrayList<>();
        for (String name : way.members) {
            File file = store.trackFile(name);
            Track track = null;
            if (file.isFile()) {
                try {
                    track = store.load(file);
                } catch (IOException | IllegalArgumentException e) {
                    // The line shows "not found".
                }
            }
            members.add(new Member(name, file, track));
        }
        return members;
    }

    // The points of each track for MapView.setMembers. A track that
    // Retrace cannot read gives an empty list. Thus the colors agree with
    // the lines of the list.
    static List<List<TrackPoint>> points(List<Member> members) {
        List<List<TrackPoint>> points = new ArrayList<>();
        for (Member m : members) {
            points.add(m.track != null ? m.track.points : new ArrayList<TrackPoint>());
        }
        return points;
    }

    // The check box and the lines, with the colors of the map. The check
    // box also sets the tracks of the map.
    static View build(Activity activity, List<Member> members, MapView map, boolean shown,
                      ShownListener listener) {
        int pad = dp(activity, 16);

        CheckBox title = new CheckBox(activity);
        title.setText(activity.getString(R.string.way_members_title, members.size()));
        title.setSaveEnabled(false);
        LinearLayout.LayoutParams titleMargin = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleMargin.setMargins(pad / 2, 0, pad, 0);

        LinearLayout lines = new LinearLayout(activity);
        lines.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < members.size(); i++) {
            lines.addView(line(activity, members.get(i), map.memberColor(i)));
        }

        ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int max = Math.min(dp(activity, MAX_HEIGHT_DP),
                        getResources().getDisplayMetrics().heightPixels * 3 / 10);
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(lines);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(title, titleMargin);
        box.addView(scroll);

        List<List<TrackPoint>> points = points(members);
        title.setChecked(shown);
        show(scroll, map, points, shown);
        title.setOnCheckedChangeListener((b, checked) -> {
            show(scroll, map, points, checked);
            listener.onShown(checked);
        });
        return box;
    }

    private static void show(View list, MapView map, List<List<TrackPoint>> points,
                             boolean shown) {
        list.setVisibility(shown ? View.VISIBLE : View.GONE);
        map.setMembers(shown ? points : new ArrayList<List<TrackPoint>>());
    }

    private static View line(Activity activity, Member m, int color) {
        int pad = dp(activity, 16);

        View mark = new View(activity);
        mark.setBackgroundColor(color);
        LinearLayout.LayoutParams markSize = new LinearLayout.LayoutParams(
                dp(activity, 24), dp(activity, 4));
        markSize.setMargins(0, 0, dp(activity, 12), 0);

        TextView text = new TextView(activity);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        if (m.track != null) {
            text.setText(activity.getString(R.string.way_member_item, m.track.name,
                    Labels.distance(activity, m.track.distanceM()),
                    TimeText.duration(m.track.durationMs())));
        } else {
            text.setText(activity.getString(R.string.way_member_missing, m.fileName));
        }

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, dp(activity, 8), pad, dp(activity, 8));
        row.addView(mark, markSize);
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (m.track != null) {
            TypedValue ripple = new TypedValue();
            activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                    ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            row.setOnClickListener(v -> activity.startActivity(
                    new Intent(activity, TrackMapActivity.class)
                            .putExtra(TrackMapActivity.EXTRA_FILE, m.file.getPath())));
        }
        return row;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, activity.getResources().getDisplayMetrics()));
    }
}
