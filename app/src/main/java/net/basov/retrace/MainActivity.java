package net.basov.retrace;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
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
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
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

import net.basov.retrace.core.GpxReader;
import net.basov.retrace.core.LimitedInputStream;
import net.basov.retrace.core.Profile;
import net.basov.retrace.core.RecordingSnapshot;
import net.basov.retrace.core.Replay;
import net.basov.retrace.core.TimeText;
import net.basov.retrace.core.Track;
import net.basov.retrace.core.TrackImport;

// ----------------------------------------------------------------------
// The list of tracks and the control of the recording
// ----------------------------------------------------------------------
//
// The top row holds the profile and the start or stop button. Below it, a
// line shows the state of the recording. The list of saved tracks fills the
// rest of the screen. A push on a track shows it on the map. A long push on a
// track opens a menu: start a trip on its route, replay it, share, rename or
// delete.
// The options menu imports a GPX file from another application.
//
// A start opens the trip screen. While Retrace records, a push on the status
// line opens it again.
//
// The activity reads TrackService.snapshot() once each second. A file task
// runs on one background thread, and its result comes back on the UI thread.
public final class MainActivity extends ThemedActivity {

    private static final int REQUEST_LOCATION = 1;
    private static final int REQUEST_IMPORT = 2;

    // A larger GPX file is not imported. The reader keeps all points in
    // memory. 20 MB is more than 100000 points.
    private static final long MAX_IMPORT_BYTES = 20L * 1024 * 1024;
    private static final long REFRESH_MS = 1000;
    private static final String PREF_PROFILE = "profile";

    private static final int MENU_PROBE = 1;
    private static final int MENU_RENAME = 2;
    private static final int MENU_DELETE = 3;
    private static final int MENU_TRIP = 4;
    private static final int MENU_SHARE = 5;
    private static final int MENU_IMPORT = 6;
    private static final int MENU_ABOUT = 7;
    private static final int MENU_REPLAY = 8;
    private static final int MENU_SETTINGS = 9;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();

    private TrackStore store;
    private TrackListAdapter adapter;
    private Spinner profileSpinner;
    private Button startStopButton;
    private TextView statusView;

    // The trip that waits for the permission dialog. The reference track is
    // null for a trip without a comparison.
    private Profile pendingProfile = Profile.WALKING;
    private String pendingReference;

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

        statusView = new TextView(this);
        statusView.setPadding(0, pad / 2, 0, pad / 2);
        statusView.setOnClickListener(v -> {
            if (TrackService.snapshot().recording) {
                startActivity(new Intent(this, RunActivity.class));
            }
        });

