package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// ----------------------------------------------------------------------
// A track: a name, a profile and a list of points
// ----------------------------------------------------------------------
//
// The constructor copies the list. Thus a track does not change after the
// constructor, and two threads can read it.
public final class Track {

    public final String name;

    // The profile, or null when a GPX file from another application has an
    // unknown type.
    public final Profile profile;

    public final List<TrackPoint> points;

    private final double distanceM;

    public Track(String name, Profile profile, List<TrackPoint> points) {
        this.name = name == null ? "" : name;
        this.profile = profile;
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.distanceM = sumDistance(this.points);
    }

    // Returns the same points with a different name.
    public Track withName(String newName) {
        return new Track(newName, profile, points);
    }

    // The length of the track along its points, in meters.
    public double distanceM() {
        return distanceM;
    }

    // The time of the first point with a time, or NO_TIME.
    public long startTimeMs() {
        for (TrackPoint p : points) {
            if (p.hasTime()) {
                return p.timeMs;
            }
        }
        return TrackPoint.NO_TIME;
    }

    // The time from the first point with a time to the last point with a
    // time, in milliseconds. A track with no time gives 0.
    public long durationMs() {
        long first = startTimeMs();
        if (first == TrackPoint.NO_TIME) {
            return 0;
        }
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i).hasTime()) {
                return Math.max(0, points.get(i).timeMs - first);
            }
        }
        return 0;
    }

    private static double sumDistance(List<TrackPoint> points) {
        double sum = 0;
        for (int i = 1; i < points.size(); i++) {
            sum += Geo.distanceM(points.get(i - 1), points.get(i));
        }
        return sum;
    }
}
