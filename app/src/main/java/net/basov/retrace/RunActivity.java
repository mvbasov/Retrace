package net.basov.retrace;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.basov.retrace.core.MatchResult;
import net.basov.retrace.core.RecordingSnapshot;
import net.basov.retrace.core.Replay;
import net.basov.retrace.core.Track;
import net.basov.retrace.core.TrackMatcher;
import net.basov.retrace.core.TrackPoint;

// ----------------------------------------------------------------------
// The trip screen
// ----------------------------------------------------------------------
//
// The panel at the top shows the comparison with the reference track in
// large text. The map below shows the reference track, the current track,
// the position and the ghost. A trip without a reference track shows the
// recording values and the current track only.
//
// The activity reads TrackService.snapshot() once each second, and it adds
// the new points of the current track to the map. The screen stays on while
// the activity is visible, thus the user can read it during the trip.
//
// The line above the panel tells the reference track and how the trip got
// it. During the trip, the menu selects a different reference track. A
// replay adds a line with the replayed track and the speed, and the menu
// changes the speed.
//
// After the stop, the screen keeps the last values. The stop button then
// closes the screen.
//
// The camera at the start of a trip:
// - A trip with the GPS shows the reference track until the first point.
//   At the first point, the map follows the position, as after a push on
//   Me, and zooms in to at least FIRST_FIX_ZOOM.
// - A replay shows the whole replayed track and the reference track, as
//   after a push on All.
// After a rotation of the screen, the camera of the user stays.
public final class RunActivity extends ThemedActivity {

    private static final long REFRESH_MS = 1000;
    private static final int MENU_SELECT = 1;
    private static final int MENU_SPEED = 2;
    private static final String STATE_FOLLOW = "follow";
    private static final String STATE_LAT = "lat";
    private static final String STATE_LON = "lon";
    private static final String STATE_ZOOM = "zoom";
    private static final String STATE_TRIP = "trip";

    // A zoom level that shows the streets near the position.
    private static final double FIRST_FIX_ZOOM = 16;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();

    private TileLoader loader;
    private MapView map;
    private TextView referenceView;
    private TextView headline;
    private TextView detail1;
    private TextView detail2;
    private TextView detail3;
    private Button stopButton;
    private Button followButton;

    private int shownPoints;
    private long shownStartMs = Long.MIN_VALUE;
    private String shownReference;
    private String shownReferenceName;
    private boolean shownRecording;
    private int shownSpeed;
    private boolean cameraRestored;
    // The start time of the trip of the restored camera.
    private long restoredTripMs = Long.MIN_VALUE;
    // True from the start of a trip with the GPS to its first point.
    private boolean waitingForFirstFix;
    private volatile boolean destroyed;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            show(TrackService.snapshot());
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        loader = new TileLoader(this);
        map = new MapView(this, loader);
        setContentView(buildViews());

