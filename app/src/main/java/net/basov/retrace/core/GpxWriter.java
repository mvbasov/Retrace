package net.basov.retrace.core;

import java.io.IOException;
import java.io.Writer;

// ----------------------------------------------------------------------
// The GPX 1.1 writer
// ----------------------------------------------------------------------
//
// The writer makes the text by hand. A GPX file of Retrace has a small and
// fixed form. An XML library would add code and add no value.
//
// The writer formats each number itself. Double.toString can write an
// exponent, for example 1.0E-4. String.format is slow for many points.
public final class GpxWriter {

    public static final String NAMESPACE = "http://www.topografix.com/GPX/1/1";

    // The namespace of the extension for a way. Other applications ignore
    // it, and read a way as a plain track.
    public static final String WAY_NAMESPACE = "https://github.com/mvbasov/Retrace/gpx/1";

    // Seven decimals of a degree are about one centimeter.
    private static final int COORDINATE_DECIMALS = 7;

    private GpxWriter() {
    }

    public static void write(Track track, Writer out) throws IOException {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.write("<gpx version=\"1.1\" creator=\"Retrace\" xmlns=\"" + NAMESPACE + "\">\n");

        out.write("  <metadata>\n");
        out.write("    <name>" + escape(track.name) + "</name>\n");
        long start = track.startTimeMs();
        if (start != TrackPoint.NO_TIME) {
            out.write("    <time>" + TimeText.isoUtc(start) + "</time>\n");
        }
        if (track.isWay()) {
            out.write("    <extensions>\n");
            out.write("      <retrace:way xmlns:retrace=\"" + WAY_NAMESPACE + "\">\n");
            for (String member : track.members) {
                out.write("        <retrace:track>" + escape(member) + "</retrace:track>\n");
            }
            out.write("      </retrace:way>\n");
            out.write("    </extensions>\n");
        }
        out.write("  </metadata>\n");

        out.write("  <trk>\n");
        out.write("    <name>" + escape(track.name) + "</name>\n");
        if (track.profile != null) {
            out.write("    <type>" + track.profile.id + "</type>\n");
        }
        out.write("    <trkseg>\n");
        StringBuilder line = new StringBuilder(128);
        for (TrackPoint p : track.points) {
            line.setLength(0);
            line.append("      <trkpt lat=\"");
            appendFixed(line, p.latitude, COORDINATE_DECIMALS);
            line.append("\" lon=\"");
            appendFixed(line, p.longitude, COORDINATE_DECIMALS);
            line.append("\">");
            if (!Double.isNaN(p.elevationM) && !Double.isInfinite(p.elevationM)) {
                line.append("<ele>");
                appendFixed(line, p.elevationM, 1);
                line.append("</ele>");
            }
            if (p.hasTime()) {
                line.append("<time>").append(TimeText.isoUtc(p.timeMs)).append("</time>");
            }
            line.append("</trkpt>\n");
            out.write(line.toString());
        }
        out.write("    </trkseg>\n");
        out.write("  </trk>\n");
        out.write("</gpx>\n");
    }

    // Writes a number with a fixed count of decimals and no exponent.
    static void appendFixed(StringBuilder out, double value, int decimals) {
        long scale = 1;
        for (int i = 0; i < decimals; i++) {
            scale *= 10;
        }
        long scaled = Math.round(Math.abs(value) * scale);
        if (value < 0 && scaled != 0) {
            out.append('-');
        }
        out.append(scaled / scale);
        if (decimals > 0) {
            out.append('.');
            String fraction = Long.toString(scaled % scale);
            for (int i = fraction.length(); i < decimals; i++) {
                out.append('0');
            }
            out.append(fraction);
        }
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&apos;"); break;
                default:
                    // XML 1.0 does not permit most control characters.
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
