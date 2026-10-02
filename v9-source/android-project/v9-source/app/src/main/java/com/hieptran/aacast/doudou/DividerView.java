package com.carassistant.v9;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

/**
 * Tay nắm kéo để chỉnh tỉ lệ giữa các pane.
 */
public final class DividerView extends View {

    private final RootView root;
    private final int index;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean dragging;
    private float savedFirst;
    private float savedSecond;
    private float grabOffset;

    public DividerView(RootView root, Context context, int index) {
        super(context);
        this.root = root;
        this.index = index;
        setClickable(true);
        setFocusable(true);
        setContentDescription(context.getString(R.string.layout_divider, index + 1));
    }

    /** Huỷ kéo -> trả tỉ lệ cũ. */
    void cancel() {
        if (!dragging) {
            return;
        }
        dragging = false;
        root.ratioFirst = savedFirst;
        root.ratioSecond = savedSecond;
        root.container.finishDividerResize();
        getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();
    }

    /** Đổi tỉ lệ theo vị trí pixel. */
    void applyPosition(float pos) {
        boolean vertical = LayoutMath.isVerticalSplit(root.layoutMode, index);
        int primary = vertical ? root.container.getWidth() : root.container.getHeight();
        int gap = root.dp(2);
        int gaps = LayoutMath.isLinearThreeSplit(root.layoutMode) ? 2 : 1;
        float[] ratios = LayoutMath.ratios(root.layoutMode, root.ratioFirst, root.ratioSecond);
        float gapOffset = gap * ((LayoutMath.isLinearThreeSplit(root.layoutMode) && index == 1) ? 1.5f : 0.5f);
        float usable = Math.max(1, primary - gaps * gap);
        float fraction = (pos - gapOffset) / usable;

        if (index == 0) {
            ratios[0] = LayoutMath.clamp(fraction, 0.2f,
                    LayoutMath.isLinearThreeSplit(root.layoutMode) ? ratios[1] - 0.2f : 0.8f);
        } else {
            ratios[1] = LayoutMath.clamp(fraction,
                    LayoutMath.isLinearThreeSplit(root.layoutMode) ? 0.2f + ratios[0] : 0.2f, 0.8f);
        }
        root.ratioFirst = ratios[0];
        root.ratioSecond = ratios[1];
        root.container.requestLayout();
        invalidate();
    }

    private float position(MotionEvent event) {
        int[] location = new int[2];
        root.container.getLocationOnScreen(location);
        boolean vertical = LayoutMath.isVerticalSplit(root.layoutMode, index);
        return vertical ? event.getRawX() - location[0] : event.getRawY() - location[1];
    }

    private float centre() {
        boolean vertical = LayoutMath.isVerticalSplit(root.layoutMode, index);
        return vertical ? (getLeft() + getRight()) / 2f : (getTop() + getBottom()) / 2f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (root.destroyed) {
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                root.container.commitDrag(false);
                savedFirst = root.ratioFirst;
                savedSecond = root.ratioSecond;
                dragging = true;
                root.container.beginDividerResize();
                grabOffset = position(event) - centre();
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    applyPosition(position(event) - grabOffset);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    applyPosition(position(event) - grabOffset);
                    dragging = false;
                    root.saveRatios();
                    root.container.finishDividerResize();
                    performClick();
                }
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                cancel();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (root.destroyed) {
            return false;
        }
        if (action != AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                && action != AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            return super.performAccessibilityAction(action, arguments);
        }
        boolean vertical = LayoutMath.isVerticalSplit(root.layoutMode, index);
        float length = (vertical ? root.container.getWidth() : root.container.getHeight()) * 0.05f;
        root.container.beginDividerResize();
        applyPosition(centre() + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? length : -length));
        root.saveRatios();
        root.container.finishDividerResize();
        return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(SeekBar.class.getName());
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        float value = (index == 0 ? root.ratioFirst : root.ratioSecond) * 100f;
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 20f, 80f, value));
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean vertical = LayoutMath.isVerticalSplit(root.layoutMode, index);
        int color = (dragging || isFocused()) ? 0xFF00E5FF : 0xFF2A5078;
        paint.setColor(color);
        float width = getWidth() / 2f;
        float height = getHeight() / 2f;
        float handle = Math.min(root.dp(24), (vertical ? getHeight() : getWidth()) / 3f);
        float left = width - (vertical ? root.dp(1) : handle);
        float top = height - (vertical ? handle : root.dp(1));
        float right = width + (vertical ? root.dp(1) : handle);
        float bottom = height + (vertical ? handle : root.dp(1));
        canvas.drawRoundRect(left, top, right, bottom, root.dp(2), root.dp(2), paint);
    }
}
