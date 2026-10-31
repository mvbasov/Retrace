package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The filter between the GPS receiver and the current track
// ----------------------------------------------------------------------
//
// The filter decides which fixes become points. It drops three types of fix:
// - a fix with an accuracy worse than the limit
// - a fix that is not later than the last point
// - a fix too near to the last point
//
// The limit of the accuracy: before the first precise point, the limit is
// COARSE_LIMIT_M. A precise point has an accuracy inside the limit of the
// profile. After it, the limit of the profile applies. Thus a trip can start
// with the network location when the GPS has no fix yet, for example inside
// a building. The automatic selection then starts with that point, and the
// trip screen shows a position.
//
// The minimum step is the larger of two values: the minimum distance of the
// profile, and the accuracy of the fix. A step shorter than the accuracy can
// be noise only. Without this rule, the error of a receiver that does not
// move makes a false line of many meters, mostly at the start. A stop of ten
// minutes then adds a false distance to the track.
//
// After newSegment(), the next point starts a new track segment. It needs
// no minimum step, and the step to it is not distance.
public final class RecordFilter {

    // A network fix from Wi-Fi is usually inside this limit. A fix from the
    // cells of the phone network is not, and it is not useful for a track.
    public static final double COARSE_LIMIT_M = 100;

    private final Profile profile;
    private TrackPoint last;
    private boolean precise;
    private boolean segmentBreak;
    private int count;
    private double distanceM;

    public RecordFilter(Profile profile) {
        this.profile = profile;
    }

    // Returns true if the fix becomes a point of the track.
    public boolean accept(TrackPoint fix) {
        boolean known = !Double.isNaN(fix.accuracyM);
        double limit = precise ? profile.accuracyLimitM : COARSE_LIMIT_M;
        if (known && fix.accuracyM > limit) {
            return false;
        }
        if (last != null) {
            if (fix.timeMs <= last.timeMs) {
                return false;
            }
            if (!segmentBreak) {
                double step = Geo.distanceM(last, fix);
                double minStep = known ? Math.max(profile.minDistanceM, fix.accuracyM)
                        : profile.minDistanceM;
                if (step < minStep) {
                    return false;
                }
                distanceM += step;
            }
        }
        segmentBreak = false;
        if (!known || fix.accuracyM <= profile.accuracyLimitM) {
            precise = true;
        }
        last = fix;
        count++;
        return true;
    }

    // The next accepted point starts a new track segment.
    public void newSegment() {
        segmentBreak = true;
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
