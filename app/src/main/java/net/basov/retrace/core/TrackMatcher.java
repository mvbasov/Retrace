package net.basov.retrace.core;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

// ----------------------------------------------------------------------
// The comparison of the current track with the reference track
// ----------------------------------------------------------------------
//
// The service gives each point of the current track to update(). The
// result tells where the trip is on the route, and how it compares with the
// reference track. See section 5.4 of doc/PLAN.md. doc/MATCHING.md gives
// the math of each step, with figures.
//
// The start of the trip:
// - At the start of the route, the trip starts when it leaves the start
//   circle. Thus a wait at the start does not count.
// - Away from the start, the trip joins the route after JOIN_FIXES points
//   near the route.
// In both cases, the elapsed time starts with the time of the reference
// track at that place. Thus the time difference starts at zero, and the size
// of the start circle has no effect on it.
//
// The search for the place on the route uses a window ahead of the last
// place. Off the route, the search first looks at the rest of the route,
// then at the whole route.
//
// On a route out and back, two legs of the route can be equally near a fix.
// Thus the search does not take the nearest part of the route. Each leg
// gives one local minimum of the distance from the fix. The end point of a
// segment is never a local minimum. Thus the place does not stop at a
// vertex. From the minimums inside the off-route limit, the search takes the
// one with the lowest cost: the distance from the fix, plus FORWARD_COST for
// each meter ahead of the last place, plus BACK_COST for each meter back. A
// trip moves forward along the route. Thus a step back costs more. A loop
// that crosses itself has the same problem as a route out and back.
//
// The position alone cannot separate the way out and the way back of a route
// out and back. GPS drift can put the turn of the current track some meters
// before the turn of the reference track. Thus the search also uses the
// direction of movement. A part of the route that goes against the movement
// is not a candidate.
//
// The limits grow with the accuracy of the fix. Two noisy tracks of the same
// route lie some meters apart. A fixed limit then gives a false off-route
// state, or a trip that does not finish, in a city with poor GPS. The
// reference track and the current track both have noise. Thus the off-route
// limit adds twice the accuracy. The radius of the start circle and of the
// finish circle does the same.
//
// GPS noise gives a recorded track a small zigzag, and the zigzag makes the
// length along the track too large. Thus the matcher smooths the reference
// track first. Each point becomes the mean of the points within SMOOTH_M
// along the track. At a corner, the smooth route is some meters inside the
// corner. That is much less than the off-route limit.
//
// The projection uses a flat plane at each segment. A segment of a track is
// short, thus the error is small. A route over the 180 degree meridian is
// not supported.
public final class TrackMatcher {

    public enum State {
        // The trip is not near the route yet.
        WAITING,
        // The trip is inside the start circle and has not left it.
        AT_START,
        ON_ROUTE,
        OFF_ROUTE,
        FINISHED
    }

    // Fixes in sequence above the off-route limit before the state changes.
    static final int OFF_ROUTE_FIXES = 3;

    // Fixes in sequence on the route before an off-route trip is on route
    // again.
    static final int BACK_ON_ROUTE_FIXES = 2;

    // Fixes in sequence near the route before a trip joins the route away
    // from the start.
    static final int JOIN_FIXES = 3;

    // GPS noise can move the place back a little. The search accepts that.
    static final double BACK_TOLERANCE_M = 50;

    // The cost of each meter along the route between the last place and a
    // local minimum, forward and back.
    static final double FORWARD_COST = 0.25;
    static final double BACK_COST = 1.0;

    // The direction of movement comes from two fixes at least this far apart.
    // Nearer fixes give mostly the direction of the GPS noise.
    static final double MIN_MOVEMENT_M = 3;

    // A part of the route with a direction below this cosine against the
    // movement is not a candidate. The value is the cosine of 120 degrees.
    static final double MIN_DIRECTION = -0.5;

    // The arrival time uses the pace ratio only after this reference time.
    // Before it, one slow start makes the prediction too large.
    static final long MIN_RATIO_TIME_MS = 30000;

    static final double MIN_PACE_RATIO = 0.25;
    static final double MAX_PACE_RATIO = 4.0;

    // The longest gap in the GPS fixes that the search window follows.
    static final long MAX_GAP_MS = 600000;

    // The distance along the track on each side of a point for the smoothing.
    static final double SMOOTH_M = 10;

    private static final double METERS_PER_DEGREE = Geo.EARTH_RADIUS_M * Math.PI / 180.0;

