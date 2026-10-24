package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// ----------------------------------------------------------------------
// The automatic selection of the reference track
// ----------------------------------------------------------------------
//
// At the start of a trip, the candidates are the saved tracks of the same
// profile with a route near the first point. The trip can start at the
// start of a route, or join it in the middle. Each such track is a
// candidate in its own direction and in the opposite direction. See
// nearRoute(). The selector gives each point of the trip to one
// TrackMatcher for each candidate. A matcher does not join a route against
// the direction of movement. Thus the wrong direction does not get valid.
//
// During the selection, the current candidate is the temporary reference
// track. It is the candidate with the best score so far, or the candidate
// with the nearest route before that.
//
// The progress of a candidate is the distance along its route from the
// place where the trip started or joined it. The selection comes when one
// candidate has a progress of DECISION_M, or 10 percent of the shortest
// candidate if that is less. A candidate that is not on the route then is
// not valid. From the valid candidates, the lowest mean cross-track
// distance wins.
//
// Two tracks of the same route have almost the same score. Thus candidates
// within TIE_M of the best score count as equal. From them:
// 1. A way wins, because it is the typical trip on the route.
// 2. Then the same direction wins over the opposite direction. The time in
//    the opposite direction is correct only on a flat route.
// 3. Then the newest wins. The trip then races the last trip on that route.
//
// If no candidate is valid after GIVE_UP_FACTOR times the decision distance,
// the trip has no reference track. Retrace then records a new route.
//
// The matcher of the selected candidate has all the points of the trip.
// Thus the comparison continues with no gap after the selection.
//
// Two routes with the same first part are a limit. The selection can come
// before the routes go apart. Then the tie rule wins, also if the trip
// later follows the other route. A manual selection corrects this.
public final class ReferenceSelector {

    public enum State {
        SELECTING,
        SELECTED,
        NONE
    }

    // The distance along the route for the selection.
    public static final double DECISION_M = 300;

    // The selection comes after at least this distance, also for a short route.
    public static final double MIN_DECISION_M = 50;

    // Scores nearer than this to the best score count as equal.
    public static final double TIE_M = 3;

    // After this many decision distances with no valid candidate, the
    // selector stops.
    public static final double GIVE_UP_FACTOR = 3;

    // A candidate needs its route inside this many start circles of the
    // first point.
    public static final double START_FACTOR = 3;

    // A valid candidate has this part of the decision distance as progress.
    static final double MIN_PROGRESS = 0.5;

    private static final double METERS_PER_DEGREE = Geo.EARTH_RADIUS_M * Math.PI / 180.0;

    // A saved track that can be the reference track. A candidate in the
    // opposite direction has the reversed track.
    public static final class Candidate {
        public final String id;
        public final Track track;
        public final boolean reversed;

        public Candidate(String id, Track track) {
            this(id, track, false);
        }

        public Candidate(String id, Track track, boolean reversed) {
            this.id = id;
            this.track = track;
            this.reversed = reversed;
        }
    }

    // The state of one candidate during the selection.
    private static final class Trial {
        final Candidate candidate;
        final TrackMatcher matcher;
        final double routeDistanceM;
        MatchResult last;
        double crossSum;
        int crossCount;
        double firstAlong = Double.NaN;

        Trial(Candidate candidate, Profile profile, double routeDistanceM) {
            this.candidate = candidate;
            this.matcher = new TrackMatcher(candidate.track, profile);
            this.routeDistanceM = routeDistanceM;
        }

        boolean onRoute() {
            return last != null && (last.state == TrackMatcher.State.ON_ROUTE
                    || last.state == TrackMatcher.State.FINISHED);
        }

        // The distance along the route from the start or the join.
        double progress() {
            return Double.isNaN(firstAlong) || !onRoute() ? 0 : last.alongM - firstAlong;
        }

        double score() {
            return crossCount == 0 ? Double.POSITIVE_INFINITY : crossSum / crossCount;
        }
    }

    private final List<Trial> trials = new ArrayList<>();
    private final double decisionM;
    private State state;
    private Trial current;
    private TrackPoint lastFix;
    private double travelledM;

    // Returns the candidates of the profile with a route near the fix, each
    // in its own direction and in the opposite direction.
    public static List<Candidate> nearRoute(List<Candidate> all, Profile profile, TrackPoint fix) {
        List<Candidate> near = new ArrayList<>();
        for (Candidate c : all) {
            if (routeNear(c.track, profile, fix)) {
                near.add(new Candidate(c.id, c.track, false));
                near.add(new Candidate(c.id, c.track.reversed(), true));
            }
        }
        return near;
    }

    // True if the track can be a candidate for a trip with this first fix:
    // the same profile, and a route inside START_FACTOR start circles of the
    // fix. TrackService reads the saved tracks one at a time with this
    // check. Thus only the near tracks stay in memory.
    public static boolean routeNear(Track track, Profile profile, TrackPoint fix) {
        if (track.profile != profile || track.points.size() < 2) {
            return false;
        }
        double accuracy = Double.isNaN(fix.accuracyM) ? 0 : fix.accuracyM;
        double limit = START_FACTOR * (profile.radiusM + 2 * accuracy);
        return distanceToRoute(track, fix) <= limit;
    }

