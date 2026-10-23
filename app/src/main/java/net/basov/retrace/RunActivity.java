package net.basov.retrace;

import android.app.Activity;
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
import net.basov.retrace.core.Profile;
import net.basov.retrace.core.RecordingSnapshot;
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
// it. During the trip, the menu selects a different reference track.
//
// After the stop, the screen keeps the last values. The stop button then
// closes the screen.
public final class RunActivity extends Activity {

    private static final long REFRESH_MS = 1000;
    private static final int MENU_SELECT = 1;
    private static final String STATE_FOLLOW = "follow";
    private static final String STATE_LAT = "lat";
    private static final String STATE_LON = "lon";
    private static final String STATE_ZOOM = "zoom";

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
    private boolean cameraRestored;
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
        return true;
    }

    // The selection needs a running trip.
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(MENU_SELECT).setVisible(TrackService.snapshot().recording);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SELECT) {
            selectReference();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // Reads the saved tracks of the profile of the trip on the background
    // thread, then shows them in a dialog. The first two lines of the dialog
    // are the automatic selection and no comparison.
    private void selectReference() {
        final Profile profile = TrackService.snapshot().profile;
        TrackStore store = new TrackStore(this);
        files.execute(() -> {
            List<TrackStore.Entry> tracks = new ArrayList<>();
            for (TrackStore.Entry e : store.list()) {
                if (e.readable && e.profile == profile && e.points >= TrackStore.MIN_POINTS) {
                    tracks.add(e);
                }
            }
            runOnUiThread(() -> {
                if (!destroyed) {
                    showSelectDialog(tracks);
                }
            });
        });
    }

    private void showSelectDialog(List<TrackStore.Entry> tracks) {
        String[] items = new String[tracks.size() + 2];
        items[0] = getString(R.string.select_automatic);
        items[1] = getString(R.string.select_none);
        for (int i = 0; i < tracks.size(); i++) {
            TrackStore.Entry e = tracks.get(i);
            items[i + 2] = getString(R.string.select_item, e.name, Labels.distance(this, e.distanceM));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.select_reference)
                .setItems(items, (d, which) -> {
                    Intent select = new Intent(this, TrackService.class)
                            .setAction(TrackService.ACTION_SELECT);
                    if (which == 0) {
                        select.putExtra(TrackService.EXTRA_AUTOMATIC, true);
                    } else if (which >= 2) {
                        select.putExtra(TrackService.EXTRA_REFERENCE,
                                tracks.get(which - 2).file.getPath());
                    }
                    startService(select);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // Shows one snapshot: the panel, the new points and the markers.
    private void show(RecordingSnapshot s) {
        if (s.recording != shownRecording) {
            shownRecording = s.recording;
            invalidateOptionsMenu();
        }
        stopButton.setText(s.recording ? R.string.stop_recording : R.string.close);
        if (s.recording) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        if (s.startTimeMs != shownStartMs) {
            // A new trip started while the screen was open.
            shownStartMs = s.startTimeMs;
            shownPoints = 0;
            map.setCurrent(new ArrayList<TrackPoint>());
        }
        loadReference(s.referencePath);
        referenceView.setText(referenceText(s));
        List<TrackPoint> fresh = TrackService.pointsFrom(shownPoints);
        for (TrackPoint p : fresh) {
            map.addCurrent(p);
        }
        shownPoints += fresh.size();
        if (!fresh.isEmpty()) {
            TrackPoint last = fresh.get(fresh.size() - 1);
            map.setPosition(last.latitude, last.longitude);
        }

        MatchResult m = s.match;
        headline.setText(TripText.headline(this, s));
        headline.setTextColor(getResources().getColor(headlineColor(s), null));
        if (m == null) {
            detail1.setText(s.hasFix() ? "" : getString(R.string.waiting_for_gps));
            detail2.setText("");
            detail3.setText(s.recording ? "" : getString(R.string.trip_stopped));
            map.setGhost(Double.NaN, Double.NaN);
            return;
        }
        boolean compared = m.state == TrackMatcher.State.ON_ROUTE
                || m.state == TrackMatcher.State.FINISHED;
        detail1.setText(compared ? TripText.ghost(this, m.deltaDistanceM) : "");
        detail2.setText(compared ? TripText.speeds(this, m) : "");
        String stopped = s.recording ? "" : getString(R.string.trip_stopped);
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

    // The line above the panel.
    private String referenceText(RecordingSnapshot s) {
        String name = shownReferenceName == null
                ? getString(R.string.value_unknown) : shownReferenceName;
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
