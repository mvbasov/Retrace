package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The removal of GPS spikes from a track
// ----------------------------------------------------------------------
//
// A spike is a short burst of bad fixes, at most MAX_POINTS points. The
// track jumps away from the last good point, and it jumps back after the
// burst. A jump is a step faster than WayBuilder.JUMP_FACTOR times the top
// speed of the profile. From the last good point to the first point after
// the burst, the speed is possible. The step into the last good point is
// not a jump, thus that point is good. remove() removes the points of the
// burst and keeps all other points. Thus the detail of the track stays.
//
// Example: one fix 95 m to the side of a run, 2.5 s after the point before
// it and 2.5 s before the point after it. Each of its two steps is near
// 40 m/s. The step over it is 8 m in 5 s. The spike adds almost 200 m to
// the length of the track.
//
// These are not spikes, and they stay:
// - A fast movement that does not come back. A spike always comes back to
//   the line of the track.
// - A gap with a possible speed, for example in a passage with no GPS.
// - An error at the start or at the end of a track. It has no good point
//   on one side. Its jump does not come back. WayBuilder cuts such an
//   error with its own rule. Without the two jumps, a burst of bad points
//   at the start could look like good points with a spike after them.
//
// The saved file does not change. The calculations use the points without
// spikes: the length of a track, its speeds, a way and the reference track
// of a trip.
public final class GpsSpikes {

    // The longest burst that remove() takes as a spike.
    public static final int MAX_POINTS = 5;

    private GpsSpikes() {
    }

    // Returns the points with no spike. The list is the same object if the
    // track has no spike. A track with no profile has no top speed. Its
    // points stay.
    public static List<TrackPoint> remove(List<TrackPoint> points, Profile profile) {
        int n = points.size();
        if (profile == null || n < 3) {
            return points;
        }
        double jumpMps = WayBuilder.JUMP_FACTOR * profile.maxSpeedMps;
        List<TrackPoint> out = new ArrayList<>(n);
        out.add(points.get(0));
        int i = 1;
        while (i < n) {
            TrackPoint good = out.get(out.size() - 1);
            boolean goodIsGood = out.size() < 2
                    || !fast(out.get(out.size() - 2), good, jumpMps);
            if (goodIsGood && i < n - 1 && fast(good, points.get(i), jumpMps)) {
                int back = -1;
                for (int k = i + 1; k < n && k <= i + MAX_POINTS; k++) {
                    if (!fast(good, points.get(k), jumpMps)
                            && fast(points.get(k - 1), points.get(k), jumpMps)) {
                        back = k;
                        break;
                    }
                }
                if (back > 0) {
                    i = back;
                    continue;
                }
            }
            out.add(points.get(i));
            i++;
        }
        return out.size() == n ? points : out;
    }

    // A step with no time, or with no time between the points, is not fast.
    // Retrace cannot tell its speed.
    private static boolean fast(TrackPoint a, TrackPoint b, double jumpMps) {
        if (!a.hasTime() || !b.hasTime() || b.timeMs <= a.timeMs) {
            return false;
        }
        return Geo.distanceM(a, b) * 1000.0 / (b.timeMs - a.timeMs) > jumpMps;
    }
}
