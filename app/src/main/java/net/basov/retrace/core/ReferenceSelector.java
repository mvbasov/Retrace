package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// ----------------------------------------------------------------------
// The automatic selection of the reference track
// ----------------------------------------------------------------------
//
// At the start of a trip, the candidates are the saved tracks of the same
// profile with a start near the first point. See nearStart(). The selector
// then gives each point of the trip to one TrackMatcher for each candidate.
//
// During the selection, the current candidate is the temporary reference
// track. It is the candidate with the best score so far, or the candidate
// with the nearest start before that.
//
// The selection comes when one candidate is DECISION_M along its route, or
// 10 percent of the shortest candidate if that is less. A candidate that is
// not on the route then is not valid. From the valid candidates, the lowest mean
// cross-track distance wins. Two tracks of the same route have almost the
// same score. Thus candidates within TIE_M of the best score count as equal,
// and the newest of them wins. The trip then races the last trip on that
// route.
//
// If no candidate is valid after GIVE_UP_FACTOR times the decision distance,
// the trip has no reference track. Retrace then records a new route.
//
// The matcher of the selected candidate has all the points of the trip.
// Thus the comparison continues with no gap after the selection.
//
// Two routes with the same first part are a limit. The selection can come
// before the routes go apart. Then the newest track wins, also if the trip
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

    // A candidate needs its start inside this many start circles.
    public static final double START_FACTOR = 3;

    // A valid candidate is this part of the decision distance along its route.
    static final double MIN_PROGRESS = 0.5;

    // A saved track that can be the reference track.
    public static final class Candidate {
        public final String id;
        public final Track track;

        public Candidate(String id, Track track) {
            this.id = id;
            this.track = track;
        }
    }

    // The state of one candidate during the selection.
    private static final class Trial {
        final Candidate candidate;
        final TrackMatcher matcher;
        final double startDistanceM;
        MatchResult last;
        double crossSum;
        int crossCount;

        Trial(Candidate candidate, Profile profile, double startDistanceM) {
            this.candidate = candidate;
            this.matcher = new TrackMatcher(candidate.track, profile);
            this.startDistanceM = startDistanceM;
        }

        boolean onRoute() {
            return last != null && (last.state == TrackMatcher.State.ON_ROUTE
                    || last.state == TrackMatcher.State.FINISHED);
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

    // Returns the candidates of the profile with a start near the fix. The
    // radius is the start circle of TrackMatcher times START_FACTOR.
    public static List<Candidate> nearStart(List<Candidate> all, Profile profile, TrackPoint fix) {
        List<Candidate> near = new ArrayList<>();
        for (Candidate c : all) {
            if (startsNear(c.track, profile, fix)) {
                near.add(c);
            }
        }
        return near;
    }

    // True if the track can be a candidate for a trip with this first fix.
    // TrackService reads the saved tracks one at a time with this check.
    // Thus only the near tracks stay in memory.
    public static boolean startsNear(Track track, Profile profile, TrackPoint fix) {
        if (track.profile != profile || track.points.size() < 2) {
            return false;
        }
        double accuracy = Double.isNaN(fix.accuracyM) ? 0 : fix.accuracyM;
        double limit = START_FACTOR * (profile.radiusM + 2 * accuracy);
        return Geo.distanceM(track.points.get(0), fix) <= limit;
    }

    // The candidates must have at least 2 points each. The start distances
    // come from the first fix of the trip.
    public ReferenceSelector(List<Candidate> candidates, Profile profile, TrackPoint firstFix) {
        double shortest = Double.POSITIVE_INFINITY;
        for (Candidate c : candidates) {
            double start = Geo.distanceM(c.track.points.get(0), firstFix);
            trials.add(new Trial(c, profile, start));
            shortest = Math.min(shortest, c.track.distanceM());
        }
        decisionM = Math.max(MIN_DECISION_M, Math.min(DECISION_M, 0.1 * shortest));
        state = trials.isEmpty() ? State.NONE : State.SELECTING;
        current = nearestStart();
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
                t.crossSum += t.last.crossTrackM;
                t.crossCount++;
            }
            if (t.onRoute() && t.last.alongM >= decisionM) {
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
        current = best != null ? best : nearestStart();
        return state;
    }

    // The valid candidate with the lowest score. Candidates within TIE_M of
    // that score count as equal, and the newest of them wins.
    private Trial best() {
        List<Trial> valid = new ArrayList<>();
        double bestScore = Double.POSITIVE_INFINITY;
        for (Trial t : trials) {
            if (t.onRoute() && t.last.alongM >= MIN_PROGRESS * Math.min(decisionM, travelledM)) {
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
        return Collections.max(equal, (a, b) -> Long.compare(
                a.candidate.track.startTimeMs(), b.candidate.track.startTimeMs()));
    }

    private Trial nearestStart() {
        Trial nearest = null;
        for (Trial t : trials) {
            if (nearest == null || t.startDistanceM < nearest.startDistanceM) {
                nearest = t;
            }
        }
        return nearest;
    }
}
