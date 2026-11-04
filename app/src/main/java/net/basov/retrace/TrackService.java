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
import android.os.SystemClock;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import net.basov.retrace.core.DeltaSmoother;
import net.basov.retrace.core.FixSelector;
import net.basov.retrace.core.FixSmoother;
import net.basov.retrace.core.FixSource;
import net.basov.retrace.core.MatchResult;
import net.basov.retrace.core.PointLog;
import net.basov.retrace.core.Profile;
import net.basov.retrace.core.RecordFilter;
import net.basov.retrace.core.RecordingSnapshot;
import net.basov.retrace.core.ReferenceSelector;
import net.basov.retrace.core.RouteAgreement;
import net.basov.retrace.core.Replay;
import net.basov.retrace.core.Track;
import net.basov.retrace.core.TrackMatcher;
import net.basov.retrace.core.TrackPoint;
import net.basov.retrace.core.Waypoint;

// ----------------------------------------------------------------------
// The recording of the current track, and the comparison
// ----------------------------------------------------------------------
//
// A foreground service with a notification. It continues to record when the
// screen is off. MainActivity starts it with ACTION_START.
//
// ACTION_PAUSE is the Stop button of a trip with the GPS. The service ends
// the current track segment, but the trip stays open. The GPS stays on, thus
// it keeps its fix for Continue. During the pause, the fixes go to the dot
// and to the position of a waypoint, but not into the track. ACTION_CONTINUE
// starts a new track segment at the next point. ACTION_STOP is the End
// button: it stops the GPS and saves the track. The
// notification has the same buttons. A replay has no pause: Stop ends it.
//
// ACTION_WAYPOINT adds a waypoint at the last position, with the next
// automatic name. It goes into the log file at once. See Waypoint.
//
// With EXTRA_REFERENCE, the service compares the trip with that track. With
// EXTRA_REVERSED too, the trip follows that track in the opposite direction.
// Without it, ReferenceSelector selects the reference track. At the first
// point, the service reads the saved tracks of the profile, and keeps the
// tracks with a start near that point. If no track starts there, the trip
// has no comparison. If the trip later goes away from the automatic
// reference track, the comparison stops. See RouteAgreement.
//
// From Android 12, the service gets the fused location of Android. See
// FusedFixes. A device with no fused provider, and an older Android, use
// the GPS and the network location of Android. A network fix is then a
// fallback for a gap of the GPS, for example in a passage. FixSelector
// selects the fixes and gives them one time base. A device with only one
// of the two providers records with that provider.
//
// FixSmoother smooths each fix and drops the GPS spikes. Each point that
// RecordFilter accepts goes to TrackMatcher. The notification and the trip
// screen show the result. At the finish, the device vibrates.
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
// A trip with a way as the reference track can join the way after the
// finish. See WayUpdater. The service keeps one offer, and the visible
// screen takes it with takeWayOffer().
//
// One worker thread does all the work: the fixes, the filter, the
// matcher and the log file. Thus the fields of the recording need no lock.
// The main thread only receives the intents and posts the work to the worker
// thread.
//
// The service returns START_NOT_STICKY. If Android stops the process, the
// log file keeps the points. The next start of MainActivity saves them.
public final class TrackService extends Service {

    static final String ACTION_START = "net.basov.retrace.action.START";
    static final String ACTION_STOP = "net.basov.retrace.action.STOP";
    static final String ACTION_PAUSE = "net.basov.retrace.action.PAUSE";
    static final String ACTION_CONTINUE = "net.basov.retrace.action.CONTINUE";
    static final String ACTION_WAYPOINT = "net.basov.retrace.action.WAYPOINT";
    static final String ACTION_SELECT = "net.basov.retrace.action.SELECT";
    static final String EXTRA_PROFILE = "net.basov.retrace.extra.PROFILE";
    static final String EXTRA_REFERENCE = "net.basov.retrace.extra.REFERENCE";
    // True with EXTRA_REFERENCE: the trip follows the reference track in the
    // opposite direction.
    static final String EXTRA_REVERSED = "net.basov.retrace.extra.REVERSED";
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
    // The index in POINTS of the first point of each track segment after the
    // first one, and the waypoints. The lock of POINTS guards them too.
    private static final List<Integer> SEGMENT_STARTS = new ArrayList<>();
    private static final List<Waypoint> WAYPOINTS = new ArrayList<>();

