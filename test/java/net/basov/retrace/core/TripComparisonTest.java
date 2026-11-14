package net.basov.retrace.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// ----------------------------------------------------------------------
// The tests of TripComparison
// ----------------------------------------------------------------------
//
// TripComparison decides the comparison of each trip: the selection, the
// stop of a comparison that differs, and the finish signal. The tests use a
// TrackSource with invented tracks in memory.
public final class TripComparisonTest {

    private static int failures = 0;

    private static final long DAY_MS = 86400000L;
    private static final double SIGMA = 4;
    private static final int SEEDS = 10;

    private static final double[][] NORTH = {{0, 0}, {0, 1000}};
    private static final double[][] NORTH_1200 = {{0, 0}, {0, 1200}};
    private static final long PAUSE_MS = 600_000;
    // Route A goes north. Route B goes east from the place at 400 m of A.
    private static final double[][] ROUTE_A = {{0, 0}, {0, 1000}};
    private static final double[][] ROUTE_B = {{0, 400}, {1500, 400}};
    private static final double[][] A_THEN_B = {{0, 0}, {0, 400}, {1500, 400}};
    private static final double[][] FAR = {{3000, 3000}, {3000, 4000}};
    // The routes of RouteAgreementTest: a short loop, and a long route with
    // the same first 40 m.
    private static final double[][] LOOP = {{0, 0}, {0, 40}, {-60, 100}, {-120, 40},
            {-100, -40}, {60, -40}, {60, 0}, {5, 0}};
    private static final double[][] LONG = {{0, 0}, {0, 40}, {-300, 60}, {-900, -500}};

    public static void main(String[] args) {
        aManualReferenceSignalsTheFinishOnce();
        noReferenceGivesNoComparison();
        theAutomaticSelectionGoesThroughItsStates();
        noNearTrackGivesNotFound();
        aDifferentAutomaticReferenceStops();
        aDifferentManualReferenceStays();
        aSelectionAfterTheFinishGivesNoSignal();
        theReplayedTrackIsNeverItsOwnReference();
        anUnreadableTrackGivesNoComparison();
        aPauseCountsWithNoAddPause();
        aPauseDoesNotCountWithAddPause();
        aSelectionKeepsThePause();
        startTripStartsAgain();
        aTripThatLeavesItsRouteFindsTheNextRoute();
        noComparisonNeverSearches();
        aManualReferenceNeverSearches();
        aTripFromFarFindsTheRouteLater();
        aPauseBeforeAStopStillDoesNotCount();

        if (failures > 0) {
            System.out.println("TripComparisonTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("TripComparisonTest: every check passed");
    }

    // The device vibrates when add() returns true. One trip must vibrate one
    // time only, at the finish.
    private static void aManualReferenceSignalsTheFinishOnce() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            TripComparison c = comparison("north", walk(NORTH, 100 + seed, 1));
            c.setReference("north", false);
            check("manual " + seed + ": selection", RecordingSnapshot.SELECTION_MANUAL,
                    c.selection());
            List<TrackPoint> trip = trip(NORTH, 200 + seed);
            int signals = 0;
            int at = -1;
            for (int i = 0; i < trip.size(); i++) {
                if (c.add(trip.get(i))) {
                    signals++;
                    at = i;
                }
            }
            check("manual " + seed + ": one signal", 1, signals);
            check("manual " + seed + ": near the end", true, at >= trip.size() * 9 / 10);
            check("manual " + seed + ": finished", TrackMatcher.State.FINISHED,
                    c.match().state);
        }
    }

    private static void noReferenceGivesNoComparison() {
        TripComparison c = comparison("north", walk(NORTH, 1, 1));
        boolean any = false;
        for (TrackPoint p : trip(NORTH, 2)) {
            any |= c.add(p);
        }
        check("none: no signal", false, any);
        check("none: no match", null, c.match());
        check("none: selection", RecordingSnapshot.SELECTION_OFF, c.selection());
    }

