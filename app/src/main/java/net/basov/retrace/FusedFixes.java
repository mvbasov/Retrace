package net.basov.retrace;

import android.annotation.TargetApi;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Handler;
import android.os.SystemClock;

import java.util.concurrent.Executor;

import net.basov.retrace.core.FixSource;
import net.basov.retrace.core.GpsReadiness;

// ----------------------------------------------------------------------
// The fused location of Android 12 and newer
// ----------------------------------------------------------------------
//
// From Android 12, the location manager has the fused provider. It mixes the
// GPS, the network location and the motion sensors of the device into one
// fix. Where the GPS loses the satellites, for example near high buildings,
// it gives better fixes than the network location alone. The fused provider
// needs no library.
//
// TrackService uses the fused provider if the device has it. Otherwise, it
// uses the GPS and the network location as on older Android versions.
//
// The request asks for the high accuracy. Without it, the fused provider can
// use the network location only, to save power.
//
// A fused fix does not tell its source. A satellite callback counts the
// satellites that the GPS receiver uses now. FixSource.ofFused() then gives
// the source for the dot of the trip screen.
//
// TrackService keeps the object as an Object. Thus Android 6 to 11 never
// load this class. Each call comes after a check of the version.
//
// The callbacks run on the worker thread of TrackService. Thus the fields
// need no lock.
@TargetApi(31)
final class FusedFixes extends GnssStatus.Callback {

    private int satellitesUsed = -1;
    // The time of the last satellite count on the clock since the boot.
    private long statusClockMs = GpsReadiness.NO_FIX;

    private FusedFixes() {
    }

    @Override
    public void onSatelliteStatusChanged(GnssStatus status) {
        int used = 0;
        for (int i = 0; i < status.getSatelliteCount(); i++) {
            if (status.usedInFix(i)) {
                used++;
            }
        }
        satellitesUsed = used;
        statusClockMs = SystemClock.elapsedRealtime();
    }

    // Starts the fused fixes and the satellite count on the thread of the
    // handler. Returns null if the device has no fused provider, or if
    // Android refuses it. A missing satellite count is no fault.
    static Object start(LocationManager lm, long intervalMs, Handler handler,
                        LocationListener listener) {
        if (!lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
            return null;
        }
        Executor executor = handler::post;
        LocationRequest request = new LocationRequest.Builder(intervalMs)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(intervalMs)
                .build();
        try {
            lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, executor, listener);
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
        FusedFixes fused = new FusedFixes();
        try {
            lm.registerGnssStatusCallback(executor, fused);
        } catch (SecurityException e) {
            // The accuracy then gives the source.
        }
        return fused;
    }

    // Stops the satellite count. The removal of the listener stops the fixes.
    static void stop(LocationManager lm, Object fused) {
        lm.unregisterGnssStatusCallback((FusedFixes) fused);
    }

    static boolean isFused(Location location) {
        return LocationManager.FUSED_PROVIDER.equals(location.getProvider());
    }

    // The source of one fused fix. See FixSource.ofFused().
    static int source(Object fused, Location location) {
        FusedFixes f = (FusedFixes) fused;
        long age = f.statusClockMs == GpsReadiness.NO_FIX
                ? GpsReadiness.NO_FIX : SystemClock.elapsedRealtime() - f.statusClockMs;
        return FixSource.ofFused(f.satellitesUsed, age,
                location.hasAccuracy() ? location.getAccuracy() : Double.NaN);
    }
}
