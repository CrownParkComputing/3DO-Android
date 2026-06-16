package com.fourdo.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * A single translucent cross-style D-pad, shared by the on-screen touch overlay
 * (input mode) and the controller editor (editor mode) so both render the same
 * cross.
 *
 * Input mode: reports held directions (8-way; diagonals press two) via
 * {@link Listener}. Drag-to-reposition is handled by an {@link View.OnTouchListener}
 * the host attaches — in edit mode the listener consumes the gesture (returns
 * true) so {@link #onTouchEvent} is never reached.
 *
 * Editor mode: a tap on an arm fires {@link ArmTapListener} with the matching
 * 3DO direction button; each arm is tinted by its assignment state.
 */
final class DpadView extends View {

    interface Listener {
        void onDirections(boolean up, boolean down, boolean left, boolean right);
    }

    interface ArmTapListener {
        void onArmTap(int dpadButtonIndex);
    }

    // Arm state values (shared numbering with the editor hotspot states).
    static final int STATE_UNASSIGNED = 0;
    static final int STATE_ASSIGNED = 1;
    static final int STATE_SELECTED = 2;

    private Listener listener;
    private ArmTapListener armTapListener;
    private boolean editorMode = false;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean up, down, left, right;                 // input mode: held dirs
    private int stUp, stDown, stLeft, stRight;             // editor mode: arm states

    DpadView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
    }

    void setListener(Listener l) {
        this.listener = l;
    }

    void setArmTapListener(ArmTapListener l) {
        this.armTapListener = l;
    }

    void setEditorMode(boolean editor) {
        this.editorMode = editor;
        invalidate();
    }

    void setArmState(int dpadButtonIndex, int state) {
        if (dpadButtonIndex == ControllerMappingManager.BUTTON_DPAD_UP) stUp = state;
        else if (dpadButtonIndex == ControllerMappingManager.BUTTON_DPAD_DOWN) stDown = state;
        else if (dpadButtonIndex == ControllerMappingManager.BUTTON_DPAD_LEFT) stLeft = state;
        else if (dpadButtonIndex == ControllerMappingManager.BUTTON_DPAD_RIGHT) stRight = state;
    }

    /** Clear all held directions (e.g. when entering edit mode). */
    void release() {
        if (up || down || left || right) {
            up = down = left = right = false;
            if (listener != null) listener.onDirections(false, false, false, false);
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float arm = Math.min(w, h) / 2f;
        float thick = arm * 0.66f;
        stroke.setStrokeWidth(Math.max(2f, w * 0.012f));

        if (editorMode) {
            // Four independently-tinted arms forming the cross.
            drawArm(canvas, cx - thick / 2, cy - arm, cx + thick / 2, cy, armColor(stUp), true);
            drawArm(canvas, cx - thick / 2, cy, cx + thick / 2, cy + arm, armColor(stDown), true);
            drawArm(canvas, cx - arm, cy - thick / 2, cx, cy + thick / 2, armColor(stLeft), true);
            drawArm(canvas, cx, cy - thick / 2, cx + arm, cy + thick / 2, armColor(stRight), true);
            return;
        }

        // Input mode: full bars, with pressed arms highlighted.
        drawArm(canvas, cx - thick / 2, cy - arm, cx + thick / 2, cy + arm, BASE, false);
        drawArm(canvas, cx - arm, cy - thick / 2, cx + arm, cy + thick / 2, BASE, false);
        if (up) drawArm(canvas, cx - thick / 2, cy - arm, cx + thick / 2, cy, PRESSED, false);
        if (down) drawArm(canvas, cx - thick / 2, cy, cx + thick / 2, cy + arm, PRESSED, false);
        if (left) drawArm(canvas, cx - arm, cy - thick / 2, cx, cy + thick / 2, PRESSED, false);
        if (right) drawArm(canvas, cx, cy - thick / 2, cx + arm, cy + thick / 2, PRESSED, false);
    }

    // Packed [fill, stroke] colour pairs.
    private static final int[] BASE = {0x665F6670, 0x99D6DADF};
    private static final int[] PRESSED = {0x99C5CBD3, 0xEEFFFFFF};
    private static final int[] ASSIGNED = {0x6632D17A, 0xFF32D17A};
    private static final int[] SELECTED = {0x553BA7FF, 0xFF3BA7FF};

    private int[] armColor(int state) {
        if (state == STATE_SELECTED) return SELECTED;
        if (state == STATE_ASSIGNED) return ASSIGNED;
        return BASE;
    }

    private void drawArm(Canvas canvas, float l, float t, float r, float b, int[] color, boolean square) {
        rect.set(l, t, r, b);
        float rad = (rect.width() < rect.height() ? rect.width() : rect.height()) * 0.22f;
        fill.setColor(color[0]);
        canvas.drawRoundRect(rect, rad, rad, fill);
        stroke.setColor(color[1]);
        canvas.drawRoundRect(rect, rad, rad, stroke);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editorMode) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && armTapListener != null) {
                float dx = event.getX() - getWidth() / 2f;
                float dy = event.getY() - getHeight() / 2f;
                if (Math.abs(dx) < getWidth() * 0.12f && Math.abs(dy) < getHeight() * 0.12f) {
                    return true; // dead centre — ignore
                }
                int idx = (Math.abs(dx) > Math.abs(dy))
                        ? (dx < 0 ? ControllerMappingManager.BUTTON_DPAD_LEFT : ControllerMappingManager.BUTTON_DPAD_RIGHT)
                        : (dy < 0 ? ControllerMappingManager.BUTTON_DPAD_UP : ControllerMappingManager.BUTTON_DPAD_DOWN);
                armTapListener.onArmTap(idx);
            }
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - getWidth() / 2f;
                float dy = event.getY() - getHeight() / 2f;
                float dz = getWidth() * 0.18f; // dead zone around centre
                up = dy < -dz;
                down = dy > dz;
                left = dx < -dz;
                right = dx > dz;
                if (listener != null) listener.onDirections(up, down, left, right);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
            default:
                return true;
        }
    }
}
