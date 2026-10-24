package net.basov.retrace.core;

import java.util.List;

// ----------------------------------------------------------------------
// The lowest, the mean and the highest speed of a saved track
// ----------------------------------------------------------------------
//
// The track screen shows the three speeds after the profile, the distance
// and the time.
//
// A stop does not count. A step between two points slower than STOP_MPS is
// a stop. Examples are a wait for the GPS at the start, or a wait at a
// traffic light. Without this rule, the lowest speed is zero for almost
// each track.
//
// A GPS jump does not count either. A step faster than WayBuilder.JUMP_FACTOR
// times the top speed of the profile is a jump, the same as in WayBuilder.
// Without this rule, a GPS error at the start gives the highest speed.
//
// The mean speed is the distance of the moving steps divided by their time.
//
// The lowest and the highest speed come from windows of the smoothing time
// of the profile. A window starts at a point and ends at the first point at
// least that time later. It has no stop in it. One step can have a large
// GPS error, and the window makes its effect small.
//
// The windows do not cover a short movement between two stops. Thus the
// mean can be outside the range of the windows in rare cases. The mean
// then moves to the nearest end of the range, because the screen shows the
// three values as one range.
//
// A track with no times, or with no moving step, has no speeds. A track
// shorter than one window has the mean speed only. A value that is not
// known is NaN.
public final class TrackSpeeds {

    public static final double STOP_MPS = 0.5;

    public final double minMps;
    public final double meanMps;
    public final double maxMps;

    private TrackSpeeds(double minMps, double meanMps, double maxMps) {
        this.minMps = minMps;
        this.meanMps = meanMps;
        this.maxMps = maxMps;
    }

    public boolean hasMean() {
        return !Double.isNaN(meanMps);
    }

    public boolean hasRange() {
        return !Double.isNaN(minMps) && !Double.isNaN(maxMps);
    }

    public static TrackSpeeds of(Track track, Profile profile) {
        TrackSpeeds none = new TrackSpeeds(Double.NaN, Double.NaN, Double.NaN);
        List<TrackPoint> p = track.points;
        int n = p.size();
        if (n < 2) {
            return none;
        }
        // along[i] is the distance from point 0 to point i. stop[i] is true
        // if the step from point i - 1 to point i is a stop or a jump.
        double[] along = new double[n];
        boolean[] stop = new boolean[n];
        double movingM = 0;
        double movingMs = 0;
        double jumpMps = WayBuilder.JUMP_FACTOR * profile.maxSpeedMps;
        for (int i = 1; i < n; i++) {
            TrackPoint a = p.get(i - 1);
            TrackPoint b = p.get(i);
            if (!a.hasTime() || !b.hasTime()) {
                return none;
            }
            double d = Geo.distanceM(a, b);
            long dt = b.timeMs - a.timeMs;
            along[i] = along[i - 1] + d;
            double v = dt <= 0 ? 0 : d * 1000.0 / dt;
            stop[i] = v < STOP_MPS || v > jumpMps;
            if (!stop[i]) {
                movingM += d;
                movingMs += dt;
            }
        }
        if (movingMs <= 0) {
            return none;
        }
        double mean = movingM * 1000.0 / movingMs;

        // nextStop[i] is the first k > i with a stop or a jump from point
        // k - 1 to k, or n.
        int[] nextStop = new int[n];
        nextStop[n - 1] = n;
        for (int i = n - 2; i >= 0; i--) {
            nextStop[i] = stop[i + 1] ? i + 1 : nextStop[i + 1];
        }
        long windowMs = 1000L * profile.speedSmoothingS;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int j = 1;
        for (int i = 0; i < n - 1; i++) {
            j = Math.max(j, i + 1);
            while (j < n && p.get(j).timeMs - p.get(i).timeMs < windowMs) {
                j++;
            }
            if (j >= n) {
                break;
            }
            if (j < nextStop[i]) {
                double v = (along[j] - along[i]) * 1000.0 / (p.get(j).timeMs - p.get(i).timeMs);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (min > max) {
            return new TrackSpeeds(Double.NaN, mean, Double.NaN);
        }
        return new TrackSpeeds(min, Math.max(min, Math.min(max, mean)), max);
    }
}
