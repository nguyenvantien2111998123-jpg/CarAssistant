package com.carassistant.v10;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;

/**
 * ViewGroup xếp các pane + divider theo LayoutMath, và xử lý kéo-thả đổi chỗ pane.
 */
public final class PaneContainer extends ViewGroup {

    private final RootView root;
    final DividerView[] dividers = new DividerView[2];

    private int dragSource = -1;
    private int dragTarget = -1;
    private float dragX;
    private float dragY;
    private boolean dividerResizeActive;
    private boolean dividerResizeCommitPending;
    private boolean dividerResizeCommitPosted;
    private final Runnable dividerResizeCommit;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PaneContainer(RootView root, Context context) {
        super(context);
        this.root = root;
        dividerResizeCommit = () -> {
            dividerResizeCommitPosted = false;
            if (dividerResizeActive || root.destroyed) {
                return;
            }
            for (PaneView pane : root.panes) {
                pane.resizeAfterDivider();
            }
        };
        dividers[0] = new DividerView(root, context, 0);
        dividers[1] = new DividerView(root, context, 1);
    }

    // ---------------------------------------------------------------- measure

    private static void measureExact(View view, int[] rect) {
        view.measure(MeasureSpec.makeMeasureSpec(Math.max(0, rect[2] - rect[0]), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, rect[3] - rect[1]), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);

        LayoutRects rects = LayoutMath.compute(root.layoutMode, width, height, root.dp(2),
                root.ratioFirst, root.ratioSecond);
        for (PaneView pane : root.panes) {
            if (root.isPaneVisible(pane.index)) {
                measureExact(pane, rects.panes[root.visibleIndex(pane.index)]);
            }
        }
        for (int i = 0; i < dividers.length; i++) {
            dividers[i].setVisibility(i < rects.dividers.length ? VISIBLE : GONE);
            if (i < rects.dividers.length) {
                measureExact(dividers[i], expandDivider(rects.dividers[i], i));
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        LayoutRects rects = LayoutMath.compute(root.layoutMode, right - left, bottom - top, root.dp(2),
                root.ratioFirst, root.ratioSecond);
        for (PaneView pane : root.panes) {
            if (root.isPaneVisible(pane.index)) {
                int[] rect = rects.panes[root.visibleIndex(pane.index)];
                pane.layout(rect[0], rect[1], rect[2], rect[3]);
            }
        }
        for (int i = 0; i < rects.dividers.length; i++) {
            int[] rect = expandDivider(rects.dividers[i], i);
            dividers[i].layout(rect[0], rect[1], rect[2], rect[3]);
            dividers[i].invalidate();
        }
        if (dividerResizeCommitPending && !dividerResizeCommitPosted) {
            dividerResizeCommitPending = false;
            dividerResizeCommitPosted = true;
            post(dividerResizeCommit);
        }
    }

    /** Mở rộng vùng chạm divider ±12dp (tối thiểu 32dp theo trục kia). */
    private int[] expandDivider(int[] rect, int index) {
        int[] out = rect.clone();
        boolean vertical = LayoutMath.isVerticalSplit(root.layoutMode, index);
        int axis = vertical ? 0 : 1;
        int lo = axis;
        int hi = axis + 2;
        int mid = (out[lo] + out[hi]) / 2;
        out[lo] = mid - root.dp(12);
        out[hi] = mid + root.dp(12);

        int crossLo = vertical ? 1 : 0;
        int crossHi = crossLo + 2;
        int crossMid = (out[crossLo] + out[crossHi]) / 2;
        int half = Math.min(root.dp(32), (out[crossHi] - out[crossLo]) / 2);
        out[crossLo] = crossMid - half;
        out[crossHi] = crossMid + half;
        return out;
    }

    // ------------------------------------------------------------ drag & drop

    void beginDrag(int paneIndex) {
        dragSource = paneIndex;
        dragTarget = -1;
        invalidate();
    }

    /** Xác định pane đích theo toạ độ màn hình. */
    void onDragMove(float rawX, float rawY) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        dragX = rawX - location[0];
        dragY = rawY - location[1];
        dragTarget = -1;
        for (PaneView pane : root.panes) {
            if (root.isPaneVisible(pane.index)
                    && dragX >= pane.getLeft() && dragX < pane.getRight()
                    && dragY >= pane.getTop() && dragY < pane.getBottom()) {
                dragTarget = pane.index;
                break;
            }
        }
        invalidate();
    }

    /** Chốt hoán vị (commit) hoặc huỷ. */
    void commitDrag(boolean commit) {
        if (commit && dragSource >= 0 && dragTarget >= 0
                && root.tileOrder.swap(dragSource, dragTarget)) {
            root.prefs.edit().putString("tile_order", root.tileOrder.serialize()).apply();
            announceForAccessibility(getContext().getString(R.string.launcher_moved,
                    root.panes[dragSource].app.label, root.panes[dragTarget].app.label));
            requestLayout();
        }
        dragSource = -1;
        dragTarget = -1;
        invalidate();
    }

    void beginDividerResize() {
        removeCallbacks(dividerResizeCommit);
        dividerResizeCommitPosted = false;
        dividerResizeCommitPending = false;
        dividerResizeActive = true;
        for (PaneView pane : root.panes) {
            pane.cancelScheduledResize();
        }
    }

    void finishDividerResize() {
        dividerResizeActive = false;
        dividerResizeCommitPending = true;
        requestLayout();
    }

    boolean isDividerResizeActive() {
        return dividerResizeActive;
    }

    /** Resize mọi display sau khi kéo divider. */
    void resizeAll() {
        for (PaneView pane : root.panes) {
            pane.resize();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (dragSource < 0) {
            return;
        }
        PaneView source = root.panes[dragSource];
        if (dragTarget >= 0 && dragTarget != dragSource) {
            PaneView target = root.panes[dragTarget];
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x334EB8FF);
            canvas.drawRoundRect(target.getLeft(), target.getTop(), target.getRight(), target.getBottom(),
                    root.dp(12), root.dp(12), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(root.dp(3));
            paint.setColor(0xFF87DFFF);
            canvas.drawRoundRect(root.dp(2) + target.getLeft(), root.dp(2) + target.getTop(),
                    target.getRight() - root.dp(2), target.getBottom() - root.dp(2),
                    root.dp(12), root.dp(12), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(root.dp(15));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        String label = source.app.label;
        float textWidth = paint.measureText(label) + root.dp(32);
        float x = Math.max(0f, Math.min(getWidth() - textWidth, dragX - textWidth / 2f));
        float y = Math.max(0f, Math.min(getHeight() - root.dp(44), dragY - root.dp(22)));
        paint.setColor(0xFFBDE7FF);
        canvas.drawRoundRect(x, y, x + textWidth, y + root.dp(44), root.dp(12), root.dp(12), paint);
        paint.setColor(0xFF102030);
        canvas.drawText(label, x + root.dp(16), root.dp(28) + y, paint);
    }
}
