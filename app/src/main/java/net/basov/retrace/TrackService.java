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

import java.io.IOException;
import java.io.Writer;

import net.basov.retrace.core.PointLog;
import net.basov.retrace.core.Profile;
import net.basov.retrace.core.RecordFilter;
import net.basov.retrace.core.RecordingSnapshot;
import net.basov.retrace.core.TrackPoint;

// ----------------------------------------------------------------------
// The recording of the current track
// ----------------------------------------------------------------------
//
// A foreground service with a notification. It continues to record when the
// screen is off. MainActivity starts it with ACTION_START and stops it with
// ACTION_STOP. The stop button of the notification also sends ACTION_STOP.
//
// One worker thread does all the work: the GPS fixes, the filter and the log
// file. Thus the fields of the recording need no lock. The main thread only
// receives the intents and posts the work to the worker thread.
//
// The service returns START_NOT_STICKY. If Android stops the process, the
// log file keeps the points. The next start of MainActivity saves them.
public final class TrackService extends Service {

    static final String ACTION_START = "net.basov.retrace.action.START";
    static final String ACTION_STOP = "net.basov.retrace.action.STOP";
    static final String EXTRA_PROFILE = "net.basov.retrace.extra.PROFILE";

    private static final String CHANNEL_ID = "recording";
    private static final int NOTIFICATION_ID = 1;

    // The GPS receiver gives one fix each second. The filter then drops the
    // fixes that are too near. See RecordFilter.
    private static final long FIX_INTERVAL_MS = 1000;

    // An update of the notification each second uses battery for no value.
    private static final long NOTIFICATION_INTERVAL_MS = 5000;

    // The last state of the recording. The activity reads it.
    private static volatile RecordingSnapshot snapshot = RecordingSnapshot.IDLE;

    // True from onCreate to onDestroy. TrackStore reads it before it saves an
    // old log file.
    private static volatile boolean active;

    static RecordingSnapshot snapshot() {
        return snapshot;
    }

    static boolean isActive() {
        return active;
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
            final long start = System.currentTimeMillis();
            // Android 8 and newer stop the service if startForeground does not
            // come soon after startForegroundService. Thus it comes first.
            if (!enterForeground(notification(p, start, 0, 0, false))) {
                snapshot = withError(RecordingSnapshot.ERROR_NO_PERMISSION);
                stopSelf();
                return START_NOT_STICKY;
            }
            worker.post(() -> begin(p, start));
        } else if (ACTION_STOP.equals(action)) {
            worker.post(() -> finish(true));
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
    private void begin(Profile p, long start) {
        if (recording) {
            return;
        }
        profile = p;
        startTimeMs = start;
        filter = new RecordFilter(p);
        lastAccuracyM = Double.NaN;
        lastFixTimeMs = TrackPoint.NO_TIME;
        error = RecordingSnapshot.ERROR_NONE;

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
        lastAccuracyM = fix.accuracyM;
        lastFixTimeMs = System.currentTimeMillis();

        if (filter.accept(fix)) {
            try {
                log.write(PointLog.line(fix));
                log.write('\n');
                log.flush();
            } catch (IOException e) {
                stopWithError(RecordingSnapshot.ERROR_STORAGE);
                return;
            }
        }
        publish();

        long now = System.currentTimeMillis();
        if (now - lastNotificationMs >= NOTIFICATION_INTERVAL_MS) {
            lastNotificationMs = now;
            notificationManager.notify(NOTIFICATION_ID, notification(
                    profile, startTimeMs, filter.count(), filter.distanceM(), true));
        }
    }

    // Worker thread. Stops the GPS updates and saves the track.
    private void finish(boolean stopService) {
        boolean saved = false;
        if (recording) {
            recording = false;
            locationManager.removeUpdates(listener);
            closeLog();
            try {
                saved = store.saveLog() != null;
            } catch (IOException e) {
                error = RecordingSnapshot.ERROR_STORAGE;
            }
        }
        RecordingSnapshot last = snapshot;
        snapshot = new RecordingSnapshot(false, profile, startTimeMs, filter.count(),
                filter.distanceM(), lastAccuracyM, lastFixTimeMs,
                last.savedCount + (saved ? 1 : 0), error);
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
                filter.distanceM(), lastAccuracyM, lastFixTimeMs, snapshot.savedCount, error);
    }

    private static RecordingSnapshot withError(int code) {
        RecordingSnapshot s = snapshot;
        return new RecordingSnapshot(false, s.profile, s.startTimeMs, 0, 0, Double.NaN,
                TrackPoint.NO_TIME, s.savedCount, code);
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

    private Notification notification(Profile p, long start, int points, double distanceM,
                                      boolean hasFixes) {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, TrackService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stop),
                getString(R.string.stop_recording), stopIntent).build();

        String text = hasFixes
                ? getString(R.string.notification_progress, points, Labels.distance(this, distanceM))
                : getString(R.string.waiting_for_gps);

        return builder()
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title, Labels.profile(this, p)))
                .setContentText(text)
                .setContentIntent(openIntent)
                .addAction(stopAction)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(start)
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
