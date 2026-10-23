package net.basov.retrace.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The test of GpxWriter and GpxReader
// ----------------------------------------------------------------------
//
// Retrace keeps each track as a GPX file. A fault here loses the tracks of
// the user. Thus the test writes and reads tracks, and also reads files in
// the forms of other applications.
public final class GpxTest {

    private static int failures = 0;

    public static void main(String[] args) throws IOException {
        aTrackComesBackTheSame();
        theNameIsEscaped();
        numbersHaveNoExponent();
        aFileOfAnotherApplication();
        aFileWithOnlyARoute();
        aBadFileIsAnError();
        anExternalEntityIsNotRead();

        if (failures > 0) {
            System.out.println("GpxTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("GpxTest: every check passed");
    }

    // The writer and the reader must agree on each value that GPX holds.
    private static void aTrackComesBackTheSame() throws IOException {
        List<TrackPoint> points = new ArrayList<>();
        points.add(new TrackPoint(55.7558123, 37.6173456, 1792753500000L, 156.5, 3));
        points.add(new TrackPoint(55.7559, -0.0000001, 1792753501250L, Double.NaN, 3));
        points.add(new TrackPoint(-33.8568, 151.2153, TrackPoint.NO_TIME, -2.0, 3));
        Track track = new Track("Morning walk", Profile.WALKING, points);

        Track back = roundTrip(track);
        check("name", "Morning walk", back.name);
        check("profile", Profile.WALKING, back.profile);
        check("count", 3, back.points.size());
        for (int i = 0; i < 3; i++) {
            TrackPoint a = points.get(i);
            TrackPoint b = back.points.get(i);
            near("lat " + i, a.latitude, b.latitude, 1e-7);
            near("lon " + i, a.longitude, b.longitude, 1e-7);
            check("time " + i, a.timeMs, b.timeMs);
        }
        near("ele", 156.5, back.points.get(0).elevationM, 1e-9);
        check("no ele", true, Double.isNaN(back.points.get(1).elevationM));
        near("negative ele", -2.0, back.points.get(2).elevationM, 1e-9);
    }

    // A user can type each character into the name of a track.
    private static void theNameIsEscaped() throws IOException {
        String name = "A & B <test> \"quoted\" 'single' é中";
        Track back = roundTrip(new Track(name, Profile.DRIVING, new ArrayList<TrackPoint>()));
        check("escaped name", name, back.name);
        check("control character", "ab", GpxWriter.escape("a\u0001b"));
    }

    // Double.toString writes 1.0E-4. A GPX reader of another application
    // can reject that form.
    private static void numbersHaveNoExponent() {
        check("small", "0.0001000", fixed(0.0001, 7));
        check("negative small", "-0.0000001", fixed(-0.0000001, 7));
        check("negative zero", "0.0000000", fixed(-0.00000001, 7));
        check("whole", "37.6173000", fixed(37.6173, 7));
        check("one decimal", "156.5", fixed(156.49, 1));
        check("rounding", "1.0", fixed(0.96, 1));
    }

    // GPX 1.0, a namespace prefix, two segments and extensions.
    private static void aFileOfAnotherApplication() throws IOException {
        String gpx = "<?xml version=\"1.0\"?>\n"
                + "<g:gpx xmlns:g=\"http://www.topografix.com/GPX/1/0\" version=\"1.0\">"
                + "<g:wpt lat=\"1\" lon=\"1\"><g:name>A waypoint</g:name></g:wpt>"
                + "<g:trk><g:name> Evening ride </g:name><g:type>Cycling</g:type>"
                + "<g:trkseg>"
                + "<g:trkpt lat=\"55.1\" lon=\"37.1\"><g:ele>100</g:ele>"
                + "<g:time>2026-10-23T14:05:00+03:00</g:time>"
                + "<g:extensions><x:hr xmlns:x=\"urn:x\">120</x:hr></g:extensions></g:trkpt>"
                + "</g:trkseg><g:trkseg>"
                + "<g:trkpt lat=\"55.2\" lon=\"37.2\"><g:time>bad time</g:time></g:trkpt>"
                + "<g:trkpt lat=\"999\" lon=\"37.3\"/>"
                + "</g:trkseg></g:trk></g:gpx>";
        Track track = GpxReader.read(stream(gpx), "fallback");
        check("name", "Evening ride", track.name);
        check("type", Profile.CYCLING, track.profile);
        check("count", 2, track.points.size());
        check("time", TimeText.parseIso("2026-10-23T11:05:00Z"), track.points.get(0).timeMs);
        check("bad time", TrackPoint.NO_TIME, track.points.get(1).timeMs);
        near("ele", 100, track.points.get(0).elevationM, 1e-9);
    }

    // A route has no times. It is still a line on the map.
    private static void aFileWithOnlyARoute() throws IOException {
        String gpx = "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" version=\"1.1\">"
                + "<rte><rtept lat=\"1\" lon=\"2\"/><rtept lat=\"1.1\" lon=\"2\"/></rte></gpx>";
        Track track = GpxReader.read(stream(gpx), "fallback");
        check("route count", 2, track.points.size());
        check("fallback name", "fallback", track.name);
        check("no profile", true, track.profile == null);
    }

    private static void aBadFileIsAnError() {
        boolean thrown = false;
        try {
            GpxReader.read(stream("<gpx><trk>"), "x");
        } catch (IOException e) {
            thrown = true;
        }
        check("bad file", true, thrown);
    }

    // A GPX file from another application must not make Retrace read a
    // different file of the device.
    private static void anExternalEntityIsNotRead() throws IOException {
        Path secret = Files.createTempFile("retrace", ".txt");
        try {
            Files.write(secret, "SECRET".getBytes(StandardCharsets.UTF_8));
            String gpx = "<?xml version=\"1.0\"?>"
                    + "<!DOCTYPE gpx [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>"
                    + "<gpx><trk><name>a&x;b</name></trk></gpx>";
            String name;
            try {
                name = GpxReader.read(stream(gpx), "x").name;
            } catch (IOException e) {
                name = "rejected";
            }
            check("no secret", false, name.contains("SECRET"));
        } finally {
            Files.delete(secret);
        }
    }

    private static Track roundTrip(Track track) throws IOException {
        StringWriter out = new StringWriter();
        GpxWriter.write(track, out);
        return GpxReader.read(stream(out.toString()), "fallback");
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String fixed(double value, int decimals) {
        StringBuilder out = new StringBuilder();
        GpxWriter.appendFixed(out, value, decimals);
        return out.toString();
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
