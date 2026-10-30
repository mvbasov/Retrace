package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The choice between a GPS fix and a network fix
// ----------------------------------------------------------------------
//
// TrackService gets fixes from the GPS receiver and from the network
// location of Android. A network fix comes from Wi-Fi networks and cell
// towers. It is less accurate, but it also works where the GPS has no
// satellites, for example in a passage or near high buildings.
//
// The GPS comes first. A network fix is a fallback: Retrace uses it only if
// the GPS gave no fix for GPS_FRESH_MS. When the GPS gives fixes again, the
// network fixes wait again. FixSmoother and RecordFilter then apply the
// same rules to each fix. RecordFilter has a larger accuracy limit before
// the first precise point. Thus a network fix can start the track.
//
// The time of a GPS fix comes from the satellites. The time of a network
// fix comes from the clock of the device, and that clock can be some
// seconds wrong. Two time bases in one track can put a point before the
// point before it, and RecordFilter then drops good fixes. Thus all points
// get the time of the first fix plus the time on the clock since the boot
// of the device. That clock never goes back, and both providers give it.
//
// Android can give an old network fix from its cache. A fix older than
// MAX_AGE_MS is not used.
public final class FixSelector {

    public static final long GPS_FRESH_MS = 5000;
    public static final long MAX_AGE_MS = 10000;

    private boolean based;
    private long baseTimeMs;
    private long baseClockMs;
    private boolean gpsSeen;
    private long lastGpsClockMs;

    // Returns the time of the point, or TrackPoint.NO_TIME if Retrace does
    // not use the fix. fixTimeMs is the time that the provider gives. The
    // clock values are the time since the boot: when the provider made the
    // fix, and now.
    public long timeOf(boolean gps, long fixTimeMs, long fixClockMs, long nowClockMs) {
        if (nowClockMs - fixClockMs > MAX_AGE_MS) {
            return TrackPoint.NO_TIME;
        }
        if (gps) {
            gpsSeen = true;
            lastGpsClockMs = fixClockMs;
        } else if (gpsSeen && fixClockMs - lastGpsClockMs < GPS_FRESH_MS) {
            return TrackPoint.NO_TIME;
        }
        if (!based) {
            based = true;
            baseTimeMs = fixTimeMs;
            baseClockMs = fixClockMs;
        }
        return baseTimeMs + (fixClockMs - baseClockMs);
    }
}
