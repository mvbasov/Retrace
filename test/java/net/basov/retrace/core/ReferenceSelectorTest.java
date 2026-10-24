package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The test of ReferenceSelector
// ----------------------------------------------------------------------
//
// All saved tracks start at the same place. Two of them go north on the same
// route, one goes east, and one is a cycling track to the north. One more
// walking track is far away. The trip must select the right track, or no
// track for a new route.
public final class ReferenceSelectorTest {

    private static int failures = 0;

    private static final long DAY_MS = 86400000L;
    private static final double WALK = 1.4;
    private static final double SIGMA = 4;

    private static final double[][] NORTH = {{0, 0}, {0, 1000}};
    private static final double[][] EAST = {{0, 0}, {1000, 0}};
    private static final double[][] SOUTH = {{0, 0}, {0, -1000}};
    private static final double[][] FAR = {{3000, 3000}, {3000, 4000}};
    private static final double[][] SHORT = {{0, 0}, {0, 200}};

    public static void main(String[] args) {
        onlyNearTracksOfTheProfileAreCandidates();
        aTripNorthSelectsTheNewestNorthTrack();
        aTripEastSelectsTheEastTrack();
        aNewRouteGivesNoReference();
        noCandidateGivesNoReference();
        theNearestStartIsTheFirstTemporaryReference();
        aShortRouteDecidesEarly();
        theComparisonContinuesAfterTheChoice();
        aWayWinsOverTheTracksOfItsRoute();
        aTripInTheOppositeDirectionSelectsTheReversedTrack();
        aTripFromTheMiddleJoinsTheRoute();

        if (failures > 0) {
            System.out.println("ReferenceSelectorTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ReferenceSelectorTest: every check passed");
    }

    // A far track and a track of a different profile must not be candidates.
    // Each near track is a candidate in the two directions.
    private static void onlyNearTracksOfTheProfileAreCandidates() {
        List<ReferenceSelector.Candidate> near = ReferenceSelector.nearRoute(
                saved(), Profile.WALKING, trip(NORTH, 100, 0).get(0));
        List<String> ids = new ArrayList<>();
        for (ReferenceSelector.Candidate c : near) {
            ids.add(c.id + (c.reversed ? "/back" : ""));
        }
        check("candidates", "[north-old, north-old/back, north-new, north-new/back, east, east/back]",
                ids.toString());
    }

    // Two north tracks have almost the same score. The newest one wins.
    private static void aTripNorthSelectsTheNewestNorthTrack() {
        Result r = run(trip(NORTH, 101, 0));
        check("north: state", ReferenceSelector.State.SELECTED, r.state);
        check("north: track", "north-new", r.selected);
        // The routes have 1000 m. Thus the selection comes after 10 percent of
        // that, not after 300 m.
        near("north: decision distance", 100, r.decisionM, 5);
        near("north: decision place", r.decisionM, r.decidedAtM, 30);
    }

    private static void aTripEastSelectsTheEastTrack() {
        Result r = run(trip(EAST, 102, 0));
        check("east: state", ReferenceSelector.State.SELECTED, r.state);
        check("east: track", "east", r.selected);
    }

    // No saved track goes south. After three decision distances, the trip
    // has no reference track.
    private static void aNewRouteGivesNoReference() {
        Result r = run(trip(SOUTH, 103, 0));
        check("south: state", ReferenceSelector.State.NONE, r.state);
        check("south: no track", null, r.selected);
        near("south: give up place", ReferenceSelector.GIVE_UP_FACTOR * r.decisionM,
                r.decidedAtM, 30);
    }

    private static void noCandidateGivesNoReference() {
        ReferenceSelector s = new ReferenceSelector(new ArrayList<ReferenceSelector.Candidate>(),
                Profile.WALKING, trip(NORTH, 104, 0).get(0));
        check("empty: state", ReferenceSelector.State.NONE, s.state());
        check("empty: no current", true, s.current() == null);
    }

    // Before the first valid score, the track with the nearest start is the
    // temporary reference track. It gives the screen something to show.
    private static void theNearestStartIsTheFirstTemporaryReference() {
        List<TrackPoint> points = trip(EAST, 105, 0);
        List<ReferenceSelector.Candidate> all = new ArrayList<>();
        all.add(candidate("near", TestTracks.shift(points.get(0), 2, 0), EAST, 1));
        all.add(candidate("farther", TestTracks.shift(points.get(0), 30, 0), EAST, 2));
        ReferenceSelector s = new ReferenceSelector(all, Profile.WALKING, points.get(0));
        check("temporary: nearest", "near", s.current().id);
        check("temporary: state", ReferenceSelector.State.SELECTING, s.state());
    }

    // A route of 200 m decides after 10 percent of it, but after 50 m at the
    // least.
    private static void aShortRouteDecidesEarly() {
        List<ReferenceSelector.Candidate> all = new ArrayList<>();
        all.add(new ReferenceSelector.Candidate("short", walk(SHORT, 106, 1)));
        List<TrackPoint> points = trip(SHORT, 107, 0);
        ReferenceSelector s = new ReferenceSelector(all, Profile.WALKING, points.get(0));
        near("short: decision distance", ReferenceSelector.MIN_DECISION_M, s.decisionM(), 1e-9);
        Result r = feed(s, points);
        check("short: selected", "short", r.selected);
        check("short: early", true, r.decidedAtM < 120);
    }

    // The matcher of the selected track has all the points. At the same speed,
    // the time difference at the finish is near zero.
    private static void theComparisonContinuesAfterTheChoice() {
        List<TrackPoint> points = trip(NORTH, 108, 0);
        List<ReferenceSelector.Candidate> near = ReferenceSelector.nearRoute(
                saved(), Profile.WALKING, points.get(0));
        ReferenceSelector s = new ReferenceSelector(near, Profile.WALKING, points.get(0));
        for (TrackPoint p : points) {
            s.update(p);
        }
        MatchResult last = s.currentResult();
        check("continue: finished", TrackMatcher.State.FINISHED, last.state);
        double typical = SIGMA * Math.sqrt(2) / WALK;
        near("continue: time at finish", 0, last.deltaTimeMs / 1000.0, 3 * typical);
    }

    // The way of the two north tracks is older than the newest north track.
    // It still wins, because a way is the typical trip on its route.
    private static void aWayWinsOverTheTracksOfItsRoute() {
        List<Track> north = new ArrayList<>();
        north.add(walk(NORTH, 11, 1));
        north.add(walk(NORTH, 12, 2));
        Track built = WayBuilder.build("way", Profile.WALKING, north, 0).way;
        List<String> members = new ArrayList<>();
        members.add("a.gpx");
        members.add("b.gpx");
        List<ReferenceSelector.Candidate> all = saved();
        all.add(new ReferenceSelector.Candidate("north-way", built.withMembers(members)));
        List<TrackPoint> points = trip(NORTH, 109, 0);
        ReferenceSelector s = new ReferenceSelector(
                ReferenceSelector.nearRoute(all, Profile.WALKING, points.get(0)),
                Profile.WALKING, points.get(0));
        Result r = feed(s, points);
        check("way: selected", "north-way", r.selected);
    }

    // The trip starts at the north end of the north tracks and goes south.
    // The newest north track in the opposite direction is the reference.
    private static void aTripInTheOppositeDirectionSelectsTheReversedTrack() {
        Result r = run(trip(new double[][]{{0, 1000}, {0, 0}}, 110, 0));
        check("opposite: state", ReferenceSelector.State.SELECTED, r.state);
        check("opposite: track", "north-new", r.selected);
        check("opposite: reversed", true, r.reversed);
    }

    // The trip starts at 400 m of the north route. It joins the route
    // there, and the progress counts from the join. Thus the selection
    // comes after the decision distance from the join, not at once.
    private static void aTripFromTheMiddleJoinsTheRoute() {
        Result r = run(trip(new double[][]{{0, 400}, {0, 1000}}, 111, 0));
        check("middle: state", ReferenceSelector.State.SELECTED, r.state);
        check("middle: track", "north-new", r.selected);
        check("middle: same direction", false, r.reversed);
        check("middle: not at once", true, r.decidedAtM >= 0.8 * r.decisionM);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static final class Result {
        ReferenceSelector.State state;
        String selected;
        boolean reversed;
        double decisionM;
        double decidedAtM = Double.NaN;
    }

    private static Result run(List<TrackPoint> points) {
        List<ReferenceSelector.Candidate> near = ReferenceSelector.nearRoute(
                saved(), Profile.WALKING, points.get(0));
        return feed(new ReferenceSelector(near, Profile.WALKING, points.get(0)), points);
    }

    // Gives the points to the selector. Records the travelled distance at the
    // moment of the selection.
    private static Result feed(ReferenceSelector s, List<TrackPoint> points) {
        Result r = new Result();
        double travelled = 0;
        TrackPoint before = null;
        for (TrackPoint p : points) {
            if (before != null) {
                travelled += Geo.distanceM(before, p);
            }
            before = p;
            ReferenceSelector.State state = s.update(p);
            if (state != ReferenceSelector.State.SELECTING && Double.isNaN(r.decidedAtM)) {
                r.decidedAtM = travelled;
            }
        }
        r.state = s.state();
        r.decisionM = s.decisionM();
        r.selected = s.current() == null ? null : s.current().id;
        r.reversed = s.current() != null && s.current().reversed;
        return r;
    }

    // The saved tracks. The ids tell the route and the age.
    private static List<ReferenceSelector.Candidate> saved() {
        List<ReferenceSelector.Candidate> all = new ArrayList<>();
        all.add(new ReferenceSelector.Candidate("north-old", walk(NORTH, 1, 1)));
        all.add(new ReferenceSelector.Candidate("north-new", walk(NORTH, 2, 5)));
        all.add(new ReferenceSelector.Candidate("east", walk(EAST, 3, 3)));
        all.add(new ReferenceSelector.Candidate("far", walk(FAR, 4, 4)));
        all.add(new ReferenceSelector.Candidate("north-cycling", new Track("c", Profile.CYCLING,
                TestTracks.recorded(TestTracks.noisy(TestTracks.ideal(NORTH, 5,
                        TestTracks.START_MS + 6 * DAY_MS, 0), 5, SIGMA), Profile.CYCLING))));
        return all;
    }

    private static Track walk(double[][] corners, long seed, int day) {
        return new Track("t" + seed, Profile.WALKING, TestTracks.recorded(TestTracks.noisy(
                TestTracks.ideal(corners, WALK, TestTracks.START_MS + day * DAY_MS, 0),
                seed, SIGMA), Profile.WALKING));
    }

    // A walking track along the corners from a different start point.
    private static ReferenceSelector.Candidate candidate(String id, TrackPoint start,
                                                         double[][] corners, long seed) {
        List<TrackPoint> points = new ArrayList<>();
        points.add(start);
        points.addAll(walk(corners, seed, 1).points);
        return new ReferenceSelector.Candidate(id, new Track(id, Profile.WALKING, points));
    }

    // The current trip, on day 10, after the saved tracks.
    private static List<TrackPoint> trip(double[][] corners, long seed, int waitS) {
        return TestTracks.recorded(TestTracks.noisy(TestTracks.ideal(corners, WALK,
                TestTracks.START_MS + 10 * DAY_MS, waitS), seed, SIGMA), Profile.WALKING);
    }

    private static void near(String name, double expected, double actual, double tolerance) {
        if (Double.isNaN(actual) || Math.abs(expected - actual) > tolerance) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + " +- " + tolerance
                    + ", got " + actual);
        }
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
