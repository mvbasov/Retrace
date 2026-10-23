package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The timing of a replay
// ----------------------------------------------------------------------
//
// A replay sends the points of a saved track to TrackService as if they
// were GPS fixes. Each point keeps its own time. Thus the comparison is the
// same as on the real trip. Only the wait between two points on the screen
// is shorter: the time difference divided by the speed of the replay.
//
// A long stop in the saved track would stop the replay for many minutes.
// Thus no wait is longer than MAX_WAIT_MS.
public final class Replay {

    // The speeds that the user can select.
    public static final int[] SPEEDS = {1, 5, 10, 30, 100};
    public static final int DEFAULT_SPEED = 10;

    public static final long MAX_WAIT_MS = 2000;

    // The wait for a point with no time, before the speed applies.
    static final long NO_TIME_WAIT_MS = 1000;

    private Replay() {
    }

    // The wait on the screen between two points of the saved track.
    public static long waitMs(TrackPoint before, TrackPoint next, int speed) {
        int s = Math.max(1, speed);
        long gap = before.hasTime() && next.hasTime()
                ? Math.max(0, next.timeMs - before.timeMs) : NO_TIME_WAIT_MS;
        return Math.min(MAX_WAIT_MS, gap / s);
    }
}
