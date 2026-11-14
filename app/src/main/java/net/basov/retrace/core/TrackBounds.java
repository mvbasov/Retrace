package net.basov.retrace.core;

import java.util.List;

// ----------------------------------------------------------------------
// The bounds of a track in degrees
// ----------------------------------------------------------------------
//
// At the first point of a trip, the automatic selection needs the saved
// tracks with a route near that point. The precise check reads the whole
// track. The bounds give a fast first check: a track with bounds far from
// the point cannot be near.
//
// The check must never say "far" for a near track. Thus it makes the limit
// 10 percent larger. It also uses the shortest degree of longitude between
// the fix and the bounds, because a degree of longitude is shorter far from
// the equator. A "near" for a far track is permitted. The precise check
// comes after it.
//
// The bounds of an empty track are never near.
public final class TrackBounds {

    private static final double METERS_PER_DEGREE = Geo.EARTH_RADIUS_M * Math.PI / 180.0;
    private static final double MARGIN = 1.1;
    // Near a pole, the cosine goes to zero. This value keeps the division
    // finite. The check is then true for each longitude.
    private static final double MIN_COSINE = 0.01;

    public final double minLat;
    public final double maxLat;
    public final double minLon;
    public final double maxLon;

    private TrackBounds(double minLat, double maxLat, double minLon, double maxLon) {
        this.minLat = minLat;
        this.maxLat = maxLat;
        this.minLon = minLon;
        this.maxLon = maxLon;
    }

    public static TrackBounds of(List<TrackPoint> points) {
        if (points.isEmpty()) {
            return new TrackBounds(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        for (TrackPoint p : points) {
            minLat = Math.min(minLat, p.latitude);
            maxLat = Math.max(maxLat, p.latitude);
            minLon = Math.min(minLon, p.longitude);
            maxLon = Math.max(maxLon, p.longitude);
        }
        return new TrackBounds(minLat, maxLat, minLon, maxLon);
    }

    // True if the fix can be inside limitM of a point of the track.
    public boolean near(TrackPoint fix, double limitM) {
        if (Double.isNaN(minLat)) {
            return false;
        }
        double latLimit = MARGIN * limitM / METERS_PER_DEGREE;
        if (fix.latitude < minLat - latLimit || fix.latitude > maxLat + latLimit) {
            return false;
        }
        // The latitude nearest to a pole, of the fix and of the bounds.
        double poleward = Math.max(Math.abs(fix.latitude),
                Math.max(Math.abs(minLat), Math.abs(maxLat))) + latLimit;
        double cos = Math.max(MIN_COSINE, Math.cos(Math.toRadians(Math.min(90, poleward))));
        double lonLimit = latLimit / cos;
        return fix.longitude >= minLon - lonLimit && fix.longitude <= maxLon + lonLimit;
    }
}
