package net.basov.retrace.core;

import java.util.Locale;

// ----------------------------------------------------------------------
// The tile numbers of the Web Mercator projection
// ----------------------------------------------------------------------
//
// OpenStreetMap divides the world into square tiles. At zoom level z, the
// map has 2^z tiles in each direction. Tile 0/0 is at the top left corner.
//
// This class imports no Android package. Thus test/run.sh compiles it with
// plain javac and runs TileMathTest on the JVM.
public final class TileMath {

    // The Web Mercator projection stops at this latitude. Above it, the map
    // is not square.
    public static final double MAX_LATITUDE = 85.05112878;

    // The lowest and the highest zoom level of the map.
    public static final int MIN_ZOOM = 0;
    public static final int MAX_ZOOM = 19;

    private TileMath() {
    }

    // Returns the column of the tile that holds the longitude.
    public static int tileX(double longitude, int zoom) {
        int n = tileCount(zoom);
        double fraction = (normalizeLongitude(longitude) + 180.0) / 360.0;
        return clampTile((int) Math.floor(fraction * n), n);
    }

    // Returns the row of the tile that holds the latitude.
    public static int tileY(double latitude, int zoom) {
        int n = tileCount(zoom);
        double lat = Math.toRadians(clampLatitude(latitude));
        double mercator = Math.log(Math.tan(lat) + 1.0 / Math.cos(lat));
        double fraction = (1.0 - mercator / Math.PI) / 2.0;
        return clampTile((int) Math.floor(fraction * n), n);
    }

    // Makes the URL of one tile from a template with three %d fields.
    // The fields are the zoom, the column and the row, in that order.
    public static String tileUrl(String template, int zoom, double latitude, double longitude) {
        return String.format(Locale.ROOT, template,
                zoom, tileX(longitude, zoom), tileY(latitude, zoom));
    }

    // Returns the number of tiles in one direction at the zoom level.
    public static int tileCount(int zoom) {
        if (zoom < MIN_ZOOM || zoom > MAX_ZOOM) {
            throw new IllegalArgumentException("zoom out of range: " + zoom);
        }
        return 1 << zoom;
    }

    public static double clampLatitude(double latitude) {
        return Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
    }

    // Moves the longitude into the range from -180 to 180.
    public static double normalizeLongitude(double longitude) {
        double lon = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        // A longitude of exactly 180 is the same line as -180.
        return lon == 180.0 ? -180.0 : lon;
    }

    private static int clampTile(int index, int n) {
        return Math.max(0, Math.min(n - 1, index));
    }
}
