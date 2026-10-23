package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The way: the mean track of some tracks on one route
// ----------------------------------------------------------------------
//
// A way has the mean position and the mean elapsed time of its tracks at
// each place along the route. The GPS errors of different trips are not
// related. Thus the mean line is nearer to the real route than one track,
// and the mean time is a typical trip on that route.
//
// The builder works in meters on a flat plane around the start of the base
// track. The steps:
//
// 1. Sample the base track at one point each step along its length. These
//    points are the stations. GPS noise makes a zigzag line that is longer
//    than the route. Thus the stations are first smoothed along the line.
//    A wait at the start or at the finish makes a small tangle of points.
//    Thus the base track first loses its points before the departure and
//    after the arrival. See trimmed().
// 2. Project each point of each track on the stations. The projection only
//    goes forward, in a short window after the last projection. Thus a
//    route out and back has no jump between its legs. The window grows
//    with the distance from the last point that gave progress along the
//    route. At the turn of a route out and back, the other leg then comes
//    into the window. GPS noise during a wait gives no such distance. The two legs can be at the same place. Thus a part
//    of the line against the direction of movement is not a candidate, the
//    same as in TrackMatcher.
// 3. At each station, find the place and the time where the track first
//    passes the station.
// 4. The way point at a station is the mean of these places. Its elapsed
//    time is the mean of the elapsed times of the tracks.
// 5. Do steps 2 to 4 again with the way as the base. This removes the
//    errors of the base track from the result.
// 6. Cut the way at the mean place where its tracks end. Near the end, only
//    the tracks that go far enough give the place. Without the cut, the
//    end of the way is the end of the base track, with its GPS error.
//
// The elapsed time of a track starts at the start of the route, not at the
// first point. A track can wait at the start before the trip. The time near
// the start thus comes from the speed after the start zone: the start
// circle plus the off route limit. The GPS drift can move the points of a
// wait along the route, also past the start zone. Thus a wait is also found
// by time: an interval between two points with a speed below STOP_MPS. The
// elapsed time does not start before the end of such a wait.
//
// The base track of the first pass has its own GPS error. Thus the first
// pass uses two times the off route limit. A saved track has no accuracy
// for its points. Thus the second pass uses 1.5 times the limit, for the
// slow GPS drift of one track.
//
// A track that does not follow the route is not used. See the REJECT
// values.
public final class WayBuilder {

    // A way needs at least this many tracks.
    public static final int MIN_TRACKS = 2;

    // The reasons for a track that the way does not use.
    public static final int REJECT_START = 1;
    public static final int REJECT_FINISH = 2;
    public static final int REJECT_OFF_ROUTE = 3;

    // A track with more of its stations off the route is not used.
    static final double MAX_OFF_ROUTE_PART = 0.1;

    // The speed of a track at the start and at the finish comes from this
    // distance along the route.
    static final double SPEED_SPAN_M = 50;

    // An interval between two points with a lower speed is a wait, if it is
    // at least WAIT_MS long.
    static final double STOP_MPS = 0.3;
    static final double WAIT_MS = 3000;

    // The stations are smoothed over this distance before and after each
    // station. TrackMatcher smooths the reference track in the same way.
    static final double SMOOTH_M = 10;

    // The projection can go back by this part of the off route limit. GPS
    // noise moves a point back a little.
    static final double BACK_PART = 0.5;

    // The forward window grows by this factor of the distance from the last
    // point with progress, up to MAX_WINDOW_FACTOR times the off route
    // limit.
    static final double GROW_FACTOR = 1.5;
    static final double MAX_WINDOW_FACTOR = 10;

    // The direction of movement comes from the points over this distance.
    // A part of the line with a cosine below AGAINST to that direction is
    // not a candidate.
    static final double DIRECTION_M = 10;
    static final double AGAINST = -0.5;

    private static final double METERS_PER_DEGREE = Geo.EARTH_RADIUS_M * Math.PI / 180.0;

