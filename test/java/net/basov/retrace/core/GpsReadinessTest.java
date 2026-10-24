package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The tests of GpsReadiness
// ----------------------------------------------------------------------
public final class GpsReadinessTest {

    private static int failures = 0;

    public static void main(String[] args) {
        theLocationSettingComesFirst();
        anOldFixIsNoFix();
        theLimitOfTheProfileDecides();
        aFixWithNoAccuracyIsReady();

        if (failures > 0) {
            System.out.println("GpsReadinessTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("GpsReadinessTest: every check passed");
    }

    // With the location off, the receiver gives no new fix. An old fix
    // must not show a ready state.
    private static void theLocationSettingComesFirst() {
        check("off", GpsReadiness.State.OFF,
                GpsReadiness.of(false, 100, 5, Profile.WALKING));
    }

    private static void anOldFixIsNoFix() {
        check("no fix", GpsReadiness.State.SEARCHING,
                GpsReadiness.of(true, GpsReadiness.NO_FIX, Double.NaN, Profile.WALKING));
        check("old fix", GpsReadiness.State.SEARCHING,
                GpsReadiness.of(true, GpsReadiness.FRESH_MS + 1, 5, Profile.WALKING));
        check("fresh fix", GpsReadiness.State.READY,
                GpsReadiness.of(true, GpsReadiness.FRESH_MS, 5, Profile.WALKING));
    }

    // The state must agree with RecordFilter. The same accuracy can be too
    // low for walking and good for driving.
    private static void theLimitOfTheProfileDecides() {
        double accuracy = Profile.WALKING.accuracyLimitM + 1;
        check("too low for walking", GpsReadiness.State.TOO_LOW,
                GpsReadiness.of(true, 0, accuracy, Profile.WALKING));
        check("good for driving", GpsReadiness.State.READY,
                GpsReadiness.of(true, 0, accuracy, Profile.DRIVING));
        check("at the limit", GpsReadiness.State.READY,
                GpsReadiness.of(true, 0, Profile.WALKING.accuracyLimitM, Profile.WALKING));
        RecordFilter filter = new RecordFilter(Profile.WALKING);
        check("filter agrees", false,
                filter.accept(new TrackPoint(0, 0, 1000, Double.NaN, accuracy)));
    }

    // RecordFilter accepts a fix with no accuracy.
    private static void aFixWithNoAccuracyIsReady() {
        check("no accuracy", GpsReadiness.State.READY,
                GpsReadiness.of(true, 0, Double.NaN, Profile.WALKING));
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
