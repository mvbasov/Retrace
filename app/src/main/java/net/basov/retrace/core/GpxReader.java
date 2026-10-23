package net.basov.retrace.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

// ----------------------------------------------------------------------
// The GPX reader
// ----------------------------------------------------------------------
//
// The reader uses the SAX parser of javax.xml. Android has it from API 1,
// and the JVM of the tests has it too. Thus the class stays in core.
//
// The reader accepts GPX 1.0 and GPX 1.1 from other applications. It reads
// the points of each track segment. A file with no track gives the points of
// its routes. It ignores waypoints and each unknown element.
public final class GpxReader {

    private GpxReader() {
    }

    // Reads a GPX file. The fallback name is for a file with no name element.
    public static Track read(InputStream in, String fallbackName) throws IOException {
        Handler handler = new Handler();
        try {
            SAXParser parser = newFactory().newSAXParser();
            parser.parse(new InputSource(in), handler);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("not a GPX file: " + e.getMessage(), e);
        }
        List<TrackPoint> points = handler.trackPoints.isEmpty()
                ? handler.routePoints : handler.trackPoints;
        String name = firstNonEmpty(handler.trackName, handler.metadataName, fallbackName);
        return new Track(name, Profile.fromId(handler.trackType), points);
    }

    private static SAXParserFactory newFactory() {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        // A GPX file needs no external entity. A file from another
        // application must not make the parser read other files. Some
        // parsers do not know a feature. That is not an error.
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory;
    }

    private static void setFeature(SAXParserFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (ParserConfigurationException | SAXException | UnsupportedOperationException e) {
            // The parser does not know the feature.
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    // The SAX handler. It keeps the path of open elements as a stack of
    // local names.
    private static final class Handler extends DefaultHandler {
        final List<TrackPoint> trackPoints = new ArrayList<>();
        final List<TrackPoint> routePoints = new ArrayList<>();
        String metadataName;
        String trackName;
        String trackType;

        private final List<String> path = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private boolean inPoint;
        private double lat;
        private double lon;
        private double ele;
        private long time;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            String name = local(localName, qName);
            path.add(name);
            text.setLength(0);
            if (name.equals("trkpt") || name.equals("rtept")) {
                inPoint = true;
                lat = parseDouble(attrs.getValue("lat"));
                lon = parseDouble(attrs.getValue("lon"));
                ele = Double.NaN;
                time = TrackPoint.NO_TIME;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = local(localName, qName);
            String parent = path.size() >= 2 ? path.get(path.size() - 2) : "";
            String value = text.toString().trim();

            if (inPoint && name.equals("ele")) {
                ele = parseDouble(value);
            } else if (inPoint && name.equals("time")) {
                time = parseTime(value);
            } else if (name.equals("trkpt") || name.equals("rtept")) {
                inPoint = false;
                if (!Double.isNaN(lat) && !Double.isNaN(lon)
                        && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
                    TrackPoint p = new TrackPoint(lat, lon, time, ele, Double.NaN);
                    (name.equals("trkpt") ? trackPoints : routePoints).add(p);
                }
            } else if (name.equals("name") && parent.equals("metadata") && metadataName == null) {
                metadataName = value;
            } else if (name.equals("name") && parent.equals("trk") && trackName == null) {
                trackName = value;
            } else if (name.equals("type") && parent.equals("trk") && trackType == null) {
                trackType = value;
            }

            if (!path.isEmpty()) {
                path.remove(path.size() - 1);
            }
            text.setLength(0);
        }

        private static String local(String localName, String qName) {
            String name = (localName == null || localName.isEmpty()) ? qName : localName;
            int colon = name.indexOf(':');
            return colon >= 0 ? name.substring(colon + 1) : name;
        }

        private static double parseDouble(String s) {
            if (s == null) {
                return Double.NaN;
            }
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        private static long parseTime(String s) {
            try {
                return TimeText.parseIso(s);
            } catch (IllegalArgumentException e) {
                return TrackPoint.NO_TIME;
            }
        }
    }
}
