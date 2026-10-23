package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The test of TrackMatcher
// ----------------------------------------------------------------------
//
// Each test makes a reference track and a current track with TestTracks.
// Both tracks have GPS-like noise with different seeds, as two real trips
// have. The test gives each point of the current track to the matcher and
// checks the results.
//
// The tolerances come from the noise. The drift of two trips is different,
// and it stays for minutes. Thus the two tracks differ by about sigma times
// the square root of 2 along the route. At a walking speed of 1.4 m/s and a
// sigma of 4 m, that is about 4 s. This value is TYPICAL_TIME_S. Each time
// tolerance is a multiple of it.
public final class TrackMatcherTest {

    private static int failures = 0;

    private static final long DAY_MS = 86400000L;
    private static final double WALK = 1.4;
    private static final double SIGMA = 4;

    // The typical time difference of two trips at the same speed. See the
    // banner.
    private static final double TYPICAL_TIME_S = SIGMA * Math.sqrt(2) / WALK;

    // Three times the typical value.
    private static final double TIME_TOLERANCE_S = 3 * TYPICAL_TIME_S;

    // The finish circle: the radius of the profile plus twice the accuracy.
    private static final double FINISH_CIRCLE_M = Profile.WALKING.radiusM + 2 * SIGMA;

    private static final double[][] STRAIGHT = {{0, 0}, {0, 1100}};
    private static final double[][] CORNERS = {{0, 0}, {0, 500}, {600, 500}, {600, 900}};
    private static final double[][] DETOUR = {{0, 0}, {0, 400}, {150, 450}, {150, 650},
            {0, 700}, {0, 1100}};
    private static final double[][] OUT_AND_BACK = {{0, 0}, {0, 800}, {0, 0}};
    private static final double[][] SQUARE = {{0, 0}, {400, 0}, {400, 400}, {0, 400}, {0, 0}};
    private static final double[][] FIGURE_EIGHT = {{0, 0}, {300, 300}, {300, 0}, {0, 300},
            {0, 0}};
    private static final double[][] PARALLEL = {{200, 0}, {200, 1100}};