    private final Profile profile;
    private final int n;
    private final double[] lat;
    private final double[] lon;
    private final double[] along;
    private final long[] time;
    private final boolean hasTime;
    private final double totalM;
    private final long referenceStartMs;
    private final long referenceTotalMs;
    private final double finishAlongM;
    private final double baseWindowM;

    private State state = State.WAITING;
    private double s;
    private long tripStartMs;
    private long lastInsideMs;
    private long lastFixMs = TrackPoint.NO_TIME;
    private int offCount;
    private int onCount;
    private int joinCount;
    private long finishElapsedMs = MatchResult.UNKNOWN;
    private MatchResult finishResult;

    // The pairs of time and distance along the route of the last points,
    // for the speed.
    private final ArrayDeque<double[]> history = new ArrayDeque<>();

    // The output of the last search.
    private double foundCross;
    private double foundAlong;

    // The off-route limit for the current fix: the limit of the profile plus
    // twice the accuracy of the fix.
    private double limitM;

    // The distances from the fix and the places of the segments of the last
    // search. The arrays grow when a search needs more space.
    private double[] crossBuffer = new double[64];
    private double[] placeBuffer = new double[64];
    private double[] directionBuffer = new double[64];

    // The direction of movement as a unit vector in meters, east and north.
    // NaN before the first movement of MIN_MOVEMENT_M.
    private double moveX = Double.NaN;
    private double moveY = Double.NaN;
    private double anchorLat = Double.NaN;
    private double anchorLon = Double.NaN;

    public TrackMatcher(Track reference, Profile profile) {
        List<TrackPoint> pts = reference.points;
        if (pts.size() < 2) {
            throw new IllegalArgumentException("the reference track has fewer than 2 points");
        }
        this.profile = profile;
        n = pts.size();
        lat = new double[n];
        lon = new double[n];
        along = new double[n];
        time = new long[n];
        boolean times = true;
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            TrackPoint p = pts.get(i);
            time[i] = p.timeMs;
            if (i > 0) {
                raw[i] = raw[i - 1] + Geo.distanceM(pts.get(i - 1), p);
                if (!p.hasTime() || !pts.get(i - 1).hasTime() || p.timeMs < pts.get(i - 1).timeMs) {
                    times = false;
                }
            } else if (!p.hasTime()) {
                times = false;
            }
        }
        smooth(pts, raw);
        for (int i = 1; i < n; i++) {
            along[i] = along[i - 1] + Geo.distanceM(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        }
        hasTime = times;
        totalM = along[n - 1];
        baseWindowM = Math.max(250, 4 * profile.offRouteM);

        // The reference track starts when it leaves its start circle.
        int last = 0;
        while (last + 1 < n
                && Geo.distanceM(lat[0], lon[0], lat[last + 1], lon[last + 1]) <= profile.radiusM) {
            last++;
        }
        referenceStartMs = hasTime ? time[last] : 0;
        referenceTotalMs = hasTime ? time[n - 1] - referenceStartMs : MatchResult.UNKNOWN;

        // The finish place is where the reference track enters its finish
        // circle. GPS noise makes the length along a track a little too
        // large. Thus a fixed distance before the end is not the right test.
        int enter = n - 1;
        while (enter > 0
                && Geo.distanceM(lat[n - 1], lon[n - 1], lat[enter - 1], lon[enter - 1])
                <= profile.radiusM) {
            enter--;
        }
        finishAlongM = along[enter];
    }

    // Sets lat and lon to the mean of the points within SMOOTH_M along the
    // raw track. The first and the last point stay as they are. The window
    // moves along the track, thus the work is linear.
    private void smooth(List<TrackPoint> pts, double[] raw) {
        int from = 0;
        int to = 0;
        double sumLat = 0;
        double sumLon = 0;
        for (int i = 0; i < n; i++) {
            while (to < n && raw[to] <= raw[i] + SMOOTH_M) {
                sumLat += pts.get(to).latitude;
                sumLon += pts.get(to).longitude;
                to++;
            }
            while (raw[from] < raw[i] - SMOOTH_M) {
                sumLat -= pts.get(from).latitude;
                sumLon -= pts.get(from).longitude;
                from++;
            }
            int count = to - from;
            lat[i] = sumLat / count;
            lon[i] = sumLon / count;
        }
        lat[0] = pts.get(0).latitude;
        lon[0] = pts.get(0).longitude;
        lat[n - 1] = pts.get(n - 1).latitude;
        lon[n - 1] = pts.get(n - 1).longitude;
    }

