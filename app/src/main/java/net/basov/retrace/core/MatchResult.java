package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The result of TrackMatcher for one fix
// ----------------------------------------------------------------------
//
// A result is immutable. A double value that is not known is NaN. A long
// value that is not known is UNKNOWN.
//
// The sign rules agree with doc/TERMINOLOGY.md:
// - deltaTimeMs below zero means ahead, above zero means behind.
// - deltaDistanceM above zero means ahead of the ghost.
public final class MatchResult {

    public static final long UNKNOWN = Long.MIN_VALUE;

    public final TrackMatcher.State state;

    // The distance from the fix to the nearest part of the route.
    public final double crossTrackM;

    // The distance along the route, s in doc/PLAN.md.
    public final double alongM;

    public final double totalM;

    // The distance along the route to the finish.
    public final double remainingM;

    // False if the reference track has no times. Then each time value is
    // UNKNOWN and each speed of the reference track is NaN.
    public final boolean hasTime;

    // The time since the start of the trip.
    public final long elapsedMs;

    // The elapsed time minus the time of the reference track at the same
    // distance along the route.
    public final long deltaTimeMs;

    // The distance along the route minus the distance of the ghost.
    public final double deltaDistanceM;

    // The smoothed speed along the route of the current track and of the
    // reference track at the same place.
    public final double speedMps;
    public final double referenceSpeedMps;

    // The predicted elapsed time at the finish, and the elapsed time of the
    // reference track at the finish.
    public final long predictedTotalMs;
    public final long referenceTotalMs;

    // The position of the ghost.
    public final double ghostLatitude;
    public final double ghostLongitude;

    MatchResult(TrackMatcher.State state, double crossTrackM, double alongM, double totalM,
                boolean hasTime, long elapsedMs, long deltaTimeMs, double deltaDistanceM,
                double speedMps, double referenceSpeedMps, long predictedTotalMs,
                long referenceTotalMs, double ghostLatitude, double ghostLongitude) {
        this.state = state;
        this.crossTrackM = crossTrackM;
        this.alongM = alongM;
        this.totalM = totalM;
        this.remainingM = Double.isNaN(alongM) ? Double.NaN : Math.max(0, totalM - alongM);
        this.hasTime = hasTime;
        this.elapsedMs = elapsedMs;
        this.deltaTimeMs = deltaTimeMs;
        this.deltaDistanceM = deltaDistanceM;
        this.speedMps = speedMps;
        this.referenceSpeedMps = referenceSpeedMps;
        this.predictedTotalMs = predictedTotalMs;
        this.referenceTotalMs = referenceTotalMs;
        this.ghostLatitude = ghostLatitude;
        this.ghostLongitude = ghostLongitude;
    }

    // True from the start of the trip. Before the start, Retrace has no
    // elapsed time and no comparison.
    public boolean started() {
        return state == TrackMatcher.State.ON_ROUTE || state == TrackMatcher.State.OFF_ROUTE
                || state == TrackMatcher.State.FINISHED;
    }

    public boolean ahead() {
        return deltaTimeMs != UNKNOWN && deltaTimeMs < 0;
    }
}