    // The result of a build.
    public static final class Result {
        // The way, or null if fewer than MIN_TRACKS tracks follow the route.
        public final Track way;

        // The indexes of the used tracks, in the input list.
        public final List<Integer> used;

        // The indexes of the other tracks, and one REJECT value for each.
        public final List<Integer> rejected;
        public final List<Integer> reasons;

        Result(Track way, List<Integer> used, List<Integer> rejected, List<Integer> reasons) {
            this.way = way;
            this.used = used;
            this.rejected = rejected;
            this.reasons = reasons;
        }
    }

    // The stations distance for the profile, in meters.
    static double stepM(Profile profile) {
        return Math.max(5, 2.5 * profile.minDistanceM);
    }

    private WayBuilder() {
    }

    // Builds a way from the tracks. The base track gives the route of the
    // first pass. The tracks must have increasing times.
    public static Result build(String name, Profile profile, List<Track> tracks, int baseIndex) {
        Track base = tracks.get(baseIndex);
        double lat0 = base.points.get(0).latitude;
        double lon0 = base.points.get(0).longitude;
        double cos0 = Math.cos(Math.toRadians(lat0));

        List<Plane> planes = new ArrayList<>();
        for (Track t : tracks) {
            planes.add(new Plane(t.points, lat0, lon0, cos0));
        }
        double step = stepM(profile);
        Stations stations = Stations.smoothed(
                planes.get(baseIndex).trimmed(profile.radiusM + profile.offRouteM,
                        profile.minDistanceM),
                step);

        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            candidates.add(i);
        }
        List<Integer> rejected = new ArrayList<>();
        List<Integer> reasons = new ArrayList<>();
        Mean mean = null;
        Stations used = stations;
        double meanEnd = 0;
        for (int pass = 0; pass < 2; pass++) {
            List<Crossings> crossings = new ArrayList<>();
            List<Integer> kept = new ArrayList<>();
            for (int i : candidates) {
                double limit = (pass == 0 ? 2 : 1.5) * profile.offRouteM;
                Crossings c = Crossings.of(planes.get(i), stations, profile, limit);
                if (c.reject != 0) {
                    rejected.add(i);
                    reasons.add(c.reject);
                } else {
                    crossings.add(c);
                    kept.add(i);
                }
            }
            candidates = kept;
            if (kept.size() < MIN_TRACKS) {
                return new Result(null, kept, rejected, reasons);
            }
            mean = Mean.of(stations, crossings, profile);
            used = stations;
            meanEnd = 0;
            for (Crossings c : crossings) {
                meanEnd += c.endAlong / crossings.size();
            }
            stations = Stations.smoothed(mean.toPlane(), step);
        }

