package com.carassistant.v10;

import android.content.ComponentName;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.widget.FrameLayout;

import com.google.android.apps.auto.sdk.CarActivity;

public final class V10CarActivity
        extends CarActivity {

    private final Handler handler =
            new Handler(
                    Looper.getMainLooper());

    private TextureView textureView;

    private V10ProjectionSession session;

    private boolean resumed;

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

                        session.setTarget(
                                target);
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
    public void onCreate(
            Bundle state) {

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

            FrameLayout root =
                    new FrameLayout(this);

            root.setBackgroundColor(
                    Color.BLACK);

            textureView =
                    new TextureView(this);

            root.addView(
                    textureView,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));

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

            setContentView(root);
        }

        handler.removeCallbacks(
                targetPoller);

        handler.post(
                targetPoller);
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

        V10SessionStore.clearCurrentActivity(this);

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
        // Reserved for the next control-session implementation.
    }

    public void handleControlApps() {
        // Reserved for the next launcher/control implementation.
    }

}
