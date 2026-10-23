package net.basov.retrace.core;

import java.util.List;

// ----------------------------------------------------------------------
// The test of DeltaSmoother
// ----------------------------------------------------------------------
//
// The trip screen shows the smoothed time difference. The smoothing must
// follow a real change, and it must make the noise smaller.
public final class DeltaSmootherTest {

    private static int failures = 0;

    public static void main(String[] args) {
        theFirstValueIsTheStart();
        aStepReachesItsTargetAfterSomeTimeConstants();
        aLongGapMovesTheValueMore();
        theNoiseOfATripGetsSmaller();
        resetStartsAgain();
        aBadTimeConstantIsAnError();

        if (failures > 0) {
            System.out.println("DeltaSmootherTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("DeltaSmootherTest: every check passed");
    }

    private static void theFirstValueIsTheStart() {
        DeltaSmoother s = new DeltaSmoother(30000);
        check("first", 12000L, s.add(1000, 12000));
        check("started", true, s.started());
    }

    // After one time constant, an exponential mean has 63 percent of a step.
    // After five, it has more than 99 percent.
    private static void aStepReachesItsTargetAfterSomeTimeConstants() {
        DeltaSmoother s = new DeltaSmoother(30000);
        s.add(0, 0);
        long value = 0;
        for (int t = 1; t <= 30; t++) {
            value = s.add(t * 1000L, 10000);
        }
        near("one time constant", 6321, value, 30);
        for (int t = 31; t <= 150; t++) {
            value = s.add(t * 1000L, 10000);
        }
        near("five time constants", 10000, value, 70);
    }

    // A fix after a gap of a time constant counts as much as 30 fixes of one
    // second each.
    private static void aLongGapMovesTheValueMore() {
        DeltaSmoother s = new DeltaSmoother(30000);
        s.add(0, 0);
        near("gap", 6321, s.add(30000, 10000), 1);
    }

    // On the same route at the same speed, the smoothed value must have a
    // smaller typical size than the raw value.
    private static void theNoiseOfATripGetsSmaller() {
        double[][] corners = {{0, 0}, {0, 500}, {600, 500}, {600, 900}};
        long day = 86400000L;
        List<TrackPoint> ref = TestTracks.recorded(TestTracks.noisy(
                TestTracks.ideal(corners, 1.4, TestTracks.START_MS, 0), 31, 4), Profile.WALKING);
        List<TrackPoint> cur = TestTracks.recorded(TestTracks.noisy(
                TestTracks.ideal(corners, 1.4, TestTracks.START_MS + day, 0), 32, 4), Profile.WALKING);
        TrackMatcher matcher = new TrackMatcher(TestTracks.track("ref", ref), Profile.WALKING);
        DeltaSmoother smoother = DeltaSmoother.forProfile(Profile.WALKING);
        double rawJumps = 0;
        double smoothJumps = 0;
        long lastRaw = MatchResult.UNKNOWN;
        long lastSmooth = MatchResult.UNKNOWN;
        for (TrackPoint p : cur) {
            MatchResult m = matcher.update(p);
            if (m.state != TrackMatcher.State.ON_ROUTE) {
                continue;
            }
            long smooth = smoother.add(p.timeMs, m.deltaTimeMs);
            if (lastRaw != MatchResult.UNKNOWN) {
                rawJumps += Math.abs(m.deltaTimeMs - lastRaw);
                smoothJumps += Math.abs(smooth - lastSmooth);
            }
            lastRaw = m.deltaTimeMs;
            lastSmooth = smooth;
        }
        check("smooth moves less", true, smoothJumps < rawJumps / 3);
    }

    private static void resetStartsAgain() {
        DeltaSmoother s = new DeltaSmoother(30000);
        s.add(0, 50000);
        s.reset();
        check("after reset", -2000L, s.add(1000, -2000));
    }

    private static void aBadTimeConstantIsAnError() {
        boolean thrown = false;
        try {
            new DeltaSmoother(0);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        check("zero time constant", true, thrown);
    }

    private static void near(String name, double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
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
