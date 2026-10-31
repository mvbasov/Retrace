package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The tests of FixSource
// ----------------------------------------------------------------------
public final class FixSourceTest {

    private static int failures = 0;

    public static void main(String[] args) {
        noFixIsRed();
        aFreshFixGivesItsSource();
        anOldFixIsRed();
        theGpsComesFirst();
        theSnapshotGivesTheDot();
        theSatellitesGiveTheSourceOfAFusedFix();

        if (failures > 0) {
            System.out.println("FixSourceTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("FixSourceTest: every check passed");
    }

    // Before the first fix, the dot is red.
    private static void noFixIsRed() {
        check("no fix", FixSource.NONE, FixSource.of(FixSource.GPS, GpsReadiness.NO_FIX));
        check("no source", FixSource.NONE, FixSource.of(FixSource.NONE, 0));
        check("no fixes", FixSource.NONE,
                FixSource.best(GpsReadiness.NO_FIX, GpsReadiness.NO_FIX));
    }

    private static void aFreshFixGivesItsSource() {
        check("gps", FixSource.GPS, FixSource.of(FixSource.GPS, 1000));
        check("network", FixSource.NETWORK, FixSource.of(FixSource.NETWORK, 1000));
    }

    // The GPS gives a fix each second, the network less frequently. Thus
    // the two sources have different limits of the age.
    private static void anOldFixIsRed() {
        check("gps at the limit", FixSource.GPS,
                FixSource.of(FixSource.GPS, FixSource.GPS_FRESH_MS));
        check("old gps", FixSource.NONE,
                FixSource.of(FixSource.GPS, FixSource.GPS_FRESH_MS + 1));
        check("network after the gps limit", FixSource.NETWORK,
                FixSource.of(FixSource.NETWORK, FixSource.GPS_FRESH_MS + 1));
        check("old network", FixSource.NONE,
                FixSource.of(FixSource.NETWORK, FixSource.NETWORK_FRESH_MS + 1));
    }

    // Before a trip, the receiver gets fixes from the two sources. The dot
    // shows the GPS while it works, the same as FixSelector.
    private static void theGpsComesFirst() {
        check("both", FixSource.GPS, FixSource.best(1000, 500));
        check("gps lost", FixSource.NETWORK,
                FixSource.best(FixSource.GPS_FRESH_MS + 1, 500));
        check("network only", FixSource.NETWORK, FixSource.best(GpsReadiness.NO_FIX, 500));
    }

    // The trip screen reads the dot from the snapshot each second. The
    // snapshot changes only at a fix, thus the age comes from the clock.
    private static void theSnapshotGivesTheDot() {
        long t = 1_700_000_000_000L;
        check("idle", FixSource.NONE, RecordingSnapshot.IDLE.fixSource(t));
        RecordingSnapshot s = new RecordingSnapshot(true, Profile.WALKING, t, 1, 0, 5, t,
                FixSource.GPS, 0, RecordingSnapshot.ERROR_NONE, null,
                RecordingSnapshot.SELECTION_OFF, false, null, MatchResult.UNKNOWN, null, null, 0, 0,
                false, 0);
        check("fresh", FixSource.GPS, s.fixSource(t + 1000));
        check("old", FixSource.NONE, s.fixSource(t + FixSource.GPS_FRESH_MS + 1));
    }

    // A fused fix does not tell its source. The satellites in use tell if
    // the GPS gives the position. Without a recent count, the accuracy
    // decides.
    private static void theSatellitesGiveTheSourceOfAFusedFix() {
        check("satellites", FixSource.GPS, FixSource.ofFused(FixSource.MIN_SATELLITES, 0, 30));
        check("too few", FixSource.NETWORK,
                FixSource.ofFused(FixSource.MIN_SATELLITES - 1, 0, 5));
        check("no count, good accuracy", FixSource.GPS,
                FixSource.ofFused(-1, GpsReadiness.NO_FIX, FixSource.GPS_ACCURACY_M));
        check("no count, bad accuracy", FixSource.NETWORK,
                FixSource.ofFused(-1, GpsReadiness.NO_FIX, 25));
        check("old count", FixSource.GPS,
                FixSource.ofFused(0, FixSource.GPS_FRESH_MS + 1, 5));
        check("no accuracy", FixSource.NETWORK,
                FixSource.ofFused(-1, GpsReadiness.NO_FIX, Double.NaN));
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
