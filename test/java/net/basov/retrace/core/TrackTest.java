package net.basov.retrace.core;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The test of the track classes
// ----------------------------------------------------------------------
//
// The test covers Geo, Track, Profile, RecordFilter and PointLog.
public final class TrackTest {

    private static int failures = 0;

    // A small step to the north: one millionth of a degree is about 0.11 m.
    private static final double MICRO_DEGREE_M = 0.111195;

    public static void main(String[] args) throws IOException {
        distances();
        lengthAndDuration();
        profileIds();
        aReversedTrackGoesBack();
        theFilterDropsBadFixes();
        theFilterAddsTheDistance();
        theLogKeepsEachPoint();
        theLogIgnoresABrokenLine();
        theLogRejectsAWrongHeader();

        if (failures > 0) {
            System.out.println("TrackTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("TrackTest: every check passed");
    }

    // One degree of latitude is 111195 m on a sphere with the mean radius.
    // The distance from Moscow to Saint Petersburg is about 634 km.
    private static void distances() {
        near("one degree", 111195.08, Geo.distanceM(10, 20, 11, 20), 0.01);
        near("zero", 0, Geo.distanceM(55.75, 37.61, 55.75, 37.61), 1e-9);
        near("Moscow to Saint Petersburg", 634000, Geo.distanceM(55.7558, 37.6173, 59.9343, 30.3351), 2000);
        near("across the date line", 111195.08, Geo.distanceM(0, 179.5, 0, -179.5), 0.01);
    }

    // The list of tracks shows the length and the duration.
    private static void lengthAndDuration() {
        List<TrackPoint> points = new ArrayList<>();
        points.add(point(0, 0, 1000));
        points.add(point(0.001, 0, 61000));
        points.add(point(0.002, 0, TrackPoint.NO_TIME));
        points.add(point(0.003, 0, 181000));
        Track track = new Track("test", Profile.RUNNING, points);
        near("length", 3 * 111.195, track.distanceM(), 0.01);
        check("start", 1000L, track.startTimeMs());
        check("duration", 180000L, track.durationMs());

        Track noTime = new Track("x", null, points.subList(2, 3));
        check("no time duration", 0L, noTime.durationMs());
        check("empty", 0.0, new Track(null, null, new ArrayList<TrackPoint>()).distanceM());
        check("rename keeps points", 4, track.withName("other").points.size());
    }

    // The reversed track starts at the old finish, at the old start time.
    // Each step takes the same time as the step in the other direction.
    private static void aReversedTrackGoesBack() {
        List<TrackPoint> points = new ArrayList<>();
        points.add(point(0, 0, 1000));
        points.add(point(0.001, 0, 61000));
        points.add(point(0.002, 0, TrackPoint.NO_TIME));
        points.add(point(0.003, 0, 181000));
        Track track = new Track("test", Profile.RUNNING, points);
        Track back = track.reversed();
        near("reversed: first point", 0.003, back.points.get(0).latitude, 1e-12);
        check("reversed: start time", 1000L, back.startTimeMs());
        check("reversed: duration", track.durationMs(), back.durationMs());
        check("reversed: point with no time", false, back.points.get(1).hasTime());
        check("reversed: third time", 121000L, back.points.get(2).timeMs);
        check("reversed: last time", 181000L, back.points.get(3).timeMs);
        near("reversed: length", track.distanceM(), back.distanceM(), 1e-9);
        Track again = back.reversed();
        for (int i = 0; i < points.size(); i++) {
            check("twice: time " + i, points.get(i).timeMs, again.points.get(i).timeMs);
        }
    }

    // The id is in each GPX file. A change of an id breaks old files.
    private static void profileIds() {
        check("walking", Profile.WALKING, Profile.fromId("walking"));
        check("case and space", Profile.CYCLING, Profile.fromId(" Cycling "));
        check("unknown", true, Profile.fromId("hiking") == null);
        check("null", true, Profile.fromId(null) == null);
        String[] ids = {"walking", "running", "cycling", "driving"};
        for (int i = 0; i < ids.length; i++) {
            check("id " + i, ids[i], Profile.values()[i].id);
        }
    }

    // Each rule of the filter drops one type of bad fix.
    private static void theFilterDropsBadFixes() {
        RecordFilter filter = new RecordFilter(Profile.WALKING);
        check("first fix", true, filter.accept(fix(0, 1000, 5)));
        check("bad accuracy", false, filter.accept(fix(0.001, 2000, 21)));
        check("same time", false, filter.accept(fix(0.001, 1000, 5)));
        check("older time", false, filter.accept(fix(0.001, 500, 5)));
        check("too near", false, filter.accept(fix(10 * 1e-6, 3000, 5)));
        check("far enough", true, filter.accept(fix(30 * 1e-6, 4000, 5)));
        check("no accuracy", true, filter.accept(fix(60 * 1e-6, 5000, Double.NaN)));
        check("count", 3, filter.count());
    }

    // The distance of the filter is the sum of the kept steps only.
    private static void theFilterAddsTheDistance() {
        RecordFilter filter = new RecordFilter(Profile.DRIVING);
        filter.accept(fix(0, 1000, 5));
        filter.accept(fix(50 * 1e-6, 2000, 5));
        filter.accept(fix(200 * 1e-6, 3000, 5));
        near("distance", 200 * MICRO_DEGREE_M, filter.distanceM(), 0.01);
        check("last", 3000L, filter.last().timeMs);
    }

    // The log must give back each point that the service wrote.
    private static void theLogKeepsEachPoint() throws IOException {
        StringBuilder log = new StringBuilder();
        log.append(PointLog.header(Profile.CYCLING, 123456789L)).append('\n');
        TrackPoint a = new TrackPoint(55.7558123, 37.6173456, 1000, 156.5, 4);
        TrackPoint b = new TrackPoint(-33.8568, 151.2153, 2000, Double.NaN, 4);
        log.append(PointLog.line(a)).append('\n').append(PointLog.line(b)).append('\n');

        PointLog.Content content = PointLog.read(new StringReader(log.toString()));
        check("profile", Profile.CYCLING, content.profile);
        check("start", 123456789L, content.startTimeMs);
        check("count", 2, content.points.size());
        check("lat", a.latitude, content.points.get(0).latitude);
        check("lon", a.longitude, content.points.get(0).longitude);
        check("ele", a.elevationM, content.points.get(0).elevationM);
        check("no ele", true, Double.isNaN(content.points.get(1).elevationM));
        check("time", 2000L, content.points.get(1).timeMs);
    }

    // A stopped process can leave half a line at the end of the log.
    private static void theLogIgnoresABrokenLine() throws IOException {
        String log = PointLog.header(Profile.WALKING, 1) + "\n"
                + "1000 55.1 37.1 NaN\n"
                + "2000 55.2 37.2 NaN\n"
                + "3000 55.3";
        PointLog.Content content = PointLog.read(new StringReader(log));
        check("broken line", 2, content.points.size());
    }

    private static void theLogRejectsAWrongHeader() throws IOException {
        check("empty", true, PointLog.read(new StringReader("")) == null);
        check("other file", true, PointLog.read(new StringReader("<gpx>\n")) == null);
        check("other format", true,
                PointLog.read(new StringReader("RETRACE-LOG 2 walking 1\n")) == null);
        PointLog.Content unknown = PointLog.read(new StringReader("RETRACE-LOG 1 hiking 1\n"));
        check("unknown profile", Profile.WALKING, unknown.profile);
    }

    private static TrackPoint point(double lat, double lon, long time) {
        return new TrackPoint(lat, lon, time, Double.NaN, Double.NaN);
    }

    private static TrackPoint fix(double lat, long time, double accuracy) {
        return new TrackPoint(lat, 0, time, Double.NaN, accuracy);
    }

    private static void near(String name, double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }

    private static void check(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