    public static void main(String[] args) {
        theSameSpeedGivesNoDifference();
        aFasterTripIsAhead();
        aSlowerTripIsBehind();
        aDetourIsOffRoute();
        aRouteOutAndBackDoesNotJump();
        aLoopFinishesOnlyAtTheEnd();
        aFigureEightPassesTheCrossing();
        aTripJoinsTheRouteAwayFromTheStart();
        aWaitAtTheStartDoesNotCount();
        aGpsGapDoesNotLoseTheRoute();
        aRouteWithNoTimesGivesDistancesOnly();
        aDifferentRouteNeverStarts();
        theGhostIsAtThePositionOfTheReference();
        aDrivingTripAtTheSameSpeed();
        aTooShortReferenceIsAnError();

        if (failures > 0) {
            System.out.println("TrackMatcherTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("TrackMatcherTest: every check passed");
    }

    // Two trips at the same speed must give a time difference near zero
    // during the whole trip, and at the finish.
    private static void theSameSpeedGivesNoDifference() {
        List<MatchResult> r = run(Profile.WALKING, trip(CORNERS, WALK, 0, 1), trip(CORNERS, WALK, 0, 2));
        check("same: never off route", false, hasState(r, TrackMatcher.State.OFF_ROUTE));
        MatchResult last = last(r);
        check("same: finished", TrackMatcher.State.FINISHED, last.state);
        near("same: time at finish", 0, last.deltaTimeMs / 1000.0, TIME_TOLERANCE_S);
        // GPS noise moves the difference around zero. An error of 5 m along
        // the route is about 4 s at a walking speed. Thus the test checks the
        // typical size of the difference and a limit for a single peak.
        double sum = 0;
        double peak = 0;
        int count = 0;
        for (MatchResult m : r) {
            if (m.state == TrackMatcher.State.ON_ROUTE) {
                double d = m.deltaTimeMs / 1000.0;
                sum += d * d;
                peak = Math.max(peak, Math.abs(d));
                count++;
            }
        }
        near("same: typical time difference", 0, Math.sqrt(sum / count), 2 * TYPICAL_TIME_S);
        near("same: peak time difference", 0, peak, 5 * TYPICAL_TIME_S);
        // The trip finishes when it enters the finish circle.
        near("same: place at finish", last.totalM, last.alongM, FINISH_CIRCLE_M + 5);
    }

    // A trip 10 percent faster saves about 9 percent of the time.
    private static void aFasterTripIsAhead() {
        Track reference = trip(STRAIGHT, WALK, 0, 3);
        List<MatchResult> r = run(Profile.WALKING, reference, trip(STRAIGHT, WALK * 1.1, 0, 4));
        MatchResult last = last(r);
        check("faster: finished", TrackMatcher.State.FINISHED, last.state);
        double expected = -(1085 / WALK) * (1 - 1 / 1.1);
        near("faster: time at finish", expected, last.deltaTimeMs / 1000.0, TIME_TOLERANCE_S);
        check("faster: ahead at finish", true, last.ahead());

        MatchResult half = atPlace(r, 550);
        check("faster: ahead of the ghost", true, half.deltaDistanceM > 0);
        // One speed value has noise. The mean over the middle of the trip
        // does not.
        near("faster: speed ratio", 1.1, meanSpeedRatio(r), 0.05);
        near("faster: predicted finish", last.elapsedMs / 1000.0, half.predictedTotalMs / 1000.0,
                last.elapsedMs / 1000.0 * 0.05);
    }

    // A trip at 80 percent of the speed needs 25 percent more time.
    private static void aSlowerTripIsBehind() {
        List<MatchResult> r = run(Profile.WALKING, trip(STRAIGHT, WALK, 0, 5),
                trip(STRAIGHT, WALK * 0.8, 0, 6));
        MatchResult last = last(r);
        check("slower: finished", TrackMatcher.State.FINISHED, last.state);
        near("slower: time at finish", (1085 / WALK) * 0.25, last.deltaTimeMs / 1000.0,
                TIME_TOLERANCE_S);
        MatchResult half = atPlace(r, 550);
        check("slower: behind the ghost", true, half.deltaDistanceM < 0);
        check("slower: behind", false, half.ahead());
    }

    // The detour goes 150 m away from the route. The trip must be off route
    // there, then on route again, and it must reach the finish.
    private static void aDetourIsOffRoute() {
        List<MatchResult> r = run(Profile.WALKING, trip(STRAIGHT, WALK, 0, 7), trip(DETOUR, WALK, 0, 8));
        check("detour: off route", true, hasState(r, TrackMatcher.State.OFF_ROUTE));
        MatchResult last = last(r);
        check("detour: finished", TrackMatcher.State.FINISHED, last.state);
        boolean onRouteAfter = false;
        boolean wasOff = false;
        for (MatchResult m : r) {
            wasOff |= m.state == TrackMatcher.State.OFF_ROUTE;
            if (wasOff && m.state == TrackMatcher.State.ON_ROUTE) {
                onRouteAfter = true;
                near("detour: place after the detour", 700.0 / 1100, m.alongM / m.totalM, 0.04);
                break;
            }
        }
        check("detour: on route again", true, onRouteAfter);
        check("detour: behind at finish", true, last.deltaTimeMs > 0);
    }

    // The way back lies on the way out. The place must continue on the way
    // back, and the finish must come only at the end.
    private static void aRouteOutAndBackDoesNotJump() {
        Track current = trip(OUT_AND_BACK, WALK, 0, 10);
        List<MatchResult> r = run(Profile.WALKING, trip(OUT_AND_BACK, WALK, 0, 9), current);
        check("out and back: finished", TrackMatcher.State.FINISHED, last(r).state);
        check("out and back: finish at the end", true, firstFinish(r) > current.points.size() - 30);
        check("out and back: no jump back", 0, jumpsBack(r));
        MatchResult back = r.get(r.size() * 3 / 4);
        near("out and back: place on the way back", 0.75, back.alongM / back.totalM, 0.05);
    }

    // The start and the finish of a loop are at the same place. The trip must
    // not finish at the start.
    private static void aLoopFinishesOnlyAtTheEnd() {
        Track current = trip(SQUARE, WALK, 0, 12);
        List<MatchResult> r = run(Profile.WALKING, trip(SQUARE, WALK, 0, 11), current);
        check("loop: finished", TrackMatcher.State.FINISHED, last(r).state);
        check("loop: finish at the end", true, firstFinish(r) > current.points.size() - 30);
        near("loop: time at finish", 0, last(r).deltaTimeMs / 1000.0, TIME_TOLERANCE_S);
    }

    // At the crossing, two parts of the route are near. The place must stay
    // on the current part.
    private static void aFigureEightPassesTheCrossing() {
        List<MatchResult> r = run(Profile.WALKING, trip(FIGURE_EIGHT, WALK, 0, 13),
                trip(FIGURE_EIGHT, WALK, 0, 14));
        check("figure eight: finished", TrackMatcher.State.FINISHED, last(r).state);
        check("figure eight: no jump back", 0, jumpsBack(r));
        check("figure eight: never off route", false, hasState(r, TrackMatcher.State.OFF_ROUTE));
    }

    // A trip that starts at 40 percent of the route joins it there. The time
    // difference then starts at zero.
    private static void aTripJoinsTheRouteAwayFromTheStart() {
        double[][] secondPart = {{0, 440}, {0, 1100}};
        List<MatchResult> r = run(Profile.WALKING, trip(STRAIGHT, WALK, 0, 15),
                trip(secondPart, WALK, 0, 16));
        MatchResult first = null;
        for (MatchResult m : r) {
            if (m.started()) {
                first = m;
                break;
            }
        }
        check("join: started", true, first != null);
        near("join: place", 445.0 / 1100, first.alongM / first.totalM, 0.03);
        near("join: time at join", 0, first.deltaTimeMs / 1000.0, 3);
        near("join: time at finish", 0, last(r).deltaTimeMs / 1000.0, TIME_TOLERANCE_S);
    }

    // The trip starts when it leaves the start circle. A wait of one minute at
    // the start must not count, also when the reference track waited less.
    private static void aWaitAtTheStartDoesNotCount() {
        List<TrackPoint> reference = TestTracks.noisy(
                TestTracks.ideal(STRAIGHT, WALK, TestTracks.START_MS, 10), 17, 2);
        List<TrackPoint> current = TestTracks.noisy(
                TestTracks.ideal(STRAIGHT, WALK, TestTracks.START_MS + DAY_MS, 60), 18, 2);
        List<MatchResult> r = run(Profile.WALKING, TestTracks.track("ref", reference),
                TestTracks.track("cur", current));
        check("wait: at start first", TrackMatcher.State.AT_START, r.get(30).state);
        near("wait: time at finish", 0, last(r).deltaTimeMs / 1000.0, TIME_TOLERANCE_S);
    }

    // A gap of 90 s in the GPS fixes is about 126 m of route. The search window
    // grows with the gap. Thus the trip is on route after the gap.
    private static void aGpsGapDoesNotLoseTheRoute() {
        Track current = trip(STRAIGHT, WALK, 0, 20);
        long gapStart = current.points.get(300).timeMs;
        Track withGap = TestTracks.track("gap",
                TestTracks.withGap(current.points, gapStart, gapStart + 90000));
        List<MatchResult> r = run(Profile.WALKING, trip(STRAIGHT, WALK, 0, 19), withGap);
        check("gap: never off route", false, hasState(r, TrackMatcher.State.OFF_ROUTE));
        check("gap: finished", TrackMatcher.State.FINISHED, last(r).state);
        near("gap: time at finish", 0, last(r).deltaTimeMs / 1000.0, TIME_TOLERANCE_S);
    }

    // A GPX route has no times. The matcher must still give the place, the
    // remaining distance and the finish.
    private static void aRouteWithNoTimesGivesDistancesOnly() {
        Track route = TestTracks.track("route",
                TestTracks.withoutTimes(TestTracks.ideal(CORNERS, WALK, 0, 0)));
        List<MatchResult> r = run(Profile.WALKING, route, trip(CORNERS, WALK, 0, 21));
        MatchResult half = r.get(r.size() / 2);
        check("no times: no time", false, half.hasTime);
        check("no times: unknown delta", MatchResult.UNKNOWN, half.deltaTimeMs);
        near("no times: remaining", 0.5, half.remainingM / half.totalM, 0.05);
        check("no times: elapsed", true, half.elapsedMs > 0);
        check("no times: finished", TrackMatcher.State.FINISHED, last(r).state);
    }

    // A trip 200 m beside the route is a different route. It must not start.
    private static void aDifferentRouteNeverStarts() {
        List<MatchResult> r = run(Profile.WALKING, trip(STRAIGHT, WALK, 0, 22),
                trip(PARALLEL, WALK, 0, 23));
        check("different: never started", false, hasStarted(r));
        check("different: waiting", TrackMatcher.State.WAITING, last(r).state);
    }

    // At the same speed, the ghost is near the current position.
    private static void theGhostIsAtThePositionOfTheReference() {
        Track current = trip(CORNERS, WALK, 0, 25);
        List<MatchResult> r = run(Profile.WALKING, trip(CORNERS, WALK, 0, 24), current);
        int index = current.points.size() / 2;
        MatchResult m = r.get(index);
        TrackPoint p = current.points.get(index);
        double gap = Geo.distanceM(m.ghostLatitude, m.ghostLongitude, p.latitude, p.longitude);
        // The ghost is on the reference track, and the fix has its own drift.
        // The drift of each track has a size of sigma on each axis. Thus the
        // two positions differ by about 2 sigma, and 4 times that is the limit.
        near("ghost: near the current position", 0, gap, 8 * SIGMA);
    }

    // The driving profile has larger limits and a higher speed. A trip of
    // 5 km at 15 m/s must give the same result as the walking tests.
    private static void aDrivingTripAtTheSameSpeed() {
        double[][] road = {{0, 0}, {2000, 0}, {2000, 1500}, {3500, 1500}};
        Track reference = TestTracks.track("ref", TestTracks.noisy(
                TestTracks.ideal(road, 15, TestTracks.START_MS, 0), 26, 6));
        Track current = TestTracks.track("cur", TestTracks.noisy(
                TestTracks.ideal(road, 15, TestTracks.START_MS + DAY_MS, 0), 27, 6));
        List<MatchResult> r = run(Profile.DRIVING, reference, current);
        check("driving: never off route", false, hasState(r, TrackMatcher.State.OFF_ROUTE));
        check("driving: finished", TrackMatcher.State.FINISHED, last(r).state);
        near("driving: time at finish", 0, last(r).deltaTimeMs / 1000.0, 4);
    }

    private static void aTooShortReferenceIsAnError() {
        boolean thrown = false;
        try {
            List<TrackPoint> one = new ArrayList<>();
            one.add(TestTracks.point(0, 0, TestTracks.START_MS));
            new TrackMatcher(TestTracks.track("one", one), Profile.WALKING);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        check("one point: error", true, thrown);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    // A noisy trip along the corners. Each seed gives a different trip, and
    // each trip starts on a different day.
    private static Track trip(double[][] corners, double speed, int waitS, long seed) {
        List<TrackPoint> points = TestTracks.noisy(
                TestTracks.ideal(corners, speed, TestTracks.START_MS + seed * DAY_MS, waitS),
                seed, SIGMA);
        return TestTracks.track("trip " + seed, TestTracks.recorded(points, Profile.WALKING));
    }

    private static List<MatchResult> run(Profile profile, Track reference, Track current) {
        TrackMatcher matcher = new TrackMatcher(reference, profile);
        List<MatchResult> results = new ArrayList<>();
        for (TrackPoint p : current.points) {
            results.add(matcher.update(p));
        }
        return results;
    }

    private static MatchResult last(List<MatchResult> r) {
        return r.get(r.size() - 1);
    }

    private static boolean hasState(List<MatchResult> r, TrackMatcher.State state) {
        for (MatchResult m : r) {
            if (m.state == state) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStarted(List<MatchResult> r) {
        for (MatchResult m : r) {
            if (m.started()) {
                return true;
            }
        }
        return false;
    }

    // The first result on route at or after a distance along the route.
    private static MatchResult atPlace(List<MatchResult> r, double placeM) {
        for (MatchResult m : r) {
            if (m.state == TrackMatcher.State.ON_ROUTE && m.alongM >= placeM) {
                return m;
            }
        }
        return null;
    }

    // The mean of speed / reference speed over the middle 40 percent of the
    // route.
    private static double meanSpeedRatio(List<MatchResult> r) {
        double sum = 0;
        int count = 0;
        for (MatchResult m : r) {
            double f = m.alongM / m.totalM;
            if (m.state == TrackMatcher.State.ON_ROUTE && f > 0.3 && f < 0.7
                    && m.speedMps > 0 && m.referenceSpeedMps > 0) {
                sum += m.speedMps / m.referenceSpeedMps;
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static int firstFinish(List<MatchResult> r) {
        for (int i = 0; i < r.size(); i++) {
            if (r.get(i).state == TrackMatcher.State.FINISHED) {
                return i;
            }
        }
        return -1;
    }

    // Counts each step back of more than 50 m along the route. Noise moves
    // the place back a little, but a jump to an earlier part of the route is
    // a fault.
    private static int jumpsBack(List<MatchResult> r) {
        int jumps = 0;
        double before = Double.NaN;
        for (MatchResult m : r) {
            if (!m.started()) {
                continue;
            }
            if (!Double.isNaN(before) && m.alongM < before - 50) {
                jumps++;
            }
            before = m.alongM;
        }
        return jumps;
    }

    private static void near(String name, double expected, double actual, double tolerance) {
        if (Double.isNaN(actual) || Math.abs(expected - actual) > tolerance) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + " +- " + tolerance
                    + ", got " + actual);
        }
    }

    private static void check(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
