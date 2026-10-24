package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The tests of EndMarks
// ----------------------------------------------------------------------
public final class EndMarksTest {

    private static int failures = 0;

    public static void main(String[] args) {
        theDirectionSkipsTheNoiseAtTheStart();
        aShortTrackPointsToItsLastPoint();
        aTrackWithOnePointHasNoDirection();
        theArrowPointsInTheDirectionOfTravel();
        anArrowWithNoDirectionPointsUp();

        if (failures > 0) {
            System.out.println("EndMarksTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("EndMarksTest: every check passed");
    }

    // A wait at the start gives points near each other in random
    // directions. The arrow must use the first point that is far enough.
    private static void theDirectionSkipsTheNoiseAtTheStart() {
        double[] xs = {0, 1, -1, 0, 5, 20, 40};
        double[] ys = {0, -1, 1, 1, 0, 0, 0};
        check("first far point", 5, EndMarks.aheadIndex(xs, ys, xs.length, 12));
        check("near point counts", 4, EndMarks.aheadIndex(xs, ys, xs.length, 5));
    }

    // A track that stays in the minimum distance still gets a direction.
    private static void aShortTrackPointsToItsLastPoint() {
        double[] xs = {0, 1, 2};
        double[] ys = {0, 0, 0};
        check("last index", 2, EndMarks.aheadIndex(xs, ys, xs.length, 100));
        check("count limits the points", 1, EndMarks.aheadIndex(xs, ys, 2, 100));
    }

    private static void aTrackWithOnePointHasNoDirection() {
        check("one point", -1, EndMarks.aheadIndex(new double[]{0}, new double[]{0}, 1, 1));
    }

    // To the east, the tip is to the right of the center and the base is
    // to the left. The base is across the direction.
    private static void theArrowPointsInTheDirectionOfTravel() {
        float[] a = EndMarks.arrow(100, 50, 30, 0, 20);
        check("tip x", 112f, a[0]);
        check("tip y", 50f, a[1]);
        check("base x", 92f, a[2]);
        check("base x 2", 92f, a[4]);
        check("base across", 18f, Math.abs(a[3] - a[5]));

        float[] down = EndMarks.arrow(0, 0, 0, 5, 10);
        check("south tip y", 6f, down[1]);
    }

    // The screen y axis points down. Up is a negative y.
    private static void anArrowWithNoDirectionPointsUp() {
        float[] a = EndMarks.arrow(0, 0, 0, 0, 10);
        check("up tip x", 0f, a[0]);
        check("up tip y", -6f, a[1]);
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (actual instanceof Float && expected instanceof Float) {
            same = Math.abs((Float) actual - (Float) expected) < 1e-3f;
        }
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
