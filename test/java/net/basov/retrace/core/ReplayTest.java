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
        aTrackWithNoProfileIsReplayedAsWalking();
        onlyAStoppedReplayCanStartAgain();
        aRepeatKeepsTheSelection();

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

    // The reference dialog and the service must use the same profile.
    private static void aTrackWithNoProfileIsReplayedAsWalking() {
        check("no profile", Profile.WALKING, Replay.profileFor(null));
        check("own profile", Profile.CYCLING, Replay.profileFor(Profile.CYCLING));
    }

    // The trip screen shows the Replay button only after the stop of a
    // replay. A trip with the GPS cannot start again as a replay.
    private static void onlyAStoppedReplayCanStartAgain() {
        check("stopped replay", true,
                snapshot(false, 10, RecordingSnapshot.SELECTION_OFF, null).canReplayAgain());
        check("running replay", false,
                snapshot(true, 10, RecordingSnapshot.SELECTION_OFF, null).canReplayAgain());
        check("trip with the GPS", false,
                snapshot(false, 0, RecordingSnapshot.SELECTION_OFF, null).canReplayAgain());
        check("idle", false, RecordingSnapshot.IDLE.canReplayAgain());
    }

    // A repeat uses the last selection. The automatic selection runs again,
    // and a track that the user selected stays.
    private static void aRepeatKeepsTheSelection() {
        int[] automatic = {RecordingSnapshot.SELECTION_SEARCH,
                RecordingSnapshot.SELECTION_TEMPORARY, RecordingSnapshot.SELECTION_AUTOMATIC,
                RecordingSnapshot.SELECTION_NOT_FOUND};
        for (int selection : automatic) {
            RecordingSnapshot s = snapshot(false, 10, selection, "/a.gpx");
            check("automatic " + selection, true, s.automaticAgain());
            check("no fixed track " + selection, null, s.referenceAgain());
        }
        RecordingSnapshot manual = snapshot(false, 10, RecordingSnapshot.SELECTION_MANUAL, "/a.gpx");
        check("manual is not automatic", false, manual.automaticAgain());
        check("manual track", "/a.gpx", manual.referenceAgain());
        RecordingSnapshot off = snapshot(false, 10, RecordingSnapshot.SELECTION_OFF, null);
        check("off is not automatic", false, off.automaticAgain());
        check("off has no track", null, off.referenceAgain());
    }

    private static RecordingSnapshot snapshot(boolean recording, int speed, int selection,
                                              String reference) {
        return new RecordingSnapshot(recording, Profile.RUNNING, TestTracks.START_MS, 10, 100,
                5, TestTracks.START_MS, 0, RecordingSnapshot.ERROR_NONE, reference, selection,
                false, null, MatchResult.UNKNOWN, speed > 0 ? "/replayed.gpx" : null,
                speed > 0 ? "Replayed" : null, speed, 0);
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
