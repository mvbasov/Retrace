package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The filter between the GPS receiver and the current track
// ----------------------------------------------------------------------
//
// The filter decides which fixes become points. It drops three types of fix:
// - a fix with an accuracy worse than the limit of the profile
// - a fix that is not later than the last point
// - a fix nearer to the last point than the minimum distance of the profile
//
// The minimum distance stops the noise of a GPS receiver that does not move.
// Without it, a stop of ten minutes adds a false distance to the track.
public final class RecordFilter {

    private final Profile profile;
    private TrackPoint last;
    private int count;
    private double distanceM;

    public RecordFilter(Profile profile) {
        this.profile = profile;
    }

    // Returns true if the fix becomes a point of the track.
    public boolean accept(TrackPoint fix) {
        if (!Double.isNaN(fix.accuracyM) && fix.accuracyM > profile.accuracyLimitM) {
            return false;
        }
        if (last != null) {
            if (fix.timeMs <= last.timeMs) {
                return false;
            }
            double step = Geo.distanceM(last, fix);
            if (step < profile.minDistanceM) {
                return false;
            }
            distanceM += step;
        }
        last = fix;
        count++;
        return true;
    }

    public int count() {
        return count;
    }

    public double distanceM() {
        return distanceM;
    }

    public TrackPoint last() {
        return last;
    }
}