    public double totalM() {
        return totalM;
    }

    public State state() {
        return state;
    }

    // Takes the next point of the current track. The points must come in the
    // order of their times.
    public MatchResult update(TrackPoint fix) {
        if (state == State.FINISHED) {
            return finishResult;
        }
        long gapMs = lastFixMs == TrackPoint.NO_TIME ? 0 : fix.timeMs - lastFixMs;
        lastFixMs = fix.timeMs;
        updateMovement(fix);
        double accuracy = Double.isNaN(fix.accuracyM) ? 0 : fix.accuracyM;
        limitM = profile.offRouteM + 2 * accuracy;
        double circleM = profile.radiusM + 2 * accuracy;
        double fromStart = Geo.distanceM(lat[0], lon[0], fix.latitude, fix.longitude);

        if (state == State.WAITING) {
            if (fromStart <= circleM) {
                state = State.AT_START;
                lastInsideMs = fix.timeMs;
                s = 0;
                return result(fix, fromStart);
            }
            search(fix, 0, totalM, Double.NaN);
            if (foundCross <= limitM && foundAlong > circleM) {
                joinCount++;
                if (joinCount >= JOIN_FIXES) {
                    s = foundAlong;
                    tripStartMs = hasTime ? fix.timeMs - referenceTimeAt(s) : fix.timeMs;
                    state = State.ON_ROUTE;
                    history.clear();
                    return afterMove(fix, foundCross, circleM);
                }
            } else {
                joinCount = 0;
            }
            return result(fix, foundCross);
        }

        if (state == State.AT_START) {
            if (fromStart <= circleM) {
                lastInsideMs = fix.timeMs;
                return result(fix, fromStart);
            }
            search(fix, 0, baseWindowM, 0);
            s = foundCross <= limitM ? foundAlong : 0;
            tripStartMs = hasTime ? fix.timeMs - referenceTimeAt(s) : lastInsideMs;
            state = State.ON_ROUTE;
            history.clear();
            history.addLast(new double[]{fix.timeMs, s});
            return afterMove(fix, foundCross, circleM);
        }

        if (state == State.ON_ROUTE) {
            double window = baseWindowM
                    + Math.min(Math.max(gapMs, 0), MAX_GAP_MS) / 1000.0 * profile.maxSpeedMps;
            search(fix, s - BACK_TOLERANCE_M, s + window, s);
            if (foundCross <= limitM) {
                s = foundAlong;
                offCount = 0;
            } else {
                offCount++;
                if (offCount >= OFF_ROUTE_FIXES) {
                    state = State.OFF_ROUTE;
                    onCount = 0;
                }
            }
            return afterMove(fix, foundCross, circleM);
        }

        // OFF_ROUTE: first the rest of the route, then the whole route.
        search(fix, s - BACK_TOLERANCE_M, totalM, s);
        if (foundCross > limitM) {
            search(fix, 0, totalM, s);
        }
        if (foundCross <= limitM) {
            onCount++;
            if (onCount >= BACK_ON_ROUTE_FIXES) {
                state = State.ON_ROUTE;
                s = foundAlong;
                offCount = 0;
            }
        } else {
            onCount = 0;
        }
        return afterMove(fix, foundCross, circleM);
    }

    // Records the place for the speed and checks the finish. The trip
    // finishes inside the finish circle after the place where the reference
    // track enters that circle. It also finishes when its place is inside
    // the circle size from the end of the route. GPS drift can move the
    // place along the route by that size.
    private MatchResult afterMove(TrackPoint fix, double cross, double circleM) {
        if (state == State.ON_ROUTE) {
            history.addLast(new double[]{fix.timeMs, s});
            long oldest = fix.timeMs - 2000L * profile.speedSmoothingS;
            while (history.size() > 2 && history.peekFirst()[0] < oldest) {
                history.removeFirst();
            }
            double toEnd = Geo.distanceM(lat[n - 1], lon[n - 1], fix.latitude, fix.longitude);
            boolean inCircle = s >= finishAlongM - profile.radiusM && toEnd <= circleM;
            boolean atEnd = s >= totalM - circleM;
            if (inCircle || atEnd) {
                state = State.FINISHED;
                finishElapsedMs = fix.timeMs - tripStartMs;
                finishResult = result(fix, cross);
                return finishResult;
            }
        }
        return result(fix, cross);
    }

