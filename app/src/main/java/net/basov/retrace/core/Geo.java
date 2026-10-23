package net.basov.retrace.core;

// ----------------------------------------------------------------------
// Distances on the Earth
// ----------------------------------------------------------------------
//
// The haversine formula on a sphere has an error below 0.5 percent. That is
// much less than the error of a GPS fix.
public final class Geo {

    // The mean radius of the Earth in meters.
    public static final double EARTH_RADIUS_M = 6371008.8;

    private Geo() {
    }

    // Returns the distance between two positions in meters.
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(lon2 - lon1);
        double sinPhi = Math.sin(dPhi / 2.0);
        double sinLambda = Math.sin(dLambda / 2.0);
        double a = sinPhi * sinPhi + Math.cos(phi1) * Math.cos(phi2) * sinLambda * sinLambda;
        return 2.0 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static double distanceM(TrackPoint a, TrackPoint b) {
        return distanceM(a.latitude, a.longitude, b.latitude, b.longitude);
    }
}
