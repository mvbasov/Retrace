package net.basov.retrace;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.View;

import net.basov.retrace.core.FixSource;

// ----------------------------------------------------------------------
// The small dot that shows the source of the fixes
// ----------------------------------------------------------------------
//
// Red: no fix now. Yellow: the network location. Green: the GPS receiver.
// See FixSource. The trip screen shows the dot on the map, and the list
// screen shows it in the GPS line. A thin white ring keeps the dot visible
// on each part of the map.
final class SourceDot extends View {

    private static final int SIZE_DP = 14;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    private int source = -1;

    SourceDot(Context context) {
        super(context);
        size = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SIZE_DP,
                getResources().getDisplayMetrics()));
        fill.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(size / 8f);
        ring.setColor(getResources().getColor(R.color.map_ring, null));
        setSource(FixSource.NONE);
    }

    void setSource(int value) {
        if (value == source) {
            return;
        }
        source = value;
        int color;
        int label;
        if (value == FixSource.GPS) {
            color = R.color.source_gps;
            label = R.string.source_gps;
        } else if (value == FixSource.NETWORK) {
            color = R.color.source_network;
            label = R.string.source_network;
        } else {
            color = R.color.source_none;
            label = R.string.source_none;
        }
        fill.setColor(getResources().getColor(color, null));
        setContentDescription(getContext().getString(label));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float c = size / 2f;
        float r = c - ring.getStrokeWidth();
        canvas.drawCircle(c, c, r, fill);
        canvas.drawCircle(c, c, r, ring);
    }
}
