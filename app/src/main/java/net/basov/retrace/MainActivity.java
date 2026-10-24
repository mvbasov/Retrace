package net.basov.retrace;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.basov.retrace.core.GpsReadiness;
import net.basov.retrace.core.GpxReader;
import net.basov.retrace.core.LimitedInputStream;
import net.basov.retrace.core.Profile;
import net.basov.retrace.core.RecordingSnapshot;
import net.basov.retrace.core.TimeText;
import net.basov.retrace.core.Track;
import net.basov.retrace.core.TrackImport;

// ----------------------------------------------------------------------
// The list of tracks and the control of the recording
// ----------------------------------------------------------------------
//
// The top row holds the profile and the start or stop button. Below it, a
// line shows the state of the recording. Two tabs select the list of saved
// tracks or the list of ways. The list fills the rest of the screen. A push
// on a track opens the track screen. That screen has the Replay button and
// the full menu of the track: start a trip on its route, make or change a
// way, share, rename and delete. A long push on a track in the list opens
// a shorter menu: share, rename and delete. See TrackActions.
// The options menu imports a GPX file from another application.
//
// A start opens the trip screen. While Retrace records, a push on the status
// line opens it again.
//
// Before a trip, the GPS button starts the receiver. Its row shows the state
// of the receiver and the accuracy. See GpsWarmup and GpsReadiness.
//
// The activity reads TrackService.snapshot() once each second. A file task
// runs on one background thread, and its result comes back on the UI thread.
public final class MainActivity extends ThemedActivity {

    private static final int REQUEST_LOCATION = 1;
    private static final int REQUEST_IMPORT = 2;
    private static final int REQUEST_GPS = 3;

    // A larger GPX file is not imported. The reader keeps all points in
    // memory. 20 MB is more than 100000 points.
    private static final long MAX_IMPORT_BYTES = 20L * 1024 * 1024;
    private static final long REFRESH_MS = 1000;
    private static final String PREF_PROFILE = "profile";
    private static final String PREF_SHOW_WAYS = "show_ways";

    private static final int MENU_PROBE = 1;
    private static final int MENU_IMPORT = 6;
    private static final int MENU_ABOUT = 7;
    private static final int MENU_SETTINGS = 9;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();

    private TrackStore store;
    private TrackListAdapter adapter;
    private Spinner profileSpinner;
    private Button startStopButton;
    private TextView statusView;
    private LinearLayout gpsRow;
    private Button gpsButton;
    private TextView gpsView;
    private TextView emptyView;
    private RadioButton tracksTab;
    private RadioButton waysTab;
    private RadioGroup tabs;

    // All saved tracks and ways. The adapter gets one kind of them.
    private List<TrackStore.Entry> allEntries = new ArrayList<>();
    private boolean showWays;

    // The trip that waits for the permission dialog. The reference track is
    // null for a trip without a comparison.
    private Profile pendingProfile = Profile.WALKING;
    private String pendingReference;
    private boolean pendingReversed;

