package net.basov.retrace.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The tests of TrackImport and LimitedInputStream
// ----------------------------------------------------------------------
public final class TrackImportTest {

    private static int failures = 0;

    private static final double[][] ROUTE = {{0, 0}, {800, 0}, {800, 600}};
    private static final double SIGMA = 4;

    public static void main(String[] args) throws IOException {
        theGuessFollowsTheSpeed();
        aStopDoesNotChangeTheGuess();
        theProfileOfTheFileWins();
        aRouteWithNoTimeIsRefused();
        pointsInTheWrongSequenceAreRemoved();
        oneTimedPointIsNotATrack();
        theStreamStopsAtTheLimit();

        if (failures > 0) {
            System.out.println("TrackImportTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("TrackImportTest: every check passed");
    }

    // Each profile has a typical speed. A noisy track at that speed must get
    // that profile, with many seeds.
    private static void theGuessFollowsTheSpeed() {
        Object[][] cases = {
                {Profile.WALKING, 1.4},
                {Profile.RUNNING, 3.0},
                {Profile.CYCLING, 6.0},
                {Profile.DRIVING, 15.0},
        };
        for (Object[] c : cases) {
            Profile p = (Profile) c[0];
            double speed = (Double) c[1];
            int wrong = 0;
            for (long seed = 1; seed <= 50; seed++) {
                List<TrackPoint> points = noisy(speed, seed, 0, p);
                if (TrackImport.guessProfile(points) != p) {
                    wrong++;
                }
            }
            check("guess " + p.id + ": wrong guesses", 0, wrong);
        }
    }

    // A walk with a long wait at the start. The points of the wait have no
    // speed, thus the guess stays walking.
    private static void aStopDoesNotChangeTheGuess() {
        List<TrackPoint> points = noisy(1.4, 7, 600, Profile.WALKING);
        check("stop: walking", Profile.WALKING, TrackImport.guessProfile(points));
    }

    // A Retrace GPX file has the profile. The check keeps it, also if the
    // speed tells a different profile.
    private static void theProfileOfTheFileWins() {
        Track raw = new Track("x", Profile.RUNNING, noisy(1.4, 8, 0, Profile.WALKING));
        TrackImport.Result r = TrackImport.check(raw);
        check("file profile: ok", TrackImport.OK, r.error);
        check("file profile: known", true, r.profileKnown);
        check("file profile: running", Profile.RUNNING, r.track.profile);
        check("file profile: all points", raw.points.size(), r.track.points.size());

        TrackImport.Result g = TrackImport.check(new Track("y", null, raw.points));
        check("no profile: not known", false, g.profileKnown);
        check("no profile: guess", Profile.WALKING, g.profile);
    }

    // A planned route has no time. It cannot be a reference track.
    private static void aRouteWithNoTimeIsRefused() {
        List<TrackPoint> points = new ArrayList<>();
        for (TrackPoint p : noisy(1.4, 9, 0, Profile.WALKING)) {
            points.add(new TrackPoint(p.latitude, p.longitude, TrackPoint.NO_TIME,
                    p.elevationM, p.accuracyM));
        }
        check("no time", TrackImport.NO_TIME, TrackImport.check(new Track("r", null, points)).error);
        check("one point", TrackImport.TOO_FEW_POINTS,
                TrackImport.check(new Track("r", null, points.subList(0, 1))).error);
    }

    // Two points swap places, and one point repeats the time before it. The
    // check removes the two points that do not go forward in time.
    private static void pointsInTheWrongSequenceAreRemoved() {
        List<TrackPoint> points = new ArrayList<>(noisy(1.4, 10, 0, Profile.WALKING));
        int n = points.size();
        TrackPoint a = points.get(10);
        points.set(10, points.get(11));
        points.set(11, a);
        TrackPoint b = points.get(20);
        points.set(21, new TrackPoint(b.latitude, b.longitude, b.timeMs, b.elevationM, b.accuracyM));
        TrackImport.Result r = TrackImport.check(new Track("s", Profile.WALKING, points));
        check("sequence: ok", TrackImport.OK, r.error);
        check("sequence: two removed", n - 2, r.track.points.size());
        boolean increasing = true;
        for (int i = 1; i < r.track.points.size(); i++) {
            increasing &= r.track.points.get(i).timeMs > r.track.points.get(i - 1).timeMs;
        }
        check("sequence: increasing", true, increasing);
    }

    private static void oneTimedPointIsNotATrack() {
        List<TrackPoint> points = new ArrayList<>();
        points.add(TestTracks.point(0, 0, TestTracks.START_MS));
        points.add(TestTracks.point(10, 0, TrackPoint.NO_TIME));
        points.add(TestTracks.point(20, 0, TrackPoint.NO_TIME));
        check("one timed point", TrackImport.NO_TIME,
                TrackImport.check(new Track("t", null, points)).error);
    }

    // The stream gives the bytes up to the limit, and stops after it.
    private static void theStreamStopsAtTheLimit() throws IOException {
        byte[] data = new byte[1000];
        check("limit: exact size", 1000, readAll(new LimitedInputStream(
                new ByteArrayInputStream(data), 1000)));
        boolean stopped = false;
        try {
            readAll(new LimitedInputStream(new ByteArrayInputStream(data), 999));
        } catch (LimitedInputStream.TooLargeException e) {
            stopped = true;
        }
        check("limit: too large", true, stopped);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static List<TrackPoint> noisy(double speed, long seed, int waitS, Profile p) {
        return TestTracks.recorded(TestTracks.noisy(TestTracks.ideal(ROUTE, speed,
                TestTracks.START_MS, waitS), seed, SIGMA), p);
    }

    private static int readAll(InputStream in) throws IOException {
        byte[] buffer = new byte[64];
        int total = 0;
        int single = in.read();
        if (single >= 0) {
            total++;
        }
        for (int n; (n = in.read(buffer, 0, buffer.length)) > 0; ) {
            total += n;
        }
        return total;
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
