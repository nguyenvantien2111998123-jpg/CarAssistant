package com.carassistant.v10;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Display-1 pane host. Each pane is a separate TextureView backed by its own
 * V10ProjectionSession/VirtualDisplay; Display 2 remains outside this container.
 */
public final class V10PaneContainer {
    private final Context activity;
    private final FrameLayout host;
    private final V10ProjectionSession leftSession;
    private final V10ProjectionSession rightSession;
    private final V10PaneView.MenuListener menuListener;
    private final V10PaneView.FocusListener focusListener;
    private final Runnable interactionListener;

    private LinearLayout row;
    private V10PaneView leftPane;
    private V10PaneView rightPane;
    private View divider;
    private float ratio;

    public V10PaneContainer(Context activity, FrameLayout host,
            V10ProjectionSession leftSession, V10ProjectionSession rightSession,
            V10PaneView.MenuListener menuListener, V10PaneView.FocusListener focusListener,
            Runnable interactionListener) {
        this.activity = activity;
        this.host = host;
        this.leftSession = leftSession;
        this.rightSession = rightSession;
        this.menuListener = menuListener;
        this.focusListener = focusListener;
        this.interactionListener = interactionListener;
        ratio = V10SessionStore.getSplitRatio(activity);
    }

    public void attach() {
        host.removeAllViews();
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(true);
        row.setClipToPadding(true);
        host.addView(row, new FrameLayout.LayoutParams(-1, -1));

        leftPane = new V10PaneView(activity, true, leftSession,
                menuListener::onOpenMenu, focusListener::onPaneTouched, interactionListener);
        rightPane = new V10PaneView(activity, false, rightSession,
                menuListener::onOpenMenu, focusListener::onPaneTouched, interactionListener);

        row.addView(leftPane, new LinearLayout.LayoutParams(0, -1, ratio));
        divider = new Divider(activity);
        row.addView(divider, new LinearLayout.LayoutParams(dp(9), -1));
        row.addView(rightPane, new LinearLayout.LayoutParams(0, -1, 1f - ratio));
    }

    public android.view.TextureView getLeftTextureView() { return leftPane.getTextureView(); }
    public android.view.TextureView getRightTextureView() { return rightPane.getTextureView(); }

    private void applyRatio(float next) {
        ratio = Math.max(0.15f, Math.min(0.85f, next));
        if (row == null || leftPane == null || rightPane == null) return;
        LinearLayout.LayoutParams left = (LinearLayout.LayoutParams) leftPane.getLayoutParams();
        LinearLayout.LayoutParams right = (LinearLayout.LayoutParams) rightPane.getLayoutParams();
        left.weight = ratio;
        right.weight = 1f - ratio;
        leftPane.setLayoutParams(left);
        rightPane.setLayoutParams(right);
        V10SessionStore.setSplitRatio(activity, ratio);
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private final class Divider extends View {
        private float downRatio;

        Divider(Context context) {
            super(context);
            setBackgroundColor(Color.argb(65, 220, 235, 245));
            setContentDescription("Kéo để thay đổi kích thước hai vùng chiếu");
            setOnTouchListener((view, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRatio = ratio;
                        if (interactionListener != null) interactionListener.run();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                    case MotionEvent.ACTION_UP:
                        if (row != null && row.getWidth() > 0) {
                            int[] location = new int[2];
                            row.getLocationOnScreen(location);
                            float x = event.getRawX() - location[0];
                            float usable = Math.max(1f, row.getWidth() - getWidth());
                            applyRatio(x / usable);
                            if (interactionListener != null) interactionListener.run();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        applyRatio(downRatio);
                        return true;
                    default:
                        return true;
                }
            });
        }
    }
}
