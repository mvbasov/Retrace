package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The tests of FixSelector
// ----------------------------------------------------------------------
public final class FixSelectorTest {

    private static int failures = 0;

    // The time of the first fix, and the clock since the boot at that time.
    private static final long T0 = 1_700_000_000_000L;
    private static final long C0 = 50_000L;

    public static void main(String[] args) {
        theGpsIsAlwaysUsed();
        aNetworkFixWaitsWhileTheGpsWorks();
        aNetworkFixFillsAGapOfTheGps();
        aNetworkFixCanStartATrack();
        aWrongClockDoesNotPutAPointBack();
        anOldFixIsNotUsed();

        if (failures > 0) {
            System.out.println("FixSelectorTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("FixSelectorTest: every check passed");
    }

    private static void theGpsIsAlwaysUsed() {
        FixSelector s = new FixSelector();
        check("gps 1", T0, s.timeOf(true, T0, C0, C0));
        check("gps 2", T0 + 1000, s.timeOf(true, T0 + 1000, C0 + 1000, C0 + 1000));
    }

    // The GPS gives a fix each second. A network fix between them is less
    // accurate. It must not go into the track.
    private static void aNetworkFixWaitsWhileTheGpsWorks() {
        FixSelector s = new FixSelector();
        s.timeOf(true, T0, C0, C0);
        check("network 1 s after gps", TrackPoint.NO_TIME,
                s.timeOf(false, T0 + 1000, C0 + 1000, C0 + 1000));
        check("network 4.9 s after gps", TrackPoint.NO_TIME,
                s.timeOf(false, T0 + 4900, C0 + 4900, C0 + 4900));
    }

    // In a passage, the GPS gives no fix. After GPS_FRESH_MS, the network
    // fixes go into the track. When the GPS comes back, they wait again.
    private static void aNetworkFixFillsAGapOfTheGps() {
        FixSelector s = new FixSelector();
        s.timeOf(true, T0, C0, C0);
        long gap = FixSelector.GPS_FRESH_MS;
        check("network after the gap", T0 + gap, s.timeOf(false, T0 + gap, C0 + gap, C0 + gap));
        check("gps again", T0 + 20000, s.timeOf(true, T0 + 20000, C0 + 20000, C0 + 20000));
        check("network after gps again", TrackPoint.NO_TIME,
                s.timeOf(false, T0 + 21000, C0 + 21000, C0 + 21000));
    }

    // Indoors at the start, the GPS has no fix yet. A network fix can be
    // the first point. The GPS fixes then continue on its time base.
    private static void aNetworkFixCanStartATrack() {
        FixSelector s = new FixSelector();
        check("network first", T0, s.timeOf(false, T0, C0, C0));
        check("gps later", T0 + 3000, s.timeOf(true, T0 + 3000, C0 + 3000, C0 + 3000));
    }

    // The clock of the device is 30 s ahead of the GPS time. The first
    // point comes from the network with the time of that clock. The GPS
    // fixes after it must have later times, or RecordFilter drops them.
    private static void aWrongClockDoesNotPutAPointBack() {
        FixSelector s = new FixSelector();
        long first = s.timeOf(false, T0 + 30000, C0, C0);
        long gps = s.timeOf(true, T0 + 2000, C0 + 2000, C0 + 2000);
        check("gps after network", true, gps > first);
        check("gps step", 2000L, gps - first);
    }

    // Android can give a network fix from its cache. A fix of 20 s ago is
    // not the current position.
    private static void anOldFixIsNotUsed() {
        FixSelector s = new FixSelector();
        check("old fix", TrackPoint.NO_TIME, s.timeOf(false, T0, C0, C0 + 20000));
        check("then a new fix starts the track", T0 + 20000,
                s.timeOf(false, T0 + 20000, C0 + 20000, C0 + 20000));
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
