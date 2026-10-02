package com.carassistant.v10;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.TextureView;
import android.widget.FrameLayout;

import com.google.android.apps.auto.sdk.CarActivity;

/**
 * Android Auto car-side activity.
 *
 * The TextureView is attached BEFORE the target is assigned so that
 * the projection engine follows the same lifecycle as V9.
 */
public final class V10CarActivity extends CarActivity {

    private FrameLayout root;
    private TextureView textureView;
    private V10ProjectionSession session;

    @Override
    public void onCreate(Bundle bundle) {
        setTheme(R.style.Theme_AACast);
        super.onCreate(bundle);
        setIgnoreConfigChanges(-1);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (root == null) {
            root = new FrameLayout(this);

            textureView = new TextureView(this);
            textureView.setOpaque(false);

            root.addView(
                    textureView,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));

            session =
                    new V10ProjectionSession(this);

            // IMPORTANT:
            // Surface listener is installed first.
            session.attachTextureView(textureView);

            textureView.setOnTouchListener(
                    (v, event) -> handleTouch(event));

            setContentView(root);
        }
    }

    private boolean handleTouch(MotionEvent event) {
        if (session == null) {
            return false;
        }

        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_UP:
                session.sendTap(
                        event.getX(),
                        event.getY());
                return true;

            case MotionEvent.ACTION_CANCEL:
                return true;

            default:
                return true;
        }
    }

    /**
     * Called by launcher/controller through the existing V10 activity.
     */
    public void setProjectionTarget(
            android.content.ComponentName component) {

        if (session != null) {
            session.setTarget(component);
        }
    }

    public V10ProjectionSession getProjectionSession() {
        return session;
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onStop() {
        if (session != null) {
            session.release();
        }

        super.onStop();
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.destroy();
            session = null;
        }

        root = null;
        textureView = null;

        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (session != null
                && session.isActive()) {
            session.sendBack();
            return;
        }

        super.onBackPressed();
    }
}
