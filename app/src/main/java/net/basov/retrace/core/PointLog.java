package net.basov.retrace.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The log file of the current track
// ----------------------------------------------------------------------
//
// During a trip, TrackService adds one line to the log file for each point.
// One line is short, and the service flushes it at once. If the process
// stops, the log file still holds the points. The next start of Retrace
// makes a GPX file from it.
//
// The first line is the header: the magic text, the format number, the id
// of the profile and the start time. Each other line holds the time, the
// latitude, the longitude and the elevation of one point.
public final class PointLog {

    static final String MAGIC = "RETRACE-LOG";
    static final int FORMAT = 1;

    private PointLog() {
    }

    // The header line, with no line end.
    public static String header(Profile profile, long startTimeMs) {
        return MAGIC + " " + FORMAT + " " + profile.id + " " + startTimeMs;
    }

    // The line of one point, with no line end. Long.toString and
    // Double.toString do not use the locale. Thus the decimal mark is always
    // a period.
    public static String line(TrackPoint p) {
        return p.timeMs + " " + p.latitude + " " + p.longitude + " " + p.elevationM;
    }

    // The content of a log file.
    public static final class Content {
        public final Profile profile;
        public final long startTimeMs;
        public final List<TrackPoint> points;

        Content(Profile profile, long startTimeMs, List<TrackPoint> points) {
            this.profile = profile;
            this.startTimeMs = startTimeMs;
            this.points = points;
        }
    }

    // Reads a log file. A line that Retrace cannot read is not used. The last
    // line of a log file from a stopped process can be incomplete.
    // Returns null if the header is not correct.
    public static Content read(Reader reader) throws IOException {
        BufferedReader in = reader instanceof BufferedReader
                ? (BufferedReader) reader : new BufferedReader(reader);
        String first = in.readLine();
        if (first == null) {
            return null;
        }
        String[] head = first.trim().split(" ");
        if (head.length != 4 || !MAGIC.equals(head[0]) || !String.valueOf(FORMAT).equals(head[1])) {
            return null;
        }
        Profile profile = Profile.fromId(head[2]);
        long start;
        try {
            start = Long.parseLong(head[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (profile == null) {
            profile = Profile.WALKING;
        }

        List<TrackPoint> points = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            TrackPoint p = parsePoint(line);
            if (p != null) {
                points.add(p);
            }
        }
        return new Content(profile, start, points);
    }

    private static TrackPoint parsePoint(String line) {
        String[] f = line.trim().split(" ");
        if (f.length != 4) {
            return null;
        }
        try {
            long time = Long.parseLong(f[0]);
            double lat = Double.parseDouble(f[1]);
            double lon = Double.parseDouble(f[2]);
            double ele = Double.parseDouble(f[3]);
            if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
                return null;
            }
            return new TrackPoint(lat, lon, time, ele, Double.NaN);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
