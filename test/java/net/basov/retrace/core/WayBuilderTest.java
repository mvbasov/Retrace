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
    private static final double[][] BACK = {{800, 600}, {800, 0}, {0, 0}};
    private static final double[][] FROM_MIDDLE = {{500, 0}, {800, 0}, {800, 600}};
    private static final double[][] STREET_WEST = {{250, 0}, {0, 0}};
    private static final double[][] STREET_EAST = {{0, 0}, {250, 0}, {250, 40}};
    // A loop that starts at a corner, and the same loop from 25 m before the
    // corner. The loop ends 30 m before the corner, in the direction of the
    // first 25 m.
    private static final double[][] LOOP_AT_CORNER = {{0, 0}, {300, 0}, {300, -250},
            {0, -250}, {0, -30}};
    private static final double[][] LOOP_BEFORE_CORNER = {{0, -25}, {0, 0}, {300, 0},
            {300, -250}, {0, -250}, {0, -30}};

    public static void main(String[] args) {
        theWayIsNearerToTheRouteThanOneTrack();
        theTimeOfTheWayIsTheMeanTime();
        aTripAtTheMeanSpeedIsOnTime();
        aWayOutAndBackHasTheLengthOfTheRoute();
        theBaseTrackDoesNotChangeTheResult();
        aTrackOnADifferentRouteIsNotUsed();
        aTrackThatCoversTooLittleIsNotUsed();
        aTrackFarFromTheRouteIsNotUsed();
        oneGoodTrackGivesNoWay();
        theTimesOfTheWayIncrease();
        aCyclingWayHasTheCyclingStep();
        aGpsErrorAtTheStartDoesNotStopATrack();
        aTrackInTheOppositeDirectionJoinsTheWay();
        aTrackFromTheMiddleGivesItsPart();
        twoTracksInOppositeDirections();
        theMatchTellsTheCoverAndTheDirection();
        eachTrackOfALoopJoinsAtTheStart();

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

    // A track must cover at least half of the route. This one covers 30
    // percent.
    private static void aTrackThatCoversTooLittleIsNotUsed() {
        List<Track> tracks = walks(ROUTE, 70);
        Track full = walk(ROUTE, 1.4, 77, 7, 0);
        tracks.add(new Track("part", Profile.WALKING,
                full.points.subList(0, full.points.size() * 3 / 10)));
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, tracks, 0);
        check("part: rejected", "[5]", r.rejected.toString());
        check("part: reason", "[" + WayBuilder.REJECT_SHORT + "]", r.reasons.toString());
    }

    // The same shape 400 m to the north and 400 m to the east. No part of
    // it is near the route, thus it never joins the route.
    private static void aTrackFarFromTheRouteIsNotUsed() {
        List<Track> tracks = walks(ROUTE, 80);
        List<TrackPoint> moved = new ArrayList<>();
        for (TrackPoint p : walk(ROUTE, 1.4, 88, 7, 0).points) {
            moved.add(TestTracks.shift(p, 400, 400));
        }
        tracks.add(new Track("far", Profile.WALKING, moved));
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, tracks, 0);
        check("far: reason", "[" + WayBuilder.REJECT_NO_JOIN + "]", r.reasons.toString());
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

    // The first six points of one walk are a GPS error: they come from 110 m
    // before the start, at 18 m/s. The walk joins the route after them. The
    // error does not go into the way, and the walk gives its time.
    private static void aGpsErrorAtTheStartDoesNotStopATrack() {
        List<Track> tracks = walks(ROUTE, 120);
        List<TrackPoint> points = new ArrayList<>(walk(ROUTE, 1.4, 1205, 5, 0).points);
        for (int i = 0; i < 6; i++) {
            TrackPoint p = points.get(i);
            points.set(i, TestTracks.point(-110 + 18 * i, -15, p.timeMs));
        }
        tracks.add(new Track("jump", Profile.WALKING, points));
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, tracks, 0);
        check("jump: all used", 6, r.used.size());
        near("jump: duration", meanDurationS(ROUTE, 1.4), r.way.durationMs() / 1000.0,
                3 * TYPICAL_TIME_S);
        near("jump: start of the way", 0, distanceFrom(r.way.points.get(0), 0, 0), 3 * SIGMA);
    }

    // One walk goes from the finish to the start. The builder turns it
    // around. Its time is correct here, because the invented route is flat.
    private static void aTrackInTheOppositeDirectionJoinsTheWay() {
        List<Track> tracks = walks(ROUTE, 130);
        tracks.add(walk(BACK, 1.4, 1305, 6, 0));
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, tracks, 0);
        check("back: all used", 6, r.used.size());
        check("back: reversed", "[5]", r.reversed.toString());
        near("back: duration", meanDurationS(ROUTE, 1.4), r.way.durationMs() / 1000.0,
                3 * TYPICAL_TIME_S);
        near("back: start of the way", 0, distanceFrom(r.way.points.get(0), 0, 0), 3 * SIGMA);
    }

    // One slow walk starts in the middle of the route, at 500 m. It gives
    // its time to the part from 500 m only. The first part has the mean of
    // the five other walks.
    private static void aTrackFromTheMiddleGivesItsPart() {
        List<Track> tracks = walks(ROUTE, 140);
        tracks.add(walk(FROM_MIDDLE, 1.0, 1405, 6, 0));
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, tracks, 0);
        check("middle: all used", 6, r.used.size());
        double fivePerMeter = 0;
        for (double v : SPEEDS) {
            fivePerMeter += 1 / v / SPEEDS.length;
        }
        double sixPerMeter = (fivePerMeter * SPEEDS.length + 1 / 1.0) / (SPEEDS.length + 1);
        double expected = 500 * fivePerMeter + (length(ROUTE) - 500) * sixPerMeter;
        near("middle: duration", expected, r.way.durationMs() / 1000.0, 3 * TYPICAL_TIME_S);
    }

    // Two runs on one street of 250 m, the case of the maintainer. One goes
    // west, and its first five points are a GPS jump from 130 m to the east
    // of the street. The other goes east, and has a tail of 40 m to the
    // north at the end.
    //
    // The way is the street alone, in the direction of the base track: no
    // jump and no tail. The westbound run has no good point on the first
    // part of the street. Thus the way can start up to 25 m after the east
    // end of the street. The west end is at the start or the finish, in the
    // finish circle of the trip. Thus the radius of the profile is its
    // tolerance.
    private static void twoTracksInOppositeDirections() {
        List<TrackPoint> west = new ArrayList<>(run(STREET_WEST, 3.0, 1501, 1));
        for (int i = 0; i < 5; i++) {
            TrackPoint p = west.get(i);
            west.set(i, TestTracks.point(380 - 25 * i, -8, p.timeMs));
        }
        List<Track> tracks = new ArrayList<>();
        tracks.add(new Track("west", Profile.RUNNING, west));
        tracks.add(new Track("east", Profile.RUNNING, run(STREET_EAST, 2.4, 1502, 2)));
        double end = Profile.RUNNING.radiusM;

        WayBuilder.Result a = WayBuilder.build("way", Profile.RUNNING, tracks, 0);
        check("street west: used", "[0, 1]", a.used.toString());
        check("street west: reversed", "[1]", a.reversed.toString());
        checkOnStreet("street west", a.way);
        near("street west: finish", 0,
                distanceFrom(a.way.points.get(a.way.points.size() - 1), 0, 0), end);

        WayBuilder.Result b = WayBuilder.build("way", Profile.RUNNING, tracks, 1);
        check("street east: reversed", "[0]", b.reversed.toString());
        checkOnStreet("street east", b.way);
        near("street east: start", 0, distanceFrom(b.way.points.get(0), 0, 0), end);
    }

    // Each point of the way is on the street, and the way has most of it.
    // The tail is 40 m long. A point more than half of it from the street is
    // a part of the tail. The corner of the eastbound run moves the end of
    // the way by some meters. GPS noise makes the line of the way longer
    // than the street.
    private static void checkOnStreet(String name, Track way) {
        double worst = 0;
        for (TrackPoint p : way.points) {
            double[] q = xy(p);
            double x = Math.max(0, Math.min(250, q[0]));
            worst = Math.max(worst, Math.hypot(q[0] - x, q[1]));
        }
        check(name + ": on the street, worst " + round(worst) + " m", true, worst <= 20);
        check(name + ": length " + round(way.distanceM()) + " m", true,
                way.distanceM() >= 200 && way.distanceM() <= 280);
    }

    // The way dialog shows the cover and the direction of each track.
    private static void theMatchTellsTheCoverAndTheDirection() {
        Track base = walk(ROUTE, 1.4, 1601, 1, 0);
        WayBuilder.Match back = WayBuilder.match(base, walk(BACK, 1.4, 1602, 2, 0), Profile.WALKING);
        check("match back: no reject", 0, back.reject);
        check("match back: reversed", true, back.reversed);
        check("match back: whole route", true, back.cover > 0.9);

        WayBuilder.Match part = WayBuilder.match(base, walk(FROM_MIDDLE, 1.4, 1603, 3, 0),
                Profile.WALKING);
        check("match part: no reject", 0, part.reject);
        check("match part: forward", false, part.reversed);
        near("match part: cover", 900 / length(ROUTE), part.cover, 0.05);

        List<TrackPoint> moved = new ArrayList<>();
        for (TrackPoint p : walk(ROUTE, 1.4, 1604, 4, 0).points) {
            moved.add(TestTracks.shift(p, 400, 400));
        }
        check("match far: no join", WayBuilder.REJECT_NO_JOIN, WayBuilder.match(base,
                new Track("far", Profile.WALKING, moved), Profile.WALKING).reject);
    }

    // The case of the maintainer: three walks on a loop. They start at
    // different places near a corner, and the loop ends where it starts.
    // The first points of each walk are near the start and near the finish
    // of the route. At the finish, the route has the direction of the first
    // part of the walks. At the start of the walk from the corner, it has a
    // different direction. With that walk as the base, the rule of direction
    // joined the other walks at the finish, and no way came out. Each base
    // must give a way of all three walks. The dialog that changes the way
    // then finds each walk with a match against the way.
    private static void eachTrackOfALoopJoinsAtTheStart() {
        List<Track> tracks = new ArrayList<>();
        tracks.add(walk(LOOP_AT_CORNER, 1.3, 1701, 1, 0));
        tracks.add(walk(LOOP_BEFORE_CORNER, 1.4, 1702, 2, 0));
        tracks.add(walk(LOOP_BEFORE_CORNER, 1.2, 1703, 3, 0));
        for (int base = 0; base < tracks.size(); base++) {
            WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, tracks, base);
            check("loop base " + base + ": all used", "[0, 1, 2]", r.used.toString());
            if (r.way == null) {
                continue;
            }
            for (int i = 0; i < tracks.size(); i++) {
                check("loop base " + base + ": match " + i, 0,
                        WayBuilder.match(r.way, tracks.get(i), Profile.WALKING).reject);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static List<TrackPoint> run(double[][] corners, double speed, long seed, int day) {
        return TestTracks.recorded(TestTracks.noisy(TestTracks.ideal(corners, speed,
                TestTracks.START_MS + day * DAY_MS, 0), seed, SIGMA), Profile.RUNNING);
    }

    // The mean time of the five walks and one more walk at the speed.
    private static double meanDurationS(double[][] corners, double extraSpeed) {
        return (meanDurationS(corners) * SPEEDS.length + length(corners) / extraSpeed)
                / (SPEEDS.length + 1);
    }

    // The distance of a point from a place of the invented plane.
    private static double distanceFrom(TrackPoint p, double x, double y) {
        double[] q = xy(p);
        return Math.hypot(q[0] - x, q[1] - y);
    }

    private static double[] xy(TrackPoint p) {
        TrackPoint origin = TestTracks.point(0, 0, 0);
        double x = Geo.distanceM(origin.latitude, origin.longitude, origin.latitude, p.longitude)
                * Math.signum(p.longitude - origin.longitude);
        double y = Geo.distanceM(origin.latitude, origin.longitude, p.latitude, origin.longitude)
                * Math.signum(p.latitude - origin.latitude);
        return new double[]{x, y};
    }

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
