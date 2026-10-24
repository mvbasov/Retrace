package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The state of the GPS before a trip
// ----------------------------------------------------------------------
//
// The list screen can start the GPS receiver before a trip. It then shows
// if the receiver is ready for the profile. The rules agree with
// RecordFilter: a fix with an accuracy worse than the limit of the profile
// does not become a point. A fix with no accuracy becomes a point.
//
// The receiver gives one fix each second. A fix older than FRESH_MS tells
// that the receiver lost the satellites, for example inside a building.
public final class GpsReadiness {

    public enum State {
        // The location of the device is off in the settings.
        OFF,
        // No fix, or the last fix is too old.
        SEARCHING,
        // A fix, but its accuracy is worse than the limit of the profile.
        TOO_LOW,
        // A fix that RecordFilter accepts.
        READY
    }

    public static final long FRESH_MS = 5000;

    // The age of a fix when there is no fix.
    public static final long NO_FIX = -1;

    private GpsReadiness() {
    }

    // The age of the fix and its accuracy come from the receiver. An
    // accuracy of NaN is not known.
    public static State of(boolean locationOn, long fixAgeMs, double accuracyM, Profile profile) {
        if (!locationOn) {
            return State.OFF;
        }
        if (fixAgeMs == NO_FIX || fixAgeMs > FRESH_MS) {
            return State.SEARCHING;
        }
        if (!Double.isNaN(accuracyM) && accuracyM > profile.accuracyLimitM) {
            return State.TOO_LOW;
        }
        return State.READY;
    }
}
