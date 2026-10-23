package net.basov.retrace;

import android.app.Activity;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.basov.retrace.core.TimeText;
import net.basov.retrace.core.Track;

// ----------------------------------------------------------------------
// A saved track on the map
// ----------------------------------------------------------------------
//
// A push on a track in the list opens this screen. It shows the track on
// the map, and its profile, distance and duration. The buttons zoom in, zoom
// out and show the whole track.
//
// The screen keeps the center and the zoom of the map when Android makes the
// activity again, for example after a turn of the device.
public final class TrackMapActivity extends Activity {

    static final String EXTRA_FILE = "net.basov.retrace.extra.FILE";

    private static final String STATE_LAT = "lat";
    private static final String STATE_LON = "lon";
    private static final String STATE_ZOOM = "zoom";

    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private TileLoader loader;
    private MapView map;
    private TextView info;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loader = new TileLoader(this);
        map = new MapView(this, loader);
        setContentView(buildViews());

        if (savedInstanceState != null) {
            map.setCamera(savedInstanceState.getDouble(STATE_LAT),
                    savedInstanceState.getDouble(STATE_LON),
                    savedInstanceState.getDouble(STATE_ZOOM));
        }
        String path = getIntent().getStringExtra(EXTRA_FILE);
        if (path == null) {
            finish();
            return;
        }
        load(new File(path), savedInstanceState == null);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(info);
        root.addView(mapFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // Reads the track on the background thread. At the first start, the map
    // then shows the whole track.
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
            runOnUiThread(() -> {
                if (destroyed) {
                    return;
                }
                if (result == null || result.points.isEmpty()) {
                    info.setText(R.string.track_unreadable);
                    return;
                }
                setTitle(result.name);
                info.setText(getString(R.string.track_details,
                        Labels.profile(this, result.profile),
                        Labels.distance(this, result.distanceM()),
                        TimeText.duration(result.durationMs())));
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
