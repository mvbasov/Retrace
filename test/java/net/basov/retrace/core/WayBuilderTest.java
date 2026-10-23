package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The tests of WayBuilder
// ----------------------------------------------------------------------
//
// Five invented walks on one route, each at its own speed and with its own
// GPS noise. One of them waits two minutes at the start. The way must be
// nearer to the route than one walk, and its time must be the mean time of
// the walks.
//
// The tolerances come from the noise model of TestTracks. One time at one
// place has an error of about TYPICAL_TIME_S. See TrackMatcherTest. Runs of
// 100 seeds at a noise of 4 m and 8 m gave the limits below.
public final class WayBuilderTest {

    private static int failures = 0;

    private static final double SIGMA = 4;
    private static final double[] SPEEDS = {1.2, 1.3, 1.4, 1.5, 1.6};
    private static final double TYPICAL_TIME_S = SIGMA * Math.sqrt(2) / 1.4;
    private static final long DAY_MS = 86400000L;

    private static final double[][] ROUTE = {{0, 0}, {800, 0}, {800, 600}};
    private static final double[][] OUT_AND_BACK = {{0, 0}, {0, 500}, {0, 0}};
    private static final double[][] OTHER = {{0, 0}, {300, 0}, {300, -600}};
    private static final double[][] RIDE = {{0, 0}, {2000, 0}, {2000, 1500}};

    public static void main(String[] args) {
        theWayIsNearerToTheRouteThanOneTrack();
        theTimeOfTheWayIsTheMeanTime();
        aTripAtTheMeanSpeedIsOnTime();
        aWayOutAndBackHasTheLengthOfTheRoute();
        theBaseTrackDoesNotChangeTheResult();
        aTrackOnADifferentRouteIsNotUsed();
        aTrackThatStopsHalfwayIsNotUsed();
        aTrackFarFromTheStartIsNotUsed();
        oneGoodTrackGivesNoWay();
        theTimesOfTheWayIncrease();
        aCyclingWayHasTheCyclingStep();

        if (failures > 0) {
            System.out.println("WayBuilderTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("WayBuilderTest: every check passed");
    }

    // The GPS errors of different trips are not related. The mean of five
    // tracks has about 1 / sqrt(5) of the error of one track. The limit
    // 0.8 is above the worst of 100 seeds.
    private static void theWayIsNearerToTheRouteThanOneTrack() {
        List<Track> walks = walks(ROUTE, 10);
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, walks, 0);
        check("near: all used", 5, r.used.size());
        double member = 0;
        for (Track t : walks) {
            member += meanError(ROUTE, t.points) / walks.size();
        }
        double way = meanError(ROUTE, r.way.points);
        check("near: way error " + round(way) + " m, track error " + round(member) + " m",
                true, way < 0.8 * member);
    }

    // The wait at the start of one walk is not a part of its time. The
    // time of the way is the mean of the times of the walks.
    private static void theTimeOfTheWayIsTheMeanTime() {
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, walks(ROUTE, 20), 0);
        near("time: duration", meanDurationS(ROUTE), r.way.durationMs() / 1000.0,
                3 * TYPICAL_TIME_S);
        check("time: profile", Profile.WALKING, r.way.profile);
        check("time: no members yet", false, r.way.isWay());
    }

    // The way is a reference track. A trip at 1.4 m/s has a known time
    // difference at the finish. The trip and the way each have their own
    // error, thus the limit is larger.
    private static void aTripAtTheMeanSpeedIsOnTime() {
        Track way = WayBuilder.build("way", Profile.WALKING, walks(ROUTE, 30), 0).way;
        List<TrackPoint> trip = TestTracks.recorded(TestTracks.noisy(TestTracks.ideal(ROUTE, 1.4,
                TestTracks.START_MS + 20 * DAY_MS, 0), 31, SIGMA), Profile.WALKING);
        TrackMatcher matcher = new TrackMatcher(way, Profile.WALKING);
        MatchResult last = null;
        for (TrackPoint p : trip) {
            last = matcher.update(p);
        }
        check("race: finished", TrackMatcher.State.FINISHED, last.state);
        double expected = length(ROUTE) / 1.4 - meanDurationS(ROUTE);
        near("race: time at the finish", expected, last.deltaTimeMs / 1000.0, 4 * TYPICAL_TIME_S);
    }

