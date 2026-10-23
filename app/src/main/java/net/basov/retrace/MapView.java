package net.basov.retrace;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.basov.retrace.core.MapViewport;
import net.basov.retrace.core.TileMath;
import net.basov.retrace.core.TrackPoint;

// ----------------------------------------------------------------------
// The map with OpenStreetMap tiles and the tracks
// ----------------------------------------------------------------------
//
// The view draws the tiles, the reference track, the current track, the
// position and the ghost. MapViewport does the math. TileLoader gives the
// tiles.
//
// Gestures: move with one finger, zoom with two fingers, and zoom in with a
// double tap. A move with the finger turns off the follow mode.
//
// If a tile is not available yet, the view enlarges a part of a tile of a
// lower zoom level from the cache. Thus the map shows something with no
// network.
//
// A screen with a high density shows each tile larger than 256 pixels.
// Otherwise the text of the map is too small to read.
public final class MapView extends View {

    // The lowest zoom level of a replacement tile.
    private static final int MAX_LEVELS_UP = 4;

    // Two points of a track nearer than this on the screen give one point of
    // the path. A long track then draws fast.
    private static final float MIN_PATH_STEP_PX = 1.5f;

    // Tells the activity that the follow mode changed.
    interface FollowListener {
        void onFollowChanged(boolean follow);
    }

    private final TileLoader loader;
    private final MapViewport viewport;
    private final float density;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint referencePaint = strokePaint(R.color.map_reference, 5);
    private final Paint currentPaint = strokePaint(R.color.map_current, 4);
    private final Paint positionPaint = fillPaint(R.color.map_position);
    private final Paint ghostPaint = fillPaint(R.color.map_ghost);
    private final Paint ringPaint = strokePaint(R.color.map_ring, 2);
    private final Paint attributionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint attributionBackground = fillPaint(R.color.map_attribution_background);
    private final Rect source = new Rect();
    private final RectF target = new RectF();
    private final Path path = new Path();
    private final String attribution;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    // The tracks in world coordinates.
    private double[] referenceX = new double[0];
    private double[] referenceY = new double[0];
    private double[] currentX = new double[0];
    private double[] currentY = new double[0];
    private int currentCount;

    private double positionLat = Double.NaN;
    private double positionLon = Double.NaN;
    private double ghostLat = Double.NaN;
    private double ghostLon = Double.NaN;

    private boolean follow;
    private boolean fitPending;
    private FollowListener followListener;

