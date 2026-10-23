package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The tests of Replay
// ----------------------------------------------------------------------
public final class ReplayTest {

    private static int failures = 0;

    public static void main(String[] args) {
        theSpeedDividesTheWait();
        aLongStopIsShort();
        aPointWithNoTimeWaitsOneSecond();
        theDefaultSpeedIsInTheList();

        if (failures > 0) {
            System.out.println("ReplayTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ReplayTest: every check passed");
    }

    // Points one second apart: one second at 1x, 100 ms at 10x.
    private static void theSpeedDividesTheWait() {
        TrackPoint a = TestTracks.point(0, 0, TestTracks.START_MS);
        TrackPoint b = TestTracks.point(1, 0, TestTracks.START_MS + 1000);
        check("1x", 1000L, Replay.waitMs(a, b, 1));
        check("10x", 100L, Replay.waitMs(a, b, 10));
        check("speed 0 counts as 1x", 1000L, Replay.waitMs(a, b, 0));
        check("time back gives no wait", 0L, Replay.waitMs(b, a, 1));
    }

    // A stop of ten minutes in the saved track must not stop the replay.
    private static void aLongStopIsShort() {
        TrackPoint a = TestTracks.point(0, 0, TestTracks.START_MS);
        TrackPoint b = TestTracks.point(1, 0, TestTracks.START_MS + 600000);
        check("long stop", Replay.MAX_WAIT_MS, Replay.waitMs(a, b, 10));
    }

    private static void aPointWithNoTimeWaitsOneSecond() {
        TrackPoint a = TestTracks.point(0, 0, TrackPoint.NO_TIME);
        TrackPoint b = TestTracks.point(1, 0, TestTracks.START_MS);
        check("no time", 200L, Replay.waitMs(a, b, 5));
    }

    // The menu marks the default speed. It must be one of the speeds.
    private static void theDefaultSpeedIsInTheList() {
        boolean found = false;
        for (int s : Replay.SPEEDS) {
            found |= s == Replay.DEFAULT_SPEED;
        }
        check("default speed in the list", true, found);
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