    // At the turn, the two legs are at the same place. The projection must
    // not jump to the other leg.
    private static void aWayOutAndBackHasTheLengthOfTheRoute() {
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, walks(OUT_AND_BACK, 40), 0);
        check("out and back: all used", 5, r.used.size());
        near("out and back: length", length(OUT_AND_BACK), r.way.distanceM(),
                0.05 * length(OUT_AND_BACK));
        near("out and back: duration", meanDurationS(OUT_AND_BACK), r.way.durationMs() / 1000.0,
                3 * TYPICAL_TIME_S);
    }

    // The second pass uses the way as the base. Thus the first base track
    // changes the result only a little. The base here is the walk with the
    // wait at the start.
    private static void theBaseTrackDoesNotChangeTheResult() {
        List<Track> walks = walks(ROUTE, 50);
        Track a = WayBuilder.build("way", Profile.WALKING, walks, 0).way;
        Track b = WayBuilder.build("way", Profile.WALKING, walks, 2).way;
        near("base: duration", a.durationMs() / 1000.0, b.durationMs() / 1000.0, TYPICAL_TIME_S);
        near("base: length", a.distanceM(), b.distanceM(), 0.01 * a.distanceM());
    }

    // The first 300 m are the same. Then the track goes south and not
    // north. The way must not use it.
    private static void aTrackOnADifferentRouteIsNotUsed() {
        List<Track> tracks = walks(ROUTE, 60);
        tracks.add(walk(OTHER, 1.4, 66, 7, 0));
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, tracks, 0);
        check("other route: used", 5, r.used.size());
        check("other route: rejected", "[5]", r.rejected.toString());
    }

    private static void aTrackThatStopsHalfwayIsNotUsed() {
        List<Track> tracks = walks(ROUTE, 70);
        Track full = walk(ROUTE, 1.4, 77, 7, 0);
        tracks.add(new Track("half", Profile.WALKING,
                full.points.subList(0, full.points.size() / 2)));
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, tracks, 0);
        check("half: rejected", "[5]", r.rejected.toString());
        check("half: reason", "[" + WayBuilder.REJECT_FINISH + "]", r.reasons.toString());
    }

    private static void aTrackFarFromTheStartIsNotUsed() {
        List<Track> tracks = walks(ROUTE, 80);
        List<TrackPoint> moved = new ArrayList<>();
        for (TrackPoint p : walk(ROUTE, 1.4, 88, 7, 0).points) {
            moved.add(TestTracks.shift(p, 0, 400));
        }
        tracks.add(new Track("far", Profile.WALKING, moved));
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, tracks, 0);
        check("far: reason", "[" + WayBuilder.REJECT_START + "]", r.reasons.toString());
    }

    // A way needs two tracks. One track and one track of a different route
    // give no way.
    private static void oneGoodTrackGivesNoWay() {
        List<Track> tracks = new ArrayList<>();
        tracks.add(walk(ROUTE, 1.4, 90, 1, 0));
        tracks.add(walk(OTHER, 1.4, 91, 2, 0));
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, tracks, 0);
        check("one track: no way", true, r.way == null);
    }

    // The matcher and the GPX reader expect increasing times.
    private static void theTimesOfTheWayIncrease() {
        Track way = WayBuilder.build("way", Profile.WALKING, walks(ROUTE, 100), 0).way;
        boolean increasing = true;
        for (int i = 1; i < way.points.size(); i++) {
            increasing &= way.points.get(i).timeMs > way.points.get(i - 1).timeMs;
        }
        check("times increase", true, increasing);
        // The way starts at the start of its newest track. The automatic
        // selection then sorts it with its newest trip.
        check("start time", TestTracks.START_MS + 4 * DAY_MS, way.startTimeMs());
    }

    // A faster profile has a longer step between the stations.
    private static void aCyclingWayHasTheCyclingStep() {
        List<Track> rides = new ArrayList<>();
        double[] speeds = {5, 6, 7};
        double mean = 0;
        for (int i = 0; i < speeds.length; i++) {
            rides.add(new Track("r" + i, Profile.CYCLING, TestTracks.recorded(TestTracks.noisy(
                    TestTracks.ideal(RIDE, speeds[i], TestTracks.START_MS + i * DAY_MS, 0),
                    110 + i, SIGMA), Profile.CYCLING)));
            mean += length(RIDE) / speeds[i] / speeds.length;
        }
        WayBuilder.Result r = WayBuilder.build("ride", Profile.CYCLING, rides, 0);
        check("cycling: all used", 3, r.used.size());
        double step = WayBuilder.stepM(Profile.CYCLING);
        near("cycling: points", length(RIDE) / step, r.way.points.size(), 0.05 * length(RIDE) / step);
        near("cycling: duration", mean, r.way.durationMs() / 1000.0, 3 * SIGMA * Math.sqrt(2) / 6);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    // Five walks on the corners. Walk 2 waits two minutes at the start.
    private static List<Track> walks(double[][] corners, long seed) {
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < SPEEDS.length; i++) {
            tracks.add(walk(corners, SPEEDS[i], seed * 10 + i, i, i == 2 ? 120 : 0));
        }
        return tracks;
    }

    private static Track walk(double[][] corners, double speed, long seed, int day, int waitS) {
        return new Track("w" + seed, Profile.WALKING, TestTracks.recorded(TestTracks.noisy(
                TestTracks.ideal(corners, speed, TestTracks.START_MS + day * DAY_MS, waitS),
                seed, SIGMA), Profile.WALKING));
    }

    // The mean time of the walks from the start to the finish, with no
    // wait.
    private static double meanDurationS(double[][] corners) {
        double sum = 0;
        for (double v : SPEEDS) {
            sum += length(corners) / v;
        }
        return sum / SPEEDS.length;
    }

    private static double length(double[][] corners) {
        double sum = 0;
        for (int i = 1; i < corners.length; i++) {
            sum += Math.hypot(corners[i][0] - corners[i - 1][0], corners[i][1] - corners[i - 1][1]);
        }
        return sum;
    }

    // The mean distance of the points from the ideal route.
    private static double meanError(double[][] corners, List<TrackPoint> points) {
        TrackPoint origin = TestTracks.point(0, 0, 0);
        double sum = 0;
        for (TrackPoint p : points) {
            double x = Geo.distanceM(origin.latitude, origin.longitude, origin.latitude, p.longitude)
                    * Math.signum(p.longitude - origin.longitude);
            double y = Geo.distanceM(origin.latitude, origin.longitude, p.latitude, origin.longitude)
                    * Math.signum(p.latitude - origin.latitude);
            double best = Double.POSITIVE_INFINITY;
            for (int i = 1; i < corners.length; i++) {
                double ax = corners[i - 1][0];
                double ay = corners[i - 1][1];
                double dx = corners[i][0] - ax;
                double dy = corners[i][1] - ay;
                double f = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
                best = Math.min(best, Math.hypot(x - ax - f * dx, y - ay - f * dy));
            }
            sum += best;
        }
        return sum / points.size();
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static void near(String name, double expected, double actual, double tolerance) {
        if (Double.isNaN(actual) || Math.abs(expected - actual) > tolerance) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + " +- " + tolerance
                    + ", got " + actual);
        }
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
