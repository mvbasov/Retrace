package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// ----------------------------------------------------------------------
// The generator of invented tracks for the tests
// ----------------------------------------------------------------------
//
// The tests use no real track. A real track holds the precise positions of
// a person. Thus it does not go into a public repository.
//
// A route is a list of corners in meters, x to the east and y to the north,
// from an invented origin. The generator moves along the route at a fixed
// speed and makes one point each second.
//
// The noise imitates a GPS receiver. The error of one fix is near the error
// of the fix before it, because a real receiver has a slow drift. Thus each
// error is a slow drift plus a small fast part. The drift has a typical size
// of sigma on each axis and changes over some minutes. The fast part is 0.05
// sigma. A real error changes slowly. The speed from the Doppler shift of a
// receiver is accurate for the same reason. A fixed seed gives the same track
// in each test run.
//
// recorded() also sends the noisy points through RecordFilter, the same as
// TrackService does. A saved track thus has the same form as a real one.
//
// The class name does not end with Test. Thus test/run.sh does not run it.
final class TestTracks {

    // An invented origin in a field with no special meaning.
    static final double ORIGIN_LAT = 45.0;
    static final double ORIGIN_LON = 10.0;

    static final long START_MS = 1792753200000L;

    private static final double METERS_PER_DEGREE = Geo.EARTH_RADIUS_M * Math.PI / 180.0;

    private TestTracks() {
    }

    // Makes the points of a trip along the corners at a fixed speed. The trip
    // waits at the first corner for waitS seconds first.
    static List<TrackPoint> ideal(double[][] corners, double speedMps, long startMs, int waitS) {
        double[] at = new double[corners.length];
        for (int i = 1; i < corners.length; i++) {
            double dx = corners[i][0] - corners[i - 1][0];
            double dy = corners[i][1] - corners[i - 1][1];
            at[i] = at[i - 1] + Math.sqrt(dx * dx + dy * dy);
        }
        double length = at[corners.length - 1];

        List<TrackPoint> points = new ArrayList<>();
        long t = startMs;
        for (int i = 0; i < waitS; i++) {
            points.add(point(corners[0][0], corners[0][1], t));
            t += 1000;
        }
        for (int k = 0; ; k++) {
            double d = Math.min(k * speedMps, length);
            int i = 0;
            while (i < corners.length - 2 && at[i + 1] < d) {
                i++;
            }
            double segment = at[i + 1] - at[i];
            double f = segment > 0 ? (d - at[i]) / segment : 0;
            double x = corners[i][0] + f * (corners[i + 1][0] - corners[i][0]);
            double y = corners[i][1] + f * (corners[i + 1][1] - corners[i][1]);
            points.add(point(x, y, t));
            t += 1000;
            if (d >= length) {
                return points;
            }
        }
    }

    // Adds GPS-like noise to each point. sigmaM is the typical error.
    static List<TrackPoint> noisy(List<TrackPoint> ideal, long seed, double sigmaM) {
        Random random = new Random(seed);
        List<TrackPoint> out = new ArrayList<>();
        // The drift has a correlation time of 180 s. The step keeps its
        // typical size at sigma.
        double decay = Math.exp(-1.0 / 180);
        double step = sigmaM * Math.sqrt(1 - decay * decay);
        double driftX = random.nextGaussian() * sigmaM;
        double driftY = random.nextGaussian() * sigmaM;
        for (TrackPoint p : ideal) {
            driftX = decay * driftX + random.nextGaussian() * step;
            driftY = decay * driftY + random.nextGaussian() * step;
            double ex = driftX + random.nextGaussian() * sigmaM * 0.05;
            double ey = driftY + random.nextGaussian() * sigmaM * 0.05;
            TrackPoint q = shift(p, ex, ey);
            // Android gives the accuracy of each fix. The test gives the
            // typical error of the noise.
            out.add(new TrackPoint(q.latitude, q.longitude, q.timeMs, q.elevationM, sigmaM));
        }
        return out;
    }

    // Keeps only the points that RecordFilter accepts for the profile.
    static List<TrackPoint> recorded(List<TrackPoint> points, Profile profile) {
        RecordFilter filter = new RecordFilter(profile);
        List<TrackPoint> out = new ArrayList<>();
        for (TrackPoint p : points) {
            if (filter.accept(p)) {
                out.add(p);
            }
        }
        return out;
    }

    // Removes the points between two times. This imitates a gap in the GPS
    // fixes, for example in a tunnel.
    static List<TrackPoint> withGap(List<TrackPoint> points, long fromMs, long toMs) {
        List<TrackPoint> out = new ArrayList<>();
        for (TrackPoint p : points) {
            if (p.timeMs < fromMs || p.timeMs > toMs) {
                out.add(p);
            }
        }
        return out;
    }

    // Removes the times. A GPX route has no times.
    static List<TrackPoint> withoutTimes(List<TrackPoint> points) {
        List<TrackPoint> out = new ArrayList<>();
        for (TrackPoint p : points) {
            out.add(new TrackPoint(p.latitude, p.longitude, TrackPoint.NO_TIME,
                    Double.NaN, Double.NaN));
        }
        return out;
    }

    static Track track(String name, List<TrackPoint> points) {
        return new Track(name, Profile.WALKING, points);
    }

    static TrackPoint point(double x, double y, long timeMs) {
        double lat = ORIGIN_LAT + y / METERS_PER_DEGREE;
        double lon = ORIGIN_LON + x / (METERS_PER_DEGREE * Math.cos(Math.toRadians(ORIGIN_LAT)));
        return new TrackPoint(lat, lon, timeMs, Double.NaN, 4);
    }

    static TrackPoint shift(TrackPoint p, double eastM, double northM) {
        double lat = p.latitude + northM / METERS_PER_DEGREE;
        double lon = p.longitude
                + eastM / (METERS_PER_DEGREE * Math.cos(Math.toRadians(p.latitude)));
        return new TrackPoint(lat, lon, p.timeMs, p.elevationM, p.accuracyM);
    }
}