    // The trip screen shows these states: the search, the temporary
    // reference track, then the selected one.
    private static void theAutomaticSelectionGoesThroughItsStates() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            TripComparison c = comparison("north", walk(NORTH, 300 + seed, 1));
            c.searchAutomatically();
            check("auto " + seed + ": search", RecordingSnapshot.SELECTION_SEARCH, c.selection());
            List<TrackPoint> trip = trip(NORTH, 400 + seed);
            c.add(trip.get(0));
            check("auto " + seed + ": temporary", RecordingSnapshot.SELECTION_TEMPORARY,
                    c.selection());
            for (int i = 1; i < trip.size(); i++) {
                c.add(trip.get(i));
            }
            check("auto " + seed + ": automatic", RecordingSnapshot.SELECTION_AUTOMATIC,
                    c.selection());
            check("auto " + seed + ": track", "north", c.referenceId());
            check("auto " + seed + ": direction", false, c.referenceReversed());
        }
    }

    private static void noNearTrackGivesNotFound() {
        TripComparison c = comparison("far", walk(FAR, 1, 1));
        c.searchAutomatically();
        c.add(trip(NORTH, 2).get(0));
        check("far: selection", RecordingSnapshot.SELECTION_NOT_FOUND, c.selection());
    }

    // The case of the maintainer: the long trip selects the short loop with
    // the same start. Then the comparison must stop.
    private static void aDifferentAutomaticReferenceStops() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            TripComparison c = comparison("loop", walk(LOOP, 500 + seed, 1));
            c.searchAutomatically();
            for (TrackPoint p : trip(LONG, 600 + seed)) {
                c.add(p);
            }
            check("differs " + seed + ": selection", RecordingSnapshot.SELECTION_NOT_FOUND,
                    c.selection());
            check("differs " + seed + ": no match", null, c.match());
        }
    }

    // The user selected the track. The comparison stays, also off the route.
    private static void aDifferentManualReferenceStays() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            TripComparison c = comparison("loop", walk(LOOP, 700 + seed, 1));
            c.setReference("loop", false);
            for (TrackPoint p : trip(LONG, 800 + seed)) {
                c.add(p);
            }
            check("manual stays " + seed + ": selection", RecordingSnapshot.SELECTION_MANUAL,
                    c.selection());
            check("manual stays " + seed + ": track", "loop", c.referenceId());
        }
    }

    // A selection after the finish gives the points again. The finish is in
    // the past, thus the device must not vibrate at the next point.
    private static void aSelectionAfterTheFinishGivesNoSignal() {
        TripComparison c = comparison("north", walk(NORTH, 11, 1));
        List<TrackPoint> trip = trip(NORTH, 12);
        List<TrackPoint> before = trip.subList(0, trip.size() - 1);
        c.select("north", false, false, before);
        check("late: finish signaled", true, c.finishSignaled());
        check("late: next point", false, c.add(trip.get(trip.size() - 1)));
    }

    // A replay gives the points of a saved track. That track agrees with
    // itself, thus it must not be a candidate.
    private static void theReplayedTrackIsNeverItsOwnReference() {
        Track saved = walk(NORTH, 13, 1);
        TripComparison c = comparison("north", saved);
        c.setExcluded("north");
        c.searchAutomatically();
        for (TrackPoint p : saved.points) {
            c.add(p);
        }
        check("excluded: selection", RecordingSnapshot.SELECTION_NOT_FOUND, c.selection());
        check("excluded: no track", null, c.referenceId());
        c.reset(Profile.WALKING);
        c.searchAutomatically();
        c.add(saved.points.get(0));
        check("excluded after reset", RecordingSnapshot.SELECTION_NOT_FOUND, c.selection());
        // A new trip is not a replay. The track is a candidate again.
        c.startTrip(Profile.WALKING);
        c.searchAutomatically();
        c.add(saved.points.get(0));
        check("new trip: candidate", RecordingSnapshot.SELECTION_TEMPORARY, c.selection());
    }

    // A deleted file gives an exception in the source. The recording must
    // continue with no comparison.
    private static void anUnreadableTrackGivesNoComparison() {
        TripComparison c = comparison("north", walk(NORTH, 14, 1));
        c.setReference("deleted", false);
        check("unreadable: selection", RecordingSnapshot.SELECTION_OFF, c.selection());
        boolean any = false;
        for (TrackPoint p : trip(NORTH, 15)) {
            any |= c.add(p);
        }
        check("unreadable: no signal", false, any);
    }

    // Without addPause, a stop of ten minutes is ten minutes behind the
    // reference track at the finish. The other pause tests compare with it.
    private static void aPauseCountsWithNoAddPause() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            TripComparison c = comparison("ref", walk(NORTH_1200, 900 + seed, 1));
            c.setReference("ref", false);
            List<TrackPoint> trip = pausedTrip(1000 + seed);
            for (TrackPoint p : trip) {
                c.add(p);
            }
            near("no addPause " + seed, PAUSE_MS, finishDelta(c), 30_000);
        }
    }

    // After Stop and Continue, the service calls addPause with the time of
    // the pause. The difference at the finish then does not count it.
    private static void aPauseDoesNotCountWithAddPause() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            TripComparison c = comparison("ref", walk(NORTH_1200, 900 + seed, 1));
            c.setReference("ref", false);
            addWithPause(c, pausedTrip(1000 + seed));
            near("addPause " + seed, 0, finishDelta(c), 30_000);
        }
    }

    // A new selection gives all points again. The points after the pause
    // must get the same shift again.
    private static void aSelectionKeepsThePause() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            TripComparison c = comparison("ref", walk(NORTH_1200, 900 + seed, 1));
            c.setReference("ref", false);
            List<TrackPoint> trip = pausedTrip(1000 + seed);
            addWithPause(c, trip);
            long before = finishDelta(c);
            c.select("ref", false, false, trip);
            near("select " + seed, before, finishDelta(c), 1000);
        }
    }

    // A new trip has no pause of the trip before it.
    private static void startTripStartsAgain() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            TripComparison c = comparison("ref", walk(NORTH_1200, 900 + seed, 1));
            c.setReference("ref", false);
            addWithPause(c, pausedTrip(1000 + seed));
            c.startTrip(Profile.WALKING);
            c.setReference("ref", false);
            List<TrackPoint> trip = TestTracks.recorded(TestTracks.noisy(TestTracks.ideal(
                    NORTH_1200, 1.4, TestTracks.START_MS + 11 * DAY_MS, 0), 1100 + seed, SIGMA),
                    Profile.WALKING);
            for (TrackPoint p : trip) {
                c.add(p);
            }
            near("cleared " + seed, 0, finishDelta(c), 30_000);
        }
    }

    // The trip follows route A, then turns onto route B. The comparison with
    // A stops, and a new search finds B.
    private static void aTripThatLeavesItsRouteFindsTheNextRoute() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            TripComparison c = twoRoutes(seed);
            c.searchAutomatically();
            boolean sawA = false;
            TrackPoint stop = null;
            double waitM = -1;
            for (TrackPoint p : walkTrip(A_THEN_B, 1700 + seed)) {
                c.add(p);
                sawA |= "a".equals(c.referenceId());
                if (stop == null && sawA && c.selection() == RecordingSnapshot.SELECTION_NOT_FOUND) {
                    stop = p;
                } else if (stop != null && waitM < 0
                        && c.selection() != RecordingSnapshot.SELECTION_NOT_FOUND) {
                    waitM = Geo.distanceM(stop, p);
                }
            }
            check("next route " + seed + ": A first", true, sawA);
            // The new search waits 10 off-route limits after the stop.
            near("next route " + seed + ": wait", 250, waitM, 20);
            check("next route " + seed + ": B", "b", c.referenceId());
            check("next route " + seed + ": finished", true, c.match() != null
                    && c.match().state == TrackMatcher.State.FINISHED);
            near("next route " + seed + ": difference", 0, finishDelta(c), 60_000);
        }
    }

    // The user selected no comparison. The trip never searches. Also a new
    // trip after an automatic trip starts with no comparison.
    private static void noComparisonNeverSearches() {
        TripComparison c = twoRoutes(1);
        c.select(null, false, false, new ArrayList<TrackPoint>());
        for (TrackPoint p : walkTrip(A_THEN_B, 1801)) {
            c.add(p);
        }
        check("no comparison", RecordingSnapshot.SELECTION_OFF, c.selection());
        c = twoRoutes(2);
        c.searchAutomatically();
        for (TrackPoint p : walkTrip(A_THEN_B, 1803)) {
            c.add(p);
        }
        c.startTrip(Profile.WALKING);
        for (TrackPoint p : walkTrip(A_THEN_B, 1804)) {
            c.add(p);
        }
        check("new trip", RecordingSnapshot.SELECTION_OFF, c.selection());
    }

    // The user selected A. The comparison stays with A to the end.
    private static void aManualReferenceNeverSearches() {
        TripComparison c = twoRoutes(1);
        c.setReference("a", false);
        for (TrackPoint p : walkTrip(A_THEN_B, 1802)) {
            c.add(p);
        }
        check("manual: selection", RecordingSnapshot.SELECTION_MANUAL, c.selection());
        check("manual: track", "a", c.referenceId());
    }

    // The trip starts 400 m before route A. No route is near the first
    // point. A later search finds A.
    private static void aTripFromFarFindsTheRouteLater() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            TripComparison c = twoRoutes(seed);
            c.searchAutomatically();
            List<TrackPoint> trip = walkTrip(new double[][] {{0, -400}, {0, 1000}}, 1900 + seed);
            c.add(trip.get(0));
            check("from far " + seed + ": first", RecordingSnapshot.SELECTION_NOT_FOUND,
                    c.selection());
            for (int i = 1; i < trip.size(); i++) {
                c.add(trip.get(i));
            }
            check("from far " + seed + ": track", "a", c.referenceId());
        }
    }

    // The trip pauses at 380 m on route A, then the comparison with A stops,
    // and the trip joins B. The stop must not lose the count of the points.
    // Else the count gets to the index of the pause again after 380 m. That
    // is in the middle of B, and the difference at the finish of B jumps by
    // the pause.
    private static void aPauseBeforeAStopStillDoesNotCount() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            TripComparison c = twoRoutes(seed);
            c.searchAutomatically();
            List<TrackPoint> walked = walkTrip(A_THEN_B, 2000 + seed);
            List<TrackPoint> trip = new ArrayList<>();
            int pauseAt = -1;
            for (TrackPoint p : walked) {
                boolean after = Geo.distanceM(walked.get(0), p) >= 380;
                if (after && pauseAt < 0) {
                    pauseAt = trip.size();
                }
                trip.add(after ? new TrackPoint(p.latitude, p.longitude, p.timeMs + PAUSE_MS,
                        p.elevationM, p.accuracyM) : p);
            }
            for (int i = 0; i < trip.size(); i++) {
                if (i == pauseAt) {
                    c.addPause(PAUSE_MS);
                }
                c.add(trip.get(i));
            }
            check("pause and stop " + seed + ": B", "b", c.referenceId());
            near("pause and stop " + seed + ": difference", 0, finishDelta(c), 60_000);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    // Holds the tracks in a map. near() gives each track, because the
    // caller does the precise check.
    private static final class MapSource implements TrackSource {
        final Map<String, Track> tracks = new HashMap<>();

        @Override
        public List<String> near(TrackPoint fix, double limitM) {
            return new ArrayList<>(tracks.keySet());
        }

        @Override
        public Track load(String id) throws IOException {
            Track t = tracks.get(id);
            if (t == null) {
                throw new IOException("no track " + id);
            }
            return t;
        }
    }

    // A walk on the route of 1200 m at the speed of the reference track,
    // with a pause of PAUSE_MS at 600 m. Each point after the pause has
    // PAUSE_MS more.
    private static List<TrackPoint> pausedTrip(long seed) {
        List<TrackPoint> walked = TestTracks.recorded(TestTracks.noisy(TestTracks.ideal(
                NORTH_1200, 1.4, TestTracks.START_MS + 10 * DAY_MS, 0), seed, SIGMA),
                Profile.WALKING);
        List<TrackPoint> out = new ArrayList<>();
        boolean after = false;
        for (TrackPoint p : walked) {
            after |= Geo.distanceM(walked.get(0), p) >= 600;
            out.add(after ? new TrackPoint(p.latitude, p.longitude, p.timeMs + PAUSE_MS,
                    p.elevationM, p.accuracyM) : p);
        }
        return out;
    }

    // Gives the points to add(), with addPause before the first point
    // after the pause, the same as TrackService.
    private static void addWithPause(TripComparison c, List<TrackPoint> trip) {
        for (int i = 0; i < trip.size(); i++) {
            if (i > 0 && trip.get(i).timeMs - trip.get(i - 1).timeMs > PAUSE_MS / 2) {
                c.addPause(PAUSE_MS);
            }
            c.add(trip.get(i));
        }
    }

    // The difference at the finish. The trip must reach the finish.
    private static long finishDelta(TripComparison c) {
        if (c.match() == null || c.match().state != TrackMatcher.State.FINISHED) {
            return Long.MIN_VALUE;
        }
        return c.match().deltaTimeMs;
    }

    private static void near(String name, double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + " +- " + tolerance
                    + ", got " + actual);
        }
    }

    // A source with the two routes A and B.
    private static TripComparison twoRoutes(long seed) {
        MapSource source = new MapSource();
        source.tracks.put("a", walk(ROUTE_A, 1500 + seed, 1));
        source.tracks.put("b", walk(ROUTE_B, 1600 + seed, 2));
        TripComparison c = new TripComparison(source);
        c.reset(Profile.WALKING);
        return c;
    }

    // A trip at the speed of the saved tracks, on day 10.
    private static List<TrackPoint> walkTrip(double[][] corners, long seed) {
        return TestTracks.recorded(TestTracks.noisy(TestTracks.ideal(corners, 1.4,
                TestTracks.START_MS + 10 * DAY_MS, 0), seed, SIGMA), Profile.WALKING);
    }

    private static TripComparison comparison(String id, Track track) {
        MapSource source = new MapSource();
        source.tracks.put(id, track);
        TripComparison c = new TripComparison(source);
        c.reset(Profile.WALKING);
        return c;
    }

    private static Track walk(double[][] corners, long seed, int day) {
        return new Track("t" + seed, Profile.WALKING, TestTracks.recorded(TestTracks.noisy(
                TestTracks.ideal(corners, 1.4, TestTracks.START_MS + day * DAY_MS, 0),
                seed, SIGMA), Profile.WALKING));
    }

    // The current trip, on day 10, after the saved tracks.
    private static List<TrackPoint> trip(double[][] corners, long seed) {
        return TestTracks.recorded(TestTracks.noisy(TestTracks.ideal(corners, 1.5,
                TestTracks.START_MS + 10 * DAY_MS, 0), seed, SIGMA), Profile.WALKING);
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