        TextView empty = new TextView(this);
        empty.setText(R.string.no_tracks);
        empty.setPadding(0, pad, 0, pad);

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
        registerForContextMenu(list);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);
        root.addView(top);
        root.addView(statusView);
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
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.gps_off)
                    .setPositiveButton(R.string.open_settings, (d, w) ->
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        Intent start = new Intent(this, TrackService.class)
                .setAction(TrackService.ACTION_START)
                .putExtra(TrackService.EXTRA_PROFILE, pendingProfile.id);
        if (pendingReference != null) {
            start.putExtra(TrackService.EXTRA_REFERENCE, pendingReference);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(start);
        } else {
            startService(start);
        }
        startActivity(new Intent(this, RunActivity.class));
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
        if (requestCode != REQUEST_LOCATION) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startRecording();
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

    private void showSnapshot() {
        RecordingSnapshot s = TrackService.snapshot();
        startStopButton.setText(s.recording ? R.string.stop_recording : R.string.start_recording);
        profileSpinner.setEnabled(!s.recording);
        if (s.recording && s.profile != null) {
            profileSpinner.setSelection(s.profile.ordinal());
        }
        statusView.setText(statusText(s));
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
                    adapter.setEntries(entries);
                }
            });
        });
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo info) {
        super.onCreateContextMenu(menu, v, info);
        TrackStore.Entry e = adapter.getItem(((AdapterView.AdapterContextMenuInfo) info).position);
        menu.setHeaderTitle(e.name);
        if (e.readable && e.points >= 2 && !TrackService.snapshot().recording) {
            menu.add(Menu.NONE, MENU_TRIP, Menu.NONE, R.string.start_trip_on_route);
            menu.add(Menu.NONE, MENU_REPLAY, Menu.NONE, R.string.replay_track);
        }
        if (e.readable) {
            menu.add(Menu.NONE, MENU_SHARE, Menu.NONE, R.string.share);
            menu.add(Menu.NONE, MENU_RENAME, Menu.NONE, R.string.rename);
        }
        menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.delete);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info =
                (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        TrackStore.Entry e = adapter.getItem(info.position);
        if (item.getItemId() == MENU_TRIP) {
            // The trip uses the profile of the reference track. A track from
            // another application can have no profile. The selected profile
            // then applies.
            Profile selected = Profile.values()[profileSpinner.getSelectedItemPosition()];
            pendingProfile = e.profile != null ? e.profile : selected;
            pendingReference = e.file.getPath();
            startRecording();
            return true;
        }
        if (item.getItemId() == MENU_REPLAY) {
            askReplay(e);
            return true;
        }
        if (item.getItemId() == MENU_SHARE) {
            share(e);
            return true;
        }
        if (item.getItemId() == MENU_RENAME) {
            askRename(e);
            return true;
        }
        if (item.getItemId() == MENU_DELETE) {
            askDelete(e);
            return true;
        }
        return super.onContextItemSelected(item);
    }

    private void askRename(TrackStore.Entry e) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(e.name);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty() && !name.equals(e.name)) {
                        runFileTask(() -> store.rename(e.file, name), R.string.error_rename);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askDelete(TrackStore.Entry e) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_question, e.name))
                .setPositiveButton(R.string.delete, (d, w) -> runFileTask(() -> {
                    if (!store.delete(e.file)) {
                        throw new IOException("cannot delete " + e.file);
                    }
                }, R.string.error_delete))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // A replay needs no location permission and no GPS. The dialog selects
    // the reference track. The automatic selection does not use the
    // replayed track itself.
    private void askReplay(TrackStore.Entry e) {
        Profile selected = Profile.values()[profileSpinner.getSelectedItemPosition()];
        Profile profile = e.profile != null ? e.profile : selected;
        ReferenceDialog.show(this, files, profile, (automatic, path) -> {
            Intent replay = new Intent(this, TrackService.class)
                    .setAction(TrackService.ACTION_REPLAY)
                    .putExtra(TrackService.EXTRA_REPLAY, e.file.getPath())
                    .putExtra(TrackService.EXTRA_SPEED, Replay.DEFAULT_SPEED);
            startService(ReferenceDialog.putSelection(replay, automatic, path));
            startActivity(new Intent(this, RunActivity.class));
        });
    }

    // ------------------------------------------------------------------
    // Share and import
    // ------------------------------------------------------------------

    // The share intent gives the receiver the read permission for this one
    // URI. The clip data carries the permission through the chooser.
    private void share(TrackStore.Entry e) {
        Uri uri = TrackProvider.uriFor(this, e.file);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/gpx+xml")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, e.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(e.name, uri));
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_title)));
        } catch (ActivityNotFoundException ex) {
            showMessage(getString(R.string.share_no_app));
        }
    }

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

    // A file task that can fail. The list loads again after the task.
    private interface FileTask {
        void run() throws IOException;
    }

    private void runFileTask(FileTask task, int errorText) {
        files.execute(() -> {
            boolean ok;
            try {
                task.run();
                ok = true;
            } catch (IOException ex) {
                ok = false;
            }
            final boolean done = ok;
            runOnUiThread(() -> {
                if (destroyed) {
                    return;
                }
                if (!done) {
                    new AlertDialog.Builder(this)
                            .setMessage(errorText)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                loadTracks(false);
            });
        });
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
