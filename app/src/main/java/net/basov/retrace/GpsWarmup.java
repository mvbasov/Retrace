package net.basov.retrace;

import android.annotation.TargetApi;
import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import net.basov.retrace.core.GpsReadiness;

// ----------------------------------------------------------------------
// The GPS receiver before a trip
// ----------------------------------------------------------------------
//
// A GPS receiver needs some time for its first fix, mostly after a long
// pause. The GPS button of the list screen starts the receiver before the
// trip. Then the trip starts with a good fix. The list screen shows the
// state of the receiver and the accuracy of its last fix.
//
// The receiver runs on the main thread and keeps no point. It stops:
// - after a push on the button
// - at the start of a trip, because TrackService then has the receiver
// - when the list screen closes
// - after MAX_MS, if the user forgets it. The receiver uses much power.
//
// Android 7 and newer also tell the satellites. The class Satellites uses
// an API of Android 7. Thus only a check of the version loads it.
//
// All fields change on the main thread only. Thus they need no lock.
final class GpsWarmup {

    static final long MAX_MS = 10 * 60 * 1000;
    private static final long INTERVAL_MS = 1000;

    // The state for the screen. A count of -1 is not known.
    static final class Status {
        final boolean running;
        final boolean locationOn;
        final long fixAgeMs;
        final double accuracyM;
        final int satellitesUsed;
        final int satellitesVisible;

        Status(boolean running, boolean locationOn, long fixAgeMs, double accuracyM,
               int satellitesUsed, int satellitesVisible) {
            this.running = running;
            this.locationOn = locationOn;
            this.fixAgeMs = fixAgeMs;
            this.accuracyM = accuracyM;
            this.satellitesUsed = satellitesUsed;
            this.satellitesVisible = satellitesVisible;
        }
    }

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable TIMEOUT = GpsWarmup::stop;

    private static LocationManager manager;
    private static boolean running;
    // The time of the last fix on the clock since the boot, or NO_FIX.
    private static long lastFixMs = GpsReadiness.NO_FIX;
    private static double accuracyM = Double.NaN;
    private static int satellitesUsed = -1;
    private static int satellitesVisible = -1;
    // A Satellites object on Android 7 and newer. The type is Object, thus
    // Android 6 does not load the class.
    private static Object satellites;

    private static final LocationListener LISTENER = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastFixMs = SystemClock.elapsedRealtime();
            accuracyM = location.hasAccuracy() ? location.getAccuracy() : Double.NaN;
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

    private GpsWarmup() {
    }

    // Returns false if Android refuses the receiver: no permission, or no
    // GPS receiver in the device.
    static boolean start(Context context) {
        if (running) {
            return true;
        }
        Context app = context.getApplicationContext();
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return false;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, LISTENER,
                    Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
        manager = lm;
        running = true;
        lastFixMs = GpsReadiness.NO_FIX;
        accuracyM = Double.NaN;
        satellitesUsed = -1;
        satellitesVisible = -1;
        if (Build.VERSION.SDK_INT >= 24) {
            satellites = Satellites.register(app, lm);
        }
        HANDLER.postDelayed(TIMEOUT, MAX_MS);
        return true;
    }

    static void stop() {
        HANDLER.removeCallbacks(TIMEOUT);
        if (!running) {
            return;
        }
        manager.removeUpdates(LISTENER);
        if (Build.VERSION.SDK_INT >= 24 && satellites != null) {
            Satellites.unregister(manager, satellites);
        }
        satellites = null;
        manager = null;
        running = false;
    }

    static boolean isRunning() {
        return running;
    }

    static Status status() {
        if (!running) {
            return new Status(false, true, GpsReadiness.NO_FIX, Double.NaN, -1, -1);
        }
        long age = lastFixMs == GpsReadiness.NO_FIX
                ? GpsReadiness.NO_FIX : SystemClock.elapsedRealtime() - lastFixMs;
        return new Status(true, manager.isProviderEnabled(LocationManager.GPS_PROVIDER), age,
                accuracyM, satellitesUsed, satellitesVisible);
    }

    // The satellites in view and in use, from Android 7.
    @TargetApi(24)
    private static final class Satellites extends GnssStatus.Callback {

        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            int count = status.getSatelliteCount();
            int used = 0;
            for (int i = 0; i < count; i++) {
                if (status.usedInFix(i)) {
                    used++;
                }
            }
            satellitesUsed = used;
            satellitesVisible = count;
        }

        // Returns null if Android refuses the callback.
        @SuppressWarnings("deprecation")
        static Object register(Context app, LocationManager lm) {
            Satellites callback = new Satellites();
            try {
                boolean ok;
                if (Build.VERSION.SDK_INT >= 30) {
                    ok = lm.registerGnssStatusCallback(app.getMainExecutor(), callback);
                } else {
                    ok = lm.registerGnssStatusCallback(callback, HANDLER);
                }
                return ok ? callback : null;
            } catch (SecurityException e) {
                return null;
            }
        }

        static void unregister(LocationManager lm, Object callback) {
            lm.unregisterGnssStatusCallback((Satellites) callback);
        }
    }
}