    private MatchResult result(TrackPoint fix, double cross) {
        if (state == State.WAITING || state == State.AT_START) {
            double place = state == State.AT_START ? 0 : Double.NaN;
            return new MatchResult(state, cross, place, totalM, hasTime, MatchResult.UNKNOWN,
                    MatchResult.UNKNOWN, Double.NaN, Double.NaN, Double.NaN,
                    MatchResult.UNKNOWN, referenceTotalMs, lat[0], lon[0]);
        }

        long elapsed = state == State.FINISHED ? finishElapsedMs : fix.timeMs - tripStartMs;
        double speed = speedNow(fix.timeMs);
        if (!hasTime) {
            return new MatchResult(state, cross, s, totalM, false, elapsed, MatchResult.UNKNOWN,
                    Double.NaN, speed, Double.NaN, MatchResult.UNKNOWN, MatchResult.UNKNOWN,
                    Double.NaN, Double.NaN);
        }

        long refAtS = referenceTimeAt(s);
        long deltaTime = elapsed - refAtS;
        double ghost = referenceAlongAt(elapsed);
        double deltaDistance = s - ghost;

        long windowMs = 1000L * profile.speedSmoothingS;
        long fromTime = Math.max(0, refAtS - windowMs);
        double refSpeed = refAtS > fromTime
                ? (s - referenceAlongAt(fromTime)) * 1000.0 / (refAtS - fromTime) : Double.NaN;

        long remainingRef = Math.max(0, referenceTotalMs - refAtS);
        double ratio = 1.0;
        if (refAtS >= MIN_RATIO_TIME_MS) {
            ratio = Math.max(MIN_PACE_RATIO, Math.min(MAX_PACE_RATIO, (double) elapsed / refAtS));
        }
        long predicted = state == State.FINISHED ? elapsed : elapsed + Math.round(remainingRef * ratio);

        double[] ghostPos = positionAt(ghost);
        return new MatchResult(state, cross, s, totalM, true, elapsed, deltaTime, deltaDistance,
                speed, refSpeed, predicted, referenceTotalMs, ghostPos[0], ghostPos[1]);
    }

    // The speed along the route over the smoothing window of the profile.
    private double speedNow(long nowMs) {
        if (history.size() < 2) {
            return Double.NaN;
        }
        long windowMs = 1000L * profile.speedSmoothingS;
        double[] last = history.peekLast();
        double[] first = null;
        for (double[] h : history) {
            if (h[0] >= nowMs - windowMs) {
                first = h;
                break;
            }
        }
        if (first == null || first == last) {
            first = history.peekFirst();
        }
        double dt = last[0] - first[0];
        return dt > 0 ? (last[1] - first[1]) * 1000.0 / dt : Double.NaN;
    }

    // Updates the direction of movement from the anchor fix. The anchor moves
    // to the fix each time the trip moved MIN_MOVEMENT_M.
    private void updateMovement(TrackPoint fix) {
        if (Double.isNaN(anchorLat)) {
            anchorLat = fix.latitude;
            anchorLon = fix.longitude;
            return;
        }
        double scale = Math.cos(Math.toRadians(anchorLat)) * METERS_PER_DEGREE;
        double dx = (fix.longitude - anchorLon) * scale;
        double dy = (fix.latitude - anchorLat) * METERS_PER_DEGREE;
        double length = Math.sqrt(dx * dx + dy * dy);
        if (length >= MIN_MOVEMENT_M) {
            moveX = dx / length;
            moveY = dy / length;
            anchorLat = fix.latitude;
            anchorLon = fix.longitude;
        }
    }

