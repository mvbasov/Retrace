package net.basov.retrace.core;

// ----------------------------------------------------------------------
// One point of a track
// ----------------------------------------------------------------------
//
// A point is immutable. A value that is not known is NaN for a double, and
// NO_TIME for the time.
public final class TrackPoint {

    // The time of a point that has no time, for example a point of a route.
    public static final long NO_TIME = Long.MIN_VALUE;

    public final double latitude;
    public final double longitude;

    // The UTC time in milliseconds since 1970.
    public final long timeMs;

    // The height above sea level in meters, or NaN.
    public final double elevationM;

    // The accuracy of the fix in meters, or NaN. GPX files do not hold it.
    public final double accuracyM;

    public TrackPoint(double latitude, double longitude, long timeMs,
                      double elevationM, double accuracyM) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.timeMs = timeMs;
        this.elevationM = elevationM;
        this.accuracyM = accuracyM;
    }

    public boolean hasTime() {
        return timeMs != NO_TIME;
    }
}
