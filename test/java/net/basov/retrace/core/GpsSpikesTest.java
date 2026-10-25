package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The tests of GpsSpikes
// ----------------------------------------------------------------------
public final class GpsSpikesTest {

    private static int failures = 0;

    private static final double[][] LINE = {{0, 0}, {600, 0}};

    public static void main(String[] args) {
        oneBadFixGoes();
        aBurstOfBadFixesGoes();
        aTrackWithNoSpikeStaysTheSame();
        aFastMovementStays();
        aGapStays();
        anErrorAtAnEndStays();
        aBurstAtTheStartStays();
        aTrackWithNoProfileStays();
        theLengthOfATrackHasNoSpike();

        if (failures > 0) {
            System.out.println("GpsSpikesTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("GpsSpikesTest: every check passed");
    }

    // The case of the maintainer: one fix 95 m to the side, near the end of
    // a run. Only that fix goes.
    private static void oneBadFixGoes() {
        List<TrackPoint> points = walk();
        TrackPoint bad = TestTracks.shift(points.get(300), 0, 95);
        points.set(300, bad);
        List<TrackPoint> clean = GpsSpikes.remove(points, Profile.RUNNING);
        check("one: count", points.size() - 1, clean.size());
        check("one: bad fix removed", false, clean.contains(bad));
        check("one: next fix stays", true, clean.contains(points.get(301)));
    }

    // Three bad fixes in sequence, 60 m to the side.
    private static void aBurstOfBadFixesGoes() {
        List<TrackPoint> points = walk();
        for (int i = 200; i < 203; i++) {
            points.set(i, TestTracks.shift(points.get(i), 0, 60));
        }
        check("burst: count", points.size() - 3,
                GpsSpikes.remove(points, Profile.WALKING).size());
    }

    // The detail of a good track stays. The same list comes back.
    private static void aTrackWithNoSpikeStaysTheSame() {
        List<TrackPoint> points = TestTracks.recorded(TestTracks.noisy(walk(), 3, 8),
                Profile.WALKING);
        check("clean: same list", true, GpsSpikes.remove(points, Profile.WALKING) == points);
    }

    // A ride at 10 m/s with the walking profile is faster than the limit of
    // walking. It does not come back, thus it is not a spike.
    private static void aFastMovementStays() {
        List<TrackPoint> points = TestTracks.ideal(LINE, 10, TestTracks.START_MS, 0);
        check("fast: same list", true, GpsSpikes.remove(points, Profile.WALKING) == points);
    }

    // No fix for 60 s, then 100 m further on. The speed over the gap is
    // possible. The points after the gap stay.
    private static void aGapStays() {
        List<TrackPoint> points = TestTracks.withGap(walk(), TestTracks.START_MS + 100000,
                TestTracks.START_MS + 160000);
        check("gap: same list", true, GpsSpikes.remove(points, Profile.WALKING) == points);
    }

    // An error at the first point has no good point before it. WayBuilder
    // cuts it with its own rule.
    private static void anErrorAtAnEndStays() {
        List<TrackPoint> points = walk();
        points.set(0, TestTracks.shift(points.get(0), 0, 95));
        int last = points.size() - 1;
        points.set(last, TestTracks.shift(points.get(last), 0, 95));
        check("ends: same list", true, GpsSpikes.remove(points, Profile.WALKING) == points);
    }

    // Five bad points at the start come in from 130 m away, the same as the
    // GPS error in WayBuilderTest. They do not come back, thus they are not
    // a spike. The filter must not take the good points after them for a
    // spike. WayBuilder cuts such a burst with its own rule.
    private static void aBurstAtTheStartStays() {
        List<TrackPoint> points = new ArrayList<>(TestTracks.ideal(
                new double[][]{{250, 0}, {0, 0}}, 3.0, TestTracks.START_MS, 0));
        for (int i = 0; i < 5; i++) {
            points.set(i, TestTracks.point(380 - 25 * i, -8, points.get(i).timeMs));
        }
        check("start burst: same list", true,
                GpsSpikes.remove(points, Profile.RUNNING) == points);
    }

    // A GPX file from another application can have no profile. The filter
    // then has no speed limit.
    private static void aTrackWithNoProfileStays() {
        List<TrackPoint> points = walk();
        points.set(300, TestTracks.shift(points.get(300), 0, 95));
        check("no profile: same list", true, GpsSpikes.remove(points, null) == points);
    }

    // The spike adds about 190 m to the line. The length of the track does
    // not include it.
    private static void theLengthOfATrackHasNoSpike() {
        List<TrackPoint> points = walk();
        points.set(300, TestTracks.shift(points.get(300), 0, 95));
        Track track = new Track("t", Profile.WALKING, points);
        near("length", 600, track.distanceM(), 1);
    }

    // A walk of 600 m at 1.4 m/s, one point each second.
    private static List<TrackPoint> walk() {
        return new ArrayList<>(TestTracks.ideal(LINE, 1.4, TestTracks.START_MS, 0));
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
