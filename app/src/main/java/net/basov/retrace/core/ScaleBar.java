package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The scale bar of the map
// ----------------------------------------------------------------------
//
// The bar is a line with a round length: 1, 2 or 5 times a power of 10, in
// meters. MapView draws the longest such line that is not longer than its
// limit. A round length is easy to read and to compare with the map.
//
// The map uses the Web Mercator projection. One pixel thus shows fewer
// meters far from the equator. The bar is correct at the center of the
// screen, where the map scale is the scale of the center latitude.
public final class ScaleBar {

    private static final int[] STEPS = {1, 2, 5};

    // The length in meters and the length on the screen in pixels.
    public final long meters;
    public final double pixels;

    private ScaleBar(long meters, double pixels) {
        this.meters = meters;
        this.pixels = pixels;
    }

    // The ground distance of one screen pixel at the latitude. The world
    // size is the size of the whole map on the screen, in pixels.
    public static double metersPerPixel(double latitude, double worldSizePx) {
        double equator = 2 * Math.PI * Geo.EARTH_RADIUS_M;
        return equator * Math.cos(Math.toRadians(latitude)) / worldSizePx;
    }

    // The longest round length that is not longer than maxPixels. Returns
    // null if even one meter is longer.
    public static ScaleBar choose(double metersPerPixel, double maxPixels) {
        if (!(metersPerPixel > 0) || !(maxPixels > 0)) {
            return null;
        }
        double maxMeters = metersPerPixel * maxPixels;
        long best = 0;
        for (long power = 1; power <= 100000000L && power <= maxMeters; power *= 10) {
            for (int step : STEPS) {
                long length = step * power;
                if (length <= maxMeters) {
                    best = length;
                }
            }
        }
        if (best == 0) {
            return null;
        }
        return new ScaleBar(best, best / metersPerPixel);
    }
}
