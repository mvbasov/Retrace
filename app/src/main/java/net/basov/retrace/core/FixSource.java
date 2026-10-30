package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The source of the fixes now
// ----------------------------------------------------------------------
//
// The trip screen and the GPS line of the list screen show a small dot:
// - red: no fix now
// - yellow: the fixes come from the network location of Android
// - green: the fixes come from the GPS receiver
//
// The GPS receiver gives one fix each second. Thus a GPS fix older than
// GPS_FRESH_MS tells that the receiver lost the satellites. The network
// location gives fixes less frequently, sometimes only after a movement.
// Thus a network fix stays current for NETWORK_FRESH_MS.
//
// From Android 12, the trip uses the fused location of Android. A fused fix
// can come from the GPS, from the network, or from the two. ofFused() gives
// its source from the satellites that the GPS receiver uses now. With
// MIN_SATELLITES or more, the GPS gives the position. Without a satellite
// count, the accuracy decides.
public final class FixSource {

    public static final int NONE = 0;
    public static final int NETWORK = 1;
    public static final int GPS = 2;

    public static final long GPS_FRESH_MS = GpsReadiness.FRESH_MS;
    public static final long NETWORK_FRESH_MS = 30000;

    // A GPS receiver needs four satellites for a position.
    public static final int MIN_SATELLITES = 4;

    // A fused fix with this accuracy or better and no satellite count is a
    // GPS fix. A network fix from Wi-Fi is rarely better.
    public static final double GPS_ACCURACY_M = 10;

    private FixSource() {
    }

    // The source of the last fix and its age. An age of GpsReadiness.NO_FIX
    // is no fix.
    public static int of(int source, long fixAgeMs) {
        if (fixAgeMs < 0) {
            return NONE;
        }
        if (source == GPS && fixAgeMs <= GPS_FRESH_MS) {
            return GPS;
        }
        if (source == NETWORK && fixAgeMs <= NETWORK_FRESH_MS) {
            return NETWORK;
        }
        return NONE;
    }

    // The source of a fused fix. satellitesUsed is -1 if it is not known.
    // statusAgeMs is the age of the satellite count, or GpsReadiness.NO_FIX.
    public static int ofFused(int satellitesUsed, long statusAgeMs, double accuracyM) {
        boolean counted = satellitesUsed >= 0 && statusAgeMs >= 0
                && statusAgeMs <= GPS_FRESH_MS;
        if (counted) {
            return satellitesUsed >= MIN_SATELLITES ? GPS : NETWORK;
        }
        return !Double.isNaN(accuracyM) && accuracyM <= GPS_ACCURACY_M ? GPS : NETWORK;
    }

    // The better of the two sources, from the age of the last fix of each.
    // The GPS comes first, the same as in FixSelector.
    public static int best(long gpsAgeMs, long networkAgeMs) {
        if (of(GPS, gpsAgeMs) == GPS) {
            return GPS;
        }
        return of(NETWORK, networkAgeMs);
    }
}
