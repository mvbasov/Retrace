package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The check that a trip still follows its automatic reference track
// ----------------------------------------------------------------------
//
// Two different routes can have the same start and the same first meters.
// The automatic selection can then select the wrong route, because the
// selection comes after some meters only. The trip then goes away from that
// route, and the comparison is off route until the end. That result has no
// value.
//
// This class adds the distance of each step of the trip to one of two sums:
// on route or off route. The states before the route and after the finish
// add nothing. The trip and the reference track differ when two rules are
// true:
// - The trip is LOST_FACTOR times the off-route limit of the profile off
//   the route, in all. For walking, this is 100 m.
// - The trip is more off the route than on it.
//
// The second rule keeps the comparison during a short detour on a long
// route.
//
// They also differ when the trip comes to the finish with less than
// MIN_COVERAGE of the route behind it. The coverage is the distance of the
// trip on the route. An example: a short loop that comes back to its start.
// Its start can be the finish of a long route in the opposite direction.
// The loop then finishes that long route after some meters only.
//
// The coverage does not use the distance along the route. On a loop, the
// start and the finish are at the same place. The projection can then go
// from the start to the finish in one step.
//
// After a difference, the result does not change again.
//
// ReferenceSelector drops a candidate that differs. TrackService stops the
// comparison with an automatic reference track that differs. A reference
// track that the user selected stays, because the user decided.
public final class RouteAgreement {

    public static final double LOST_FACTOR = 4;
    public static final double MIN_COVERAGE = 0.5;

    private final double lostM;
    private TrackPoint lastFix;
    private double onRouteM;
    private double offRouteM;
    private boolean finished;
    private boolean differs;

    public RouteAgreement(Profile profile) {
        this.lostM = LOST_FACTOR * profile.offRouteM;
    }

    // Takes one point of the trip and the result of the matcher for it.
    public void add(TrackPoint fix, MatchResult result) {
        double step = lastFix == null ? 0 : Geo.distanceM(lastFix, fix);
        lastFix = fix;
        if (differs || finished || result == null) {
            return;
        }
        if (result.state == TrackMatcher.State.FINISHED) {
            finished = true;
            if (onRouteM < MIN_COVERAGE * result.totalM) {
                differs = true;
            }
        } else if (result.state == TrackMatcher.State.ON_ROUTE) {
            onRouteM += step;
        } else if (result.state == TrackMatcher.State.OFF_ROUTE) {
            offRouteM += step;
            if (offRouteM >= lostM && offRouteM > onRouteM) {
                differs = true;
            }
        }
    }

    public boolean differs() {
        return differs;
    }

    public double onRouteM() {
        return onRouteM;
    }

    public double offRouteM() {
        return offRouteM;
    }
}
