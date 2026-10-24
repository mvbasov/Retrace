package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The tests of TrackSpeeds
// ----------------------------------------------------------------------
public final class TrackSpeedsTest {

    private static int failures = 0;

    private static final double[][] LINE = {{0, 0}, {1200, 0}};
    private static final double[][] HALF = {{0, 0}, {600, 0}};
    private static final double[][] SECOND_HALF = {{600, 0}, {1200, 0}};

    public static void main(String[] args) {
        aFixedSpeedGivesOneValue();
        aWaitDoesNotCount();
        twoSpeedsGiveTheRange();
        oneLargeStepCountsLittle();
        aGpsJumpDoesNotCount();
        aTrackWithNoTimesHasNoSpeeds();
        aShortTrackHasTheMeanOnly();
        theMeanIsInTheRange();

        if (failures > 0) {
            System.out.println("TrackSpeedsTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("TrackSpeedsTest: every check passed");
    }

    // A walk at 1.4 m/s with no noise: the three values are 1.4 m/s.
    private static void aFixedSpeedGivesOneValue() {
        TrackSpeeds s = speeds(TestTracks.ideal(LINE, 1.4, TestTracks.START_MS, 0));
        near("fixed: min", 1.4, s.minMps, 0.01);
        near("fixed: mean", 1.4, s.meanMps, 0.01);
        near("fixed: max", 1.4, s.maxMps, 0.01);
    }

    // A wait of two minutes for the GPS at the start, and the GPS noise of a
    // receiver that does not move. RecordFilter keeps some of these fixes.
    // The wait must not give a speed near zero, and must not lower the
    // mean. In a window of 15 s, a drift of 4 m changes the speed by some
    // tenths of a meter per second. Over 200 seeds, the lowest speed is at
    // most 0.51 m/s below 1.4 m/s. Thus the test checks that the lowest
    // speed is more than half of the real speed. The mean of the whole walk
    // has a much smaller error, at most 0.06 m/s over 200 seeds.
    private static void aWaitDoesNotCount() {
        for (long seed = 1; seed <= 20; seed++) {
            List<TrackPoint> points = TestTracks.recorded(TestTracks.noisy(
                    TestTracks.ideal(LINE, 1.4, TestTracks.START_MS, 120), seed, 4),
                    Profile.WALKING);
            TrackSpeeds s = speeds(points);
            check("wait " + seed + ": min more than half", true, s.minMps > 0.7);
            near("wait " + seed + ": mean", 1.4, s.meanMps, 0.1);
        }
    }

    // The first half at 1.0 m/s, the second half at 2.0 m/s. The mean is
    // the distance divided by the time: 1200 m in 900 s.
    private static void twoSpeedsGiveTheRange() {
        List<TrackPoint> points = new ArrayList<>(TestTracks.ideal(HALF, 1.0,
                TestTracks.START_MS, 0));
        long t = points.get(points.size() - 1).timeMs;
        List<TrackPoint> fast = TestTracks.ideal(SECOND_HALF, 2.0, t, 0);
        points.addAll(fast.subList(1, fast.size()));
        TrackSpeeds s = speeds(points);
        near("two: min", 1.0, s.minMps, 0.01);
        near("two: mean", 1200.0 / 900.0, s.meanMps, 0.01);
        near("two: max", 2.0, s.maxMps, 0.01);
    }

    // One point 30 m off the line, a GPS error. One step then has 30 m/s.
    // The window of walking is 15 s long, thus the highest speed stays
    // below 1.4 + 60 / 15 m/s.
    private static void oneLargeStepCountsLittle() {
        List<TrackPoint> points = new ArrayList<>(TestTracks.ideal(LINE, 1.4,
                TestTracks.START_MS, 0));
        TrackPoint p = points.get(300);
        points.set(300, TestTracks.shift(p, 0, 30));
        TrackSpeeds s = speeds(points);
        check("spike: max below the step", true, s.maxMps < 1.4 + 60.0 / 15 + 0.01);
        near("spike: min", 1.4, s.minMps, 0.01);
    }

    // The first five points of a walk come from 110 m before the start, at
    // 18 m/s. That is more than two times the top speed of walking. The
    // jump must not give the highest speed or change the mean.
    private static void aGpsJumpDoesNotCount() {
        List<TrackPoint> points = new ArrayList<>(TestTracks.ideal(LINE, 1.4,
                TestTracks.START_MS, 0));
        for (int i = 0; i < 5; i++) {
            TrackPoint p = points.get(i);
            points.set(i, TestTracks.point(-110 + 18 * i, 0, p.timeMs));
        }
        TrackSpeeds s = speeds(points);
        near("jump: max", 1.4, s.maxMps, 0.01);
        near("jump: mean", 1.4, s.meanMps, 0.01);
    }

    private static void aTrackWithNoTimesHasNoSpeeds() {
        TrackSpeeds s = speeds(TestTracks.withoutTimes(TestTracks.ideal(LINE, 1.4,
                TestTracks.START_MS, 0)));
        check("no times: no mean", false, s.hasMean());
        check("no times: no range", false, s.hasRange());
    }

    // A walk of 10 s is shorter than the window of 15 s.
    private static void aShortTrackHasTheMeanOnly() {
        TrackSpeeds s = speeds(TestTracks.ideal(new double[][]{{0, 0}, {14, 0}}, 1.4,
                TestTracks.START_MS, 0));
        check("short: no range", false, s.hasRange());
        near("short: mean", 1.4, s.meanMps, 0.01);
    }

    // The screen shows the three values as one range. With GPS noise and
    // many short stops, the mean must stay in the range.
    private static void theMeanIsInTheRange() {
        for (long seed = 1; seed <= 20; seed++) {
            List<TrackPoint> points = new ArrayList<>();
            long t = TestTracks.START_MS;
            for (int part = 0; part < 6; part++) {
                double[][] corners = {{part * 100, 0}, {part * 100 + 100, 0}};
                List<TrackPoint> walk = TestTracks.ideal(corners, 0.8 + 0.2 * part, t, 20);
                points.addAll(walk);
                t = walk.get(walk.size() - 1).timeMs + 1000;
            }
            TrackSpeeds s = speeds(TestTracks.recorded(TestTracks.noisy(points, seed, 4),
                    Profile.WALKING));
            check("range " + seed, true, s.hasRange()
                    && s.minMps <= s.meanMps && s.meanMps <= s.maxMps);
        }
    }

    private static TrackSpeeds speeds(List<TrackPoint> points) {
        return TrackSpeeds.of(new Track("t", Profile.WALKING, points), Profile.WALKING);
    }

    private static void near(String name, double expected, double actual, double tolerance) {
        if (!(Math.abs(expected - actual) <= tolerance)) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + " ± " + tolerance
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