        // The time of the way starts at the start of the newest track. The
        // automatic selection then sorts the way with its newest trip.
        long startMs = Long.MIN_VALUE;
        for (int i : candidates) {
            startMs = Math.max(startMs, tracks.get(i).startTimeMs());
        }
        List<TrackPoint> points = new ArrayList<>();
        for (int k = 0; k < mean.x.length; k++) {
            double along = used.along(k);
            if (k > 0 && along > meanEnd) {
                // The last point is between two stations, at the mean end.
                double f = (meanEnd - used.along(k - 1)) / (along - used.along(k - 1));
                points.add(point(lat0, lon0, cos0, startMs,
                        lerp(mean.x[k - 1], mean.x[k], f), lerp(mean.y[k - 1], mean.y[k], f),
                        lerp(mean.elapsedMs[k - 1], mean.elapsedMs[k], f),
                        lerp(mean.elevationM[k - 1], mean.elevationM[k], f)));
                break;
            }
            points.add(point(lat0, lon0, cos0, startMs, mean.x[k], mean.y[k],
                    mean.elapsedMs[k], mean.elevationM[k]));
        }
        return new Result(new Track(name, profile, points), candidates, rejected, reasons);
    }

    private static TrackPoint point(double lat0, double lon0, double cos0, long startMs,
                                    double x, double y, double elapsedMs, double ele) {
        double lat = lat0 + y / METERS_PER_DEGREE;
        double lon = lon0 + x / (METERS_PER_DEGREE * cos0);
        return new TrackPoint(lat, lon, startMs + Math.round(elapsedMs), ele, Double.NaN);
    }

    // ------------------------------------------------------------------
    // A track in meters on the plane
    // ------------------------------------------------------------------

    private static final class Plane {
        final double[] x;
        final double[] y;
        final double[] t;
        final double[] ele;

        Plane(double[] x, double[] y, double[] t, double[] ele) {
            this.x = x;
            this.y = y;
            this.t = t;
            this.ele = ele;
        }

        // The track from its departure to its arrival. The exit is the first
        // point outside the zone around the first point. From the exit, the
        // departure is the last point back where the distance to the exit
        // still grows. A track with no wait thus keeps its first point. The
        // arrival is the same from the end of the track.
        Plane trimmed(double zone, double tolerance) {
            int n = x.length;
            int first = edge(zone, tolerance, 0, 1);
            int last = n - 1 - edge(zone, tolerance, n - 1, -1);
            if (last - first < 1) {
                return this;
            }
            int m = last - first + 1;
            double[] tx = new double[m];
            double[] ty = new double[m];
            double[] tt = new double[m];
            double[] te = new double[m];
            System.arraycopy(x, first, tx, 0, m);
            System.arraycopy(y, first, ty, 0, m);
            System.arraycopy(t, first, tt, 0, m);
            System.arraycopy(ele, first, te, 0, m);
            return new Plane(tx, ty, tt, te);
        }

        // The count of points before the departure, from the index from in
        // the direction dir.
        private int edge(double zone, double tolerance, int from, int dir) {
            int n = x.length;
            int exit = -1;
            for (int i = 0; i < n; i++) {
                int j = from + dir * i;
                if (Math.hypot(x[j] - x[from], y[j] - y[from]) > zone) {
                    exit = i;
                    break;
                }
            }
            if (exit <= 0) {
                return 0;
            }
            int e = from + dir * exit;
            double before = 0;
            int departure = exit;
            for (int i = exit - 1; i >= 0; i--) {
                int j = from + dir * i;
                double d = Math.hypot(x[j] - x[e], y[j] - y[e]);
                if (d < before - tolerance) {
                    break;
                }
                if (d >= before) {
                    departure = i;
                    before = d;
                }
            }
            return departure;
        }

        Plane(List<TrackPoint> points, double lat0, double lon0, double cos0) {
            int n = points.size();
            x = new double[n];
            y = new double[n];
            t = new double[n];
            ele = new double[n];
            for (int i = 0; i < n; i++) {
                TrackPoint p = points.get(i);
                x[i] = (p.longitude - lon0) * METERS_PER_DEGREE * cos0;
                y[i] = (p.latitude - lat0) * METERS_PER_DEGREE;
                t[i] = p.timeMs;
                ele[i] = p.elevationM;
            }
        }
    }

    // ------------------------------------------------------------------
    // The stations: points at a fixed distance along a line
    // ------------------------------------------------------------------

    private static final class Stations {
        final double[] x;
        final double[] y;
        final double step;

        private Stations(double[] x, double[] y, double step) {
            this.x = x;
            this.y = y;
            this.step = step;
        }

        static Stations sample(Plane line, double step) {
            List<double[]> out = new ArrayList<>();
            out.add(new double[]{line.x[0], line.y[0]});
            double next = step;
            double done = 0;
            for (int i = 1; i < line.x.length; i++) {
                double dx = line.x[i] - line.x[i - 1];
                double dy = line.y[i] - line.y[i - 1];
                double len = Math.hypot(dx, dy);
                while (len > 0 && done + len >= next) {
                    double f = (next - done) / len;
                    out.add(new double[]{line.x[i - 1] + f * dx, line.y[i - 1] + f * dy});
                    next += step;
                }
                done += len;
            }
            // The last point of the line is the finish, also if it is less
            // than one step after the last station.
            int last = line.x.length - 1;
            double[] end = out.get(out.size() - 1);
            if (Math.hypot(line.x[last] - end[0], line.y[last] - end[1]) > step * 0.1) {
                out.add(new double[]{line.x[last], line.y[last]});
            }
            double[] x = new double[out.size()];
            double[] y = new double[out.size()];
            for (int k = 0; k < x.length; k++) {
                x[k] = out.get(k)[0];
                y[k] = out.get(k)[1];
            }
            return new Stations(x, y, step);
        }

        // Samples the line, smooths the samples, and samples the smooth
        // line again. The last sample keeps the stations at one step.
        static Stations smoothed(Plane line, double step) {
            Stations raw = sample(line, step);
            int n = raw.count();
            int w = (int) Math.round(SMOOTH_M / step);
            double[] x = new double[n];
            double[] y = new double[n];
            for (int k = 0; k < n; k++) {
                // The window gets shorter near the ends. Thus the start and
                // the finish stay in their places.
                int h = Math.min(w, Math.min(k, n - 1 - k));
                double sx = 0;
                double sy = 0;
                for (int j = k - h; j <= k + h; j++) {
                    sx += raw.x[j];
                    sy += raw.y[j];
                }
                x[k] = sx / (2 * h + 1);
                y[k] = sy / (2 * h + 1);
            }
            return sample(new Plane(x, y, new double[n], new double[n]), step);
        }

        int count() {
            return x.length;
        }

        // The distance along the stations at station k. The last step can
        // be shorter.
        double along(int k) {
            if (k < x.length - 1) {
                return k * step;
            }
            return (x.length - 2) * step
                    + Math.hypot(x[k] - x[k - 1], y[k] - y[k - 1]);
        }

        double length() {
            return along(x.length - 1);
        }
    }

    // ------------------------------------------------------------------
    // The crossings of one track: its place and time at each station
    // ------------------------------------------------------------------

    private static final class Crossings {
        final double[] x;
        final double[] y;
        final double[] elapsedMs;
        final double[] ele;
        final boolean[] near;
        int reject;

        // The distance along the route of the last point of the track.
        double endAlong;

        private Crossings(int n) {
            x = new double[n];
            y = new double[n];
            elapsedMs = new double[n];
            ele = new double[n];
            near = new boolean[n];
        }

        static Crossings of(Plane track, Stations st, Profile profile, double limit) {
            int n = st.count();
            Crossings c = new Crossings(n);
            double radius = profile.radiusM;
            double back = BACK_PART * profile.offRouteM;
            double total = st.length();

            if (Math.hypot(track.x[0] - st.x[0], track.y[0] - st.y[0])
                    > ReferenceSelector.START_FACTOR * radius + profile.offRouteM) {
                c.reject = REJECT_START;
                return c;
            }

            // The distance along the route of each point. The value never
            // goes down, thus the first pass of a station is clear.
            int m = track.x.length;
            double[] s = new double[m];
            double last = project(track.x[0], track.y[0], st, 0,
                    ReferenceSelector.START_FACTOR * radius + profile.offRouteM, Double.NaN, 0);
            s[0] = last;
            int progressAt = 0;
            int behind = 0;
            for (int i = 1; i < m; i++) {
                while (behind < i - 1 && Math.hypot(track.x[i] - track.x[behind + 1],
                        track.y[i] - track.y[behind + 1]) >= DIRECTION_M) {
                    behind++;
                }
                double dirX = track.x[i] - track.x[behind];
                double dirY = track.y[i] - track.y[behind];
                double dirLen = Math.hypot(dirX, dirY);
                if (dirLen < DIRECTION_M) {
                    dirX = Double.NaN;
                } else {
                    dirX /= dirLen;
                    dirY /= dirLen;
                }
                double moved = Math.hypot(track.x[i] - track.x[i - 1], track.y[i] - track.y[i - 1]);
                double away = Math.hypot(track.x[i] - track.x[progressAt],
                        track.y[i] - track.y[progressAt]);
                double ahead = Math.min(MAX_WINDOW_FACTOR * profile.offRouteM,
                        Math.max(2 * moved, GROW_FACTOR * away));
                double from = Math.max(0, last - back);
                double to = Math.min(total, last + ahead + profile.offRouteM);
                double p = Math.max(last, project(track.x[i], track.y[i], st, from, to, dirX, dirY));
                if (p > last) {
                    progressAt = i;
                }
                last = p;
                s[i] = last;
            }
            c.endAlong = s[m - 1];
            if (s[m - 1] < total - 2 * radius - limit) {
                c.reject = REJECT_FINISH;
                return c;
            }

            // The first pass of each station, from the points before and
            // after it.
            double[] time = new double[n];
            boolean[] has = new boolean[n];
            int i = 0;
            int lastPassed = -1;
            for (int k = 0; k < n; k++) {
                double sk = st.along(k);
                while (i < m && s[i] < sk) {
                    i++;
                }
                if (i >= m) {
                    break;
                }
                if (i == 0) {
                    c.x[k] = track.x[0];
                    c.y[k] = track.y[0];
                    time[k] = track.t[0];
                    c.ele[k] = track.ele[0];
                } else {
                    double f = s[i] == s[i - 1] ? 1 : (sk - s[i - 1]) / (s[i] - s[i - 1]);
                    c.x[k] = lerp(track.x[i - 1], track.x[i], f);
                    c.y[k] = lerp(track.y[i - 1], track.y[i], f);
                    time[k] = lerp(track.t[i - 1], track.t[i], f);
                    c.ele[k] = lerp(track.ele[i - 1], track.ele[i], f);
                }
                has[k] = true;
                lastPassed = k;
            }

            // The stations after the last point of the track. The track
            // ends near the finish. Its speed at the end gives the time.
            for (int k = lastPassed + 1; k < n; k++) {
                c.x[k] = st.x[k];
                c.y[k] = st.y[k];
                c.ele[k] = Double.NaN;
            }

            int off = 0;
            for (int k = 0; k <= lastPassed; k++) {
                c.near[k] = Math.hypot(c.x[k] - st.x[k], c.y[k] - st.y[k]) <= limit;
                if (!c.near[k]) {
                    off++;
                }
            }
            if (off > MAX_OFF_ROUTE_PART * n) {
                c.reject = REJECT_OFF_ROUTE;
                return c;
            }

            // The time near the start comes from the speed after the start
            // zone and after the departure. A wait at the start then adds
            // no time.
            double zone = Math.min(radius + profile.offRouteM, total / 4);
            int kz = Math.min(lastPassed, stationAt(st, zone));
            double far = Math.min(st.along(kz) + SPEED_SPAN_M, st.along(lastPassed));
            double departure = departure(track, s, far);
            int kr = kz;
            while (kr < lastPassed && time[kr] < departure) {
                kr++;
            }
            int ks = stationAt(st, Math.min(st.along(kr) + SPEED_SPAN_M, st.along(lastPassed)));
            double startSpeed = speed(st, time, kr, ks);
            double t0 = Math.max(time[kr] - st.along(kr) / startSpeed, departure);
            for (int k = 0; k < kr; k++) {
                time[k] = Math.min(time[kr], t0 + st.along(k) / startSpeed);
            }

            int ke = lastPassed;
            int kb = stationAt(st, Math.max(st.along(kr), st.along(ke) - SPEED_SPAN_M));
            double endSpeed = speed(st, time, kb, ke);
            for (int k = ke + 1; k < n; k++) {
                time[k] = time[ke] + (st.along(k) - st.along(ke)) / endSpeed;
            }

            for (int k = 0; k < n; k++) {
                c.elapsedMs[k] = time[k] - t0;
            }
            return c;
        }

        // The end of the last wait before the track reaches the distance
        // far along the route, or the time of the first point.
        private static double departure(Plane track, double[] s, double far) {
            double end = track.t[0];
            for (int i = 0; i + 1 < s.length && s[i] < far; i++) {
                double dt = track.t[i + 1] - track.t[i];
                double d = Math.hypot(track.x[i + 1] - track.x[i], track.y[i + 1] - track.y[i]);
                if (dt >= WAIT_MS && d / dt * 1000 < STOP_MPS) {
                    end = track.t[i + 1];
                }
            }
            return end;
        }

        // The speed in meters per millisecond between two stations. A
        // short span gives the mean speed of the whole track.
        private static double speed(Stations st, double[] time, int a, int b) {
            if (b > a && time[b] > time[a]) {
                return (st.along(b) - st.along(a)) / (time[b] - time[a]);
            }
            return 1.0 / 1000;
        }

        private static int stationAt(Stations st, double along) {
            int k = (int) Math.round(along / st.step);
            return Math.max(0, Math.min(st.count() - 1, k));
        }
    }

    // The distance along the stations of the nearest point to (px, py),
    // between from and to. With a direction, a part of the line against it
    // is not a candidate. A direction of NaN is not known.
    private static double project(double px, double py, Stations st, double from, double to,
                                  double dirX, double dirY) {
        int a = Math.max(0, (int) Math.floor(from / st.step));
        int b = Math.min(st.count() - 1, (int) Math.ceil(to / st.step));
        double best = Double.POSITIVE_INFINITY;
        double bestAlong = from;
        for (int k = a; k < b; k++) {
            double dx = st.x[k + 1] - st.x[k];
            double dy = st.y[k + 1] - st.y[k];
            double len2 = dx * dx + dy * dy;
            if (!Double.isNaN(dirX) && len2 > 0
                    && (dx * dirX + dy * dirY) / Math.sqrt(len2) < AGAINST) {
                continue;
            }
            double f = len2 == 0 ? 0 : ((px - st.x[k]) * dx + (py - st.y[k]) * dy) / len2;
            f = Math.max(0, Math.min(1, f));
            double qx = st.x[k] + f * dx;
            double qy = st.y[k] + f * dy;
            double d = Math.hypot(px - qx, py - qy);
            if (d < best) {
                best = d;
                bestAlong = st.along(k) + f * (st.along(k + 1) - st.along(k));
            }
        }
        return Math.max(from, Math.min(to, bestAlong));
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    // ------------------------------------------------------------------
    // The mean of the crossings at each station
    // ------------------------------------------------------------------

    private static final class Mean {
        final double[] x;
        final double[] y;
        final double[] elapsedMs;
        final double[] elevationM;

        private Mean(int n) {
            x = new double[n];
            y = new double[n];
            elapsedMs = new double[n];
            elevationM = new double[n];
        }

        static Mean of(Stations st, List<Crossings> all, Profile profile) {
            int n = st.count();
            Mean mean = new Mean(n);
            for (int k = 0; k < n; k++) {
                double sx = 0;
                double sy = 0;
                double se = 0;
                double sumTime = 0;
                int count = 0;
                int eleCount = 0;
                for (Crossings c : all) {
                    sumTime += c.elapsedMs[k];
                    if (c.near[k]) {
                        sx += c.x[k];
                        sy += c.y[k];
                        count++;
                    }
                    if (!Double.isNaN(c.ele[k])) {
                        se += c.ele[k];
                        eleCount++;
                    }
                }
                mean.x[k] = count == 0 ? st.x[k] : sx / count;
                mean.y[k] = count == 0 ? st.y[k] : sy / count;
                mean.elevationM[k] = eleCount == 0 ? Double.NaN : se / eleCount;
                // Each time must be later than the time before it. A way
                // is then a valid track.
                double e = sumTime / all.size();
                mean.elapsedMs[k] = k == 0 ? Math.max(0, e) : Math.max(e, mean.elapsedMs[k - 1] + 1);
            }
            return mean;
        }

        Plane toPlane() {
            return new Plane(x, y, elapsedMs, elevationM);
        }
    }
}
