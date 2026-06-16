package com.fourdo.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Shows the 3DO control-pad photo ({@code R.drawable.pad_3do}) and lays out
 * transparent {@link Hotspot}-tagged child views over each button so the caller
 * can attach tap handlers for external-controller binding.
 *
 * Button positions are fractions of the view bounds, measured against the pad
 * photo, so the same geometry holds at any size.
 */
final class ThreeDoControllerView extends FrameLayout {

    /** Hotspot outline style. */
    enum Shape { OVAL, PILL, ARM }

    /**
     * Centre (cx,cy) as a fraction of width/height; size (w,h) as a fraction of
     * width (so equal w/h render as a true circle regardless of view aspect).
     * Measured against the pad_3do photo (full 1.5:1 frame).
     */
    static final class Hotspot {
        final int buttonIndex;
        final float cx, cy, w, h;
        final Shape shape;
        Hotspot(int buttonIndex, float cx, float cy, float w, float h, Shape shape) {
            this.buttonIndex = buttonIndex;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
            this.shape = shape;
        }
    }

    static Hotspot[] hotspots() {
        return new Hotspot[]{
                // Shoulder pills at the top corners.
                new Hotspot(ControllerMappingManager.BUTTON_L1, 0.12f, 0.19f, 0.150f, 0.055f, Shape.PILL),
                new Hotspot(ControllerMappingManager.BUTTON_R1, 0.88f, 0.19f, 0.150f, 0.055f, Shape.PILL),
                // Centre media keys + face buttons. (The D-pad is a DpadView,
                // positioned via dpadRegion() so it matches the on-screen pad.)
                new Hotspot(ControllerMappingManager.BUTTON_STOP, 0.450f, 0.46f, 0.090f, 0.090f, Shape.OVAL),
                new Hotspot(ControllerMappingManager.BUTTON_PLAY_PAUSE, 0.570f, 0.46f, 0.090f, 0.090f, Shape.OVAL),
                new Hotspot(ControllerMappingManager.BUTTON_A, 0.700f, 0.54f, 0.104f, 0.104f, Shape.OVAL),
                new Hotspot(ControllerMappingManager.BUTTON_B, 0.780f, 0.44f, 0.104f, 0.104f, Shape.OVAL),
                new Hotspot(ControllerMappingManager.BUTTON_C, 0.860f, 0.34f, 0.104f, 0.104f, Shape.OVAL),
        };
    }

    /** Square region (over the photo's D-pad) used to size/place the editor DpadView. */
    static Hotspot dpadRegion() {
        return new Hotspot(-1, 0.215f, 0.405f, 0.165f, 0.165f, Shape.OVAL);
    }

    private static final float ASPECT = 683f / 1024f; // pad_3do.png height / width
    private final Drawable pad;

    ThreeDoControllerView(Context context) {
        super(context);
        setWillNotDraw(false);
        pad = context.getResources().getDrawable(R.drawable.pad_3do, context.getTheme());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int) (w * ASPECT);
        int hMode = MeasureSpec.getMode(heightMeasureSpec);
        int hSize = MeasureSpec.getSize(heightMeasureSpec);
        if (hMode != MeasureSpec.UNSPECIFIED && hSize > 0 && h > hSize) {
            h = hSize;
            w = (int) (h / ASPECT);
        }
        // Children are measured/placed in onLayout, so no super.onMeasure call.
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int w = getWidth();
        int h = getHeight();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof Hotspot)) continue;
            Hotspot hs = (Hotspot) tag;
            int pw = Math.round(hs.w * w);
            int ph = Math.round(hs.h * w);
            int cx = Math.round(hs.cx * w);
            int cy = Math.round(hs.cy * h);
            child.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY));
            int l = cx - pw / 2;
            int t = cy - ph / 2;
            child.layout(l, t, l + pw, t + ph);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (pad != null) {
            pad.setBounds(0, 0, getWidth(), getHeight());
            pad.draw(canvas);
        }
        super.dispatchDraw(canvas);
    }
}
