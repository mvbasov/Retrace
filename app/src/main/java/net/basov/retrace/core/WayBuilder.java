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
// 2. Find the direction of each track. A track in the opposite direction
//    gets its points in the opposite sequence. See Track.reversed().
// 3. Find the join of each track: the first JOIN_POINTS points in sequence
//    near the route, with a direction along the route. A track that crosses
//    the route does not join it. The points before the join are not used.
//    Thus a GPS error at the start of a track, or a start in the middle of
//    the route, does not stop the track. The join is on the first stretch
//    of the route near the point. At the start of a route out and back, the
//    finish is at the same place, and the start comes first.
// 4. Project each point after the join on the stations. The projection only
//    goes forward, in a short window after the last projection. Thus a
//    route out and back has no jump between its legs. The window grows
//    with the distance from the last point that gave progress along the
//    route. At the turn of a route out and back, the other leg then comes
//    into the window. GPS noise during a wait gives no such distance. The
//    two legs can be at the same place. Thus a part of the line against the
//    direction of movement is not a candidate, the same as in TrackMatcher.
// 5. At each station, find the place and the time where the track first
//    passes the station. A track covers the stations from its join to its
//    last point.
// 6. The way point at a station is the mean of the places of the tracks
//    that cover it. The time from one station to the next is the mean time
//    of the tracks that cover the two stations. Thus a track that covers a
//    part of the route gives its time for that part only.
// 7. The way keeps the longest part of the route that MIN_TRACKS tracks or
//    more cover. A part that only one track covers can be a GPS error of
//    that track. The way ends at the mean end of the tracks that reach the
//    finish. Near the finish, only the tracks that go far enough give the
//    place. Without the cut, the end of the way is the end of the base
//    track, with its GPS error.
// 8. Do steps 3 to 7 again with the way as the base. This removes the
//    errors of the base track from the result.
//
// The elapsed time of a track at the start of the route starts at its
// departure, not at its first point. A track can wait at the start before
// the trip. The time near the start thus comes from the speed after the
// start zone: the start circle plus the off route limit. The GPS drift can
// move the points of a wait along the route, also past the start zone. Thus
// a wait is also found by time: an interval between two points with a speed
// below STOP_MPS. The elapsed time does not start before the end of such a
// wait.
//
// A GPS receiver can jump at a cold start. Thus a step faster than
// JUMP_FACTOR times the top speed of the profile, in the first or the last
// JUMP_POINTS points, is a jump. The points before the last jump at the
// start, and after the first jump at the end, are not used. See withoutJumps().
//
// The base track of the first pass has its own GPS error. Thus the first
// pass uses two times the off route limit. A saved track has no accuracy
// for its points. Thus the second pass uses 1.5 times the limit, for the
// slow GPS drift of one track.
//
// A track in the opposite direction gives a correct time only on a flat
// route. The result tells which tracks are in the opposite direction.
//
// A track that does not follow the route is not used. See the REJECT
// values.
public final class WayBuilder {

    // A way needs at least this many tracks. The way also keeps only the
    // stations that this many tracks cover.
    public static final int MIN_TRACKS = 2;

    // The reasons for a track that the way does not use.
    // The track is never near the route.
    public static final int REJECT_NO_JOIN = 1;
    // The track covers less than MIN_COVER of the route.
    public static final int REJECT_SHORT = 2;
    // Too many stations of the track are off the route.
    public static final int REJECT_OFF_ROUTE = 3;

    // A track must cover this part of the route.
    public static final double MIN_COVER = 0.5;

    // A step faster than JUMP_FACTOR times the top speed of the profile, in
    // the first or the last JUMP_POINTS points, is a GPS jump.
    static final double JUMP_FACTOR = 2;
    static final int JUMP_POINTS = 10;

    // Points in sequence near the route that make the join of a track.
    static final int JOIN_POINTS = 3;

    // The opposite direction must cover this many times more stations than
    // the direction of the track. Otherwise the track keeps its direction.
    static final double REVERSE_FACTOR = 1.25;

    // A track with more of its covered stations off the route is not used.
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

