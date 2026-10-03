package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

public final class V10ProjectionSession
        implements TextureView.SurfaceTextureListener {

    private final Context context;

    private TextureView textureView;

    private ComponentName target;

    private boolean destroyed;

    private int contentWidth;

    private int contentHeight;


    public V10ProjectionSession(
            Context context) {

        this.context =
                context.getApplicationContext();

        /*
         * Start the projection owner independently
         * from V10CarActivity.
         */
        V10ProjectionService.ensureStarted(
                this.context);
    }


    public void attachTextureView(
            TextureView view) {

        textureView = view;

        view.setSurfaceTextureListener(
                this);

        if (view.isAvailable()) {

            attachCurrentSurface();
        }
    }


    public void setTarget(
            ComponentName component) {

        if (destroyed) {
            return;
        }

        target = component;


        if (component == null) {

            V10ProjectionService
                    .clearTarget();

            return;
        }


        V10SessionStore.setTarget(
                context,
                component);


        V10ProjectionService.setTarget(
                context,
                component);


        /*
         * If TextureView already exists,
         * connect its current Surface.
         */
        attachCurrentSurface();
    }


    public boolean isActive() {

        return !destroyed
                && V10ProjectionService
                        .isActive();
    }


    public int getDisplayId() {

        return V10ProjectionService
                .getDisplayId();
    }


    public void tap(
            float x,
            float y) {

        if (!isActive()) {
            return;
        }

        V10ProjectionService.tap(
                Math.round(
                        mapX(x)),
                Math.round(
                        mapY(y)));
    }


    public void swipe(
            float startX,
            float startY,
            float endX,
            float endY,
            long duration) {

        if (!isActive()) {
            return;
        }

        V10ProjectionService.swipe(
                Math.round(
                        mapX(startX)),
                Math.round(
                        mapY(startY)),
                Math.round(
                        mapX(endX)),
                Math.round(
                        mapY(endY)),
                duration);
    }


    public void back() {

        if (isActive()) {

            V10ProjectionService.back();
        }
    }


    private void attachCurrentSurface() {

        if (destroyed
                || target == null
                || textureView == null
                || !textureView.isAvailable()) {

            return;
        }


        SurfaceTexture surfaceTexture =
                textureView
                        .getSurfaceTexture();

        if (surfaceTexture == null) {
            return;
        }


        int width =
                textureView.getWidth();

        int height =
                textureView.getHeight();


        if (width < 1
                || height < 1) {

            return;
        }


        surfaceTexture.setDefaultBufferSize(
                width,
                height);


        contentWidth = width;

        contentHeight = height;


        Surface surface =
                new Surface(
                        surfaceTexture);


        V10ProjectionService.attachSurface(
                context,
                surface,
                width,
                height);
    }


    private float mapX(
            float x) {

        if (textureView == null
                || contentWidth < 1) {

            return x;
        }


        float viewWidth =
                textureView.getWidth();

        if (viewWidth <= 0) {
            return x;
        }


        return clamp(
                x * contentWidth
                        / viewWidth,
                0,
                contentWidth - 1);
    }


    private float mapY(
            float y) {

        if (textureView == null
                || contentHeight < 1) {

            return y;
        }


        float viewHeight =
                textureView.getHeight();

        if (viewHeight <= 0) {
            return y;
        }


        return clamp(
                y * contentHeight
                        / viewHeight,
                0,
                contentHeight - 1);
    }


    private static float clamp(
            float value,
            float min,
            float max) {

        return Math.max(
                min,
                Math.min(
                        max,
                        value));
    }


    /*
     * IMPORTANT:
     *
     * release() now means:
     *
     * detach Surface only.
     *
     * It does NOT release VirtualDisplay.
     */
    public void release() {

        if (!destroyed) {

            V10ProjectionService
                    .detachSurface();
        }
    }


    public void destroy() {

        if (destroyed) {
            return;
        }

        destroyed = true;

        /*
         * Activity destruction must not destroy
         * the service-owned VirtualDisplay.
         */
        V10ProjectionService
                .detachSurface();
    }


    @Override
    public void onSurfaceTextureAvailable(
            SurfaceTexture surface,
            int width,
            int height) {

        attachCurrentSurface();
    }


    @Override
    public void onSurfaceTextureSizeChanged(
            SurfaceTexture surface,
            int width,
            int height) {

        contentWidth = width;

        contentHeight = height;
    }


    @Override
    public boolean onSurfaceTextureDestroyed(
            SurfaceTexture surface) {

        /*
         * Detach only.
         *
         * The VirtualDisplay itself remains alive
         * inside V10ProjectionService.
         */
        V10ProjectionService
                .detachSurface();

        return true;
    }


    @Override
    public void onSurfaceTextureUpdated(
            SurfaceTexture surface) {
    }
}
