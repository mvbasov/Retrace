package net.basov.retrace;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import net.basov.retrace.core.DeltaSmoother;
import net.basov.retrace.core.MatchResult;
import net.basov.retrace.core.PointLog;
import net.basov.retrace.core.Profile;
import net.basov.retrace.core.RecordFilter;
import net.basov.retrace.core.RecordingSnapshot;
import net.basov.retrace.core.ReferenceSelector;
import net.basov.retrace.core.Replay;
import net.basov.retrace.core.Track;
import net.basov.retrace.core.TrackMatcher;
import net.basov.retrace.core.TrackPoint;

// ----------------------------------------------------------------------
// The recording of the current track, and the comparison
// ----------------------------------------------------------------------
//
// A foreground service with a notification. It continues to record when the
// screen is off. MainActivity starts it with ACTION_START and stops it with
// ACTION_STOP. The stop button of the notification also sends ACTION_STOP.
//
// With EXTRA_REFERENCE, the service compares the trip with that track.
// Without it, ReferenceSelector selects the reference track. At the first
// point, the service reads the saved tracks of the profile, and keeps the
// tracks with a start near that point. If no track starts there, the trip
// has no comparison.
//
// Each point that RecordFilter accepts goes to TrackMatcher. The notification
// and the trip screen show the result. At the finish, the device vibrates.
// The recording continues until the user stops it.
//
// ACTION_SELECT changes the reference track during the trip. The service
// then gives all the points of the trip to the new matcher again. Thus the
// comparison continues as if the trip started with that track. A manual
// selection stops the automatic selection.
//
// ACTION_REPLAY starts a replay: the points of a saved track go through the
// same steps as GPS fixes, faster than the real time. See Replay. A replay
// does not use the GPS, writes no log file and saves no track. It does not
// use the foreground state, because it needs no location permission. Thus
// Android can stop a replay soon after the user leaves Retrace.
//
// One worker thread does all the work: the GPS fixes, the filter, the
// matcher and the log file. Thus the fields of the recording need no lock.
// The main thread only receives the intents and posts the work to the worker
// thread.
//
// The service returns START_NOT_STICKY. If Android stops the process, the
// log file keeps the points. The next start of MainActivity saves them.
public final class TrackService extends Service {

    static final String ACTION_START = "net.basov.retrace.action.START";
    static final String ACTION_STOP = "net.basov.retrace.action.STOP";
    static final String ACTION_SELECT = "net.basov.retrace.action.SELECT";
    static final String EXTRA_PROFILE = "net.basov.retrace.extra.PROFILE";
    static final String EXTRA_REFERENCE = "net.basov.retrace.extra.REFERENCE";
    // ACTION_SELECT with this extra set to true starts the automatic
    // selection again. Without it and without EXTRA_REFERENCE, the trip has
    // no comparison.
    static final String EXTRA_AUTOMATIC = "net.basov.retrace.extra.AUTOMATIC";

    // ACTION_REPLAY takes the path of the replayed track in EXTRA_REPLAY, and
    // the reference track the same as ACTION_SELECT. ACTION_REPLAY_SPEED
    // changes the speed of the running replay.
    static final String ACTION_REPLAY = "net.basov.retrace.action.REPLAY";
    static final String ACTION_REPLAY_SPEED = "net.basov.retrace.action.REPLAY_SPEED";
    static final String EXTRA_REPLAY = "net.basov.retrace.extra.REPLAY";
    static final String EXTRA_SPEED = "net.basov.retrace.extra.SPEED";

    private static final String CHANNEL_ID = "recording";
    private static final int NOTIFICATION_ID = 1;

    // The GPS receiver gives one fix each second. The filter then drops the
    // fixes that are too near. See RecordFilter.
    private static final long FIX_INTERVAL_MS = 1000;

    // An update of the notification each second uses battery for no value.
    private static final long NOTIFICATION_INTERVAL_MS = 5000;

    // Three short and one long vibration at the finish.
    private static final long[] FINISH_PATTERN = {0, 300, 150, 300, 150, 300, 150, 800};

    // The last state of the recording. The activity reads it.
    private static volatile RecordingSnapshot snapshot = RecordingSnapshot.IDLE;

    // True from onCreate to onDestroy. TrackStore reads it before it saves an
    // old log file.
    private static volatile boolean active;