    public MapView(Context context, TileLoader loader) {
        super(context);
        this.loader = loader;
        density = getResources().getDisplayMetrics().density;
        int tileSize = Math.round(256 * Math.max(1f, density / 1.5f));
        viewport = new MapViewport(tileSize);

        attributionPaint.setColor(getResources().getColor(R.color.map_attribution_text, null));
        attributionPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
                getResources().getDisplayMetrics()));
        attribution = context.getString(R.string.osm_attribution);
        setBackgroundColor(getResources().getColor(R.color.map_background, null));

        loader.setListener(this::invalidate);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        viewport.zoomBy(Math.log(d.getScaleFactor()) / Math.log(2),
                                d.getFocusX(), d.getFocusY());
                        invalidate();
                        return true;
                    }
                });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaleDetector.isInProgress()) {
                    return false;
                }
                viewport.panBy(-dx, -dy);
                setFollow(false);
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                viewport.zoomBy(1, e.getX(), e.getY());
                invalidate();
                return true;
            }
        });
    }

    // ------------------------------------------------------------------
    // The content
    // ------------------------------------------------------------------

    void setReference(List<TrackPoint> points) {
        referenceX = new double[points.size()];
        referenceY = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            referenceX[i] = TileMath.worldX(points.get(i).longitude);
            referenceY[i] = TileMath.worldY(points.get(i).latitude);
        }
        invalidate();
    }

    void setCurrent(List<TrackPoint> points) {
        currentCount = 0;
        for (TrackPoint p : points) {
            addCurrentPoint(p, false);
        }
        invalidate();
    }

    // Adds one point to the current track. The arrays grow by doubling.
    void addCurrent(TrackPoint p) {
        addCurrentPoint(p, true);
    }

    private void addCurrentPoint(TrackPoint p, boolean draw) {
        if (currentCount == currentX.length) {
            int size = Math.max(256, currentCount * 2);
            currentX = Arrays.copyOf(currentX, size);
            currentY = Arrays.copyOf(currentY, size);
        }
        currentX[currentCount] = TileMath.worldX(p.longitude);
        currentY[currentCount] = TileMath.worldY(p.latitude);
        currentCount++;
        if (draw) {
            invalidate();
        }
    }

    // Sets the position. In the follow mode, the map moves to it.
    void setPosition(double lat, double lon) {
        positionLat = lat;
        positionLon = lon;
        if (follow && !Double.isNaN(lat)) {
            viewport.center(lat, lon);
        }
        invalidate();
    }

    void setGhost(double lat, double lon) {
        ghostLat = lat;
        ghostLon = lon;
        invalidate();
    }

    void setFollow(boolean on) {
        if (follow == on) {
            return;
        }
        follow = on;
        if (on && !Double.isNaN(positionLat)) {
            viewport.center(positionLat, positionLon);
            invalidate();
        }
        FollowListener l = followListener;
        if (l != null) {
            l.onFollowChanged(on);
        }
    }

    boolean isFollow() {
        return follow;
    }

    void setFollowListener(FollowListener l) {
        followListener = l;
    }

    // ------------------------------------------------------------------
    // The view
    // ------------------------------------------------------------------

    void zoomIn() {
        viewport.zoomBy(1, getWidth() / 2.0, getHeight() / 2.0);
        invalidate();
    }

    void zoomOut() {
        viewport.zoomBy(-1, getWidth() / 2.0, getHeight() / 2.0);
        invalidate();
    }

    // Shows the whole reference track and the current track. Before the
    // first layout, the view has no size. The fit then waits for it.
    void showAll() {
        setFollow(false);
        if (getWidth() == 0 || getHeight() == 0) {
            fitPending = true;
            return;
        }
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < referenceX.length; i++) {
            minX = Math.min(minX, referenceX[i]);
            maxX = Math.max(maxX, referenceX[i]);
            minY = Math.min(minY, referenceY[i]);
            maxY = Math.max(maxY, referenceY[i]);
        }
        for (int i = 0; i < currentCount; i++) {
            minX = Math.min(minX, currentX[i]);
            maxX = Math.max(maxX, currentX[i]);
            minY = Math.min(minY, currentY[i]);
            maxY = Math.max(maxY, currentY[i]);
        }
        if (minX > maxX) {
            return;
        }
        viewport.fit(TileMath.latitudeOf(maxY), TileMath.longitudeOf(minX),
                TileMath.latitudeOf(minY), TileMath.longitudeOf(maxX), Math.round(32 * density));
        invalidate();
    }

    void setCamera(double lat, double lon, double zoom) {
        viewport.center(lat, lon);
        viewport.setZoom(zoom);
        fitPending = false;
        invalidate();
    }

    double centerLatitude() {
        return viewport.centerLatitude();
    }

    double centerLongitude() {
        return viewport.centerLongitude();
    }

    double zoom() {
        return viewport.zoom();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        viewport.setSize(w, h);
        if (fitPending) {
            fitPending = false;
            showAll();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawTiles(canvas);
        drawTrack(canvas, referenceX, referenceY, referenceX.length, referencePaint);
        drawTrack(canvas, currentX, currentY, currentCount, currentPaint);
        drawMarker(canvas, ghostLat, ghostLon, ghostPaint, 6);
        drawMarker(canvas, positionLat, positionLon, positionPaint, 7);
        drawAttribution(canvas);
    }

    // First the view gives the loader the full list of tiles, with the
    // replacement tiles. Then it asks for each tile. The loader stops each
    // task for a tile that is not on that list.
    private void drawTiles(Canvas canvas) {
        int level = viewport.tileLevel();
        int[] t = viewport.visibleTiles();
        Set<String> wanted = new HashSet<>();
        for (int ty = t[1]; ty <= t[3]; ty++) {
            for (int tx = t[0]; tx <= t[2]; tx++) {
                wanted.add(TileCache.key(level, tx, ty));
                for (int up = 1; up <= MAX_LEVELS_UP && level - up >= 0; up++) {
                    wanted.add(TileCache.key(level - up, tx >> up, ty >> up));
                }
            }
        }
        loader.setWanted(wanted);

        float size = (float) viewport.tileScreenSizePx();
        for (int ty = t[1]; ty <= t[3]; ty++) {
            for (int tx = t[0]; tx <= t[2]; tx++) {
                float left = (float) viewport.tileScreenX(tx);
                float top = (float) viewport.tileScreenY(ty);
                target.set(left, top, left + size, top + size);
                Bitmap tile = loader.get(level, tx, ty, true);
                if (tile != null) {
                    canvas.drawBitmap(tile, null, target, tilePaint);
                } else {
                    drawReplacement(canvas, level, tx, ty);
                }
            }
        }
    }

    // Draws the part of a lower tile that covers the missing tile. The loader
    // reads a lower tile from the disk only. It does not download it.
    private void drawReplacement(Canvas canvas, int level, int tx, int ty) {
        for (int up = 1; up <= MAX_LEVELS_UP && level - up >= 0; up++) {
            Bitmap lower = loader.get(level - up, tx >> up, ty >> up, false);
            if (lower == null) {
                continue;
            }
            int part = lower.getWidth() >> up;
            int sx = (tx - ((tx >> up) << up)) * part;
            int sy = (ty - ((ty >> up) << up)) * part;
            source.set(sx, sy, sx + part, sy + part);
            canvas.drawBitmap(lower, source, target, tilePaint);
            return;
        }
    }

    private void drawTrack(Canvas canvas, double[] xs, double[] ys, int count, Paint paint) {
        if (count < 2) {
            return;
        }
        path.rewind();
        float lastX = (float) viewport.screenX(xs[0]);
        float lastY = (float) viewport.screenY(ys[0]);
        path.moveTo(lastX, lastY);
        for (int i = 1; i < count; i++) {
            float x = (float) viewport.screenX(xs[i]);
            float y = (float) viewport.screenY(ys[i]);
            boolean lastPoint = i == count - 1;
            if (lastPoint || Math.abs(x - lastX) + Math.abs(y - lastY) >= MIN_PATH_STEP_PX) {
                path.lineTo(x, y);
                lastX = x;
                lastY = y;
            }
        }
        canvas.drawPath(path, paint);
    }

    private void drawMarker(Canvas canvas, double lat, double lon, Paint fill, int radiusDp) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return;
        }
        float x = (float) viewport.screenX(TileMath.worldX(lon));
        float y = (float) viewport.screenY(TileMath.worldY(lat));
        float r = radiusDp * density;
        canvas.drawCircle(x, y, r, fill);
        canvas.drawCircle(x, y, r, ringPaint);
    }

    // The tile usage policy asks for the attribution on the map.
    private void drawAttribution(Canvas canvas) {
        float pad = 4 * density;
        float textWidth = attributionPaint.measureText(attribution);
        Paint.FontMetrics fm = attributionPaint.getFontMetrics();
        float boxHeight = fm.descent - fm.ascent + 2 * pad;
        float right = getWidth();
        float bottom = getHeight();
        canvas.drawRect(right - textWidth - 2 * pad, bottom - boxHeight, right, bottom,
                attributionBackground);
        canvas.drawText(attribution, right - textWidth - pad, bottom - pad - fm.descent,
                attributionPaint);
    }

    private Paint strokePaint(int colorId, float widthDp) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(widthDp * getResources().getDisplayMetrics().density);
        p.setColor(getResources().getColor(colorId, null));
        return p;
    }

    private Paint fillPaint(int colorId) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(getResources().getColor(colorId, null));
        return p;
    }
}
