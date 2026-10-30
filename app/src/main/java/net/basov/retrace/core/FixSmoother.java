package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The smoothing of the fixes before the filter
// ----------------------------------------------------------------------
//
// A GPS receiver gives one fix each second. Each fix has a small random
// error. At walking speed the steps are short, thus the error makes a
// zigzag line. A fix can also have a large error, for example a GPS spike
// of 70 m for one second near a high building. This class removes the two
// faults before RecordFilter sees the fix.
//
// The class is a Kalman filter with a constant speed model. It keeps an
// estimate of the position and of the speed on each axis, and the variance
// of the two. For each fix:
// 1. The estimate moves with its speed to the time of the fix. The
//    variance increases, because the speed can change.
// 2. The fix pulls the estimate to it. A fix with a good accuracy pulls
//    more than a fix with a bad accuracy.
//
// The two axes have the same variance, because the accuracy of a fix is a
// circle. Thus one 2 x 2 matrix serves the two axes.
//
// The gate: the distance from the estimate to the fix, divided by its
// expected size, tells if the fix is possible. A fix of a GPS spike is
// outside the gate. The top speed of the profile also cannot give that
// distance in the time since the last fix. Without the second rule, a car
// in a sharp turn gives false spikes. The class drops the fix of a spike
// and returns null.
//
// After MAX_REJECTED dropped fixes in a sequence, the fixes tell a real
// change of the position. The filter then starts again at the new fix. An example is the first GPS fix after some
// fixes of the network location with a large error.
//
// The filter does not remove the drift. The drift changes over minutes, and
// it looks like a real movement.
//
// The output point has the time, the elevation and the accuracy of the fix.
// RecordFilter uses that accuracy.
public final class FixSmoother {

    // The square of the gate distance. A 2D normal error goes outside it in
    // one fix of 1000.
    static final double GATE = 13.8;

    // The same limit as the burst of GpsSpikes.
    static final int MAX_REJECTED = GpsSpikes.MAX_POINTS;

    // The accuracy of a fix is never better than this in the filter. A
    // receiver can tell 1 m, but the real error is larger.
    static final double MIN_SIGMA_M = 3;

    // The accuracy of a fix with no accuracy.
    static final double DEFAULT_SIGMA_M = 10;

    // The change of the speed: the variance of the acceleration noise, in
    // m^2/s^3, is this factor times the top speed of the profile. A car
    // changes its speed faster than a person on foot.
    static final double ACCELERATION_FACTOR = 0.15;

    private static final double METERS_PER_DEGREE = Geo.EARTH_RADIUS_M * Math.PI / 180.0;

    private final double q;
    private final double maxSpeedMps;

    private boolean started;
    private double originLat;
    private double originLon;
    private double metersPerDegreeLon;
    private long lastTimeMs;
    // The estimate: position x (east) and y (north) in meters from the
    // origin, and the speed on each axis.
    private double x;
    private double y;
    private double vx;
    private double vy;
    // The covariance of position and speed on one axis.
    private double pxx;
    private double pxv;
    private double pvv;
    private int rejected;

    public FixSmoother(Profile profile) {
        this.q = ACCELERATION_FACTOR * profile.maxSpeedMps;
        this.maxSpeedMps = profile.maxSpeedMps;
    }

    // Returns the smoothed point, or null for a fix of a GPS spike.
    public TrackPoint filter(TrackPoint fix) {
        double sigma = Double.isNaN(fix.accuracyM) ? DEFAULT_SIGMA_M
                : Math.max(MIN_SIGMA_M, fix.accuracyM);
        double r = sigma * sigma;
        if (!started) {
            originLat = fix.latitude;
            originLon = fix.longitude;
            metersPerDegreeLon = METERS_PER_DEGREE * Math.cos(Math.toRadians(originLat));
            started = true;
            restart(fix, r);
            return fix;
        }
        double zx = (fix.longitude - originLon) * metersPerDegreeLon;
        double zy = (fix.latitude - originLat) * METERS_PER_DEGREE;

        // A fix with no time, or with the time of the last fix, does not
        // move the estimate in time.
        double dt = 0;
        if (fix.hasTime() && lastTimeMs != TrackPoint.NO_TIME && fix.timeMs > lastTimeMs) {
            dt = (fix.timeMs - lastTimeMs) / 1000.0;
        }
        // Step 1: the prediction to the time of the fix.
        double px = x + vx * dt;
        double py = y + vy * dt;
        double nxx = pxx + 2 * dt * pxv + dt * dt * pvv + q * dt * dt * dt / 3;
        double nxv = pxv + dt * pvv + q * dt * dt / 2;
        double nvv = pvv + q * dt;

        // The gate.
        double ex = zx - px;
        double ey = zy - py;
        double s = nxx + r;
        double e2 = ex * ex + ey * ey;
        double reach = maxSpeedMps * dt;
        if (e2 / s > GATE && e2 > reach * reach) {
            rejected++;
            if (rejected <= MAX_REJECTED) {
                return null;
            }
            restart(fix, r);
            return fix;
        }
        rejected = 0;

        // Step 2: the correction with the fix.
        double kx = nxx / s;
        double kv = nxv / s;
        x = px + kx * ex;
        y = py + kx * ey;
        vx = vx + kv * ex;
        vy = vy + kv * ey;
        pxx = (1 - kx) * nxx;
        pxv = (1 - kx) * nxv;
        pvv = nvv - kv * nxv;
        if (fix.hasTime()) {
            lastTimeMs = fix.timeMs;
        }
        double lat = originLat + y / METERS_PER_DEGREE;
        double lon = originLon + x / metersPerDegreeLon;
        return new TrackPoint(lat, lon, fix.timeMs, fix.elevationM, fix.accuracyM);
    }

    // The estimate starts at the fix with no speed. The speed can be any
    // value up to the top speed of the profile.
    private void restart(TrackPoint fix, double r) {
        x = (fix.longitude - originLon) * metersPerDegreeLon;
        y = (fix.latitude - originLat) * METERS_PER_DEGREE;
        vx = 0;
        vy = 0;
        pxx = r;
        pxv = 0;
        pvv = maxSpeedMps * maxSpeedMps;
        rejected = 0;
        lastTimeMs = fix.timeMs;
    }
}