    // Finds the place of the fix on the route between two distances along
    // it. The result goes to foundCross and foundAlong. If near is a number,
    // the local minimum inside the off-route limit with the lowest cost wins.
    // Otherwise, or with no minimum inside the limit, the nearest part wins.
    private void search(TrackPoint fix, double fromM, double toM, double near) {
        double from = Math.max(0, fromM);
        double to = Math.min(totalM, toM);
        int first = segmentAt(from);
        int count = 0;
        for (int i = first; i < n - 1 && along[i] <= to; i++) {
            if (count == crossBuffer.length) {
                crossBuffer = Arrays.copyOf(crossBuffer, count * 2);
                placeBuffer = Arrays.copyOf(placeBuffer, count * 2);
                directionBuffer = Arrays.copyOf(directionBuffer, count * 2);
            }
            projectOnSegment(i, fix.latitude, fix.longitude, count);
            count++;
        }

        int nearest = 0;
        for (int k = 1; k < count; k++) {
            if (crossBuffer[k] < crossBuffer[nearest]) {
                nearest = k;
            }
        }
        foundCross = crossBuffer[nearest];
        foundAlong = placeBuffer[nearest];
        if (Double.isNaN(near)) {
            return;
        }

        double bestCost = Double.POSITIVE_INFINITY;
        for (int k = 0; k < count; k++) {
            double c = crossBuffer[k];
            boolean minimum = (k == 0 || c <= crossBuffer[k - 1])
                    && (k == count - 1 || c <= crossBuffer[k + 1]);
            boolean against = directionBuffer[k] < MIN_DIRECTION;
            boolean tooFarBack = placeBuffer[k] < fromM;
            if (minimum && c <= limitM && !against && !tooFarBack) {
                double step = placeBuffer[k] - near;
                double cost = c + (step >= 0 ? step * FORWARD_COST : -step * BACK_COST);
                if (cost < bestCost) {
                    bestCost = cost;
                    foundCross = c;
                    foundAlong = placeBuffer[k];
                }
            }
        }
    }

    // Projects a position onto segment i. The result goes to slot k of the
    // buffers.
    private void projectOnSegment(int i, double plat, double plon, int k) {
        double scale = Math.cos(Math.toRadians(lat[i])) * METERS_PER_DEGREE;
        double bx = (lon[i + 1] - lon[i]) * scale;
        double by = (lat[i + 1] - lat[i]) * METERS_PER_DEGREE;
        double px = (plon - lon[i]) * scale;
        double py = (plat - lat[i]) * METERS_PER_DEGREE;
        double length2 = bx * bx + by * by;
        double f = length2 > 0 ? (px * bx + py * by) / length2 : 0;
        f = Math.max(0, Math.min(1, f));
        double dx = px - f * bx;
        double dy = py - f * by;
        crossBuffer[k] = Math.sqrt(dx * dx + dy * dy);
        placeBuffer[k] = along[i] + f * (along[i + 1] - along[i]);
        double length = Math.sqrt(length2);
        directionBuffer[k] = Double.isNaN(moveX) || length == 0
                ? Double.NaN : (bx * moveX + by * moveY) / length;
    }

    // Returns the index of the segment that holds the distance.
    private int segmentAt(double distanceM) {
        int low = 0;
        int high = n - 2;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (along[mid] <= distanceM) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    // The time of the reference track at a distance along the route, from
    // the start of the reference track.
    long referenceTimeAt(double distanceM) {
        if (distanceM <= 0) {
            return Math.max(0, time[0] - referenceStartMs);
        }
        if (distanceM >= totalM) {
            return referenceTotalMs;
        }
        int i = segmentAt(distanceM);
        double length = along[i + 1] - along[i];
        double f = length > 0 ? (distanceM - along[i]) / length : 0;
        long t = time[i] + Math.round(f * (time[i + 1] - time[i]));
        return Math.max(0, t - referenceStartMs);
    }

    // The distance along the route of the reference track at an elapsed
    // time. This is the place of the ghost.
    double referenceAlongAt(long elapsedMs) {
        long t = referenceStartMs + elapsedMs;
        if (t <= time[0]) {
            return 0;
        }
        if (t >= time[n - 1]) {
            return totalM;
        }
        int low = 0;
        int high = n - 2;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (time[mid] <= t) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        long dt = time[low + 1] - time[low];
        double f = dt > 0 ? (double) (t - time[low]) / dt : 0;
        return along[low] + f * (along[low + 1] - along[low]);
    }

    // The position on the route at a distance along it.
    private double[] positionAt(double distanceM) {
        if (distanceM <= 0) {
            return new double[]{lat[0], lon[0]};
        }
        if (distanceM >= totalM) {
            return new double[]{lat[n - 1], lon[n - 1]};
        }
        int i = segmentAt(distanceM);
        double length = along[i + 1] - along[i];
        double f = length > 0 ? (distanceM - along[i]) / length : 0;
        return new double[]{lat[i] + f * (lat[i + 1] - lat[i]), lon[i] + f * (lon[i + 1] - lon[i])};
    }
}
