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
