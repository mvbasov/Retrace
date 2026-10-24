package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// ----------------------------------------------------------------------
// The tests of WayCandidates
// ----------------------------------------------------------------------
public final class WayCandidatesTest {

    private static int failures = 0;

    private static final double SIGMA = 4;
    private static final long DAY_MS = 86400000L;

    private static final double[][] ROUTE = {{0, 0}, {800, 0}, {800, 600}};
    private static final double[][] BACK = {{800, 600}, {800, 0}, {0, 0}};
    private static final double[][] OTHER = {{0, 0}, {300, 0}, {300, -600}};

    public static void main(String[] args) {
        aNewWayListsTheBaseFirstAndTheTracksOfTheRoute();
        eachMemberIsListedAlsoWithoutAMatch();
        theMembersAfterAnAutomaticAdditionAreListed();
        aMemberKeepsItsDirection();
        aWayAnotherProfileAndAShortTrackAreNeverListed();

        if (failures > 0) {
            System.out.println("WayCandidatesTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("WayCandidatesTest: every check passed");
    }

    // The dialog of a new way shows the track of the menu first, also if
    // the list gives it later. A track in the opposite direction is marked.
    // A track of a different route is not shown. Each line is checked.
    private static void aNewWayListsTheBaseFirstAndTheTracksOfTheRoute() {
        Track base = walk(ROUTE, 1.4, 11, 1, 0);
        WayCandidates c = new WayCandidates(base, "base.gpx");
        check("new: route track", true, c.offer("a.gpx", walk(ROUTE, 1.3, 12, 2, 0)));
        check("new: other route", false, c.offer("other.gpx", walk(OTHER, 1.3, 13, 3, 0)));
        check("new: back", true, c.offer("back.gpx", walk(BACK, 1.3, 14, 4, 0)));
        check("new: base", true, c.offer("base.gpx", base));

        List<WayCandidates.Item> items = c.items();
        check("new: names", "[base.gpx, a.gpx, back.gpx]", names(items));
        check("new: back is reversed", true, items.get(2).reversed);
        check("new: route is forward", false, items.get(1).reversed);
        for (WayCandidates.Item item : items) {
            check("new: " + item.name + " checked", true, item.checked);
        }
    }

    // The way keeps the file names of its members. Thus the dialog shows
    // each member also if the match against the way fails. Here the line
    // of the way is 5 km away, thus no track matches it. Before the fix,
    // the dialog then told that the way needs two tracks.
    private static void eachMemberIsListedAlsoWithoutAMatch() {
        List<TrackPoint> far = new ArrayList<>();
        for (TrackPoint p : walk(ROUTE, 1.4, 21, 1, 0).points) {
            far.add(TestTracks.shift(p, 5000, 5000));
        }
        Track way = new Track("way", Profile.WALKING, far,
                Arrays.asList("m1.gpx", "m2.gpx", "m3.gpx"));
        WayCandidates c = new WayCandidates(way, "way.gpx");
        check("far way: no match", WayBuilder.REJECT_NO_JOIN,
                WayBuilder.match(way, walk(ROUTE, 1.3, 22, 2, 0), Profile.WALKING).reject);
        check("far way: m1", true, c.offer("m1.gpx", walk(ROUTE, 1.3, 22, 2, 0)));
        check("far way: not a member", false, c.offer("x.gpx", walk(ROUTE, 1.2, 23, 3, 0)));
        check("far way: m2", true, c.offer("m2.gpx", walk(ROUTE, 1.5, 24, 4, 0)));
        check("far way: m3", true, c.offer("m3.gpx", walk(ROUTE, 1.4, 25, 5, 0)));
        List<WayCandidates.Item> items = c.items();
        check("far way: names", "[m1.gpx, m2.gpx, m3.gpx]", names(items));
        check("far way: enough for a way", true, items.size() >= WayBuilder.MIN_TRACKS);
        for (WayCandidates.Item item : items) {
            check("far way: " + item.name + " checked", true, item.checked);
        }
    }

    // The case of the maintainer: a way of two tracks, then a trip that
    // TrackService adds automatically. WayUpdater builds the way again with
    // the first member as the base. The trip waits two minutes at the
    // start and three minutes at the finish, the same as a real trip. The
    // dialog must show the three members, checked. A track of the route
    // that is not a member is shown, not checked.
    private static void theMembersAfterAnAutomaticAdditionAreListed() {
        List<Track> tracks = new ArrayList<>();
        tracks.add(walk(ROUTE, 1.3, 31, 1, 0));
        tracks.add(walk(ROUTE, 1.4, 32, 2, 0));
        WayBuilder.Result made = WayBuilder.build("way", Profile.WALKING, tracks, 1);
        check("auto: made", 2, made.used.size());

        tracks.add(withWaitAtTheFinish(walk(ROUTE, 1.35, 33, 3, 120), 180, 33));
        List<String> names = Arrays.asList("t1.gpx", "t2.gpx", "trip.gpx");
        WayBuilder.Result added = WayBuilder.build("way", Profile.WALKING, tracks, 0);
        check("auto: trip used", true, added.used.contains(2));
        List<String> members = new ArrayList<>();
        for (int i : added.used) {
            members.add(names.get(i));
        }
        Track way = added.way.withMembers(members);

        WayCandidates c = new WayCandidates(way, "way.gpx");
        check("auto: newer track", true, c.offer("new.gpx", walk(ROUTE, 1.25, 34, 4, 0)));
        for (int i = 0; i < names.size(); i++) {
            check("auto: " + names.get(i), true, c.offer(names.get(i), tracks.get(i)));
        }
        check("auto: other route", false, c.offer("other.gpx", walk(OTHER, 1.3, 35, 5, 0)));
        List<WayCandidates.Item> items = c.items();
        check("auto: names", "[t1.gpx, t2.gpx, trip.gpx, new.gpx]", names(items));
        for (int i = 0; i < 3; i++) {
            check("auto: member " + i + " checked", true, items.get(i).checked);
        }
        check("auto: new track not checked", false, items.get(3).checked);
    }

    // The dialog marks a member in the opposite direction.
    private static void aMemberKeepsItsDirection() {
        List<Track> tracks = new ArrayList<>();
        tracks.add(walk(ROUTE, 1.3, 41, 1, 0));
        tracks.add(walk(ROUTE, 1.4, 42, 2, 0));
        tracks.add(walk(BACK, 1.35, 43, 3, 0));
        WayBuilder.Result r = WayBuilder.build("way", Profile.WALKING, tracks, 0);
        check("direction: all used", 3, r.used.size());
        Track way = r.way.withMembers(Arrays.asList("a.gpx", "b.gpx", "back.gpx"));
        WayCandidates c = new WayCandidates(way, "way.gpx");
        c.offer("a.gpx", tracks.get(0));
        c.offer("back.gpx", tracks.get(2));
        List<WayCandidates.Item> items = c.items();
        check("direction: forward member", false, items.get(0).reversed);
        check("direction: back member", true, items.get(1).reversed);
    }

    // A way is never a member of a way. A track of a different profile, or
    // with fewer than 2 points, cannot be a member. A member name does not
    // change these rules.
    private static void aWayAnotherProfileAndAShortTrackAreNeverListed() {
        Track base = walk(ROUTE, 1.4, 51, 1, 0);
        Track way = base.withMembers(Arrays.asList("w.gpx", "run.gpx", "one.gpx"));
        WayCandidates c = new WayCandidates(way, "way.gpx");
        check("never: a way", false, c.offer("w.gpx", walk(ROUTE, 1.3, 52, 2, 0)
                .withMembers(Arrays.asList("x.gpx"))));
        Track walkTrack = walk(ROUTE, 1.3, 53, 3, 0);
        check("never: another profile", false,
                c.offer("run.gpx", new Track("run", Profile.RUNNING, walkTrack.points)));
        check("never: one point", false, c.offer("one.gpx",
                new Track("one", Profile.WALKING, walkTrack.points.subList(0, 1))));
        check("never: empty list", 0, c.items().size());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Track walk(double[][] corners, double speed, long seed, int day, int waitS) {
        return new Track("w" + seed, Profile.WALKING, TestTracks.recorded(TestTracks.noisy(
                TestTracks.ideal(corners, speed, TestTracks.START_MS + day * DAY_MS, waitS),
                seed, SIGMA), Profile.WALKING));
    }

    // Adds a wait at the last point. The GPS noise continues during the
    // wait, and RecordFilter keeps the fixes that move enough.
    private static Track withWaitAtTheFinish(Track track, int waitS, long seed) {
        List<TrackPoint> ideal = new ArrayList<>();
        TrackPoint last = track.points.get(track.points.size() - 1);
        for (int s = 1; s <= waitS; s++) {
            ideal.add(new TrackPoint(last.latitude, last.longitude, last.timeMs + s * 1000L,
                    Double.NaN, Double.NaN));
        }
        List<TrackPoint> all = new ArrayList<>(track.points);
        all.addAll(TestTracks.recorded(TestTracks.noisy(ideal, seed, SIGMA), Profile.WALKING));
        return new Track(track.name, track.profile, all);
    }

    private static String names(List<WayCandidates.Item> items) {
        List<String> out = new ArrayList<>();
        for (WayCandidates.Item item : items) {
            out.add(item.name);
        }
        return out.toString();
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
