package com.carassistant.v10;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * One independently interactive pane on Android Auto Display 1.
 *
 * The pane owns only its TextureView and input/menu surface. Its
 * V10ProjectionSession owns the VirtualDisplay, task and root-shell lifecycle.
 */
final class V10PaneView extends FrameLayout {
    interface MenuListener { void onOpenMenu(boolean leftPane); }
    interface FocusListener { void onPaneTouched(V10ProjectionSession session); }

    private final V10ProjectionSession session;
    private final TextureView textureView;

    V10PaneView(Context context, boolean leftPane, V10ProjectionSession session,
            MenuListener menuListener, FocusListener focusListener, Runnable interactionListener) {
        super(context);
        this.session = session;
        setBackgroundColor(Color.BLACK);
        setClipChildren(true);
        setClipToPadding(true);

        textureView = new TextureView(context);
        addView(textureView, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        session.attachTextureView(textureView);

        View.OnTouchListener input = new V10InputController(session).createListener();
        textureView.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && focusListener != null) {
                focusListener.onPaneTouched(session);
            }
            if (interactionListener != null) interactionListener.run();
            return input.onTouch(view, event);
        });

        addMenuHandle(leftPane, menuListener, interactionListener);
    }

    TextureView getTextureView() { return textureView; }

    private void addMenuHandle(boolean leftPane, MenuListener menuListener,
            Runnable interactionListener) {
        TextView handle = new TextView(getContext());
        handle.setText("•••");
        handle.setTextColor(Color.argb(145, 255, 255, 255));
        handle.setTextSize(9);
        handle.setGravity(Gravity.CENTER);
        handle.setContentDescription(leftPane
                ? "Mở menu ứng dụng được phép cho vùng tỉ lệ 3 (30%)"
                : "Mở menu chọn ứng dụng cho vùng tỉ lệ 7 (70%)");
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(30, 255, 255, 255));
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), Color.argb(32, 255, 255, 255));
        handle.setBackground(background);
        handle.setAlpha(0.42f);
        handle.setElevation(dp(12));
        handle.setOnClickListener(view -> {
            if (interactionListener != null) interactionListener.run();
            if (menuListener != null) menuListener.onOpenMenu(leftPane);
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(48), dp(24), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = dp(3);
        addView(handle, params);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