    // The points of the current track, for the map of the trip screen. The
    // worker thread adds them. The activity reads a copy.
    private static final List<TrackPoint> POINTS = new ArrayList<>();

    static RecordingSnapshot snapshot() {
        return snapshot;
    }

    static boolean isActive() {
        return active;
    }

    // Returns a copy of the points from an index to the end.
    static List<TrackPoint> pointsFrom(int from) {
        synchronized (POINTS) {
            if (from >= POINTS.size()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(POINTS.subList(Math.max(0, from), POINTS.size()));
        }
    }

    private HandlerThread thread;
    private Handler worker;
    private LocationManager locationManager;
    private NotificationManager notificationManager;
    private TrackStore store;

    // These fields change on the worker thread only.
    private boolean recording;
    private Profile profile = Profile.WALKING;
    private long startTimeMs;
    private RecordFilter filter = new RecordFilter(Profile.WALKING);
    private Writer log;
    private double lastAccuracyM = Double.NaN;
    private long lastFixTimeMs = TrackPoint.NO_TIME;
    private long lastNotificationMs;
    private int error = RecordingSnapshot.ERROR_NONE;

    private String referencePath;
    private int selection = RecordingSnapshot.SELECTION_OFF;
    private ReferenceSelector selector;
    private TrackMatcher matcher;
    private DeltaSmoother smoother;
    private MatchResult match;
    private long smoothedDeltaMs = MatchResult.UNKNOWN;
    private boolean finishSignaled;

    // The replay. A speed of 0 is a trip with the GPS.
    private int replaySpeed;
    private String replayPath;
    private String replayName;
    private List<TrackPoint> replayPoints;
    private int replayIndex;
    private long replayElapsedMs;
    private final Runnable replayStep = this::replayNext;

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            onFix(location);
        }