    // The savedCount of the last snapshot that the list shows.
    private int shownSavedCount = -1;
    private volatile boolean destroyed;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            showSnapshot();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new TrackStore(this);
        adapter = new TrackListAdapter(this);
        setContentView(buildViews());
        if (savedInstanceState == null) {
            startTripFrom(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        startTripFrom(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTracks(true);
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        // A turn of the device also destroys the activity. The receiver
        // continues then, because the new activity shows it again.
        if (isFinishing()) {
            GpsWarmup.stop();
        }
        files.shutdown();
        super.onDestroy();
    }

    private View buildViews() {
        int pad = dp(16);

        profileSpinner = new Spinner(this);
        List<String> labels = new ArrayList<>();
        for (Profile p : Profile.values()) {
            labels.add(Labels.profile(this, p));
        }
        ArrayAdapter<String> profiles = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        profiles.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(profiles);
        profileSpinner.setSelection(savedProfile().ordinal());
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                saveProfile(Profile.values()[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        startStopButton = new Button(this);
        startStopButton.setOnClickListener(v -> onStartStop());

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(profileSpinner, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(startStopButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        gpsButton = new Button(this);
        gpsButton.setOnClickListener(v -> onGps());
        gpsView = new TextView(this);
        gpsView.setPadding(pad / 2, 0, 0, 0);
        gpsRow = new LinearLayout(this);
        gpsRow.setOrientation(LinearLayout.HORIZONTAL);
        gpsRow.setGravity(Gravity.CENTER_VERTICAL);
        gpsRow.addView(gpsButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        gpsRow.addView(gpsView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        statusView = new TextView(this);
        statusView.setPadding(0, pad / 2, 0, pad / 2);
        statusView.setOnClickListener(v -> {
            if (TrackService.snapshot().recording) {
                startActivity(new Intent(this, RunActivity.class));
            }
        });

        // The tabs keep no view state. The preference holds the selection.
        showWays = prefs().getBoolean(PREF_SHOW_WAYS, false);
        tracksTab = new RadioButton(this);
        waysTab = new RadioButton(this);
        tabs = new RadioGroup(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setSaveEnabled(false);
        for (RadioButton tab : new RadioButton[]{tracksTab, waysTab}) {
            tab.setId(View.generateViewId());
            tab.setSaveEnabled(false);
            tabs.addView(tab, new RadioGroup.LayoutParams(0,
                    RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        tabs.check(showWays ? waysTab.getId() : tracksTab.getId());
        tabs.setOnCheckedChangeListener((g, id) -> setShowWays(id == waysTab.getId()));

        TextView empty = new TextView(this);
        empty.setPadding(0, pad, 0, pad);
        emptyView = empty;

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> {
            TrackStore.Entry e = adapter.getItem(position);
            if (e.readable) {
                startActivity(new Intent(this, TrackMapActivity.class)
                        .putExtra(TrackMapActivity.EXTRA_FILE, e.file.getPath()));
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showTrackMenu(adapter.getItem(position));
            return true;
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);
        root.addView(top);
        root.addView(gpsRow);
        root.addView(statusView);
        root.addView(tabs);
        root.addView(empty, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    private void onStartStop() {
        if (TrackService.snapshot().recording) {
            startService(new Intent(this, TrackService.class).setAction(TrackService.ACTION_STOP));
        } else {
            pendingProfile = Profile.values()[profileSpinner.getSelectedItemPosition()];
            pendingReference = null;
            pendingReversed = false;
            startRecording();
        }
        startStopButton.setEnabled(false);
        handler.postDelayed(() -> startStopButton.setEnabled(true), REFRESH_MS);
    }

    private void startRecording() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(permissions(), REQUEST_LOCATION);
            return;
        }
        if (!locationOn()) {
            showLocationOff();
            return;
        }
        Intent start = new Intent(this, TrackService.class)
                .setAction(TrackService.ACTION_START)
                .putExtra(TrackService.EXTRA_PROFILE, pendingProfile.id);
        if (pendingReference != null) {
            start.putExtra(TrackService.EXTRA_REFERENCE, pendingReference);
            start.putExtra(TrackService.EXTRA_REVERSED, pendingReversed);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(start);
        } else {
            startService(start);
        }
        // TrackService now has the receiver. The receiver keeps its fix.
        GpsWarmup.stop();
        startActivity(new Intent(this, RunActivity.class));
    }

    private boolean locationOn() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private void showLocationOff() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.gps_off)
                .setPositiveButton(R.string.open_settings, (d, w) ->
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------
    // The GPS before a trip
    // ------------------------------------------------------------------

    private void onGps() {
        if (GpsWarmup.isRunning()) {
            GpsWarmup.stop();
        } else {
            startGps();
        }
        showGps(TrackService.snapshot());
    }

    // With the location off, the receiver starts too. It gives its first fix
    // when the user turns the location on.
    private void startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(permissions(), REQUEST_GPS);
            return;
        }
        if (!GpsWarmup.start(this)) {
            showMessage(getString(R.string.gps_no_receiver));
            return;
        }
        if (!locationOn()) {
            showLocationOff();
        }
    }

    // During a trip, the status line shows the GPS. Thus the screen hides
    // the row.
    private void showGps(RecordingSnapshot s) {
        gpsRow.setVisibility(s.recording ? View.GONE : View.VISIBLE);
        gpsButton.setText(GpsWarmup.isRunning() ? R.string.gps_stop : R.string.gps_start);
        gpsView.setText(gpsText());
    }

    private String gpsText() {
        GpsWarmup.Status g = GpsWarmup.status();
        if (!g.running) {
            return getString(R.string.gps_hint);
        }
        Profile profile = Profile.values()[profileSpinner.getSelectedItemPosition()];
        String text;
        switch (GpsReadiness.of(g.locationOn, g.fixAgeMs, g.accuracyM, profile)) {
            case OFF:
                return getString(R.string.gps_location_off);
            case SEARCHING:
                text = getString(R.string.waiting_for_gps);
                break;
            case TOO_LOW:
                text = getString(R.string.gps_too_low, (int) Math.round(g.accuracyM),
                        (int) Math.round(profile.accuracyLimitM));
                break;
            case READY:
            default:
                text = Double.isNaN(g.accuracyM) ? getString(R.string.gps_ready)
                        : getString(R.string.gps_ready_accuracy, (int) Math.round(g.accuracyM));
                break;
        }
        if (g.satellitesVisible >= 0) {
            text += "\n" + getString(R.string.gps_satellites,
                    g.satellitesUsed, g.satellitesVisible);
        }
        return text;
    }

    private static String[] permissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS};
        }
        return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_LOCATION && requestCode != REQUEST_GPS) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQUEST_LOCATION) {
                startRecording();
            } else {
                startGps();
                showGps(TrackService.snapshot());
            }
            return;
        }
        // Android does not show the request again after the user selects
        // "do not ask again". The settings of the application are then the
        // only way to give the permission.
        new AlertDialog.Builder(this)
                .setMessage(R.string.permission_needed)
                .setPositiveButton(R.string.open_settings, (d, w) -> startActivity(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null))))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // The offer of a way comes here if the trip stopped from the
    // notification.
    private void showSnapshot() {
        WayUpdater.Offer offer = TrackService.takeWayOffer();
        if (offer != null) {
            WayDialogs.showOffer(this, files, offer, () -> loadTracks(false));
        }
        RecordingSnapshot s = TrackService.snapshot();
        startStopButton.setText(s.recording ? R.string.stop_recording : R.string.start_recording);
        profileSpinner.setEnabled(!s.recording);
        if (s.recording && s.profile != null) {
            profileSpinner.setSelection(s.profile.ordinal());
        }
        statusView.setText(statusText(s));
        showGps(s);
        if (s.savedCount != shownSavedCount) {
            loadTracks(false);
        }
    }

    private String statusText(RecordingSnapshot s) {
        if (s.recording) {
            String elapsed = TimeText.duration(s.elapsedMs(System.currentTimeMillis()));
            String open = "\n" + getString(R.string.status_open_trip);
            if (s.isReplay()) {
                return getString(R.string.status_replay, s.replayName, s.points,
                        Labels.distance(this, s.distanceM), elapsed) + open;
            }
            String progress = getString(R.string.status_recording,
                    Labels.profile(this, s.profile), s.points,
                    Labels.distance(this, s.distanceM), elapsed);
            if (!s.hasFix()) {
                return progress + "\n" + getString(R.string.waiting_for_gps) + open;
            }
            if (Double.isNaN(s.lastAccuracyM)) {
                return progress + open;
            }
            return progress + "\n" + getString(R.string.gps_accuracy,
                    (int) Math.round(s.lastAccuracyM)) + open;
        }
        switch (s.error) {
            case RecordingSnapshot.ERROR_NO_PERMISSION:
                return getString(R.string.error_no_permission);
            case RecordingSnapshot.ERROR_STORAGE:
                return getString(R.string.error_storage);
            case RecordingSnapshot.ERROR_NO_GPS:
                return getString(R.string.error_no_gps);
            case RecordingSnapshot.ERROR_REPLAY:
                return getString(R.string.error_replay);
            default:
                return getString(R.string.status_idle);
        }
    }

    // ------------------------------------------------------------------
    // The list of tracks
    // ------------------------------------------------------------------

    // Reads the list on the background thread. On the first load, a log file
    // of a stopped process becomes a track first.
    private void loadTracks(boolean recover) {
        final int savedCount = TrackService.snapshot().savedCount;
        shownSavedCount = savedCount;
        files.execute(() -> {
            if (recover) {
                try {
                    store.recoverLog();
                } catch (IOException e) {
                    // The log file stays. The next load tries again.
                }
            }
            List<TrackStore.Entry> entries = store.list();
            runOnUiThread(() -> {
                if (!destroyed) {
                    allEntries = entries;
                    showEntries();
                }
            });
        });
    }

    // The long push shows the file items of TrackActions. The track screen
    // has the full menu.
    private void showTrackMenu(TrackStore.Entry e) {
        TrackActions.showFileItems(this, files, e, this::onTrackChanged);
    }

    private void onTrackChanged(int change) {
        if (change == TrackActions.WAY_MADE) {
            setShowWays(true);
        }
        loadTracks(false);
    }

    // A trip on the route of a track comes from the menu of the track
    // screen. See TrackActions. The trip uses the profile of the reference
    // track. A track from another application can have no profile. The
    // selected profile then applies.
    private void startTripFrom(Intent intent) {
        String reference = intent.getStringExtra(TrackActions.EXTRA_TRIP_REFERENCE);
        if (reference == null || TrackService.snapshot().recording) {
            return;
        }
        // A second delivery of the same intent must not start a second trip.
        intent.removeExtra(TrackActions.EXTRA_TRIP_REFERENCE);
        Profile p = Profile.fromId(intent.getStringExtra(TrackActions.EXTRA_TRIP_PROFILE));
        pendingProfile = p != null ? p : Profile.values()[profileSpinner.getSelectedItemPosition()];
        pendingReference = reference;
        pendingReversed = intent.getBooleanExtra(TrackActions.EXTRA_TRIP_REVERSED, false);
        startRecording();
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    // Many file managers do not know the GPX type. Thus the picker shows all
    // files, and the GPX reader refuses a file of a different type.
    private void startImport() {
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(open, REQUEST_IMPORT);
        } catch (ActivityNotFoundException ex) {
            showMessage(getString(R.string.import_no_picker));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            readImport(data.getData());
        }
    }

    // Reads and checks the file on the background thread.
    private void readImport(Uri uri) {
        files.execute(() -> {
            int error;
            TrackImport.Result result = null;
            try (InputStream in = new LimitedInputStream(new BufferedInputStream(
                    openImport(uri)), MAX_IMPORT_BYTES)) {
                Track raw = GpxReader.read(in, importName(uri));
                result = TrackImport.check(raw);
                error = result.error == TrackImport.OK ? 0
                        : result.error == TrackImport.NO_TIME
                        ? R.string.import_error_time : R.string.import_error_points;
            } catch (LimitedInputStream.TooLargeException ex) {
                error = R.string.import_error_size;
            } catch (IOException | RuntimeException ex) {
                // The picker can give a URI that Retrace cannot read, for
                // example after the user removed the file.
                error = R.string.import_error_read;
            }
            final int message = error;
            final TrackImport.Result checked = result;
            runOnUiThread(() -> {
                if (destroyed) {
                    return;
                }
                if (message != 0) {
                    showMessage(getString(message));
                } else if (checked.profileKnown) {
                    saveImport(checked.track);
                } else {
                    askImportProfile(checked);
                }
            });
        });
    }

    private InputStream openImport(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("no stream: " + uri);
        }
        return in;
    }

    // The file name without ".gpx", for a GPX file with no name element.
    private String importName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (RuntimeException ex) {
            name = null;
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null) {
            return "";
        }
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".gpx")
                ? name.substring(0, name.length() - 4) : name;
    }

    // The GPX file has no Retrace profile. The dialog shows the guess from
    // the speed, and the user confirms it or selects a different profile.
    private void askImportProfile(TrackImport.Result checked) {
        Profile[] profiles = Profile.values();
        String[] labels = new String[profiles.length];
        for (int i = 0; i < profiles.length; i++) {
            labels[i] = Labels.profile(this, profiles[i]);
        }
        final int[] selected = {checked.profile.ordinal()};
        new AlertDialog.Builder(this)
                .setTitle(R.string.import_profile_title)
                .setSingleChoiceItems(labels, selected[0], (d, which) -> selected[0] = which)
                .setPositiveButton(android.R.string.ok, (d, w) -> saveImport(new Track(
                        checked.track.name, profiles[selected[0]], checked.track.points)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveImport(Track track) {
        files.execute(() -> {
            String message;
            boolean done = false;
            try {
                if (store.importTrack(track) == null) {
                    message = getString(R.string.import_duplicate);
                } else {
                    message = getString(R.string.import_done, track.name);
                    done = true;
                }
            } catch (IOException ex) {
                message = getString(R.string.import_error_storage);
            }
            final String text = message;
            final boolean imported = done;
            runOnUiThread(() -> {
                if (destroyed) {
                    return;
                }
                if (imported) {
                    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                } else {
                    showMessage(text);
                }
                loadTracks(false);
            });
        });
    }

    private void showMessage(String text) {
        new AlertDialog.Builder(this)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ------------------------------------------------------------------
    // The options menu
    // ------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_IMPORT, Menu.NONE, R.string.import_track);
        menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, R.string.settings);
        menu.add(Menu.NONE, MENU_PROBE, Menu.NONE, R.string.probe_button);
        menu.add(Menu.NONE, MENU_ABOUT, Menu.NONE, R.string.about);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_IMPORT) {
            startImport();
            return true;
        }
        if (item.getItemId() == MENU_SETTINGS) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (item.getItemId() == MENU_ABOUT) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(getString(R.string.about_text, Labels.versionName(this)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        if (item.getItemId() == MENU_PROBE) {
            startActivity(new Intent(this, TileProbeActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void setShowWays(boolean ways) {
        if (ways == showWays) {
            return;
        }
        showWays = ways;
        prefs().edit().putBoolean(PREF_SHOW_WAYS, ways).apply();
        // check() does nothing for the button that is checked already. Thus
        // the call from the listener of the tabs is safe.
        tabs.check(ways ? waysTab.getId() : tracksTab.getId());
        showEntries();
    }

    // Gives the adapter the entries of the selected tab. An entry that
    // Retrace cannot read is in the list of tracks.
    private void showEntries() {
        List<TrackStore.Entry> tracks = new ArrayList<>();
        List<TrackStore.Entry> ways = new ArrayList<>();
        for (TrackStore.Entry e : allEntries) {
            (e.isWay() ? ways : tracks).add(e);
        }
        tracksTab.setText(getString(R.string.tab_tracks, tracks.size()));
        waysTab.setText(getString(R.string.tab_ways, ways.size()));
        emptyView.setText(showWays ? R.string.no_ways : R.string.no_tracks);
        adapter.setEntries(showWays ? ways : tracks);
    }

    private Profile savedProfile() {
        Profile p = Profile.fromId(prefs().getString(PREF_PROFILE, null));
        return p == null ? Profile.WALKING : p;
    }

    private void saveProfile(Profile p) {
        prefs().edit().putString(PREF_PROFILE, p.id).apply();
    }

    private SharedPreferences prefs() {
        return getPreferences(Context.MODE_PRIVATE);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }
}