    // A join needs a cosine of at least ALONG between the direction of
    // movement and the route.
    static final double ALONG = 0.5;

    private static final double METERS_PER_DEGREE = Geo.EARTH_RADIUS_M * Math.PI / 180.0;

    // The result of a build.
    public static final class Result {
        // The way, or null if fewer than MIN_TRACKS tracks follow the route.
        public final Track way;

        // The indexes of the used tracks, in the input list.
        public final List<Integer> used;

        // The indexes of the used tracks in the opposite direction of the
        // base track.
        public final List<Integer> reversed;

        // The indexes of the other tracks, and one REJECT value for each.
        public final List<Integer> rejected;
        public final List<Integer> reasons;

        Result(Track way, List<Integer> used, List<Integer> reversed, List<Integer> rejected,
               List<Integer> reasons) {
            this.way = way;
            this.used = used;
            this.reversed = reversed;
            this.rejected = rejected;
            this.reasons = reasons;
        }
    }

    // How one track agrees with the route of a base track. The way dialog
    // shows the tracks with no reject.
    public static final class Match {
        // 0, or a REJECT value.
        public final int reject;

        // The part of the route of the base that the track covers.
        public final double cover;

        // True if the track goes in the opposite direction.
        public final boolean reversed;

        Match(int reject, double cover, boolean reversed) {
            this.reject = reject;
            this.cover = cover;
            this.reversed = reversed;
        }
    }

    // The stations distance for the profile, in meters.
    static double stepM(Profile profile) {
        return Math.max(5, 2.5 * profile.minDistanceM);
    }

    private WayBuilder() {
    }

    // Compares one track with the route of the base track, with the limit of
    // the first pass. A quick check of the bounding boxes comes first.
    public static Match match(Track base, Track other, Profile profile) {
        if (base.points.size() < 2 || other.points.size() < 2) {
            return new Match(REJECT_NO_JOIN, 0, false);
        }
        double lat0 = base.points.get(0).latitude;
        double lon0 = base.points.get(0).longitude;
        double cos0 = Math.cos(Math.toRadians(lat0));
        double jump = JUMP_FACTOR * profile.maxSpeedMps;
        Plane b = new Plane(base.points, lat0, lon0, cos0).withoutJumps(jump);
        Plane o = new Plane(other.points, lat0, lon0, cos0).withoutJumps(jump);
        double limit = 2 * profile.offRouteM;
        if (!b.boxNear(o, limit)) {
            return new Match(REJECT_NO_JOIN, 0, false);
        }
        Stations st = Stations.smoothed(b.trimmed(profile.radiusM + profile.offRouteM,
                profile.minDistanceM), stepM(profile));
        Crossings forward = Crossings.of(o, st, profile, limit);
        Crossings backward = Crossings.of(o.reversed(), st, profile, limit);
        boolean reversed = prefersReverse(forward, backward);
        Crossings c = reversed ? backward : forward;
        return new Match(c.reject, c.coveredLength / st.length(), reversed);
    }

