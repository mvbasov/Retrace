package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The tests of ScaleBar
// ----------------------------------------------------------------------
public final class ScaleBarTest {

    private static int failures = 0;

    public static void main(String[] args) {
        theLengthIsRound();
        theBarIsNotLongerThanTheLimit();
        theScaleChangesWithTheLatitude();
        aBadValueGivesNoBar();

        if (failures > 0) {
            System.out.println("ScaleBarTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ScaleBarTest: every check passed");
    }

    // 1, 2 or 5 times a power of 10. Each case is at the next round value,
    // or a little below it.
    private static void theLengthIsRound() {
        check("0.5 m/px, 300 px", 100L, ScaleBar.choose(0.5, 300).meters);
        check("1 m/px, 300 px", 200L, ScaleBar.choose(1, 300).meters);
        check("2 m/px, 300 px", 500L, ScaleBar.choose(2, 300).meters);
        check("4 m/px, 300 px", 1000L, ScaleBar.choose(4, 300).meters);
        check("10 m/px, 199 px", 1000L, ScaleBar.choose(10, 199).meters);
        check("10 m/px, 200 px", 2000L, ScaleBar.choose(10, 200).meters);
        check("1000 m/px, 300 px", 200000L, ScaleBar.choose(1000, 300).meters);
        check("0.01 m/px, 150 px", 1L, ScaleBar.choose(0.01, 150).meters);
    }

    // The pixel length agrees with the meters, and it fits the limit.
    private static void theBarIsNotLongerThanTheLimit() {
        for (double mpp = 0.05; mpp < 50000; mpp *= 1.37) {
            ScaleBar bar = ScaleBar.choose(mpp, 300);
            if (bar == null) {
                continue;
            }
            check("fits at " + mpp, true, bar.pixels <= 300 + 1e-9);
            check("longer than 2/5 of the limit at " + mpp, true, bar.pixels >= 300 * 0.4 - 1e-9);
            near("pixels at " + mpp, bar.meters / mpp, bar.pixels, 1e-6);
        }
    }

    // At 60 degrees, one pixel shows half of the meters at the equator.
    private static void theScaleChangesWithTheLatitude() {
        double world = 256 * Math.pow(2, 16);
        double equator = ScaleBar.metersPerPixel(0, world);
        near("equator, zoom 16", 2 * Math.PI * Geo.EARTH_RADIUS_M / world, equator, 1e-9);
        near("60 degrees", equator / 2, ScaleBar.metersPerPixel(60, world), 1e-9);
    }

    private static void aBadValueGivesNoBar() {
        check("zero scale", true, ScaleBar.choose(0, 300) == null);
        check("NaN scale", true, ScaleBar.choose(Double.NaN, 300) == null);
        check("no room", true, ScaleBar.choose(10, 0) == null);
        check("one meter too long", true, ScaleBar.choose(0.001, 100) == null);
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
