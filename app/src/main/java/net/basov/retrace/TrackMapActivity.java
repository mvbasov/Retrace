package net.basov.retrace;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.basov.retrace.core.Profile;
import net.basov.retrace.core.TimeText;
import net.basov.retrace.core.Track;

// ----------------------------------------------------------------------
// A saved track on the map
// ----------------------------------------------------------------------
//
// A push on a track in the list opens this screen. It shows the track on
// the map, and its profile, distance and duration. An arrow marks the
// start and points in the direction of travel. A square marks the finish.
// The buttons zoom in, zoom out and show the whole track.
//
// The Replay button at the bottom starts a replay of the track. It is
// available after the read of the track, and not during a trip. The Menu
// button beside it shows the full menu of the track. See TrackActions.
// After a change, the screen reads the track again. After a delete, the
// screen closes.
//
// For a way, a check box below the line of values shows the tracks of the
// way. The map then draws them, and a list names them. A push on a name
// opens that track. The tracks are hidden at first. See WayMemberList.
//
// The screen keeps the center and the zoom of the map when Android makes the
// activity again, for example after a turn of the device.
public final class TrackMapActivity extends ThemedActivity {

    static final String EXTRA_FILE = "net.basov.retrace.extra.FILE";

    private static final String STATE_LAT = "lat";
    private static final String STATE_LON = "lon";
    private static final String STATE_ZOOM = "zoom";
    private static final String STATE_MEMBERS = "members";

    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private TileLoader loader;
    private MapView map;
    private TextView info;
    private FrameLayout memberFrame;
    // True if the map shows the tracks of the way. They are hidden at first.
    private boolean membersShown;
    private Button replayButton;
    private Button menuButton;
    private String path;
    // The line of the track in the menu, after the read.
    private TrackStore.Entry entry;
    // The profile of the track, after the read. Null before the read or
    // for a track from another application with no profile.
    private Profile trackProfile;
    private boolean replayable;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loader = new TileLoader(this);
        map = new MapView(this, loader);
        map.setEndMarks(true);
        setContentView(buildViews());

        if (savedInstanceState != null) {
            membersShown = savedInstanceState.getBoolean(STATE_MEMBERS, false);
            map.setCamera(savedInstanceState.getDouble(STATE_LAT),
                    savedInstanceState.getDouble(STATE_LON),
                    savedInstanceState.getDouble(STATE_ZOOM));
        }
        path = getIntent().getStringExtra(EXTRA_FILE);
        if (path == null) {
            finish();
            return;
        }
        load(new File(path), savedInstanceState == null);
    }

    // A track of a way can change on its own screen. Thus the screen of the
    // way reads the files again when it comes back.
    @Override
    protected void onRestart() {
        super.onRestart();
        if (path != null) {
            load(new File(path), false);
        }
    }

    // A trip can start or stop while this screen is in the background.
    @Override
    protected void onResume() {
        super.onResume();
        updateReplayButton();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putDouble(STATE_LAT, map.centerLatitude());
        out.putDouble(STATE_LON, map.centerLongitude());
        out.putDouble(STATE_ZOOM, map.zoom());
        out.putBoolean(STATE_MEMBERS, membersShown);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        files.shutdown();
        loader.close();
        super.onDestroy();
    }

    private View buildViews() {
        int pad = dp(8);

        info = new TextView(this);
        info.setPadding(dp(16), pad, dp(16), pad);
        info.setText(R.string.map_loading);

        Button zoomIn = button(R.string.map_zoom_in);
        zoomIn.setOnClickListener(v -> map.zoomIn());
        Button zoomOut = button(R.string.map_zoom_out);
        zoomOut.setOnClickListener(v -> map.zoomOut());
        Button all = button(R.string.map_show_all);
        all.setOnClickListener(v -> map.showAll());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.addView(zoomIn);
        buttons.addView(zoomOut);
        buttons.addView(all);

        FrameLayout mapFrame = new FrameLayout(this);
        mapFrame.addView(map, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.TOP);
        corner.setMargins(pad, pad, pad, pad);
        mapFrame.addView(buttons, corner);

        replayButton = new Button(this);
        replayButton.setText(R.string.replay);
        replayButton.setOnClickListener(v -> onReplay());
        menuButton = new Button(this);
        menuButton.setText(R.string.track_menu);
        menuButton.setEnabled(false);
        menuButton.setOnClickListener(v -> {
            if (entry != null) {
                TrackActions.showFull(this, files, entry, this::onTrackChanged);
            }
        });

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.addView(replayButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(menuButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        memberFrame = new FrameLayout(this);
        root.addView(info);
        root.addView(memberFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(mapFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottom, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        updateReplayButton();
        return root;
    }

    private void onTrackChanged(int change) {
        if (change == TrackActions.DELETED) {
            finish();
        } else {
            load(new File(path), false);
        }
    }

    // The service starts no replay during a trip. Thus the button checks
    // the state again.
    private void onReplay() {
        updateReplayButton();
        if (replayButton.isEnabled()) {
            ReplayStarter.ask(this, files, path, trackProfile);
        }
    }

    private void updateReplayButton() {
        replayButton.setEnabled(replayable && !TrackService.snapshot().recording);
    }

    // Reads the track on the background thread, and for a way also its
    // tracks. At the first start, the map then shows the whole track.
    private void load(File file, boolean fit) {
        TrackStore store = new TrackStore(this);
        files.execute(() -> {
            Track track;
            try {
                track = store.load(file);
            } catch (IOException e) {
                track = null;
            }
            final Track result = track;
            final List<WayMemberList.Member> members = track != null && track.isWay()
                    ? WayMemberList.read(store, track) : null;
            runOnUiThread(() -> {
                if (destroyed) {
                    return;
                }
                if (result == null || result.points.isEmpty()) {
                    info.setText(R.string.track_unreadable);
                    return;
                }
                setTitle(result.name);
                entry = new TrackStore.Entry(file, result);
                menuButton.setEnabled(true);
                trackProfile = result.profile;
                replayable = result.points.size() >= TrackStore.MIN_POINTS;
                updateReplayButton();
                info.setText(getString(R.string.track_details,
                        Labels.profile(this, result.profile),
                        Labels.distance(this, result.distanceM()),
                        TimeText.duration(result.durationMs())));
                if (members != null) {
                    memberFrame.removeAllViews();
                    memberFrame.addView(WayMemberList.build(this, members, map, membersShown,
                            shown -> membersShown = shown));
                }
                map.setReference(result.points);
                if (fit) {
                    map.showAll();
                }
            });
        });
    }

    private Button button(int textId) {
        Button b = new Button(this);
        b.setText(textId);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setMinimumWidth(dp(48));
        return b;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }
}