        if (savedInstanceState != null) {
            cameraRestored = true;
            restoredTripMs = savedInstanceState.getLong(STATE_TRIP, Long.MIN_VALUE);
            map.setCamera(savedInstanceState.getDouble(STATE_LAT),
                    savedInstanceState.getDouble(STATE_LON),
                    savedInstanceState.getDouble(STATE_ZOOM));
            map.setFollow(savedInstanceState.getBoolean(STATE_FOLLOW, true));
        } else {
            map.setFollow(true);
        }
        map.setFollowListener(on -> followButton.setEnabled(!on));
        followButton.setEnabled(!map.isFollow());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_FOLLOW, map.isFollow());
        out.putDouble(STATE_LAT, map.centerLatitude());
        out.putDouble(STATE_LON, map.centerLongitude());
        out.putDouble(STATE_ZOOM, map.zoom());
        out.putLong(STATE_TRIP, shownStartMs);
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

        referenceView = new TextView(this);
        referenceView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        headline = new TextView(this);
        headline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        detail1 = detailView();
        detail2 = detailView();
        detail3 = detailView();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), pad, dp(16), pad);
        panel.addView(referenceView);
        panel.addView(headline);
        panel.addView(detail1);
        panel.addView(detail2);
        panel.addView(detail3);

        Button zoomIn = mapButton(R.string.map_zoom_in);
        zoomIn.setOnClickListener(v -> map.zoomIn());
        Button zoomOut = mapButton(R.string.map_zoom_out);
        zoomOut.setOnClickListener(v -> map.zoomOut());
        Button all = mapButton(R.string.map_show_all);
        all.setOnClickListener(v -> map.showAll());
        followButton = mapButton(R.string.map_follow);
        followButton.setOnClickListener(v -> map.setFollow(true));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.addView(zoomIn);
        buttons.addView(zoomOut);
        buttons.addView(all);
        buttons.addView(followButton);

        FrameLayout mapFrame = new FrameLayout(this);
        mapFrame.addView(map, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.TOP);
        corner.setMargins(pad, pad, pad, pad);
        mapFrame.addView(buttons, corner);

        stopButton = new Button(this);
        stopButton.setText(R.string.stop_recording);
        stopButton.setOnClickListener(v -> onStopOrClose());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(panel);
        root.addView(mapFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(stopButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void onStopOrClose() {
        if (TrackService.snapshot().recording) {
            startService(new Intent(this, TrackService.class).setAction(TrackService.ACTION_STOP));
        } else {
            finish();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_SELECT, Menu.NONE, R.string.select_reference);
        menu.add(Menu.NONE, MENU_SPEED, Menu.NONE, R.string.replay_speed);
        return true;
    }

    // The selection needs a running trip. The speed needs a running replay.
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        RecordingSnapshot s = TrackService.snapshot();
        menu.findItem(MENU_SELECT).setVisible(s.recording);
        menu.findItem(MENU_SPEED).setVisible(s.recording && s.isReplay());
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SELECT) {
            selectReference();
            return true;
        }
        if (item.getItemId() == MENU_SPEED) {
            selectSpeed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // The new reference track applies to the trip from its first point.
    private void selectReference() {
        ReferenceDialog.show(this, files, TrackService.snapshot().profile,
                (automatic, path, reversed) -> startService(ReferenceDialog.putSelection(
                        new Intent(this, TrackService.class).setAction(TrackService.ACTION_SELECT),
                        automatic, path, reversed)));
    }

    // The speed applies from the next point of the replay.
    private void selectSpeed() {
        int current = TrackService.snapshot().replaySpeed;
        String[] labels = new String[Replay.SPEEDS.length];
        int checked = -1;
        for (int i = 0; i < Replay.SPEEDS.length; i++) {
            labels[i] = getString(R.string.replay_speed_value, Replay.SPEEDS[i]);
            if (Replay.SPEEDS[i] == current) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.replay_speed)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    startService(new Intent(this, TrackService.class)
                            .setAction(TrackService.ACTION_REPLAY_SPEED)
                            .putExtra(TrackService.EXTRA_SPEED, Replay.SPEEDS[which]));
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // Shows one snapshot: the panel, the new points and the markers. After
    // the stop, the offer of a way can come.
    private void show(RecordingSnapshot s) {
        WayUpdater.Offer offer = TrackService.takeWayOffer();
        if (offer != null) {
            WayDialogs.showOffer(this, files, offer, () -> { });
        }
        if (s.recording != shownRecording || s.replaySpeed != shownSpeed) {
            shownRecording = s.recording;
            shownSpeed = s.replaySpeed;
            invalidateOptionsMenu();
        }
        stopButton.setText(s.recording ? R.string.stop_recording : R.string.close);
        if (s.recording) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        if (s.startTimeMs != shownStartMs) {
            // The first snapshot of this screen, or a new trip while the
            // screen was open. The restored camera belongs to one trip.
            boolean restored = cameraRestored && s.startTimeMs == restoredTripMs;
            shownStartMs = s.startTimeMs;
            shownPoints = 0;
            map.setCurrent(new ArrayList<TrackPoint>());
            map.setFitPoints(new ArrayList<TrackPoint>());
            cameraRestored = restored;
            startCamera(s, !restored);
        }
        loadReference(s.referencePath);
        String reference = referenceText(s);
        if (s.referenceReversed && s.referencePath != null) {
            reference += "\n" + getString(R.string.opposite_warning);
        }
        referenceView.setText(s.isReplay() ? getString(R.string.replay_line, s.replayName,
                s.replaySpeed) + "\n" + reference : reference);
        List<TrackPoint> fresh = TrackService.pointsFrom(shownPoints);
        for (TrackPoint p : fresh) {
            map.addCurrent(p);
        }
        shownPoints += fresh.size();
        if (!fresh.isEmpty()) {
            TrackPoint last = fresh.get(fresh.size() - 1);
            map.setPosition(last.latitude, last.longitude);
            if (waitingForFirstFix) {
                waitingForFirstFix = false;
                map.setCamera(last.latitude, last.longitude,
                        Math.max(map.zoom(), FIRST_FIX_ZOOM));
                map.setFollow(true);
            }
        }

        MatchResult m = s.match;
        headline.setText(TripText.headline(this, s));
        headline.setTextColor(getResources().getColor(headlineColor(s), null));
        if (m == null) {
            detail1.setText(s.hasFix() ? "" : getString(R.string.waiting_for_gps));
            detail2.setText("");
            detail3.setText(s.recording ? "" : stoppedText(s));
            map.setGhost(Double.NaN, Double.NaN);
            return;
        }
        boolean compared = m.state == TrackMatcher.State.ON_ROUTE
                || m.state == TrackMatcher.State.FINISHED;
        detail1.setText(compared ? TripText.ghost(this, m.deltaDistanceM) : "");
        detail2.setText(compared ? TripText.speeds(this, m) : "");
        String stopped = s.recording ? "" : stoppedText(s);
        detail3.setText(m.state == TrackMatcher.State.ON_ROUTE
                ? TripText.toFinish(this, m) : stopped);
        if (m.started() && m.state != TrackMatcher.State.FINISHED) {
            map.setGhost(m.ghostLatitude, m.ghostLongitude);
        } else {
            map.setGhost(Double.NaN, Double.NaN);
        }
    }

    // Green while ahead, red while behind or off route.
    private static int headlineColor(RecordingSnapshot s) {
        MatchResult m = s.match;
        if (m == null || s.smoothedDeltaMs == MatchResult.UNKNOWN) {
            return m != null && m.state == TrackMatcher.State.OFF_ROUTE
                    ? R.color.trip_behind : R.color.trip_neutral;
        }
        if (m.state == TrackMatcher.State.OFF_ROUTE) {
            return R.color.trip_behind;
        }
        if (m.state != TrackMatcher.State.ON_ROUTE && m.state != TrackMatcher.State.FINISHED) {
            return R.color.trip_neutral;
        }
        if (Math.abs(s.smoothedDeltaMs) < 1000) {
            return R.color.trip_neutral;
        }
        return s.smoothedDeltaMs < 0 ? R.color.trip_ahead : R.color.trip_behind;
    }

    private String stoppedText(RecordingSnapshot s) {
        return getString(s.isReplay() ? R.string.replay_done : R.string.trip_stopped);
    }

    // The camera at the start of a trip. See the comment at the top. A
    // restored camera stays, but a replay still needs its fit points.
    private void startCamera(RecordingSnapshot s, boolean move) {
        waitingForFirstFix = false;
        if (s.isReplay()) {
            if (move) {
                map.showAll();
            }
            loadFitPoints(s.replayPath, s.startTimeMs, move);
        } else if (move && s.recording && s.points == 0) {
            // The reference track shows until the first point. See the
            // read of the reference track.
            waitingForFirstFix = true;
            map.setFollow(false);
        } else if (move) {
            map.setFollow(true);
        }
    }

    // Reads the replayed track for the fit of All, on the background thread.
    private void loadFitPoints(String path, long tripMs, boolean fit) {
        if (path == null) {
            return;
        }
        TrackStore store = new TrackStore(this);
        files.execute(() -> {
            Track track;
            try {
                track = store.load(new File(path));
            } catch (IOException e) {
                return;
            }
            runOnUiThread(() -> {
                if (destroyed || tripMs != shownStartMs) {
                    return;
                }
                map.setFitPoints(track.points);
                if (fit && !map.isFollow()) {
                    map.showAll();
                }
            });
        });
    }

    // The line above the panel.
    private String referenceText(RecordingSnapshot s) {
        String name = shownReferenceName == null
                ? getString(R.string.value_unknown) : shownReferenceName;
        if (s.referenceReversed) {
            name = getString(R.string.reference_opposite, name);
        }
        switch (s.selection) {
            case RecordingSnapshot.SELECTION_SEARCH:
                return getString(R.string.reference_search);
            case RecordingSnapshot.SELECTION_TEMPORARY:
                return getString(R.string.reference_temporary, name);
            case RecordingSnapshot.SELECTION_AUTOMATIC:
            case RecordingSnapshot.SELECTION_MANUAL:
                return getString(R.string.reference_selected, name);
            case RecordingSnapshot.SELECTION_NOT_FOUND:
                return getString(R.string.reference_not_found);
            case RecordingSnapshot.SELECTION_OFF:
            default:
                return getString(R.string.select_none);
        }
    }

    // Reads the reference track once, on the background thread. During the
    // automatic selection, the path can change before the read is complete.
    // Then the result of the old read is not used.
    private void loadReference(String path) {
        if (path == null) {
            if (shownReference != null) {
                shownReference = null;
                shownReferenceName = null;
                setTitle(R.string.app_name);
                map.setReference(new ArrayList<TrackPoint>());
            }
            return;
        }
        if (path.equals(shownReference)) {
            return;
        }
        shownReference = path;
        shownReferenceName = null;
        TrackStore store = new TrackStore(this);
        files.execute(() -> {
            Track track;
            try {
                track = store.load(new File(path));
            } catch (IOException e) {
                return;
            }
            runOnUiThread(() -> {
                if (destroyed || !path.equals(shownReference)) {
                    return;
                }
                shownReferenceName = track.name;
                setTitle(track.name);
                map.setReference(track.points);
                if (!cameraRestored && !map.isFollow()) {
                    map.showAll();
                }
            });
        });
    }

    private TextView detailView() {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        return t;
    }

    private Button mapButton(int textId) {
        Button b = new Button(this);
        b.setText(textId);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setMinimumWidth(dp(48));
        return b;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }
}