    // The last finished trip that can join its way, or null.
    private static final AtomicReference<WayUpdater.Offer> WAY_OFFER = new AtomicReference<>();

    // Returns the offer once. The next call returns null.
    static WayUpdater.Offer takeWayOffer() {
        return WAY_OFFER.getAndSet(null);
    }

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

    static List<Integer> segmentStarts() {
        synchronized (POINTS) {
            return new ArrayList<>(SEGMENT_STARTS);
        }
    }

    static List<Waypoint> waypoints() {
        synchronized (POINTS) {
            return new ArrayList<>(WAYPOINTS);
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
    private FixSelector fixSelector = new FixSelector();
    private FixSmoother fixSmoother = new FixSmoother(Profile.WALKING);
    private Writer log;
    // A FusedFixes object while the trip uses the fused provider, or null.
    // The type is Object, thus Android 6 to 11 do not load the class.
    private Object fused;
    private double lastAccuracyM = Double.NaN;
    private long lastFixTimeMs = TrackPoint.NO_TIME;
    private int lastFixSource = FixSource.NONE;
    private long lastNotificationMs;
    private int error = RecordingSnapshot.ERROR_NONE;
    // The pause after Stop. The first point after Continue starts a new
    // track segment.
    private boolean paused;
    private boolean newSegmentPending;
    // The last smoothed fix, also one that the filter did not keep. A
    // waypoint goes there.
    private TrackPoint lastPosition;
    private int waypointCount;

    private String referencePath;
    // True if the trip follows the reference track in the opposite direction.
    private boolean referenceReversed;
    // The name of the reference track if it is a way, or null.
    private String referenceWayName;
    private int selection = RecordingSnapshot.SELECTION_OFF;
    private ReferenceSelector selector;
    private TrackMatcher matcher;
    private DeltaSmoother smoother;
    // The check of an automatic reference track after the selection, or
    // null. See RouteAgreement.
    private RouteAgreement agreement;
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
            final boolean reversed = intent.getBooleanExtra(EXTRA_REVERSED, false);
            final long start = System.currentTimeMillis();
            // Android 8 and newer stop the service if startForeground does not
            // come soon after startForegroundService. Thus it comes first.
            RecordingSnapshot first = new RecordingSnapshot(true, p, start, 0, 0, Double.NaN,
                    TrackPoint.NO_TIME, FixSource.NONE, snapshot.savedCount,
                    RecordingSnapshot.ERROR_NONE,
                    reference, reference == null ? RecordingSnapshot.SELECTION_SEARCH
                            : RecordingSnapshot.SELECTION_MANUAL, reference != null && reversed,
                    null, MatchResult.UNKNOWN,
                    null, null, 0, 0, false, 0);
            if (!enterForeground(notification(first))) {
                snapshot = withError(RecordingSnapshot.ERROR_NO_PERMISSION);
                stopSelf();
                return START_NOT_STICKY;
            }
            worker.post(() -> begin(p, start, reference, reversed));
        } else if (ACTION_STOP.equals(action)) {
            worker.post(() -> finish(true));
        } else if (ACTION_PAUSE.equals(action)) {
            worker.post(() -> duringTrip(this::pause));
        } else if (ACTION_CONTINUE.equals(action)) {
            worker.post(() -> duringTrip(this::resume));
        } else if (ACTION_WAYPOINT.equals(action)) {
            worker.post(() -> duringTrip(this::addWaypoint));
        } else if (ACTION_REPLAY.equals(action)) {
            final String path = intent.getStringExtra(EXTRA_REPLAY);
            final String reference = intent.getStringExtra(EXTRA_REFERENCE);
            final boolean reversed = intent.getBooleanExtra(EXTRA_REVERSED, false);
            final boolean automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false);
            final int speed = intent.getIntExtra(EXTRA_SPEED, Replay.DEFAULT_SPEED);
            worker.post(() -> {
                if (!recording && !beginReplay(path, reference, reversed, automatic, speed)) {
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
            final boolean reversed = intent.getBooleanExtra(EXTRA_REVERSED, false);
            final boolean automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false);
            worker.post(() -> {
                if (recording) {
                    select(reference, reversed, automatic);
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
    private void begin(Profile p, long start, String reference, boolean reversed) {
        if (recording) {
            return;
        }
        resetTrip(p, start);
        if (reference == null) {
            selection = RecordingSnapshot.SELECTION_SEARCH;
        } else {
            loadReference(reference, reversed);
        }

        try {
            log = store.openLog(p, start);
        } catch (IOException e) {
            stopWithError(RecordingSnapshot.ERROR_STORAGE);
            return;
        }
        recording = true;
        int result = requestFixes();
        if (result != RecordingSnapshot.ERROR_NONE) {
            stopWithError(result);
            return;
        }
        publish();
    }

    // Worker thread. From Android 12, requests the fused fixes. Otherwise, if
    // the setting is off, or if the device has no fused provider, requests the fixes of the GPS and
    // of the network. The network is a fallback, thus a missing network
    // provider is no fault. Returns ERROR_NONE if a provider gives fixes.
    private int requestFixes() {
        if (Build.VERSION.SDK_INT >= 31 && AppSettings.useFused(this)) {
            fused = FusedFixes.start(locationManager, FIX_INTERVAL_MS, worker, listener);
            if (fused != null) {
                return RecordingSnapshot.ERROR_NONE;
            }
        }
        boolean gps = request(LocationManager.GPS_PROVIDER);
        boolean network = locationManager.getProvider(LocationManager.NETWORK_PROVIDER) != null
                && request(LocationManager.NETWORK_PROVIDER);
        if (gps || network) {
            return RecordingSnapshot.ERROR_NONE;
        }
        return locationManager.getProvider(LocationManager.GPS_PROVIDER) == null
                ? RecordingSnapshot.ERROR_NO_GPS : RecordingSnapshot.ERROR_NO_PERMISSION;
    }

    // Worker thread. Returns false if Android refuses the provider.
    private boolean request(String provider) {
        try {
            locationManager.requestLocationUpdates(provider, FIX_INTERVAL_MS, 0f, listener,
                    thread.getLooper());
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    // Worker thread. The common start of a trip and of a replay.
    private void resetTrip(Profile p, long start) {
        profile = p;
        startTimeMs = start;
        filter = new RecordFilter(p);
        fixSelector = new FixSelector();
        fixSmoother = new FixSmoother(p);
        lastAccuracyM = Double.NaN;
        lastFixTimeMs = TrackPoint.NO_TIME;
        lastFixSource = FixSource.NONE;
        paused = false;
        newSegmentPending = false;
        lastPosition = null;
        waypointCount = 0;
        error = RecordingSnapshot.ERROR_NONE;
        replaySpeed = 0;
        replayPath = null;
        replayName = null;
        replayPoints = null;
        replayIndex = 0;
        replayElapsedMs = 0;
        synchronized (POINTS) {
            POINTS.clear();
            SEGMENT_STARTS.clear();
            WAYPOINTS.clear();
        }
        resetComparison(p);
    }

    // Worker thread. Returns false if Retrace cannot read the track. The
    // replayed track gives the profile. A track with no profile is replayed
    // as walking.
    private boolean beginReplay(String path, String reference, boolean reversed,
                                boolean automatic, int speed) {
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
        resetTrip(Replay.profileFor(track.profile), System.currentTimeMillis());
        replaySpeed = Math.max(1, speed);
        replayPath = path;
        replayName = track.name;
        replayPoints = track.points;
        if (reference != null) {
            loadReference(reference, reversed);
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
        referenceReversed = false;
        referenceWayName = null;
        selection = RecordingSnapshot.SELECTION_OFF;
        selector = null;
        matcher = null;
        agreement = null;
        match = null;
        smoother = DeltaSmoother.forProfile(p);
        smoothedDeltaMs = MatchResult.UNKNOWN;
        finishSignaled = false;
    }

    // Worker thread. The manual selection. A reference track that Retrace
    // cannot read gives a trip with no comparison. The recording itself
    // still works. In the opposite direction, the matcher gets the reversed
    // track.
    private void loadReference(String path, boolean reversed) {
        try {
            Track reference = store.load(new File(path));
            if (reference.points.size() >= 2) {
                matcher = new TrackMatcher(reversed ? reference.reversed() : reference, profile);
                referencePath = path;
                referenceReversed = reversed;
                referenceWayName = reference.isWay() ? reference.name : null;
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
                if (ReferenceSelector.routeNear(track, profile, first)) {
                    near.add(new ReferenceSelector.Candidate(f.getPath(), track, false));
                    near.add(new ReferenceSelector.Candidate(f.getPath(), track.reversed(), true));
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
    private void select(String path, boolean reversed, boolean automatic) {
        resetComparison(profile);
        if (automatic) {
            selection = RecordingSnapshot.SELECTION_SEARCH;
        } else if (path != null) {
            loadReference(path, reversed);
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

    // Worker thread. FixSelector decides if the fix goes into the track, and
    // gives its time. A provider can give no clock value. The fix then has
    // the clock value of now. FixSmoother then smooths the fix. A spike goes
    // into the snapshot only, thus the screen still shows a working source.
    // A replay does not come here, because a saved track is already smooth.
    private void onFix(Location location) {
        if (!recording) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long clock = location.getElapsedRealtimeNanos() > 0
                ? location.getElapsedRealtimeNanos() / 1000000L : now;
        boolean gps = LocationManager.GPS_PROVIDER.equals(location.getProvider());
        // A fused fix is the only fix of the trip. FixSelector then always
        // uses it, the same as a GPS fix.
        boolean fusedFix = Build.VERSION.SDK_INT >= 31 && fused != null
                && FusedFixes.isFused(location);
        long time = fixSelector.timeOf(gps || fusedFix, location.getTime(), clock, now);
        if (time == TrackPoint.NO_TIME) {
            return;
        }
        TrackPoint fix = new TrackPoint(
                location.getLatitude(),
                location.getLongitude(),
                time,
                location.hasAltitude() ? location.getAltitude() : Double.NaN,
                location.hasAccuracy() ? location.getAccuracy() : Double.NaN);
        if (fusedFix) {
            lastFixSource = FusedFixes.source(fused, location);
        } else {
            lastFixSource = gps ? FixSource.GPS : FixSource.NETWORK;
        }
        TrackPoint smoothed = fixSmoother.filter(fix);
        if (smoothed == null) {
            lastAccuracyM = fix.accuracyM;
            lastFixTimeMs = System.currentTimeMillis();
            publish();
            return;
        }
        lastPosition = smoothed;
        if (paused) {
            // The GPS stays warm. The fix only updates the state.
            lastAccuracyM = smoothed.accuracyM;
            lastFixTimeMs = System.currentTimeMillis();
            publish();
            return;
        }
        onPoint(smoothed);
    }

    // Worker thread. A GPS fix, or a point of a replay. A replay writes no
    // log file and shows no notification.
    private void onPoint(TrackPoint fix) {
        lastAccuracyM = fix.accuracyM;
        lastFixTimeMs = System.currentTimeMillis();
        boolean live = replaySpeed == 0;

        boolean finishNow = false;
        if (filter.accept(fix)) {
            boolean segmentStart = newSegmentPending && filter.count() > 1;
            newSegmentPending = false;
            if (live) {
                try {
                    if (segmentStart) {
                        log.write(PointLog.segmentLine());
                        log.write('\n');
                    }
                    log.write(PointLog.line(fix));
                    log.write('\n');
                    log.flush();
                } catch (IOException e) {
                    stopWithError(RecordingSnapshot.ERROR_STORAGE);
                    return;
                }
            }
            synchronized (POINTS) {
                if (segmentStart) {
                    SEGMENT_STARTS.add(POINTS.size());
                }
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
        MatchResult result = matcher.update(fix);
        if (agreement != null) {
            agreement.add(fix, result);
            if (agreement.differs()) {
                stopDifferentComparison();
                return false;
            }
        }
        return follow(fix, result);
    }

    // Worker thread. The trip goes away from its automatic reference track.
    // The comparison stops, and the trip continues as a new route.
    private void stopDifferentComparison() {
        resetComparison(profile);
        selection = RecordingSnapshot.SELECTION_NOT_FOUND;
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
        boolean reversed = selector.current().reversed;
        if (!path.equals(referencePath) || reversed != referenceReversed) {
            referencePath = path;
            referenceReversed = reversed;
            Track candidate = selector.current().track;
            referenceWayName = candidate.isWay() ? candidate.name : null;
            match = null;
            smoother.reset();
            smoothedDeltaMs = MatchResult.UNKNOWN;
            finishSignaled = false;
        }
        MatchResult result = selector.currentResult();
        if (state == ReferenceSelector.State.SELECTED) {
            matcher = selector.selectedMatcher();
            agreement = selector.selectedAgreement();
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

    // Worker thread. Stop of a trip with the GPS: the current track segment
    // ends, and the fixes stop. The trip stays open. A replay ends at once.
    private void pause() {
        if (!recording || paused) {
            return;
        }
        if (replaySpeed > 0) {
            finish(true);
            return;
        }
        // The fixes continue. FixSmoother follows them during the pause,
        // thus its estimate is current at Continue.
        paused = true;
        newSegmentPending = true;
        filter.newSegment();
        publishAndNotify();
    }

    // Worker thread. Continue after Stop: the next point starts a new track
    // segment.
    private void resume() {
        if (!recording || !paused) {
            return;
        }
        paused = false;
        publishAndNotify();
    }

    // Worker thread. A waypoint at the last position. Before the first fix,
    // the trip has no position, and the push does nothing. The time of the
    // waypoint is the time of that position.
    private void addWaypoint() {
        if (!recording || replaySpeed > 0 || lastPosition == null) {
            return;
        }
        Waypoint w = Waypoint.at(lastPosition, Waypoint.autoName(waypointCount + 1));
        try {
            log.write(PointLog.waypointLine(w));
            log.write('\n');
            log.flush();
        } catch (IOException e) {
            stopWithError(RecordingSnapshot.ERROR_STORAGE);
            return;
        }
        waypointCount++;
        synchronized (POINTS) {
            WAYPOINTS.add(w);
        }
        publishAndNotify();
    }

    // Worker thread. A button of an old notification can start the service
    // with no trip. The service then stops again.
    private void duringTrip(Runnable work) {
        if (recording) {
            work.run();
        } else {
            finish(true);
        }
    }

    private void publishAndNotify() {
        publish();
        lastNotificationMs = System.currentTimeMillis();
        notificationManager.notify(NOTIFICATION_ID, notification(snapshot));
    }

    // Worker thread. Stops the location updates of a trip with the GPS.
    private void stopFixes() {
        locationManager.removeUpdates(listener);
        if (Build.VERSION.SDK_INT >= 31 && fused != null) {
            FusedFixes.stop(locationManager, fused);
        }
        fused = null;
    }

    // Worker thread. Stops the GPS updates and saves the track. The last
    // snapshot keeps the comparison, thus the trip screen shows the result.
    private void finish(boolean stopService) {
        boolean saved = false;
        if (recording) {
            recording = false;
            paused = false;
            if (replaySpeed > 0) {
                worker.removeCallbacks(replayStep);
            } else {
                stopFixes();
                closeLog();
                try {
                    File savedFile = store.saveLog();
                    saved = savedFile != null;
                    if (saved && referenceWayName != null && finishSignaled) {
                        offerWay(savedFile);
                    }
                } catch (IOException e) {
                    error = RecordingSnapshot.ERROR_STORAGE;
                }
            }
        }
        RecordingSnapshot last = snapshot;
        snapshot = new RecordingSnapshot(false, profile, startTimeMs, filter.count(),
                filter.distanceM(), lastAccuracyM, lastFixTimeMs, lastFixSource,
                last.savedCount + (saved ? 1 : 0), error, referencePath, selection,
                referenceReversed, match,
                smoothedDeltaMs, replayPath, replayName, replaySpeed, replayElapsedMs, false,
                waypointCount);
        if (stopService) {
            leaveForeground();
            stopSelf();
        }
    }

    // Worker thread. The trip reached the finish of a way. The setting tells
    // if the trip joins the way now, after a question, or never.
    private void offerWay(File savedFile) {
        int mode = AppSettings.wayUpdate(this);
        if (mode == AppSettings.WAY_NEVER) {
            return;
        }
        int result = WayUpdater.QUESTION;
        if (mode == AppSettings.WAY_AUTOMATIC) {
            result = WayUpdater.add(store, new File(referencePath), savedFile);
        }
        WAY_OFFER.set(new WayUpdater.Offer(referencePath, referenceWayName,
                savedFile.getPath(), result));
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
                filter.distanceM(), lastAccuracyM, lastFixTimeMs, lastFixSource,
                snapshot.savedCount, error,
                referencePath, selection, referenceReversed, match, smoothedDeltaMs, replayPath,
                replayName, replaySpeed, replayElapsedMs, paused, waypointCount);
    }

    private static RecordingSnapshot withError(int code) {
        RecordingSnapshot s = snapshot;
        return new RecordingSnapshot(false, s.profile, s.startTimeMs, 0, 0, Double.NaN,
                TrackPoint.NO_TIME, FixSource.NONE, s.savedCount, code, null,
                RecordingSnapshot.SELECTION_OFF, false, null, MatchResult.UNKNOWN, null, null, 0, 0,
                false, 0);
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

        String text;
        if (s.paused) {
            text = getString(R.string.trip_paused);
        } else if (s.points == 0 && s.match == null) {
            text = getString(R.string.waiting_for_gps);
        } else {
            text = TripText.notification(this, s);
        }

        // During the trip: Stop and Waypoint. After Stop: Continue and End.
        Notification.Builder b = builder();
        if (s.paused) {
            b.addAction(action(ACTION_CONTINUE, 3, R.drawable.ic_continue, R.string.trip_continue));
            b.addAction(action(ACTION_STOP, 1, R.drawable.ic_stop, R.string.trip_end));
        } else {
            b.addAction(action(ACTION_PAUSE, 2, R.drawable.ic_stop, R.string.stop_recording));
            b.addAction(action(ACTION_WAYPOINT, 4, R.drawable.ic_waypoint, R.string.waypoint));
        }
        return b
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title, Labels.profile(this, s.profile)))
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(s.startTimeMs)
                .setUsesChronometer(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    // A button of the notification. Each action has its own request code,
    // thus Android keeps the intents apart.
    private Notification.Action action(String name, int requestCode, int icon, int label) {
        Intent intent = new Intent(this, TrackService.class).setAction(name);
        PendingIntent pending = PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(this, icon),
                getString(label), pending).build();
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder() {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL_ID);
        }
        return new Notification.Builder(this);
    }
}
