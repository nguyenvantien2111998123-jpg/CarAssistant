package com.carassistant.v10;

import android.content.ComponentName;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.apps.auto.sdk.CarActivity;

public final class V10CarActivity
        extends CarActivity {

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private TextureView textureView;

    private V10ProjectionSession session;

    private boolean resumed;

    private LinearLayout controlBar;

    private final Runnable targetPoller =
            new Runnable() {

                @Override
                public void run() {

                    if (!resumed
                            || session == null) {
                        return;
                    }

                    ComponentName target =
                            V10SessionStore.getTarget(
                                    V10CarActivity.this);

                    if (target != null) {
                        session.setTarget(target);
                    }

                    handler.postDelayed(
                            this,
                            500);
                }
            };

    private void applyChrome() {
        try {
            getCarUiController()
                    .getStatusBarController()
                    .hideAppHeader();

            getCarUiController()
                    .getMenuController()
                    .hideMenuButton();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onCreate(Bundle state) {

        setTheme(
                R.style.Theme_AACast);

        super.onCreate(state);

        V10SessionStore.setCurrentActivity(this);

        setIgnoreConfigChanges(-1);

        applyChrome();
    }

    @Override
    public void onResume() {

        super.onResume();

        resumed = true;

        applyChrome();

        if (textureView == null) {

            buildUi();

            session =
                    new V10ProjectionSession(
                            this);

            session.attachTextureView(
                    textureView);

            V10InputController
                    inputController =
                    new V10InputController(
                            session);

            textureView.setOnTouchListener(
                    inputController
                            .createListener());
        }

        handler.removeCallbacks(
                targetPoller);

        handler.post(
                targetPoller);
    }

    private void buildUi() {

        FrameLayout root =
                new FrameLayout(this);

        root.setBackgroundColor(
                Color.BLACK);

        // ==================================================
        // Projection surface
        // ==================================================

        textureView =
                new TextureView(this);

        FrameLayout.LayoutParams
                textureParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);

        textureParams.gravity =
                Gravity.TOP;

        root.addView(
                textureView,
                textureParams);

        // ==================================================
        // Bottom control bar
        // ==================================================

        controlBar =
                new LinearLayout(this);

        controlBar.setOrientation(
                LinearLayout.HORIZONTAL);

        controlBar.setGravity(
                Gravity.CENTER);

        controlBar.setPadding(
                8,
                4,
                8,
                4);

        controlBar.setBackgroundColor(
                Color.rgb(24, 24, 24));

        FrameLayout.LayoutParams
                barParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        dp(58));

        barParams.gravity =
                Gravity.BOTTOM;

        root.addView(
                controlBar,
                barParams);

        // ==================================================
        // Buttons
        // ==================================================

        addControlButton(
                "BACK",
                v -> handleControlBack());

        addControlButton(
                "APPS",
                v -> handleControlApps());

        addControlButton(
                "RECENTS",
                v -> handleControlRecents());

        addControlButton(
                "HOME",
                v -> handleControlHome());

        setContentView(root);
    }

    private void addControlButton(
            String label,
            View.OnClickListener listener) {

        Button button =
                new Button(this);

        button.setText(label);

        button.setTextSize(12);

        button.setTextColor(
                Color.WHITE);

        button.setAllCaps(false);

        button.setOnClickListener(
                listener);

        LinearLayout.LayoutParams
                params =
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f);

        params.setMargins(
                4,
                4,
                4,
                4);

        controlBar.addView(
                button,
                params);
    }

    private int dp(int value) {

        float density =
                getResources()
                        .getDisplayMetrics()
                        .density;

        return Math.max(
                1,
                (int) (value * density + 0.5f));
    }

    @Override
    public void onPause() {

        resumed = false;

        handler.removeCallbacks(
                targetPoller);

        super.onPause();
    }

    @Override
    public void onBackPressed() {

        if (session != null
                && session.isActive()) {

            session.back();

            return;
        }

        super.onBackPressed();
    }

    @Override
    public void onStop() {

        resumed = false;

        handler.removeCallbacks(
                targetPoller);

        if (session != null) {
            session.release();
        }

        super.onStop();
    }

    @Override
    public void onDestroy() {

        resumed = false;

        handler.removeCallbacks(
                targetPoller);

        if (session != null) {

            session.destroy();

            session = null;
        }

        textureView = null;

        controlBar = null;

        V10SessionStore
                .clearCurrentActivity(this);

        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(
            boolean hasFocus,
            boolean touchMode) {

        super.onWindowFocusChanged(
                hasFocus,
                touchMode);

        if (hasFocus) {
            applyChrome();
        }
    }

    public void handleControlBack() {

        if (session != null
                && session.isActive()) {

            session.back();
        }
    }

    public void handleControlHome() {

        if (session != null) {
            session.release();
        }
    }

    public void handleControlRecents() {

        // V10.3:
        // Chưa thay đổi ProjectionSession.
        // Giữ chỗ cho SessionManager.
        showInfo(
                "RECENTS",
                "Session manager sẽ được thêm ở bước tiếp theo.");
    }

    public void handleControlApps() {

        // V10.3:
        // Chưa thay đổi ProjectionSession.
        // Giữ chỗ cho app selector.
        showInfo(
                "APPS",
                "App selector sẽ được thêm ở bước tiếp theo.");
    }

    private void showInfo(
            String title,
            String message) {

        if (!resumed) {
            return;
        }

        TextView info =
                new TextView(this);

        info.setText(
                title + "\n\n" + message);

        info.setTextColor(
                Color.WHITE);

        info.setTextSize(16);

        info.setGravity(
                Gravity.CENTER);

        info.setBackgroundColor(
                Color.rgb(32, 32, 32));

        FrameLayout root =
                (FrameLayout) textureView.getParent();

        if (root == null) {
            return;
        }

        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        dp(150));

        params.gravity =
                Gravity.CENTER;

        root.addView(
                info,
                params);

        handler.postDelayed(
                () -> {

                    if (info.getParent() != null) {
                        ((FrameLayout) info.getParent())
                                .removeView(info);
                    }

                },
                1800);
    }
}
