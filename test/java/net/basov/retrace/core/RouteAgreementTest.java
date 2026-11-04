package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The tests of RouteAgreement
// ----------------------------------------------------------------------
//
// The case of the maintainer: two routes with the same start. One is a short
// loop, the other goes far away. Each trip on one route selected the other
// route, and the comparison was off route, or finished, for the rest of
// the trip. The tests use invented routes of the same form.
public final class RouteAgreementTest {

    private static int failures = 0;

    private static final long DAY_MS = 86400000L;
    private static final double SIGMA = 4;

    // A short loop to the north and back to the start.
    private static final double[][] LOOP = {{0, 0}, {0, 40}, {-60, 100}, {-120, 40},
            {-100, -40}, {60, -40}, {60, 0}, {5, 0}};
    // A long route with the same first 40 m, then to the west and south.
    private static final double[][] LONG = {{0, 0}, {0, 40}, {-300, 60}, {-900, -500}};
    private static final double[][] STRAIGHT = {{0, 0}, {0, 1200}};

    public static void main(String[] args) {
        aRepeatOfTheRouteAgrees();
        aDifferentWayDiffers();
        aShortDetourOnALongRouteAgrees();
        aFinishAfterAShortPartDiffers();
        aFinishAfterMostOfTheRouteAgrees();
        theLongTripStopsTheComparisonWithTheLoop();
        theLoopFindsNoReferenceInTheLongRoute();

        if (failures > 0) {
            System.out.println("RouteAgreementTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("RouteAgreementTest: every check passed");
    }

    // The main risk: a stop of a good comparison. Repeats of the same route
    // with GPS noise must agree for each profile and many seeds.
    private static void aRepeatOfTheRouteAgrees() {
        double[] speeds = {1.4, 3, 6, 15};
        Profile[] profiles = Profile.values();
        for (int k = 0; k < profiles.length; k++) {
            int differ = 0;
            for (long seed = 1; seed <= 20; seed++) {
                Track ref = track(STRAIGHT, speeds[k], 100 + seed, 1, profiles[k]);
                Track trip = track(STRAIGHT, speeds[k] * 1.1, 200 + seed, 5, profiles[k]);
                if (run(ref, trip, profiles[k]).differs()) {
                    differ++;
                }
            }
            check(profiles[k] + " repeats", 0, differ);
        }
    }

    // The trip leaves the route after 40 m and does not come back.
    private static void aDifferentWayDiffers() {
        Track ref = track(LOOP, 1.4, 1, 1, Profile.WALKING);
        Track trip = track(LONG, 1.4, 2, 5, Profile.WALKING);
        RouteAgreement a = run(ref, trip, Profile.WALKING);
        check("other way", true, a.differs());
        double lost = RouteAgreement.LOST_FACTOR * Profile.WALKING.offRouteM;
        check("off at least the limit", true, a.offRouteM() >= lost);
        check("off more than on", true, a.offRouteM() > a.onRouteM());
    }

    // A closed street on a long route: a detour of 150 m off the route, in
    // the middle. The comparison continues.
    private static void aShortDetourOnALongRouteAgrees() {
        double[][] detour = {{0, 0}, {0, 500}, {75, 500}, {75, 650}, {0, 650}, {0, 1200}};
        for (long seed = 1; seed <= 20; seed++) {
            Track ref = track(STRAIGHT, 1.4, 300 + seed, 1, Profile.WALKING);
            Track trip = track(detour, 1.4, 400 + seed, 5, Profile.WALKING);
            RouteAgreement a = run(ref, trip, Profile.WALKING);
            check("detour " + seed, false, a.differs());
        }
    }

    // The trip joins the route at 90 percent and reaches the finish. It
    // covered a small part of the route only.
    private static void aFinishAfterAShortPartDiffers() {
        Track ref = track(STRAIGHT, 1.4, 5, 1, Profile.WALKING);
        Track trip = track(new double[][] {{0, 1080}, {0, 1200}}, 1.4, 6, 5, Profile.WALKING);
        check("short part", true, run(ref, trip, Profile.WALKING).differs());
    }

    // A trip from the middle is a correct use: the user started late. With
    // 70 percent of the route, the comparison continues to the finish.
    private static void aFinishAfterMostOfTheRouteAgrees() {
        for (long seed = 1; seed <= 20; seed++) {
            Track ref = track(STRAIGHT, 1.4, 500 + seed, 1, Profile.WALKING);
            Track trip = track(new double[][] {{0, 360}, {0, 1200}}, 1.4, 600 + seed, 5,
                    Profile.WALKING);
            check("most " + seed, false, run(ref, trip, Profile.WALKING).differs());
        }
    }

    // The case of the maintainer, with the steps of TrackService: the long
    // trip selects the loop, because the loop is short. Then the comparison
    // must stop.
    private static void theLongTripStopsTheComparisonWithTheLoop() {
        int stopped = 0;
        for (long seed = 1; seed <= 20; seed++) {
            Track loop = track(LOOP, 1.4, 700 + seed, 1, Profile.WALKING);
            Track trip = track(LONG, 1.4, 800 + seed, 5, Profile.WALKING);
            if (selectAndFollow(loop, trip) != Ending.COMPARED_TO_THE_END) {
                stopped++;
            }
        }
        check("long trip with the loop", 20, stopped);
    }

    // The other trip of the case: the loop must not keep the long route as
    // its reference track.
    private static void theLoopFindsNoReferenceInTheLongRoute() {
        int compared = 0;
        for (long seed = 1; seed <= 20; seed++) {
            Track longRoute = track(LONG, 1.4, 900 + seed, 1, Profile.WALKING);
            Track trip = track(LOOP, 1.4, 1000 + seed, 5, Profile.WALKING);
            if (selectAndFollow(longRoute, trip) == Ending.COMPARED_TO_THE_END) {
                compared++;
            }
        }
        check("loop with the long route", 0, compared);
    }

    private enum Ending {
        NO_SELECTION,
        STOPPED,
        COMPARED_TO_THE_END
    }

    // The automatic selection with one saved track, then the comparison with
    // its check, the same as TrackService.
    private static Ending selectAndFollow(Track saved, Track trip) {
        List<TrackPoint> points = trip.points;
        List<ReferenceSelector.Candidate> near = ReferenceSelector.nearRoute(
                listOf(new ReferenceSelector.Candidate("saved", saved)), Profile.WALKING,
                points.get(0));
        if (near.isEmpty()) {
            return Ending.NO_SELECTION;
        }
        ReferenceSelector selector = new ReferenceSelector(near, Profile.WALKING, points.get(0));
        TrackMatcher matcher = null;
        RouteAgreement agreement = null;
        for (TrackPoint p : points) {
            if (matcher == null) {
                ReferenceSelector.State state = selector.update(p);
                if (state == ReferenceSelector.State.NONE) {
                    return Ending.NO_SELECTION;
                }
                if (state == ReferenceSelector.State.SELECTED) {
                    matcher = selector.selectedMatcher();
                    agreement = selector.selectedAgreement();
                }
            } else {
                agreement.add(p, matcher.update(p));
                if (agreement.differs()) {
                    return Ending.STOPPED;
                }
            }
        }
        return matcher == null ? Ending.NO_SELECTION : Ending.COMPARED_TO_THE_END;
    }

    // The check of one trip against one reference track, from the start.
    private static RouteAgreement run(Track ref, Track trip, Profile profile) {
        TrackMatcher m = new TrackMatcher(ref, profile);
        RouteAgreement a = new RouteAgreement(profile);
        for (TrackPoint p : trip.points) {
            a.add(p, m.update(p));
        }
        return a;
    }

    private static Track track(double[][] corners, double speed, long seed, int day,
                               Profile profile) {
        return new Track("t" + seed, profile, TestTracks.recorded(TestTracks.noisy(
                TestTracks.ideal(corners, speed, TestTracks.START_MS + day * DAY_MS, 0),
                seed, SIGMA), profile));
    }

    private static List<ReferenceSelector.Candidate> listOf(ReferenceSelector.Candidate c) {
        List<ReferenceSelector.Candidate> list = new ArrayList<>();
        list.add(c);
        return list;
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
