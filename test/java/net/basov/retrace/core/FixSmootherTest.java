package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// ----------------------------------------------------------------------
// The tests of FixSmoother
// ----------------------------------------------------------------------
//
// The tests use the invented tracks of TestTracks. The noise of TestTracks
// is mostly drift, and the filter does not remove the drift. Thus the test
// of the smoothing adds its own random noise with no drift.
public final class FixSmootherTest {

    private static int failures = 0;

    // A route with a sharp turn of 90 degrees.
    private static final double[][] TURN = {{0, 0}, {0, 300}, {300, 300}};

    private static final double METERS_PER_DEGREE = Geo.EARTH_RADIUS_M * Math.PI / 180.0;

    public static void main(String[] args) {
        theFirstFixIsKept();
        theRandomErrorIsSmaller();
        aTurnStaysNearTheRoute();
        aSpikeIsDropped();
        aRealChangeIsFollowed();
        aGapIsBridged();
        theGpsCorrectsANetworkStart();

        if (failures > 0) {
            System.out.println("FixSmootherTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("FixSmootherTest: every check passed");
    }

    // The first fix has no history. The filter cannot change it.
    private static void theFirstFixIsKept() {
        FixSmoother f = new FixSmoother(Profile.WALKING);
        TrackPoint p = TestTracks.point(10, 20, TestTracks.START_MS);
        TrackPoint out = f.filter(p);
        check("same latitude", p.latitude, out.latitude);
        check("same longitude", p.longitude, out.longitude);
        check("same time", p.timeMs, out.timeMs);
    }

    // The main task: the random error of each fix becomes smaller. For
    // walking and running, the measured error falls by about 40 percent.
    // The test asks for 25 percent over 30 seeds.
    private static void theRandomErrorIsSmaller() {
        Profile[] profiles = {Profile.WALKING, Profile.RUNNING};
        double[] speeds = {1.4, 3.5};
        for (int k = 0; k < profiles.length; k++) {
            List<TrackPoint> ideal = TestTracks.ideal(TURN, speeds[k], TestTracks.START_MS, 0);
            double raw = 0;
            double smoothed = 0;
            for (long seed = 1; seed <= 30; seed++) {
                FixSmoother f = new FixSmoother(profiles[k]);
                for (TrackPoint fix : whiteNoise(ideal, seed, 4)) {
                    TrackPoint out = f.filter(fix);
                    TrackPoint truth = at(ideal, fix.timeMs);
                    if (out == null) {
                        continue;
                    }
                    raw += square(Geo.distanceM(fix, truth));
                    smoothed += square(Geo.distanceM(out, truth));
                }
            }
            check(profiles[k] + " smaller error", true, smoothed < 0.75 * 0.75 * raw);
        }
    }

    // The filter follows a sharp turn with a small delay. The cut of the
    // corner must stay far inside the off-route limit of each profile. The
    // measured cut is 1.2 m for walking and 7.8 m for driving at 25 m/s.
    private static void aTurnStaysNearTheRoute() {
        for (Profile profile : Profile.values()) {
            double speed = profile == Profile.WALKING ? 1.4 : profile.maxSpeedMps / 2;
            FixSmoother f = new FixSmoother(profile);
            double worst = 0;
            int dropped = 0;
            for (TrackPoint fix : TestTracks.ideal(TURN, speed, TestTracks.START_MS, 0)) {
                TrackPoint out = f.filter(fix);
                if (out == null) {
                    dropped++;
                    continue;
                }
                worst = Math.max(worst, distanceToRoute(out));
            }
            check(profile + " no fix dropped", 0, dropped);
            check(profile + " near the route", true, worst < profile.offRouteM / 4);
        }
    }

    // A fix 70 m away for one second is a GPS error. The filter drops it,
    // and the next fixes continue the line. A burst of MAX_REJECTED fixes is
    // also dropped.
    private static void aSpikeIsDropped() {
        for (int burst = 1; burst <= FixSmoother.MAX_REJECTED; burst++) {
            List<TrackPoint> ideal = TestTracks.ideal(TURN, 1.4, TestTracks.START_MS, 0);
            FixSmoother f = new FixSmoother(Profile.WALKING);
            int dropped = 0;
            double worst = 0;
            for (int i = 0; i < ideal.size(); i++) {
                TrackPoint fix = ideal.get(i);
                boolean spike = i >= 50 && i < 50 + burst;
                if (spike) {
                    fix = TestTracks.shift(fix, 70, 0);
                }
                TrackPoint out = f.filter(fix);
                if (out == null) {
                    dropped++;
                } else {
                    worst = Math.max(worst, distanceToRoute(out));
                }
            }
            check("burst " + burst + " dropped", burst, dropped);
            check("burst " + burst + " near the route", true, worst < 2);
        }
    }

    // After MAX_REJECTED dropped fixes in a sequence, the new position is real. An
    // example is a GPS that comes back far from a wrong network fix. The
    // filter starts again at the new position and does not lose the trip.
    private static void aRealChangeIsFollowed() {
        FixSmoother f = new FixSmoother(Profile.WALKING);
        long t = TestTracks.START_MS;
        for (int i = 0; i < 20; i++) {
            f.filter(TestTracks.point(0, 0, t));
            t += 1000;
        }
        TrackPoint last = null;
        for (int i = 0; i <= FixSmoother.MAX_REJECTED; i++) {
            last = f.filter(TestTracks.point(200, 0, t));
            t += 1000;
        }
        check("restart", true, last != null);
        TrackPoint next = f.filter(TestTracks.point(201, 0, t));
        check("follows after the restart", true,
                next != null && Geo.distanceM(next, TestTracks.point(201, 0, t)) < 2);
    }

    // In a tunnel, the GPS gives no fix for a minute, and the route turns in
    // the tunnel. The fix after the gap is far from the old estimate, but the
    // uncertainty grew during the gap. Thus the fix is not a spike.
    private static void aGapIsBridged() {
        List<TrackPoint> ideal = TestTracks.ideal(TURN, 1.4, TestTracks.START_MS, 0);
        long from = TestTracks.START_MS + 190_000;
        List<TrackPoint> withGap = TestTracks.withGap(ideal, from, from + 60_000);
        FixSmoother f = new FixSmoother(Profile.WALKING);
        int dropped = 0;
        for (TrackPoint fix : withGap) {
            if (f.filter(fix) == null) {
                dropped++;
            }
        }
        check("gap dropped", 0, dropped);
    }

    // At the start, the network gives fixes with an error of 40 m and an
    // accuracy of 50 m. The first GPS fix then pulls the estimate to it.
    private static void theGpsCorrectsANetworkStart() {
        FixSmoother f = new FixSmoother(Profile.WALKING);
        long t = TestTracks.START_MS;
        for (int i = 0; i < 10; i++) {
            f.filter(accuracy(TestTracks.point(40, 0, t), 50));
            t += 1000;
        }
        TrackPoint gps = accuracy(TestTracks.point(0, 0, t), 4);
        TrackPoint out = f.filter(gps);
        check("gps accepted", true, out != null);
        check("gps pulls", true, out != null && Geo.distanceM(out, gps) < 2);
    }

    // Random noise with no drift, and the accuracy of the noise.
    private static List<TrackPoint> whiteNoise(List<TrackPoint> ideal, long seed, double sigma) {
        Random random = new Random(seed);
        List<TrackPoint> out = new ArrayList<>();
        for (TrackPoint p : ideal) {
            TrackPoint q = TestTracks.shift(p, random.nextGaussian() * sigma,
                    random.nextGaussian() * sigma);
            out.add(accuracy(q, sigma));
        }
        return out;
    }

    private static TrackPoint accuracy(TrackPoint p, double accuracyM) {
        return new TrackPoint(p.latitude, p.longitude, p.timeMs, p.elevationM, accuracyM);
    }

    // The ideal points have one point each second.
    private static TrackPoint at(List<TrackPoint> ideal, long timeMs) {
        return ideal.get((int) ((timeMs - ideal.get(0).timeMs) / 1000));
    }

    private static double distanceToRoute(TrackPoint p) {
        double x = (p.longitude - TestTracks.ORIGIN_LON) * METERS_PER_DEGREE
                * Math.cos(Math.toRadians(TestTracks.ORIGIN_LAT));
        double y = (p.latitude - TestTracks.ORIGIN_LAT) * METERS_PER_DEGREE;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < TURN.length; i++) {
            double ax = TURN[i][0];
            double ay = TURN[i][1];
            double dx = TURN[i + 1][0] - ax;
            double dy = TURN[i + 1][1] - ay;
            double f = ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy);
            f = Math.max(0, Math.min(1, f));
            best = Math.min(best, Math.hypot(x - ax - f * dx, y - ay - f * dy));
        }
        return best;
    }

    private static double square(double v) {
        return v * v;
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
