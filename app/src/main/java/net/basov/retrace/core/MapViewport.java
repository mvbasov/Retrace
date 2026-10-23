package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The part of the map on the screen
// ----------------------------------------------------------------------
//
// The viewport holds the center of the map and the zoom. It converts between
// a position on the Earth and a pixel on the screen, and it gives the tiles
// on the screen. MapView draws with it.
//
// A world coordinate is the position on the map at zoom level 0, from 0 to 1
// in each direction. See TileMath.worldX and TileMath.worldY.
//
// The zoom is a real number. Two-finger zoom gives each value between two
// levels. The tiles come from the nearest whole level, and MapView scales them.
//
// The viewport keeps the center on the map. It does not repeat the map to the
// east and to the west.
public final class MapViewport {

    public static final double MIN_ZOOM = 3;
    public static final double MAX_ZOOM = 19;

    private final int tileSizePx;
    private int width;
    private int height;
    private double centerX = 0.5;
    private double centerY = 0.5;
    private double zoom = MIN_ZOOM;

    // The size of one tile on the screen at a whole zoom level, in pixels.
    // A screen with a high density shows a tile larger than 256 pixels.
    public MapViewport(int tileSizePx) {
        if (tileSizePx <= 0) {
            throw new IllegalArgumentException("tile size must be positive: " + tileSizePx);
        }
        this.tileSizePx = tileSizePx;
    }

    public void setSize(int widthPx, int heightPx) {
        width = Math.max(0, widthPx);
        height = Math.max(0, heightPx);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public double zoom() {
        return zoom;
    }

    public void setZoom(double newZoom) {
        zoom = clamp(newZoom, MIN_ZOOM, MAX_ZOOM);
    }

    public void center(double latitude, double longitude) {
        centerX = clamp(TileMath.worldX(longitude), 0, 1);
        centerY = clamp(TileMath.worldY(latitude), 0, 1);
    }

    public double centerLatitude() {
        return TileMath.latitudeOf(centerY);
    }

    public double centerLongitude() {
        return TileMath.longitudeOf(centerX);
    }

    // The size of the whole map on the screen at the current zoom, in pixels.
    public double worldSizePx() {
        return tileSizePx * Math.pow(2, zoom);
    }

    public double screenX(double worldX) {
        return (worldX - centerX) * worldSizePx() + width / 2.0;
    }

    public double screenY(double worldY) {
        return (worldY - centerY) * worldSizePx() + height / 2.0;
    }

    public double worldXAt(double screenX) {
        return centerX + (screenX - width / 2.0) / worldSizePx();
    }

    public double worldYAt(double screenY) {
        return centerY + (screenY - height / 2.0) / worldSizePx();
    }

    // Moves the map with a finger. A move to the right shows more of the map
    // to the west.
    public void panBy(double dxPx, double dyPx) {
        double size = worldSizePx();
        centerX = clamp(centerX - dxPx / size, 0, 1);
        centerY = clamp(centerY - dyPx / size, 0, 1);
    }

    // Changes the zoom by a number of levels. The point of the map under the
    // focus stays at the same place on the screen. A positive change zooms in.
    public void zoomBy(double levels, double focusX, double focusY) {
        double worldX = worldXAt(focusX);
        double worldY = worldYAt(focusY);
        setZoom(zoom + levels);
        double size = worldSizePx();
        centerX = clamp(worldX - (focusX - width / 2.0) / size, 0, 1);
        centerY = clamp(worldY - (focusY - height / 2.0) / size, 0, 1);
    }

    // Shows the whole box with a margin on each side. A box of one point
    // gets the zoom limit.
    public void fit(double minLat, double minLon, double maxLat, double maxLon, int marginPx) {
        double left = TileMath.worldX(minLon);
        double right = TileMath.worldX(maxLon);
        double top = TileMath.worldY(maxLat);
        double bottom = TileMath.worldY(minLat);
        centerX = clamp((left + right) / 2, 0, 1);
        centerY = clamp((top + bottom) / 2, 0, 1);

        double usableW = Math.max(1, width - 2 * marginPx);
        double usableH = Math.max(1, height - 2 * marginPx);
        double spanX = Math.abs(right - left);
        double spanY = Math.abs(bottom - top);
        double zoomX = spanX > 0 ? log2(usableW / (spanX * tileSizePx)) : MAX_ZOOM;
        double zoomY = spanY > 0 ? log2(usableH / (spanY * tileSizePx)) : MAX_ZOOM;
        setZoom(Math.min(zoomX, zoomY));
    }

    // The whole zoom level of the tiles: the level nearest to the zoom.
    public int tileLevel() {
        return (int) Math.round(clamp(zoom, MIN_ZOOM, MAX_ZOOM));
    }

    // The size of one tile of tileLevel() on the screen, in pixels.
    public double tileScreenSizePx() {
        return tileSizePx * Math.pow(2, zoom - tileLevel());
    }

    // The tiles on the screen at tileLevel(): the first column, the first
    // row, the last column and the last row. The values stay on the map.
    public int[] visibleTiles() {
        int level = tileLevel();
        int count = 1 << level;
        int x0 = (int) Math.floor(worldXAt(0) * count);
        int y0 = (int) Math.floor(worldYAt(0) * count);
        int x1 = (int) Math.floor(worldXAt(width) * count);
        int y1 = (int) Math.floor(worldYAt(height) * count);
        return new int[]{
                Math.max(0, x0), Math.max(0, y0),
                Math.min(count - 1, x1), Math.min(count - 1, y1)};
    }

    // The screen position of the top left corner of a tile at tileLevel().
    public double tileScreenX(int column) {
        return screenX((double) column / (1 << tileLevel()));
    }

    public double tileScreenY(int row) {
        return screenY((double) row / (1 << tileLevel()));
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
