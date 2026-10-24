package net.basov.retrace.core;

// ----------------------------------------------------------------------
// The shapes of the start mark and the finish mark
// ----------------------------------------------------------------------
//
// The track screen marks the start of a track with an arrow and the finish
// with a square. The arrow points in the direction of travel.
//
// The first points of a track can be very near each other, for example at
// a wait before the start. Their direction is GPS noise. Thus the direction
// comes from the first point that is at least a minimum distance from the
// start on the screen. A track that never goes that far gives the direction
// to its last point.
//
// The values are screen coordinates. The y axis of the screen points down.
public final class EndMarks {

    private EndMarks() {
    }

    // The index of the first point at least minDistance from point 0, or
    // the last index. The coordinates and minDistance use one unit. Returns
    // -1 for fewer than 2 points.
    public static int aheadIndex(double[] xs, double[] ys, int count, double minDistance) {
        if (count < 2) {
            return -1;
        }
        for (int i = 1; i < count; i++) {
            if (Math.hypot(xs[i] - xs[0], ys[i] - ys[0]) >= minDistance) {
                return i;
            }
        }
        return count - 1;
    }

    // The three corners of the arrow: the tip, then the two corners of the
    // base. The center of the arrow is at (x, y). The arrow is size long.
    // With no direction, the arrow points up.
    public static float[] arrow(double x, double y, double dx, double dy, double size) {
        double length = Math.hypot(dx, dy);
        double ux = length > 0 ? dx / length : 0;
        double uy = length > 0 ? dy / length : -1;
        // The normal of the direction, for the width of the base.
        double nx = -uy;
        double ny = ux;
        double front = size * 0.6;
        double back = size * 0.4;
        double half = size * 0.45;
        return new float[]{
                (float) (x + ux * front), (float) (y + uy * front),
                (float) (x - ux * back + nx * half), (float) (y - uy * back + ny * half),
                (float) (x - ux * back - nx * half), (float) (y - uy * back - ny * half)};
    }
}