        // Android 6 to 10 declare the next three methods as abstract. A class
        // without them stops with AbstractMethodError on those versions.
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        active = true;
        thread = new HandlerThread("track-service");
        thread.start();
        worker = new Handler(thread.getLooper());
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        store = new TrackStore(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            Profile requested = Profile.fromId(intent.getStringExtra(EXTRA_PROFILE));
            final Profile p = requested == null ? Profile.WALKING : requested;
            final String reference = intent.getStringExtra(EXTRA_REFERENCE);
            final long start = System.currentTimeMillis();
            // Android 8 and newer stop the service if startForeground does not
            // come soon after startForegroundService. Thus it comes first.
            RecordingSnapshot first = new RecordingSnapshot(true, p, start, 0, 0, Double.NaN,
                    TrackPoint.NO_TIME, snapshot.savedCount, RecordingSnapshot.ERROR_NONE,
                    reference, reference == null ? RecordingSnapshot.SELECTION_SEARCH
                            : RecordingSnapshot.SELECTION_MANUAL, null, MatchResult.UNKNOWN,
                    null, 0, 0);
            if (!enterForeground(notification(first))) {
                snapshot = withError(RecordingSnapshot.ERROR_NO_PERMISSION);
                stopSelf();
                return START_NOT_STICKY;
            }
            worker.post(() -> begin(p, start, reference));
        } else if (ACTION_STOP.equals(action)) {
            worker.post(() -> finish(true));
        } else if (ACTION_REPLAY.equals(action)) {
            final String path = intent.getStringExtra(EXTRA_REPLAY);
            final String reference = intent.getStringExtra(EXTRA_REFERENCE);
            final boolean automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false);
            final int speed = intent.getIntExtra(EXTRA_SPEED, Replay.DEFAULT_SPEED);
            worker.post(() -> {
                if (!recording && !beginReplay(path, reference, automatic, speed)) {
                    stopSelf(startId);
                }
            });
        } else if (ACTION_REPLAY_SPEED.equals(action)) {
            final int speed = intent.getIntExtra(EXTRA_SPEED, Replay.DEFAULT_SPEED);
            worker.post(() -> {
                if (recording && replaySpeed > 0) {
                    replaySpeed = Math.max(1, speed);
                    publish();
                } else if (!recording) {
                    stopSelf(startId);
                }
            });
        } else if (ACTION_SELECT.equals(action)) {
            final String reference = intent.getStringExtra(EXTRA_REFERENCE);
            final boolean automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false);
            worker.post(() -> {
                if (recording) {
                    select(reference, automatic);
                } else {
                    // The intent started a service with no trip.
                    stopSelf(startId);
                }
            });
        } else {
            worker.post(() -> {
                if (!recording) {
                    finish(true);
                }
            });
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        active = false;
        // quitSafely runs the posted work first. Thus finish saves the track.
        worker.post(() -> finish(false));
        thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Worker thread.
    private void begin(Profile p, long start, String reference) {
        if (recording) {
            return;
        }
        resetTrip(p, start);
        if (reference == null) {
            selection = RecordingSnapshot.SELECTION_SEARCH;
        } else {
            loadReference(reference);
        }

        try {
            log = store.openLog(p, start);
        } catch (IOException e) {
            stopWithError(RecordingSnapshot.ERROR_STORAGE);
            return;
        }
        recording = true;
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    FIX_INTERVAL_MS, 0f, listener, thread.getLooper());
        } catch (SecurityException e) {
            stopWithError(RecordingSnapshot.ERROR_NO_PERMISSION);
            return;
        } catch (IllegalArgumentException e) {
            // The device has no GPS receiver.
            stopWithError(RecordingSnapshot.ERROR_NO_GPS);
            return;
        }
        publish();
    }

    // Worker thread. The common start of a trip and of a replay.
    private void resetTrip(Profile p, long start) {
        profile = p;
        startTimeMs = start;
        filter = new RecordFilter(p);
        lastAccuracyM = Double.NaN;
        lastFixTimeMs = TrackPoint.NO_TIME;
        error = RecordingSnapshot.ERROR_NONE;
        replaySpeed = 0;
        replayPath = null;
        replayName = null;
        replayPoints = null;
        replayIndex = 0;
        replayElapsedMs = 0;
        synchronized (POINTS) {
            POINTS.clear();
        }
        resetComparison(p);
    }

    // Worker thread. Returns false if Retrace cannot read the track. The
    // replayed track gives the profile. A track with no profile is replayed
    // as walking.
    private boolean beginReplay(String path, String reference, boolean automatic, int speed) {
        Track track;
        try {
            track = path == null ? null : store.load(new File(path));
        } catch (IOException | IllegalArgumentException e) {
            track = null;
        }
        if (track == null || track.points.size() < 2) {
            snapshot = withError(RecordingSnapshot.ERROR_REPLAY);
            return false;
        }
        resetTrip(track.profile != null ? track.profile : Profile.WALKING,
                System.currentTimeMillis());
        replaySpeed = Math.max(1, speed);
        replayPath = path;
        replayName = track.name;
        replayPoints = track.points;
        if (reference != null) {
            loadReference(reference);
        } else if (automatic) {
            selection = RecordingSnapshot.SELECTION_SEARCH;
        }
        recording = true;
        publish();
        worker.post(replayStep);
        return true;
    }

    // Worker thread. Sends one point of the replayed track, and posts the
    // next point. After the last point, the replay stops.
    private void replayNext() {
        if (!recording || replaySpeed == 0) {
            return;
        }
        TrackPoint p = replayPoints.get(replayIndex++);
        TrackPoint first = replayPoints.get(0);
        if (p.hasTime() && first.hasTime()) {
            replayElapsedMs = p.timeMs - first.timeMs;
        }
        onPoint(p);
        if (!recording) {
            return;
        }
        if (replayIndex >= replayPoints.size()) {
            finish(true);
            return;
        }
        worker.postDelayed(replayStep,
                Replay.waitMs(p, replayPoints.get(replayIndex), replaySpeed));
    }

    // Worker thread. Removes the reference track and the comparison.
    private void resetComparison(Profile p) {
        referencePath = null;
        selection = RecordingSnapshot.SELECTION_OFF;
        selector = null;
        matcher = null;
        match = null;
        smoother = DeltaSmoother.forProfile(p);
        smoothedDeltaMs = MatchResult.UNKNOWN;
        finishSignaled = false;
    }

    // Worker thread. The manual selection. A reference track that Retrace
    // cannot read gives a trip with no comparison. The recording itself
    // still works.
    private void loadReference(String path) {
        try {
            Track reference = store.load(new File(path));
            if (reference.points.size() >= 2) {
                matcher = new TrackMatcher(reference, profile);
                referencePath = path;
                selection = RecordingSnapshot.SELECTION_MANUAL;
            }
        } catch (IOException | IllegalArgumentException e) {
            matcher = null;
        }
    }

    // Worker thread. Starts the automatic selection at the first point. Each
    // saved track is read and checked alone. Thus only the near tracks stay
    // in memory. The GPS fixes wait in the queue of the worker thread during
    // this work.
    private void startSearch(TrackPoint first) {
        List<ReferenceSelector.Candidate> near = new ArrayList<>();
        for (File f : store.trackFiles()) {
            // The replayed track always agrees with itself. It is not a
            // candidate for its own replay.
            if (f.getPath().equals(replayPath)) {
                continue;
            }
            try {
                Track track = store.load(f);
                if (ReferenceSelector.startsNear(track, profile, first)) {
                    near.add(new ReferenceSelector.Candidate(f.getPath(), track));
                }
            } catch (IOException | IllegalArgumentException e) {
                // A file that Retrace cannot read is not a candidate.
            }
        }
        if (near.isEmpty()) {
            selection = RecordingSnapshot.SELECTION_NOT_FOUND;
            return;
        }
        selector = new ReferenceSelector(near, profile, first);
        selection = RecordingSnapshot.SELECTION_TEMPORARY;
    }

    // Worker thread. A new reference track during the trip. The points of
    // the trip go to the new matcher again. A finish in these points gives
    // no vibration, because it is in the past.
    private void select(String path, boolean automatic) {
        resetComparison(profile);
        if (automatic) {
            selection = RecordingSnapshot.SELECTION_SEARCH;
        } else if (path != null) {
            loadReference(path);
        }
        for (TrackPoint p : pointsFrom(0)) {
            compare(p);
        }
        publish();
        if (replaySpeed == 0) {
            lastNotificationMs = System.currentTimeMillis();
            notificationManager.notify(NOTIFICATION_ID, notification(snapshot));
        }
    }

    // Worker thread.
    private void onFix(Location location) {
        if (!recording) {
            return;
        }
        TrackPoint fix = new TrackPoint(
                location.getLatitude(),
                location.getLongitude(),
                location.getTime(),
                location.hasAltitude() ? location.getAltitude() : Double.NaN,
                location.hasAccuracy() ? location.getAccuracy() : Double.NaN);
        onPoint(fix);
    }

    // Worker thread. A GPS fix, or a point of a replay. A replay writes no
    // log file and shows no notification.
    private void onPoint(TrackPoint fix) {
        lastAccuracyM = fix.accuracyM;
        lastFixTimeMs = System.currentTimeMillis();
        boolean live = replaySpeed == 0;

        boolean finishNow = false;
        if (filter.accept(fix)) {
            if (live) {
                try {
                    log.write(PointLog.line(fix));
                    log.write('\n');
                    log.flush();
                } catch (IOException e) {
                    stopWithError(RecordingSnapshot.ERROR_STORAGE);
                    return;
                }
            }
            synchronized (POINTS) {
                POINTS.add(fix);
            }
            finishNow = compare(fix);
        }
        publish();

        long now = System.currentTimeMillis();
        if (live && (finishNow || now - lastNotificationMs >= NOTIFICATION_INTERVAL_MS)) {
            lastNotificationMs = now;
            notificationManager.notify(NOTIFICATION_ID, notification(snapshot));
        }
        if (finishNow) {
            vibrate();
        }
    }

    // Worker thread. Gives the point to the matcher, or to the selector
    // during the automatic selection. Returns true at the first point of the
    // finish.
    private boolean compare(TrackPoint fix) {
        if (selection == RecordingSnapshot.SELECTION_SEARCH) {
            startSearch(fix);
        }
        if (selector != null) {
            return compareCandidates(fix);
        }
        if (matcher == null) {
            return false;
        }
        return follow(fix, matcher.update(fix));
    }

    // Worker thread. The temporary reference track can change at each point.
    // A change starts the comparison again, with the result of the matcher
    // of the new candidate.
    private boolean compareCandidates(TrackPoint fix) {
        ReferenceSelector.State state = selector.update(fix);
        if (state == ReferenceSelector.State.NONE) {
            resetComparison(profile);
            selection = RecordingSnapshot.SELECTION_NOT_FOUND;
            return false;
        }
        String path = selector.current().id;
        if (!path.equals(referencePath)) {
            referencePath = path;
            match = null;
            smoother.reset();
            smoothedDeltaMs = MatchResult.UNKNOWN;
            finishSignaled = false;
        }
        MatchResult result = selector.currentResult();
        if (state == ReferenceSelector.State.SELECTED) {
            matcher = selector.selectedMatcher();
            selector = null;
            selection = RecordingSnapshot.SELECTION_AUTOMATIC;
        }
        return follow(fix, result);
    }

    // Worker thread. Takes one result of the matcher. Returns true at the
    // first point of the finish.
    private boolean follow(TrackPoint fix, MatchResult result) {
        boolean wasStarted = match != null && match.started();
        match = result;
        if (match.state == TrackMatcher.State.FINISHED) {
            // At the finish, the exact value is the result.
            smoothedDeltaMs = match.deltaTimeMs;
            if (!finishSignaled) {
                finishSignaled = true;
                return true;
            }
            return false;
        }
        if (match.started() && !wasStarted) {
            // At the start or at a join, the difference starts again from zero.
            smoother.reset();
        }
        if (match.state == TrackMatcher.State.ON_ROUTE && match.deltaTimeMs != MatchResult.UNKNOWN) {
            smoothedDeltaMs = smoother.add(fix.timeMs, match.deltaTimeMs);
        }
        return false;
    }

    // Worker thread. Stops the GPS updates and saves the track. The last
    // snapshot keeps the comparison, thus the trip screen shows the result.
    private void finish(boolean stopService) {
        boolean saved = false;
        if (recording) {
            recording = false;
            if (replaySpeed > 0) {
                worker.removeCallbacks(replayStep);
            } else {
                locationManager.removeUpdates(listener);
                closeLog();
                try {
                    saved = store.saveLog() != null;
                } catch (IOException e) {
                    error = RecordingSnapshot.ERROR_STORAGE;
                }
            }
        }
        RecordingSnapshot last = snapshot;
        snapshot = new RecordingSnapshot(false, profile, startTimeMs, filter.count(),
                filter.distanceM(), lastAccuracyM, lastFixTimeMs,
                last.savedCount + (saved ? 1 : 0), error, referencePath, selection, match,
                smoothedDeltaMs, replayName, replaySpeed, replayElapsedMs);
        if (stopService) {
            leaveForeground();
            stopSelf();
        }
    }

    // Worker thread. The points that the log file holds are saved.
    private void stopWithError(int code) {
        error = code;
        finish(true);
    }

    private void closeLog() {
        if (log != null) {
            try {
                log.close();
            } catch (IOException e) {
                // The log file can miss the last line. PointLog ignores it.
            }
            log = null;
        }
    }

    private void publish() {
        snapshot = new RecordingSnapshot(recording, profile, startTimeMs, filter.count(),
                filter.distanceM(), lastAccuracyM, lastFixTimeMs, snapshot.savedCount, error,
                referencePath, selection, match, smoothedDeltaMs, replayName, replaySpeed,
                replayElapsedMs);
    }

    private static RecordingSnapshot withError(int code) {
        RecordingSnapshot s = snapshot;
        return new RecordingSnapshot(false, s.profile, s.startTimeMs, 0, 0, Double.NaN,
                TrackPoint.NO_TIME, s.savedCount, code, null, RecordingSnapshot.SELECTION_OFF, null,
                MatchResult.UNKNOWN, null, 0, 0);
    }

    @SuppressWarnings("deprecation")
    private void vibrate() {
        if (!AppSettings.vibrate(this)) {
            return;
        }
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(FINISH_PATTERN, -1));
        } else {
            vibrator.vibrate(FINISH_PATTERN, -1);
        }
    }

    // Returns false if Android refuses the foreground state. Android 14
    // refuses it without the location permission.
    private boolean enterForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void leaveForeground() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    // A push on the notification opens the trip screen.
    private Notification notification(RecordingSnapshot s) {
        Intent open = new Intent(this, RunActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, TrackService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stop),
                getString(R.string.stop_recording), stopIntent).build();

        String text = s.points == 0 && s.match == null
                ? getString(R.string.waiting_for_gps) : TripText.notification(this, s);

        return builder()
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title, Labels.profile(this, s.profile)))
                .setContentText(text)
                .setContentIntent(openIntent)
                .addAction(stopAction)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(s.startTimeMs)
                .setUsesChronometer(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder() {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL_ID);
        }
        return new Notification.Builder(this);
    }
}
