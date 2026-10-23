package net.basov.retrace.core;

import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The test of MapViewport, the world coordinates of TileMath and
// TileCachePolicy
// ----------------------------------------------------------------------
//
// A fault in this math puts a track at the wrong place on the map, or loads
// the wrong tiles. Nobody sees such a fault in the code. Thus each check
// compares a result with a value from a different method.
public final class MapViewportTest {

    private static int failures = 0;

    private static final int TILE = 256;

    public static void main(String[] args) {
        worldCoordinatesGoBothWays();
        worldCoordinatesAgreeWithTileNumbers();
        aPositionAtTheCenterIsAtTheMiddleOfTheScreen();
        aMoveWithTheFingerMovesTheMap();
        theFocusStaysUnderTheFingersAtAZoom();
        theZoomStaysInsideItsLimits();
        theWholeBoxIsOnTheScreenAfterAFit();
        aFitOfOnePointUsesTheZoomLimit();
        theVisibleTilesCoverTheScreen();
        theCacheKeepsATileForSevenDays();
        theCacheUsesTheHeaders();
        aTrimRemovesTheOldestTilesFirst();

        if (failures > 0) {
            System.out.println("MapViewportTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("MapViewportTest: every check passed");
    }

    // The conversion to world coordinates and back must give the start value.
    private static void worldCoordinatesGoBothWays() {
        double[] lats = {-85, -60.5, -0.001, 0, 12.34, 45, 55.7558, 84.9};
        for (double lat : lats) {
            near("latitude " + lat, lat, TileMath.latitudeOf(TileMath.worldY(lat)), 1e-9);
        }
        double[] lons = {-180, -122.4194, 0, 10, 37.6173, 179.999};
        for (double lon : lons) {
            near("longitude " + lon, lon, TileMath.longitudeOf(TileMath.worldX(lon)), 1e-9);
        }
        near("equator", 0.5, TileMath.worldY(0), 1e-12);
        near("north edge", 0, TileMath.worldY(90), 1e-6);
    }

    // The world coordinate times the tile count, rounded down, is the tile
    // number of TileMath. Both methods must agree.
    private static void worldCoordinatesAgreeWithTileNumbers() {
        double[][] places = {{51.4779, -0.0015}, {55.7558, 37.6173}, {-33.8568, 151.2153}};
        for (double[] p : places) {
            for (int z = 3; z <= 18; z += 5) {
                int n = 1 << z;
                check("x at zoom " + z, TileMath.tileX(p[1], z),
                        (int) Math.floor(TileMath.worldX(p[1]) * n));
                check("y at zoom " + z, TileMath.tileY(p[0], z),
                        (int) Math.floor(TileMath.worldY(p[0]) * n));
            }
        }
    }

    private static void aPositionAtTheCenterIsAtTheMiddleOfTheScreen() {
        MapViewport v = viewport(55.75, 37.62, 15);
        near("center x", 400, v.screenX(TileMath.worldX(37.62)), 1e-6);
        near("center y", 300, v.screenY(TileMath.worldY(55.75)), 1e-6);
        near("center latitude", 55.75, v.centerLatitude(), 1e-9);
        near("center longitude", 37.62, v.centerLongitude(), 1e-9);
        // At zoom 15, one pixel of a 256 pixel tile is about 4.8 m at the
        // equator, thus about 2.7 m at 55.75 degrees.
        double east = TileMath.worldX(37.62) + 100.0 / v.worldSizePx();
        double meters = Geo.distanceM(55.75, 37.62, 55.75, TileMath.longitudeOf(east));
        near("100 pixels", 100 * 4.777 * Math.cos(Math.toRadians(55.75)), meters, 3);
    }

    // A finger that moves to the right shows more of the map to the west.
    private static void aMoveWithTheFingerMovesTheMap() {
        MapViewport v = viewport(45, 10, 14);
        double before = v.centerLongitude();
        v.panBy(100, 0);
        check("pan right goes west", true, v.centerLongitude() < before);
        v.panBy(-100, 0);
        near("pan back", before, v.centerLongitude(), 1e-9);
        v.panBy(0, 100);
        check("pan down goes north", true, v.centerLatitude() > 45);
    }

    // A zoom with two fingers keeps the point under the fingers in place.
    private static void theFocusStaysUnderTheFingersAtAZoom() {
        MapViewport v = viewport(45, 10, 12);
        double fx = 650;
        double fy = 120;
        double worldX = v.worldXAt(fx);
        double worldY = v.worldYAt(fy);
        v.zoomBy(1.7, fx, fy);
        near("zoom", 13.7, v.zoom(), 1e-9);
        near("focus x", fx, v.screenX(worldX), 1e-6);
        near("focus y", fy, v.screenY(worldY), 1e-6);
    }

    private static void theZoomStaysInsideItsLimits() {
        MapViewport v = viewport(45, 10, 12);
        v.zoomBy(100, 400, 300);
        near("max zoom", MapViewport.MAX_ZOOM, v.zoom(), 0);
        v.zoomBy(-100, 400, 300);
        near("min zoom", MapViewport.MIN_ZOOM, v.zoom(), 0);
        v.setZoom(12.4);
        check("level rounds down", 12, v.tileLevel());
        v.setZoom(12.6);
        check("level rounds up", 13, v.tileLevel());
        near("tile size", TILE * Math.pow(2, -0.4), v.tileScreenSizePx(), 1e-9);
    }

    // After a fit, each corner of the box must be on the screen, inside the
    // margin. The box must also fill the screen in one direction.
    private static void theWholeBoxIsOnTheScreenAfterAFit() {
        MapViewport v = viewport(0, 0, 3);
        double minLat = 55.70;
        double maxLat = 55.80;
        double minLon = 37.50;
        double maxLon = 37.70;
        v.fit(minLat, minLon, maxLat, maxLon, 20);
        double left = v.screenX(TileMath.worldX(minLon));
        double right = v.screenX(TileMath.worldX(maxLon));
        double top = v.screenY(TileMath.worldY(maxLat));
        double bottom = v.screenY(TileMath.worldY(minLat));
        check("left inside", true, left >= 20 - 1e-6);
        check("right inside", true, right <= 780 + 1e-6);
        check("top inside", true, top >= 20 - 1e-6);
        check("bottom inside", true, bottom <= 580 + 1e-6);
        boolean fills = Math.abs(right - left - 760) < 1e-6 || Math.abs(bottom - top - 560) < 1e-6;
        check("box fills one direction", true, fills);
    }

    private static void aFitOfOnePointUsesTheZoomLimit() {
        MapViewport v = viewport(0, 0, 3);
        v.fit(45, 10, 45, 10, 20);
        near("one point zoom", MapViewport.MAX_ZOOM, v.zoom(), 0);
        near("one point center", 45, v.centerLatitude(), 1e-9);
    }

    // Each pixel of the screen must be in one of the visible tiles. A
    // missing tile shows as a gap in the map.
    private static void theVisibleTilesCoverTheScreen() {
        MapViewport v = viewport(55.75, 37.62, 14.3);
        int[] t = v.visibleTiles();
        double size = v.tileScreenSizePx();
        check("first column left of the screen", true, v.tileScreenX(t[0]) <= 0);
        check("first row above the screen", true, v.tileScreenY(t[1]) <= 0);
        check("last column right of the screen", true, v.tileScreenX(t[2]) + size >= 800);
        check("last row below the screen", true, v.tileScreenY(t[3]) + size >= 600);
        check("not too many columns", true, t[2] - t[0] + 1 <= 800 / size + 2);

        MapViewport world = viewport(0, 0, 3);
        int[] all = world.visibleTiles();
        check("tiles stay on the map", true, all[0] >= 0 && all[2] <= 7);
    }

    // The policy of OpenStreetMap asks for at least 7 days.
    private static void theCacheKeepsATileForSevenDays() {
        long now = 1792753200000L;
        long week = TileCachePolicy.MIN_KEEP_MS;
        check("no headers", now + week, TileCachePolicy.expiryMs(now, null, 0));
        check("short max-age", now + week, TileCachePolicy.expiryMs(now, "max-age=3600", 0));
        check("no-cache", now + week, TileCachePolicy.expiryMs(now, "no-cache", 0));
        check("past expires", now + week, TileCachePolicy.expiryMs(now, null, now - 1000));
    }

    // A header with a time after 7 days wins.
    private static void theCacheUsesTheHeaders() {
        long now = 1792753200000L;
        long tenDays = 10L * 24 * 3600;
        check("long max-age", now + tenDays * 1000,
                TileCachePolicy.expiryMs(now, "public, max-age=" + tenDays, 0));
        check("quoted and spaced", now + tenDays * 1000,
                TileCachePolicy.expiryMs(now, "Max-Age = \"" + tenDays + "\"", 0));
        long expires = now + 20L * 24 * 3600 * 1000;
        check("long expires", expires, TileCachePolicy.expiryMs(now, null, expires));
        check("max-age wins over expires", now + tenDays * 1000,
                TileCachePolicy.expiryMs(now, "max-age=" + tenDays, expires));
    }

    // The trim removes the tiles with the earliest end time, down to 90
    // percent of the limit. A cache inside its limit loses nothing.
    private static void aTrimRemovesTheOldestTilesFirst() {
        List<TileCachePolicy.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            entries.add(new TileCachePolicy.Entry("t" + i, 100, 1000 - i));
        }
        check("inside the limit", 0, TileCachePolicy.toRemove(entries, 1000).size());
        List<TileCachePolicy.Entry> remove = TileCachePolicy.toRemove(entries, 800);
        check("remove count", 3, remove.size());
        check("oldest first", "t9", remove.get(0).name);
        check("then the next", "t8", remove.get(1).name);
    }

    private static MapViewport viewport(double lat, double lon, double zoom) {
        MapViewport v = new MapViewport(TILE);
        v.setSize(800, 600);
        v.center(lat, lon);
        v.setZoom(zoom);
        return v;
    }

    private static void near(String name, double expected, double actual, double tolerance) {
        if (Double.isNaN(actual) || Math.abs(expected - actual) > tolerance) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + " +- " + tolerance
                    + ", got " + actual);
        }
    }

    private static void check(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
