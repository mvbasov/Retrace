package net.basov.retrace.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// ----------------------------------------------------------------------
// The tests of the track segments and the waypoints of a track
// ----------------------------------------------------------------------
//
// A trip that continues after Stop has two track segments. A GPX file keeps them
// as two trkseg elements, and the log file keeps them with a SEGMENT line.
// The waypoints go the same way. A fault here loses data of the user.
public final class SegmentsTest {

    private static int failures = 0;

    public static void main(String[] args) throws IOException {
        theStepBetweenSegmentsIsNotDistance();
        theSegmentsListThePoints();
        aBadStartIsNotUsed();
        theReversedTrackKeepsItsSegments();
        aNewNameKeepsTheSegmentsAndWaypoints();
        gpxKeepsSegmentsAndWaypoints();
        gpxOfAnotherApplication();
        theLogKeepsSegmentsAndWaypoints();
        anOldLogIsStillRead();
        theStepBetweenSegmentsIsAStop();
        theImportKeepsTheSegments();
        waypointNames();

        if (failures > 0) {
            System.out.println("SegmentsTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("SegmentsTest: every check passed");
    }

    // Two track segments 100 m long, with 500 m between them. The user did not
    // record the 500 m, thus the length is 200 m.
    private static void theStepBetweenSegmentsIsNotDistance() {
        Track t = twoSegments();
        near("length", 200, t.distanceM(), 0.5);
        check("starts", Arrays.asList(11), t.segmentStarts);
    }

    private static void theSegmentsListThePoints() {
        Track t = twoSegments();
        List<List<TrackPoint>> s = t.segments();
        check("count", 2, s.size());
        check("first size", 11, s.get(0).size());
        check("second size", 11, s.get(1).size());
        check("one segment", 1, new Track("a", Profile.WALKING, t.points).segments().size());
    }

    // A start at 0, outside the points or not in sequence is not a start.
    private static void aBadStartIsNotUsed() {
        Track t = twoSegments();
        Track bad = new Track("b", Profile.WALKING, t.points, new ArrayList<String>(),
                Arrays.asList(0, 11, 5, 22, 99), new ArrayList<Waypoint>());
        check("bad starts", Arrays.asList(11), bad.segmentStarts);
    }

    // The comparison in the opposite direction uses the reversed track. The
    // break between the track segments stays at the same place on the ground.
    private static void theReversedTrackKeepsItsSegments() {
        Track t = twoSegments();
        Track r = t.reversed();
        check("reversed starts", Arrays.asList(11), r.segmentStarts);
        near("reversed length", t.distanceM(), r.distanceM(), 1e-6);
        Track three = new Track("c", Profile.WALKING, t.points, new ArrayList<String>(),
                Arrays.asList(3, 11), new ArrayList<Waypoint>());
        check("three reversed", Arrays.asList(11, 19), three.reversed().segmentStarts);
    }

    // A rename, a new profile or new members must not lose data.
    private static void aNewNameKeepsTheSegmentsAndWaypoints() {
        Track t = twoSegments();
        Track n = t.withName("x").withProfile(Profile.RUNNING)
                .withMembers(Arrays.asList("m.gpx"));
        check("starts", t.segmentStarts, n.segmentStarts);
        check("waypoints", 2, n.waypoints.size());
        check("name of waypoint", "wpt02", n.waypoints.get(1).name);
        check("new waypoints", 0, t.withWaypoints(new ArrayList<Waypoint>()).waypoints.size());
    }

    private static void gpxKeepsSegmentsAndWaypoints() throws IOException {
        Track t = twoSegments();
        StringWriter out = new StringWriter();
        GpxWriter.write(t, out);
        String text = out.toString();
        check("two trkseg", 2, count(text, "<trkseg>"));
        check("wpt before trk", true, text.indexOf("<wpt") < text.indexOf("<trk>"));
        Track back = read(text);
        check("points", t.points.size(), back.points.size());
        check("starts", t.segmentStarts, back.segmentStarts);
        check("waypoints", 2, back.waypoints.size());
        Waypoint a = t.waypoints.get(0);
        Waypoint b = back.waypoints.get(0);
        check("waypoint name", a.name, b.name);
        near("waypoint lat", a.latitude, b.latitude, 1e-7);
        near("waypoint lon", a.longitude, b.longitude, 1e-7);
        check("waypoint time", a.timeMs, b.timeMs);
    }

    // Other applications write empty track segments and waypoints with no time.
    // A second trk element also starts a track segment.
    private static void gpxOfAnotherApplication() throws IOException {
        String text = "<?xml version=\"1.0\"?>\n"
                + "<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
                + "<wpt lat=\"45.1\" lon=\"10.1\"><name>Bridge &amp; gate</name></wpt>\n"
                + "<trk><name>t</name><trkseg></trkseg>\n"
                + "<trkseg><trkpt lat=\"45\" lon=\"10\"/><trkpt lat=\"45.001\" lon=\"10\"/></trkseg>\n"
                + "<trkseg></trkseg>\n"
                + "<trkseg><trkpt lat=\"45.002\" lon=\"10\"/></trkseg></trk>\n"
                + "<trk><trkseg><trkpt lat=\"45.003\" lon=\"10\"/></trkseg></trk>\n"
                + "</gpx>\n";
        Track t = read(text);
        check("other points", 4, t.points.size());
        check("other starts", Arrays.asList(2, 3), t.segmentStarts);
        check("other waypoint", "Bridge & gate", t.waypoints.get(0).name);
        check("other waypoint time", false, t.waypoints.get(0).hasTime());
        check("track name", "t", t.name);
    }

    // The service writes the log file line by line. A process that stops
    // must not lose a track segment or a waypoint.
    private static void theLogKeepsSegmentsAndWaypoints() throws IOException {
        Track t = twoSegments();
        StringBuilder log = new StringBuilder();
        log.append(PointLog.header(Profile.WALKING, 1000)).append('\n');
        // A SEGMENT line before the first point gives no empty track segment.
        log.append(PointLog.segmentLine()).append('\n');
        for (int i = 0; i < t.points.size(); i++) {
            if (i == 11) {
                log.append(PointLog.segmentLine()).append('\n');
                log.append(PointLog.segmentLine()).append('\n');
            }
            if (i == 3) {
                log.append(PointLog.waypointLine(Waypoint.at(t.points.get(3), "my\nplace"))
                        ).append('\n');
            }
            log.append(PointLog.line(t.points.get(i))).append('\n');
        }
        // The last line of a stopped process can be incomplete.
        log.append("WPT 123 45.0");
        PointLog.Content c = PointLog.read(new StringReader(log.toString()));
        check("log points", t.points.size(), c.points.size());
        check("log starts", Arrays.asList(11), c.segmentStarts);
        check("log waypoints", 1, c.waypoints.size());
        check("log waypoint name", "my place", c.waypoints.get(0).name);
        check("log waypoint time", t.points.get(3).timeMs, c.waypoints.get(0).timeMs);
    }

    // A log file of an older release has format 1 and points only.
    private static void anOldLogIsStillRead() throws IOException {
        String log = "RETRACE-LOG 1 walking 1000\n1000 45.0 10.0 NaN\n2000 45.001 10.0 NaN\n";
        PointLog.Content c = PointLog.read(new StringReader(log));
        check("old log", 2, c == null ? -1 : c.points.size());
        check("old log starts", 0, c == null ? -1 : c.segmentStarts.size());
    }

    // The user can travel from one track segment to the next in a different way.
    // Here the step is 300 m in 60 s. That step is not a speed of the trip.
    private static void theStepBetweenSegmentsIsAStop() {
        List<TrackPoint> points = new ArrayList<>();
        points.addAll(TestTracks.ideal(new double[][] {{0, 0}, {300, 0}}, 1.5,
                TestTracks.START_MS, 0));
        long later = points.get(points.size() - 1).timeMs + 60_000;
        List<TrackPoint> second = TestTracks.ideal(new double[][] {{600, 0}, {900, 0}}, 1.5,
                later, 0);
        int start = points.size();
        points.addAll(second);
        Track one = new Track("one", Profile.WALKING, points);
        Track two = new Track("two", Profile.WALKING, points, new ArrayList<String>(),
                Arrays.asList(start), new ArrayList<Waypoint>());
        TrackSpeeds s = TrackSpeeds.of(two, Profile.WALKING);
        near("mean", 1.5, s.meanMps, 0.01);
        near("max", 1.5, s.maxMps, 0.01);
        // In one track segment, the step of 300 m in 60 s counts.
        check("one segment is faster", true,
                TrackSpeeds.of(one, Profile.WALKING).meanMps > 1.6);
    }

    // The import drops points with no time. The start of a track segment
    // moves with them.
    private static void theImportKeepsTheSegments() {
        List<TrackPoint> points = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long time = i == 3 ? TrackPoint.NO_TIME : TestTracks.START_MS + i * 1000L;
            points.add(TestTracks.point(i * 10, 0, time));
        }
        Track raw = new Track("r", null, points, new ArrayList<String>(), Arrays.asList(3),
                Arrays.asList(Waypoint.at(points.get(1), "w")));
        TrackImport.Result r = TrackImport.check(raw);
        check("import points", 5, r.track.points.size());
        check("import starts", Arrays.asList(3), r.track.segmentStarts);
        check("import waypoints", 1, r.track.waypoints.size());
    }

    private static void waypointNames() {
        check("1", "wpt01", Waypoint.autoName(1));
        check("12", "wpt12", Waypoint.autoName(12));
        check("123", "wpt123", Waypoint.autoName(123));
    }

    // Two straight track segments of 100 m, 500 m apart, one point each 10 m, and
    // two waypoints.
    private static Track twoSegments() {
        List<TrackPoint> points = new ArrayList<>();
        long t = TestTracks.START_MS;
        for (int i = 0; i <= 10; i++) {
            points.add(TestTracks.point(i * 10, 0, t));
            t += 7000;
        }
        t += 600_000;
        for (int i = 0; i <= 10; i++) {
            points.add(TestTracks.point(600 + i * 10, 0, t));
            t += 7000;
        }
        List<Waypoint> waypoints = Arrays.asList(Waypoint.at(points.get(2), "wpt01"),
                Waypoint.at(points.get(15), "wpt02"));
        return new Track("two", Profile.WALKING, points, new ArrayList<String>(),
                Arrays.asList(11), waypoints);
    }

    private static Track read(String text) throws IOException {
        return GpxReader.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                "fallback");
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
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
