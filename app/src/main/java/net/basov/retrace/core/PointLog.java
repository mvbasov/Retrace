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
// of the profile and the start time. Each other line is one of these:
// - a point: the time, the latitude, the longitude and the elevation
// - SEGMENT: the next point starts a new track segment
// - a waypoint: WPT, then the values of a point, then the name
//
// Format 1 has points only. The reader accepts formats 1 and 2.
public final class PointLog {

    static final String MAGIC = "RETRACE-LOG";
    static final int FORMAT = 2;
    static final String SEGMENT = "SEGMENT";
    static final String WAYPOINT = "WPT";

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

    // The line that starts a new track segment.
    public static String segmentLine() {
        return SEGMENT;
    }

    // The line of one waypoint, with no line end. The name is last, thus it
    // can hold spaces. A line end in the name becomes a space.
    public static String waypointLine(Waypoint w) {
        return WAYPOINT + " " + w.timeMs + " " + w.latitude + " " + w.longitude + " "
                + w.elevationM + " " + w.name.replace('\n', ' ').replace('\r', ' ');
    }

    // The content of a log file.
    public static final class Content {
        public final Profile profile;
        public final long startTimeMs;
        public final List<TrackPoint> points;
        // The index of the first point of each track segment after the first one.
        public final List<Integer> segmentStarts;
        public final List<Waypoint> waypoints;

        Content(Profile profile, long startTimeMs, List<TrackPoint> points,
                List<Integer> segmentStarts, List<Waypoint> waypoints) {
            this.profile = profile;
            this.startTimeMs = startTimeMs;
            this.points = points;
            this.segmentStarts = segmentStarts;
            this.waypoints = waypoints;
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
        if (head.length != 4 || !MAGIC.equals(head[0])
                || !("1".equals(head[1]) || String.valueOf(FORMAT).equals(head[1]))) {
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
        List<Integer> starts = new ArrayList<>();
        List<Waypoint> waypoints = new ArrayList<>();
        // A SEGMENT line before the first point, or two in a sequence, give
        // no empty track segment.
        boolean newSegment = false;
        String line;
        while ((line = in.readLine()) != null) {
            String text = line.trim();
            if (text.equals(SEGMENT)) {
                newSegment = true;
            } else if (text.startsWith(WAYPOINT + " ")) {
                Waypoint w = parseWaypoint(text);
                if (w != null) {
                    waypoints.add(w);
                }
            } else {
                TrackPoint p = parsePoint(text.split(" "));
                if (p != null) {
                    if (newSegment && !points.isEmpty()) {
                        starts.add(points.size());
                    }
                    newSegment = false;
                    points.add(p);
                }
            }
        }
        return new Content(profile, start, points, starts, waypoints);
    }

    // WPT, four values and the name. A broken last line gives no waypoint.
    private static Waypoint parseWaypoint(String text) {
        String[] f = text.split(" ", 6);
        if (f.length != 6) {
            return null;
        }
        TrackPoint p = parsePoint(new String[] {f[1], f[2], f[3], f[4]});
        return p == null ? null : Waypoint.at(p, f[5]);
    }

    private static TrackPoint parsePoint(String[] f) {
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
