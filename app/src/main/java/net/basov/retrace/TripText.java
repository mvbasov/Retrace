package net.basov.retrace;

import android.content.Context;

import net.basov.retrace.core.MatchResult;
import net.basov.retrace.core.RecordingSnapshot;
import net.basov.retrace.core.TimeText;
import net.basov.retrace.core.TrackMatcher;

// ----------------------------------------------------------------------
// The user text of a trip
// ----------------------------------------------------------------------
//
// The trip screen and the notification show the same values. This class
// makes their text from strings.xml. A value that is not known shows as a
// dash.
final class TripText {

    // A time difference below this value shows as "on time". GPS noise is
    // larger than one second.
    private static final long EVEN_MS = 1000;

    private TripText() {
    }

    // The main line: the state of the trip or the time difference.
    static String headline(Context c, RecordingSnapshot s) {
        MatchResult m = s.match;
        if (m == null) {
            return c.getString(R.string.status_recording_short, s.points,
                    Labels.distance(c, s.distanceM), TimeText.duration(s.elapsedMs(System.currentTimeMillis())));
        }
        switch (m.state) {
            case WAITING:
                return c.getString(R.string.trip_waiting);
            case AT_START:
                return c.getString(R.string.trip_at_start);
            case OFF_ROUTE:
                return c.getString(R.string.trip_off_route, (int) Math.round(m.crossTrackM));
            case FINISHED:
                return finish(c, s.smoothedDeltaMs);
            case ON_ROUTE:
            default:
                return delta(c, s.smoothedDeltaMs);
        }
    }

    // "0:42 ahead", "1:05 behind" or "On time".
    static String delta(Context c, long ms) {
        if (ms == MatchResult.UNKNOWN) {
            return c.getString(R.string.value_unknown);
        }
        if (Math.abs(ms) < EVEN_MS) {
            return c.getString(R.string.delta_even);
        }
        String time = TimeText.duration(Math.abs(ms));
        return c.getString(ms < 0 ? R.string.delta_ahead : R.string.delta_behind, time);
    }

    static String finish(Context c, long ms) {
        if (ms == MatchResult.UNKNOWN || Math.abs(ms) < EVEN_MS) {
            return c.getString(R.string.finish_same);
        }
        String time = TimeText.duration(Math.abs(ms));
        return c.getString(ms < 0 ? R.string.finish_faster : R.string.finish_slower, time);
    }

    // The distance from the ghost.
    static String ghost(Context c, double meters) {
        if (Double.isNaN(meters)) {
            return c.getString(R.string.value_unknown);
        }
        String distance = Labels.distance(c, Math.abs(meters));
        return c.getString(meters >= 0 ? R.string.ghost_ahead : R.string.ghost_behind, distance);
    }

    static String speed(Context c, double mps) {
        if (Double.isNaN(mps) || Double.isInfinite(mps)) {
            return c.getString(R.string.value_unknown);
        }
        return c.getString(R.string.speed_value, Math.max(0, mps) * 3.6);
    }

    // "Speed 5.2 km/h · reference 4.9 km/h".
    static String speeds(Context c, MatchResult m) {
        return c.getString(R.string.speed_compare, speed(c, m.speedMps), speed(c, m.referenceSpeedMps));
    }

    // "3.2 km to the finish · 38:20".
    static String toFinish(Context c, MatchResult m) {
        String distance = Double.isNaN(m.remainingM)
                ? c.getString(R.string.value_unknown) : Labels.distance(c, m.remainingM);
        String time = m.predictedTotalMs == MatchResult.UNKNOWN || m.elapsedMs == MatchResult.UNKNOWN
                ? c.getString(R.string.value_unknown)
                : TimeText.duration(m.predictedTotalMs - m.elapsedMs);
        return c.getString(R.string.to_finish, distance, time);
    }

    // The text of the notification: the main line, and the distance to the
    // finish while the trip is on the route.
    static String notification(Context c, RecordingSnapshot s) {
        String head = headline(c, s);
        MatchResult m = s.match;
        if (m != null && m.state == TrackMatcher.State.ON_ROUTE) {
            return head + " · " + toFinish(c, m);
        }
        return head;
    }
}
