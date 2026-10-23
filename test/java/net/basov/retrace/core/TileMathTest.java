package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The test of TileMath
// ----------------------------------------------------------------------
//
// The test uses no JUnit. A main method runs each check, and a failure
// sets the exit code to 1. Thus the project needs no test dependency.
// test/run.sh compiles and runs this file.
//
// The expected tile numbers come from a second formula with asinh. Two
// different formulas that agree make a typing error in one of them improbable.
public final class TileMathTest {

    private static int failures = 0;

    public static void main(String[] args) {
        theWholeWorldIsOneTile();
        knownPlaces();
        thePolesClampToTheEdge();
        theLongitudeWrapsAround();
        theUrlHasZoomColumnAndRow();
        aBadZoomIsAnError();

        if (failures > 0) {
            System.out.println("TileMathTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("TileMathTest: every check passed");
    }

    // At zoom level 0, one tile holds the whole map.
    private static void theWholeWorldIsOneTile() {
        check("x at zoom 0", 0, TileMath.tileX(123.4, 0));
        check("y at zoom 0", 0, TileMath.tileY(-45.6, 0));
        check("count at zoom 0", 1, TileMath.tileCount(0));
        check("count at zoom 19", 524288, TileMath.tileCount(19));
    }

    // TileProbe downloads the Greenwich tile. A wrong number there gives a
    // tile of a different place, and nobody sees the fault.
    private static void knownPlaces() {
        check("Greenwich x", 16383, TileMath.tileX(-0.0015, 15));
        check("Greenwich y", 10900, TileMath.tileY(51.4779, 15));
        check("Moscow x", 619, TileMath.tileX(37.6173, 10));
        check("Moscow y", 320, TileMath.tileY(55.7558, 10));
        check("Sydney x", 3768, TileMath.tileX(151.2153, 12));
        check("Sydney y", 2457, TileMath.tileY(-33.8568, 12));
    }

    // A latitude above the limit of the projection gives the first row or
    // the last row, and not a number outside the map.
    private static void thePolesClampToTheEdge() {
        check("north pole", 0, TileMath.tileY(90.0, 3));
        check("south pole", 7, TileMath.tileY(-90.0, 3));
        check("south edge", 7, TileMath.tileY(-85.1, 3));
    }

    // A GPS fix near the date line can give a longitude a little above 180.
    private static void theLongitudeWrapsAround() {
        check("180 is -180", 0, TileMath.tileX(180.0, 3));
        check("181 is -179", 0, TileMath.tileX(181.0, 3));
        check("-181 is 179", 7, TileMath.tileX(-181.0, 3));
        check("540 is -180", 0, TileMath.tileX(540.0, 3));
    }

    private static void theUrlHasZoomColumnAndRow() {
        String url = TileMath.tileUrl("https://t.example/%d/%d/%d.png", 15, 51.4779, -0.0015);
        check("tile url", "https://t.example/15/16383/10900.png", url);
    }

    private static void aBadZoomIsAnError() {
        boolean thrown = false;
        try {
            TileMath.tileCount(20);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        check("zoom 20 is an error", true, thrown);
    }

    private static void check(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