    // The distance from the fix to the nearest point of the line of the
    // track, in meters. A flat plane around the fix is precise enough here.
    static double distanceToRoute(Track track, TrackPoint fix) {
        double cos = Math.cos(Math.toRadians(fix.latitude));
        double best = Double.POSITIVE_INFINITY;
        double ax = 0;
        double ay = 0;
        for (int i = 0; i < track.points.size(); i++) {
            TrackPoint p = track.points.get(i);
            double bx = (p.longitude - fix.longitude) * METERS_PER_DEGREE * cos;
            double by = (p.latitude - fix.latitude) * METERS_PER_DEGREE;
            if (i == 0) {
                best = Math.hypot(bx, by);
            } else {
                double dx = bx - ax;
                double dy = by - ay;
                double len2 = dx * dx + dy * dy;
                double f = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
                best = Math.min(best, Math.hypot(ax + f * dx, ay + f * dy));
            }
            ax = bx;
            ay = by;
        }
        return best;
    }

    // The candidates must have at least 2 points each. The route distances
    // come from the first fix of the trip.
    public ReferenceSelector(List<Candidate> candidates, Profile profile, TrackPoint firstFix) {
        double shortest = Double.POSITIVE_INFINITY;
        for (Candidate c : candidates) {
            trials.add(new Trial(c, profile, distanceToRoute(c.track, firstFix)));
            shortest = Math.min(shortest, c.track.distanceM());
        }
        decisionM = Math.max(MIN_DECISION_M, Math.min(DECISION_M, 0.1 * shortest));
        state = trials.isEmpty() ? State.NONE : State.SELECTING;
        current = nearestRoute();
    }

    public State state() {
        return state;
    }

    // The temporary or the selected candidate, or null.
    public Candidate current() {
        return current == null ? null : current.candidate;
    }

    // The last result of the matcher of the current candidate, or null.
    public MatchResult currentResult() {
        return current == null ? null : current.last;
    }

    // The matcher of the selected candidate, or null before the selection.
    public TrackMatcher selectedMatcher() {
        return state == State.SELECTED ? current.matcher : null;
    }

    double decisionM() {
        return decisionM;
    }

    // Takes the next point of the trip.
    public State update(TrackPoint fix) {
        if (state == State.NONE) {
            return state;
        }
        if (state == State.SELECTED) {
            current.last = current.matcher.update(fix);
            return state;
        }
        if (lastFix != null) {
            travelledM += Geo.distanceM(lastFix, fix);
        }
        lastFix = fix;

        boolean decide = false;
        for (Trial t : trials) {
            t.last = t.matcher.update(fix);
            if (t.last.state == TrackMatcher.State.ON_ROUTE) {
                if (Double.isNaN(t.firstAlong)) {
                    t.firstAlong = t.last.alongM;
                }
                t.crossSum += t.last.crossTrackM;
                t.crossCount++;
            }
            if (t.onRoute() && t.progress() >= decisionM) {
                decide = true;
            }
        }

        Trial best = best();
        if (decide || travelledM >= GIVE_UP_FACTOR * decisionM) {
            if (best == null) {
                state = State.NONE;
                current = null;
            } else {
                state = State.SELECTED;
                current = best;
            }
            return state;
        }
        current = best != null ? best : nearestRoute();
        return state;
    }

    // The valid candidate with the lowest score. Candidates within TIE_M of
    // that score count as equal. Then the tie rule of the comment at the top
    // selects one.
    private Trial best() {
        List<Trial> valid = new ArrayList<>();
        double bestScore = Double.POSITIVE_INFINITY;
        for (Trial t : trials) {
            if (t.onRoute() && t.progress() >= MIN_PROGRESS * Math.min(decisionM, travelledM)
                    && t.progress() > 0) {
                valid.add(t);
                bestScore = Math.min(bestScore, t.score());
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        List<Trial> equal = new ArrayList<>();
        for (Trial t : valid) {
            if (t.score() <= bestScore + TIE_M) {
                equal.add(t);
            }
        }
        return Collections.max(equal, (a, b) -> {
            boolean wayA = a.candidate.track.isWay();
            boolean wayB = b.candidate.track.isWay();
            if (wayA != wayB) {
                return wayA ? 1 : -1;
            }
            if (a.candidate.reversed != b.candidate.reversed) {
                return a.candidate.reversed ? -1 : 1;
            }
            return Long.compare(a.candidate.track.startTimeMs(), b.candidate.track.startTimeMs());
        });
    }

    // The candidate with the nearest route. Of two equal distances, the
    // first candidate wins. nearRoute() puts the same direction first.
    private Trial nearestRoute() {
        Trial nearest = null;
        for (Trial t : trials) {
            if (nearest == null || t.routeDistanceM < nearest.routeDistanceM) {
                nearest = t;
            }
        }
        return nearest;
    }
}