    // Builds a way from the tracks. The base track gives the route and the
    // direction of the first pass. The tracks must have increasing times.
    public static Result build(String name, Profile profile, List<Track> tracks, int baseIndex) {
        Track base = tracks.get(baseIndex);
        double lat0 = base.points.get(0).latitude;
        double lon0 = base.points.get(0).longitude;
        double cos0 = Math.cos(Math.toRadians(lat0));
        double step = stepM(profile);

        double jump = JUMP_FACTOR * profile.maxSpeedMps;
        List<Plane> planes = new ArrayList<>();
        for (Track t : tracks) {
            planes.add(new Plane(t.points, lat0, lon0, cos0).withoutJumps(jump));
        }
        Stations stations = Stations.smoothed(
                planes.get(baseIndex).trimmed(profile.radiusM + profile.offRouteM,
                        profile.minDistanceM),
                step);

        // The direction of each track, from the coverage of the first pass.
        List<Integer> reversed = new ArrayList<>();
        double firstLimit = 2 * profile.offRouteM;
        for (int i = 0; i < planes.size(); i++) {
            if (i == baseIndex) {
                continue;
            }
            Plane backward = planes.get(i).reversed();
            if (prefersReverse(Crossings.of(planes.get(i), stations, profile, firstLimit),
                    Crossings.of(backward, stations, profile, firstLimit))) {
                planes.set(i, backward);
                reversed.add(i);
            }
        }

        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            candidates.add(i);
        }
        List<Integer> rejected = new ArrayList<>();
        List<Integer> reasons = new ArrayList<>();
        Mean mean = null;
        for (int pass = 0; pass < 2; pass++) {
            List<Crossings> crossings = new ArrayList<>();
            List<Integer> kept = new ArrayList<>();
            double limit = (pass == 0 ? 2 : 1.5) * profile.offRouteM;
            for (int i : candidates) {
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
                return new Result(null, kept, usedOnly(reversed, kept), rejected, reasons);
            }
            mean = Mean.of(stations, crossings);
            mean.cutAtEnd(stations, crossings);
            if (mean.last - mean.first < 1) {
                return new Result(null, new ArrayList<Integer>(), new ArrayList<Integer>(),
                        rejected, reasons);
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
        double startElapsed = mean.elapsedMs[mean.first];
        for (int k = mean.first; k <= mean.last; k++) {
            double lat = lat0 + mean.y[k] / METERS_PER_DEGREE;
            double lon = lon0 + mean.x[k] / (METERS_PER_DEGREE * cos0);
            points.add(new TrackPoint(lat, lon, startMs + Math.round(mean.elapsedMs[k] - startElapsed),
                    mean.elevationM[k], Double.NaN));
        }
        return new Result(new Track(name, profile, points), candidates,
                usedOnly(reversed, candidates), rejected, reasons);
    }

    // The opposite direction wins only with clearly more coverage. On a
    // route out and back, the two directions cover the same stations.
    private static boolean prefersReverse(Crossings forward, Crossings backward) {
        if (backward.reject != 0) {
            return false;
        }
        if (forward.reject != 0) {
            return true;
        }
        return backward.coveredLength > REVERSE_FACTOR * forward.coveredLength;
    }

    private static List<Integer> usedOnly(List<Integer> reversed, List<Integer> used) {
        List<Integer> out = new ArrayList<>();
        for (int i : reversed) {
            if (used.contains(i)) {
                out.add(i);
            }
        }
        return out;
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

        // The track with no GPS jump at its start and at its end. A step
        // faster than the jump speed in m/s, in the first or the last
        // JUMP_POINTS points, is a jump. A track with fewer than two points
        // after the cut stays as it was.
        Plane withoutJumps(double jumpMps) {
            int n = x.length;
            int first = 0;
            for (int i = 1; i < Math.min(n, JUMP_POINTS); i++) {
                if (stepSpeed(i) > jumpMps) {
                    first = i;
                }
            }
            int last = n - 1;
            for (int i = n - 1; i > Math.max(0, n - JUMP_POINTS); i--) {
                if (stepSpeed(i) > jumpMps) {
                    last = i - 1;
                }
            }
            if (first == 0 && last == n - 1 || last - first < 1) {
                return this;
            }
            int m = last - first + 1;
            double[] cx = new double[m];
            double[] cy = new double[m];
            double[] ct = new double[m];
            double[] ce = new double[m];
            System.arraycopy(x, first, cx, 0, m);
            System.arraycopy(y, first, cy, 0, m);
            System.arraycopy(t, first, ct, 0, m);
            System.arraycopy(ele, first, ce, 0, m);
            return new Plane(cx, cy, ct, ce);
        }

        // The speed of the step from point i - 1 to point i, in m/s.
        private double stepSpeed(int i) {
            double dt = (t[i] - t[i - 1]) / 1000.0;
            double d = Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
            return dt > 0 ? d / dt : 0;
        }

        // The same as Track.reversed(), on the plane.
        Plane reversed() {
            int n = x.length;
            double[] rx = new double[n];
            double[] ry = new double[n];
            double[] rt = new double[n];
            double[] re = new double[n];
            for (int i = 0; i < n; i++) {
                int j = n - 1 - i;
                rx[i] = x[j];
                ry[i] = y[j];
                rt[i] = t[0] + (t[n - 1] - t[j]);
                re[i] = ele[j];
            }
            return new Plane(rx, ry, rt, re);
        }

        // True if the bounding boxes, with the margin, overlap.
        boolean boxNear(Plane other, double margin) {
            return minOf(x) - margin <= maxOf(other.x) && minOf(other.x) - margin <= maxOf(x)
                    && minOf(y) - margin <= maxOf(other.y) && minOf(other.y) - margin <= maxOf(y);
        }

        private static double minOf(double[] v) {
            double m = Double.POSITIVE_INFINITY;
            for (double d : v) {
                m = Math.min(m, d);
            }
            return m;
        }

        private static double maxOf(double[] v) {
            double m = Double.NEGATIVE_INFINITY;
            for (double d : v) {
                m = Math.max(m, d);
            }
            return m;
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

        int at(double along) {
            int k = (int) Math.round(along / step);
            return Math.max(0, Math.min(count() - 1, k));
        }

        // The first station at or after the distance along.
        int from(double along) {
            int k = (int) Math.ceil(along / step - 1e-9);
            return Math.max(0, Math.min(count() - 1, k));
        }
    }

    // ------------------------------------------------------------------
    // The crossings of one track: its place and time at each station
    // ------------------------------------------------------------------

    private static final class Crossings {
        final double[] x;
        final double[] y;
        final double[] time;
        final double[] ele;
        final boolean[] covered;
        final boolean[] near;
        int reject;
        double coveredLength;

        // True if the track ends near the finish. Then endAlong is the
        // distance along the route of its last point.
        boolean atFinish;
        double endAlong;

        private Crossings(int n) {
            x = new double[n];
            y = new double[n];
            time = new double[n];
            ele = new double[n];
            covered = new boolean[n];
            near = new boolean[n];
        }

        static Crossings of(Plane track, Stations st, Profile profile, double limit) {
            int n = st.count();
            Crossings c = new Crossings(n);
            double radius = profile.radiusM;
            double back = BACK_PART * profile.offRouteM;
            double total = st.length();
            int m = track.x.length;

            // The join: JOIN_POINTS points in sequence near the route.
            int join = -1;
            double joinAlong = 0;
            int inRow = 0;
            Hit hit = new Hit();
            for (int i = 0; i < m && join < 0; i++) {
                double[] dir = direction(track, i, 1);
                firstNear(track.x[i], track.y[i], st, limit, dir[0], dir[1], hit);
                inRow = hit.distance <= limit ? inRow + 1 : 0;
                if (inRow == JOIN_POINTS) {
                    join = i - JOIN_POINTS + 1;
                }
            }
            if (join < 0) {
                c.reject = REJECT_NO_JOIN;
                return c;
            }
            double[] dir0 = direction(track, join, 1);
            firstNear(track.x[join], track.y[join], st, limit, dir0[0], dir0[1], hit);
            joinAlong = hit.along;

            // A loop ends where it starts, often in the same direction. The
            // first points of a track are then near the start and near the
            // finish of the route. A GPS error at the start can turn the
            // direction of the route there by some degrees. Then the rule of
            // direction selects the finish, and the track covers almost
            // nothing. A track that begins at the start of a loop runs it
            // from the start. Thus a join in the finish zone, near the first
            // point of the track and near the start of the route, is at the
            // start. A join in the middle of the route stays. It can follow
            // a GPS error that the way must not get.
            double zone = Math.min(radius + profile.offRouteM, total / 4);
            if (joinAlong >= total - zone && Math.hypot(track.x[join] - track.x[0],
                    track.y[join] - track.y[0]) <= zone) {
                nearest(track.x[join], track.y[join], st, 0, zone, Double.NaN, Double.NaN, hit);
                if (hit.distance <= limit) {
                    joinAlong = hit.along;
                }
            }

            // The distance along the route of each point after the join.
            // The value never goes down, thus the first pass of a station is
            // clear.
            //
            // More than the start radius before station 0, a point gets a
            // negative distance: the distance before station 0 in the
            // direction of the route. A track can come to the start from a
            // GPS error, or jump over station 0. The first pass of station 0
            // is then between the two points on each side of it.
            double[] s = new double[m];
            double last = joinAlong;
            s[join] = signed(last, track.x[join], track.y[join], st, radius,
                    Double.NEGATIVE_INFINITY);
            int progressAt = join;
            for (int i = join + 1; i < m; i++) {
                double[] dir = direction(track, i, -1);
                double moved = Math.hypot(track.x[i] - track.x[i - 1], track.y[i] - track.y[i - 1]);
                double away = Math.hypot(track.x[i] - track.x[progressAt],
                        track.y[i] - track.y[progressAt]);
                double ahead = Math.min(MAX_WINDOW_FACTOR * profile.offRouteM,
                        Math.max(2 * moved, GROW_FACTOR * away));
                double from = Math.max(0, last - back);
                double to = Math.min(total, last + ahead + profile.offRouteM);
                nearest(track.x[i], track.y[i], st, from, to, dir[0], dir[1], hit);
                double p = Math.max(last, hit.along);
                if (p > last) {
                    progressAt = i;
                }
                last = p;
                s[i] = signed(last, track.x[i], track.y[i], st, radius, s[i - 1]);
            }

            // A track that joins in the start zone starts at the start. A
            // track that ends near the finish gives its end to the cut of the
            // way. See Mean.cutAtEnd().
            boolean atStart = joinAlong <= zone;
            boolean atFinish = s[m - 1] >= total - 2 * radius - limit;
            c.atFinish = atFinish;
            c.endAlong = s[m - 1];

            // The first pass of each station, from the points before and
            // after it.
            int kFirst = atStart ? 0 : st.from(joinAlong);
            int i = join;
            int lastPassed = -1;
            for (int k = kFirst; k < n; k++) {
                double sk = st.along(k);
                while (i < m && s[i] < sk) {
                    i++;
                }
                if (i >= m) {
                    break;
                }
                if (i == join) {
                    c.x[k] = track.x[join];
                    c.y[k] = track.y[join];
                    c.time[k] = track.t[join];
                    c.ele[k] = track.ele[join];
                } else {
                    double f = s[i] == s[i - 1] ? 1 : (sk - s[i - 1]) / (s[i] - s[i - 1]);
                    c.x[k] = lerp(track.x[i - 1], track.x[i], f);
                    c.y[k] = lerp(track.y[i - 1], track.y[i], f);
                    c.time[k] = lerp(track.t[i - 1], track.t[i], f);
                    c.ele[k] = lerp(track.ele[i - 1], track.ele[i], f);
                }
                c.covered[k] = true;
                lastPassed = k;
            }
            if (lastPassed < 0) {
                c.reject = REJECT_SHORT;
                return c;
            }

            // The stations more than the start radius before the join have no
            // place of the track. They get a time from startTimes(), but no
            // place for the mean.
            int coveredCount = 0;
            int off = 0;
            for (int k = Math.max(kFirst, st.from(joinAlong - radius)); k <= lastPassed; k++) {
                c.near[k] = Math.hypot(c.x[k] - st.x[k], c.y[k] - st.y[k]) <= limit;
                coveredCount++;
                if (!c.near[k]) {
                    off++;
                }
            }

            if (atStart) {
                startTimes(c, track, s, st, profile, lastPassed, zone);
            }
            c.coveredLength = st.along(lastPassed) - st.along(kFirst);
            if (c.coveredLength < MIN_COVER * total) {
                c.reject = REJECT_SHORT;
                return c;
            }
            if (off > MAX_OFF_ROUTE_PART * coveredCount) {
                c.reject = REJECT_OFF_ROUTE;
            }
            return c;
        }

        // The time near the start comes from the speed after the start zone
        // and after the departure. A wait at the start then adds no time.
        private static void startTimes(Crossings c, Plane track, double[] s, Stations st,
                                       Profile profile, int lastPassed, double zone) {
            int kz = Math.min(lastPassed, st.at(zone));
            double far = Math.min(st.along(kz) + SPEED_SPAN_M, st.along(lastPassed));
            double departure = departure(track, s, far);
            int kr = kz;
            while (kr < lastPassed && c.time[kr] < departure) {
                kr++;
            }
            int ks = st.at(Math.min(st.along(kr) + SPEED_SPAN_M, st.along(lastPassed)));
            double startSpeed = speed(st, c.time, kr, ks);
            double t0 = Math.max(c.time[kr] - st.along(kr) / startSpeed, departure);
            for (int k = 0; k < kr; k++) {
                c.time[k] = Math.min(c.time[kr], t0 + st.along(k) / startSpeed);
            }
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
        // short span gives one meter per second.
        private static double speed(Stations st, double[] time, int a, int b) {
            if (b > a && time[b] > time[a]) {
                return (st.along(b) - st.along(a)) / (time[b] - time[a]);
            }
            return 1.0 / 1000;
        }
    }

    // The unit direction of movement at a point, from the point back or
    // ahead to the first point at DIRECTION_M or more. NaN if the track has
    // no such point.
    private static double[] direction(Plane track, int i, int dir) {
        int m = track.x.length;
        for (int j = i + dir; j >= 0 && j < m; j += dir) {
            double dx = track.x[i] - track.x[j];
            double dy = track.y[i] - track.y[j];
            double len = Math.hypot(dx, dy);
            if (len >= DIRECTION_M) {
                // Back: from j to i. Ahead: from i to j.
                return dir < 0 ? new double[]{dx / len, dy / len}
                        : new double[]{-dx / len, -dy / len};
            }
        }
        return new double[]{Double.NaN, Double.NaN};
    }

    // Finds the place of a join. The stretches of the route nearer than the
    // limit, with a direction along the route, are the candidates. The
    // nearest place of the first stretch is the result. The distance is
    // infinite if no stretch is near.
    private static void firstNear(double px, double py, Stations st, double limit,
                                  double dirX, double dirY, Hit hit) {
        hit.distance = Double.POSITIVE_INFINITY;
        hit.along = 0;
        boolean inStretch = false;
        for (int k = 0; k + 1 < st.count(); k++) {
            double dx = st.x[k + 1] - st.x[k];
            double dy = st.y[k + 1] - st.y[k];
            double len2 = dx * dx + dy * dy;
            boolean along = Double.isNaN(dirX) || len2 == 0
                    || (dx * dirX + dy * dirY) / Math.sqrt(len2) >= ALONG;
            double f = len2 == 0 ? 0 : ((px - st.x[k]) * dx + (py - st.y[k]) * dy) / len2;
            f = Math.max(0, Math.min(1, f));
            double d = Math.hypot(px - st.x[k] - f * dx, py - st.y[k] - f * dy);
            if (along && d <= limit) {
                inStretch = true;
                if (d < hit.distance) {
                    hit.distance = d;
                    hit.along = st.along(k) + f * (st.along(k + 1) - st.along(k));
                }
            } else if (inStretch) {
                return;
            }
        }
    }

    // The distance along the route of a point. At 0, a point more than the
    // margin before station 0 gets the negative distance of beforeStart().
    // A point nearer to station 0 stays at 0. Thus GPS noise at the start
    // keeps the first point of a track as its place at station 0. The value
    // does not go below the value of the point before it.
    private static double signed(double along, double px, double py, Stations st,
                                 double margin, double previous) {
        if (along > 0) {
            return along;
        }
        double before = beforeStart(px, py, st);
        return before > margin ? Math.max(previous, -before) : 0;
    }

    // The distance of a point before station 0, in the direction of the
    // route at the start. A negative value is after station 0.
    private static double beforeStart(double px, double py, Stations st) {
        int k = Math.min(st.count() - 1, Math.max(1, st.at(DIRECTION_M)));
        double dx = st.x[k] - st.x[0];
        double dy = st.y[k] - st.y[0];
        double len = Math.hypot(dx, dy);
        if (len == 0) {
            return 0;
        }
        return -((px - st.x[0]) * dx + (py - st.y[0]) * dy) / len;
    }

    // The nearest place on the stations between from and to.
    private static final class Hit {
        double along;
        double distance;
    }

    // Finds the nearest point to (px, py) on the stations between from and
    // to. With a direction, a part of the line against it is not a
    // candidate. A direction of NaN is not known.
    private static void nearest(double px, double py, Stations st, double from, double to,
                                double dirX, double dirY, Hit hit) {
        int a = Math.max(0, (int) Math.floor(from / st.step));
        int b = Math.min(st.count() - 1, (int) Math.ceil(to / st.step));
        hit.distance = Double.POSITIVE_INFINITY;
        hit.along = from;
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
            if (d < hit.distance) {
                hit.distance = d;
                hit.along = st.along(k) + f * (st.along(k + 1) - st.along(k));
            }
        }
        hit.along = Math.max(from, Math.min(to, hit.along));
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

        // The longest part of the stations that MIN_TRACKS tracks or more
        // cover.
        int first;
        int last;

        private Mean(int n) {
            x = new double[n];
            y = new double[n];
            elapsedMs = new double[n];
            elevationM = new double[n];
        }

        static Mean of(Stations st, List<Crossings> all) {
            int n = st.count();
            Mean mean = new Mean(n);
            int[] cover = new int[n];
            for (int k = 0; k < n; k++) {
                double sx = 0;
                double sy = 0;
                double se = 0;
                int count = 0;
                int eleCount = 0;
                for (Crossings c : all) {
                    if (!c.covered[k]) {
                        continue;
                    }
                    cover[k]++;
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
            }

            // The time of each step is the mean of the tracks that cover the
            // two stations. A step that no track covers gets the mean speed.
            double sumTime = 0;
            double sumLength = 0;
            double[] step = new double[n];
            boolean[] known = new boolean[n];
            for (int k = 1; k < n; k++) {
                double sum = 0;
                int count = 0;
                for (Crossings c : all) {
                    if (c.covered[k - 1] && c.covered[k]) {
                        sum += c.time[k] - c.time[k - 1];
                        count++;
                    }
                }
                if (count > 0) {
                    step[k] = sum / count;
                    known[k] = true;
                    sumTime += step[k];
                    sumLength += st.along(k) - st.along(k - 1);
                }
            }
            double msPerMeter = sumLength > 0 ? sumTime / sumLength : 1000;
            mean.elapsedMs[0] = 0;
            for (int k = 1; k < n; k++) {
                double d = known[k] ? step[k] : (st.along(k) - st.along(k - 1)) * msPerMeter;
                // Each time must be later than the time before it. A way
                // is then a valid track.
                mean.elapsedMs[k] = mean.elapsedMs[k - 1] + Math.max(1, d);
            }

            int bestFirst = 0;
            int bestLast = -1;
            int runFirst = -1;
            for (int k = 0; k <= n; k++) {
                boolean in = k < n && cover[k] >= MIN_TRACKS;
                if (in && runFirst < 0) {
                    runFirst = k;
                } else if (!in && runFirst >= 0) {
                    if (k - 1 - runFirst > bestLast - bestFirst) {
                        bestFirst = runFirst;
                        bestLast = k - 1;
                    }
                    runFirst = -1;
                }
            }
            mean.first = bestFirst;
            mean.last = bestLast;
            return mean;
        }

        // Ends the way at the mean end of the tracks that reach the finish.
        // The cut does not go into the part with too few tracks.
        void cutAtEnd(Stations st, List<Crossings> all) {
            double sum = 0;
            int count = 0;
            for (Crossings c : all) {
                if (c.atFinish) {
                    sum += c.endAlong;
                    count++;
                }
            }
            if (count == 0) {
                return;
            }
            double end = sum / count;
            while (last > first + 1 && st.along(last - 1) >= end) {
                last--;
            }
        }

        // The covered part as a line, for the stations of the next pass.
        Plane toPlane() {
            int m = Math.max(0, last - first + 1);
            double[] px = new double[m];
            double[] py = new double[m];
            double[] pt = new double[m];
            double[] pe = new double[m];
            System.arraycopy(x, first, px, 0, m);
            System.arraycopy(y, first, py, 0, m);
            System.arraycopy(elapsedMs, first, pt, 0, m);
            System.arraycopy(elevationM, first, pe, 0, m);
            return new Plane(px, py, pt, pe);
        }
    }
}
