package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// ----------------------------------------------------------------------
// The tests of TrackBounds
// ----------------------------------------------------------------------
//
// TrackBounds is a fast check before the precise check of the automatic
// selection. A false for a near fix loses a candidate. Thus the main test
// tries many fixes inside the limit of a track point.
public final class TrackBoundsTest {

    private static int failures = 0;

    public static void main(String[] args) {
        aFixOnTheTrackIsNear();
        aFixInsideTheLimitIsNear();
        aFarFixIsNotNear();
        eachFixInsideTheLimitIsNear();
        aTrackFarFromTheEquatorWorks();
        anEmptyTrackIsNeverNear();

        if (failures > 0) {
            System.out.println("TrackBoundsTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("TrackBoundsTest: every check passed");
    }

    private static void aFixOnTheTrackIsNear() {
        TrackBounds b = TrackBounds.of(line(0));
        check("on the track", true, b.near(TestTracks.point(0, 500, 0), 20));
    }

    // The bounds are 200 m east to west. A fix 10 m east of them is inside
    // a limit of 20 m.
    private static void aFixInsideTheLimitIsNear() {
        TrackBounds b = TrackBounds.of(line(0));
        check("10 m outside", true, b.near(TestTracks.point(210, 500, 0), 20));
    }

    private static void aFarFixIsNotNear() {
        TrackBounds b = TrackBounds.of(line(0));
        check("500 m outside", false, b.near(TestTracks.point(700, 500, 0), 20));
        check("500 m north", false, b.near(TestTracks.point(0, 1500, 0), 20));
    }

    // The main rule: a fix inside the limit of a track point is always
    // near. The fixes are at random places at 99 percent of the limit, in
    // each direction from a random track point. Far from the equator, a
    // degree of longitude is shorter, thus the test uses three latitudes.
    private static void eachFixInsideTheLimitIsNear() {
        Random random = new Random(7);
        for (double latitude : new double[] {0, 60, 80}) {
            List<TrackPoint> track = moved(line(0), latitude);
            TrackBounds b = TrackBounds.of(track);
            int missed = 0;
            for (int i = 0; i < 2000; i++) {
                TrackPoint p = track.get(random.nextInt(track.size()));
                double limit = 5 + random.nextDouble() * 200;
                double angle = random.nextDouble() * 2 * Math.PI;
                TrackPoint fix = TestTracks.shift(p, 0.99 * limit * Math.cos(angle),
                        0.99 * limit * Math.sin(angle));
                if (Geo.distanceM(p, fix) <= limit && !b.near(fix, limit)) {
                    missed++;
                }
            }
            check("missed fixes at latitude " + latitude, 0, missed);
        }
    }

    // At latitude 60, one degree of longitude is half as long as at the
    // equator. A fix 10 m east of the bounds is near, and 500 m east is not.
    private static void aTrackFarFromTheEquatorWorks() {
        List<TrackPoint> track = moved(line(0), 60);
        TrackBounds b = TrackBounds.of(track);
        TrackPoint east = track.get(track.size() - 1);
        check("latitude 60, 10 m outside", true, b.near(TestTracks.shift(east, 10, 0), 20));
        check("latitude 60, 500 m outside", false,
                b.near(TestTracks.shift(east, 500, 0), 20));
    }

    private static void anEmptyTrackIsNeverNear() {
        TrackBounds b = TrackBounds.of(new ArrayList<TrackPoint>());
        check("empty", false, b.near(TestTracks.point(0, 0, 0), 1e6));
    }

    // A track from (0, 0) to (200, 1000), in meters east and north.
    private static List<TrackPoint> line(long startMs) {
        List<TrackPoint> points = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            points.add(TestTracks.point(2 * i, 10 * i, startMs + i * 1000L));
        }
        return points;
    }

    // The same track, moved to the north or south. Its first point then has
    // the latitude.
    private static List<TrackPoint> moved(List<TrackPoint> track, double latitude) {
        double degrees = latitude - track.get(0).latitude;
        List<TrackPoint> out = new ArrayList<>();
        for (TrackPoint p : track) {
            out.add(new TrackPoint(p.latitude + degrees, p.longitude, p.timeMs, p.elevationM,
                    p.accuracyM));
        }
        return out;
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
