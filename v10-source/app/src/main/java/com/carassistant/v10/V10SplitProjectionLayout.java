package com.carassistant.v10;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Two independently interactive projection panes composing Android Auto Display 1. */
public final class V10SplitProjectionLayout {
    public interface PaneMenuListener {
        void onOpenPaneMenu(boolean leftPane);
    }

    public interface PaneFocusListener {
        void onPaneTouched(V10ProjectionSession session);
    }

    private final Context activity;
    private final FrameLayout host;
    private final V10ProjectionSession leftSession;
    private final V10ProjectionSession rightSession;
    private final PaneMenuListener menuListener;
    private final PaneFocusListener focusListener;
    private final Runnable interactionListener;
    private LinearLayout row;
    private FrameLayout leftPane;
    private FrameLayout rightPane;
    private TextureView leftTexture;
    private TextureView rightTexture;
    private View divider;
    private float ratio;

    public V10SplitProjectionLayout(Context activity, FrameLayout host,
            V10ProjectionSession leftSession, V10ProjectionSession rightSession,
            PaneMenuListener menuListener, PaneFocusListener focusListener,
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

        leftPane = createPane(true);
        rightPane = createPane(false);
        leftTexture = new TextureView(activity);
        rightTexture = new TextureView(activity);
        leftPane.addView(leftTexture, new FrameLayout.LayoutParams(-1, -1));
        rightPane.addView(rightTexture, new FrameLayout.LayoutParams(-1, -1));

        leftSession.attachTextureView(leftTexture);
        rightSession.attachTextureView(rightTexture);
        View.OnTouchListener leftInput = new V10InputController(leftSession).createListener();
        View.OnTouchListener rightInput = new V10InputController(rightSession).createListener();
        leftTexture.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && focusListener != null) {
                focusListener.onPaneTouched(leftSession);
            }
            if (interactionListener != null) interactionListener.run();
            return leftInput.onTouch(view, event);
        });
        rightTexture.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && focusListener != null) {
                focusListener.onPaneTouched(rightSession);
            }
            if (interactionListener != null) interactionListener.run();
            return rightInput.onTouch(view, event);
        });

        row.addView(leftPane, new LinearLayout.LayoutParams(0, -1, ratio));
        divider = new Divider(activity);
        row.addView(divider, new LinearLayout.LayoutParams(dp(9), -1));
        row.addView(rightPane, new LinearLayout.LayoutParams(0, -1, 1f - ratio));
        addHandle(leftPane, true);
        addHandle(rightPane, false);
    }

    public TextureView getLeftTextureView() { return leftTexture; }
    public TextureView getRightTextureView() { return rightTexture; }

    private FrameLayout createPane(boolean left) {
        FrameLayout pane = new FrameLayout(activity);
        pane.setBackgroundColor(Color.BLACK);
        pane.setClipChildren(true);
        pane.setClipToPadding(true);
        return pane;
    }

    private void addHandle(FrameLayout pane, boolean left) {
        TextView handle = new TextView(activity);
        handle.setText("•••");
        handle.setTextColor(Color.argb(145, 255, 255, 255));
        handle.setTextSize(9);
        handle.setGravity(Gravity.CENTER);
        handle.setContentDescription(left
                ? "Mở menu ứng dụng được phép cho vùng tỉ lệ 3 (30%)"
                : "Mở menu chọn ứng dụng cho vùng tỉ lệ 7 (70%)");
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(30, 255, 255, 255));
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), Color.argb(32, 255, 255, 255));
        handle.setBackground(background);
        handle.setAlpha(0.42f);
        handle.setElevation(dp(12));
        handle.setOnClickListener(v -> {
            if (interactionListener != null) interactionListener.run();
            if (menuListener != null) menuListener.onOpenPaneMenu(left);
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(48), dp(24), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = dp(3);
        pane.addView(handle, params);
    }

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
