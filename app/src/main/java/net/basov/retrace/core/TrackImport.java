package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// ----------------------------------------------------------------------
// The check of a track from another application
// ----------------------------------------------------------------------
//
// A GPX file from another application can be a planned route with no time,
// or a track with points in the wrong sequence. A reference track needs a
// time for each point, because the comparison uses the time at each place.
//
// check() keeps each point that has a time later than the time of the point
// before it. A track with fewer than two such points cannot be imported.
//
// The GPX file can have no Retrace profile. Then guessProfile() gives a
// profile from the median speed of the track. The user confirms it.
public final class TrackImport {

    public static final int OK = 0;
    public static final int TOO_FEW_POINTS = 1;
    public static final int NO_TIME = 2;

    // The median speed limits of the guess, in meters per second: 8 km/h,
    // 16 km/h and 40 km/h.
    static final double WALKING_MAX_MPS = 8 / 3.6;
    static final double RUNNING_MAX_MPS = 16 / 3.6;
    static final double CYCLING_MAX_MPS = 40 / 3.6;

    // A part of a track below this speed is a stop. The guess ignores it.
    static final double STOP_MPS = 0.3;

    // The result of a check.
    public static final class Result {
        public final int error;

        // The track with the kept points, or null after an error.
        public final Track track;

        // True if the GPX file has a Retrace profile.
        public final boolean profileKnown;

        // The profile from the file, or the guess.
        public final Profile profile;

        Result(int error, Track track, boolean profileKnown, Profile profile) {
            this.error = error;
            this.track = track;
            this.profileKnown = profileKnown;
            this.profile = profile;
        }
    }

    private TrackImport() {
    }

    public static Result check(Track raw) {
        if (raw.points.size() < 2) {
            return new Result(TOO_FEW_POINTS, null, false, null);
        }
        List<TrackPoint> kept = new ArrayList<>();
        long last = Long.MIN_VALUE;
        for (TrackPoint p : raw.points) {
            if (p.hasTime() && p.timeMs > last) {
                kept.add(p);
                last = p.timeMs;
            }
        }
        if (kept.size() < 2) {
            return new Result(NO_TIME, null, false, null);
        }
        boolean known = raw.profile != null;
        Profile profile = known ? raw.profile : guessProfile(kept);
        return new Result(OK, new Track(raw.name, profile, kept), known, profile);
    }

    // The profile for the median speed of the moving parts. The points must
    // have increasing times.
    public static Profile guessProfile(List<TrackPoint> points) {
        double[] speeds = new double[points.size()];
        int count = 0;
        for (int i = 1; i < points.size(); i++) {
            TrackPoint a = points.get(i - 1);
            TrackPoint b = points.get(i);
            double seconds = (b.timeMs - a.timeMs) / 1000.0;
            if (seconds <= 0) {
                continue;
            }
            double speed = Geo.distanceM(a, b) / seconds;
            if (speed >= STOP_MPS) {
                speeds[count++] = speed;
            }
        }
        if (count == 0) {
            return Profile.WALKING;
        }
        Arrays.sort(speeds, 0, count);
        double median = speeds[count / 2];
        if (median <= WALKING_MAX_MPS) {
            return Profile.WALKING;
        }
        if (median <= RUNNING_MAX_MPS) {
            return Profile.RUNNING;
        }
        if (median <= CYCLING_MAX_MPS) {
            return Profile.CYCLING;
        }
        return Profile.DRIVING;
    }
}
