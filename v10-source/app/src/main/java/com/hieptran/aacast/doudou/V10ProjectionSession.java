package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.os.Process;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;

public final class V10ProjectionSession
        implements TextureView.SurfaceTextureListener {

    private static final String TAG =
            "CarAssistantV10Projection";

    private final Context context;

    private final RootShellSession rootShell =
            new RootShellSession();

    private TextureView textureView;

    private ComponentName target;

    private Display display;

    private android.hardware.display.VirtualDisplay
            virtualDisplay;

    private Surface surface;

    private boolean destroyed;

    private int contentWidth;

    private int contentHeight;

    public V10ProjectionSession(
            Context context) {

        this.context =
                context.getApplicationContext();
    }

    public void attachTextureView(
            TextureView view) {

        textureView = view;

        view.setSurfaceTextureListener(
                this);

        if (view.isAvailable()) {
            ensureDisplay();
        }
    }

    public void setTarget(
            ComponentName component) {

        if (component == null) {
            target = null;
            release();
            return;
        }

        boolean changed =
                !component.equals(target);

        target = component;

        if (changed
                && virtualDisplay != null) {
            releaseDisplayOnly();
        }

        ensureDisplay();
    }

    public boolean isActive() {

        return !destroyed
                && target != null
                && virtualDisplay != null
                && display != null;
    }

    private void ensureDisplay() {

        if (destroyed
                || target == null
                || textureView == null
                || !textureView.isAvailable()
                || virtualDisplay != null) {
            return;
        }

        SurfaceTexture st =
                textureView.getSurfaceTexture();

        if (st == null) {
            return;
        }

        int width =
                textureView.getWidth();

        int height =
                textureView.getHeight();

        if (width < 1 || height < 1) {
            return;
        }

        try {

            /*
             * IMPORTANT:
             * This is the V9 proven sequence.
             */

            st.setDefaultBufferSize(
                    width,
                    height);

            Surface newSurface =
                    new Surface(st);

            DisplayManager manager =
                    (DisplayManager)
                            context.getSystemService(
                                    Context.DISPLAY_SERVICE);

            if (manager == null) {

                newSurface.release();

                throw new IllegalStateException(
                        "DisplayManager unavailable");
            }

            android.hardware.display.VirtualDisplay
                    newVirtualDisplay =
                    manager.createVirtualDisplay(
                            V10Display.nameFor(0),
                            width,
                            height,
                            160,
                            newSurface,
                            10);

            if (newVirtualDisplay == null) {

                newSurface.release();

                throw new IllegalStateException(
                        "Cannot create virtual display");
            }

            Display newDisplay =
                    newVirtualDisplay.getDisplay();

            if (newDisplay == null) {

                newVirtualDisplay.release();
                newSurface.release();

                throw new IllegalStateException(
                        "Virtual display has no Display");
            }

            surface =
                    newSurface;

            virtualDisplay =
                    newVirtualDisplay;

            display =
                    newDisplay;

            contentWidth =
                    width;

            contentHeight =
                    height;

            launchTarget();

        } catch (RuntimeException e) {

            release();

            Log.w(
                    TAG,
                    "Display creation failed",
                    e);
        }
    }

    private void launchTarget() {

        if (display == null
                || target == null) {
            return;
        }

        final int displayId =
                display.getDisplayId();

        final int userId =
                Process.myUid() / 100000;

        final String packageName =
                target.getPackageName()
                        .replace(
                                "'",
                                "'\"'\"'");

        final String flat =
                target.flattenToString()
                        .replace(
                                "'",
                                "'\"'\"'");

        final String command =
                "/system/bin/am force-stop '"
                        + packageName
                        + "'; "
                        + "/system/bin/am start"
                        + " --user "
                        + userId
                        + " --display "
                        + displayId
                        + " --windowingMode 1"
                        + " -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER"
                        + " -f 0x18000000"
                        + " -n '"
                        + flat
                        + "'";

        RootShellSession.EXEC.execute(
                () -> {

                    try {

                        rootShell.run(
                                10,
                                command);

                    } catch (Throwable t) {

                        Log.w(
                                TAG,
                                "Launch failed",
                                t);
                    }
                });
    }

    private float mapX(float x) {

        if (textureView == null
                || contentWidth < 1) {
            return x;
        }

        float sourceWidth =
                textureView.getWidth();

        if (sourceWidth <= 0) {
            return x;
        }

        return clamp(
                x * contentWidth / sourceWidth,
                0,
                contentWidth - 1);
    }

    private float mapY(float y) {

        if (textureView == null
                || contentHeight < 1) {
            return y;
        }

        float sourceHeight =
                textureView.getHeight();

        if (sourceHeight <= 0) {
            return y;
        }

        return clamp(
                y * contentHeight / sourceHeight,
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

    public void tap(
            float x,
            float y) {

        if (!isActive()) {
            return;
        }

        final int displayId =
                display.getDisplayId();

        final int ix =
                Math.round(
                        mapX(x));

        final int iy =
                Math.round(
                        mapY(y));

        final String command =
                "/system/bin/input -d "
                        + displayId
                        + " tap "
                        + ix
                        + " "
                        + iy;

        RootShellSession.EXEC.execute(
                () -> {

                    try {

                        rootShell.run(
                                5,
                                command);

                    } catch (Throwable t) {

                        Log.w(
                                TAG,
                                "Tap failed",
                                t);
                    }
                });
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

        final int displayId =
                display.getDisplayId();

        final int x1 =
                Math.round(
                        mapX(startX));

        final int y1 =
                Math.round(
                        mapY(startY));

        final int x2 =
                Math.round(
                        mapX(endX));

        final int y2 =
                Math.round(
                        mapY(endY));

        final long safeDuration =
                Math.max(
                        80L,
                        Math.min(
                                800L,
                                duration));

        final String command =
                "/system/bin/input -d "
                        + displayId
                        + " swipe "
                        + x1
                        + " "
                        + y1
                        + " "
                        + x2
                        + " "
                        + y2
                        + " "
                        + safeDuration;

        RootShellSession.EXEC.execute(
                () -> {

                    try {

                        rootShell.run(
                                5,
                                command);

                    } catch (Throwable t) {

                        Log.w(
                                TAG,
                                "Swipe failed",
                                t);
                    }
                });
    }

    public void back() {

        if (!isActive()) {
            return;
        }

        final int displayId =
                display.getDisplayId();

        final String command =
                "/system/bin/input -d "
                        + displayId
                        + " keyevent 4";

        RootShellSession.EXEC.execute(
                () -> {

                    try {

                        rootShell.run(
                                5,
                                command);

                    } catch (Throwable t) {

                        Log.w(
                                TAG,
                                "Back failed",
                                t);
                    }
                });
    }

    private void releaseDisplayOnly() {

        if (virtualDisplay != null) {

            try {
                virtualDisplay.release();
            } catch (Throwable ignored) {
            }
        }

        virtualDisplay = null;
        display = null;

        if (surface != null) {

            try {
                surface.release();
            } catch (Throwable ignored) {
            }
        }

        surface = null;
    }

    public void release() {
        releaseDisplayOnly();
    }

    public void destroy() {

        if (destroyed) {
            return;
        }

        destroyed = true;

        release();

        rootShell.destroy();
    }

    @Override
    public void onSurfaceTextureAvailable(
            SurfaceTexture surface,
            int width,
            int height) {

        ensureDisplay();
    }

    @Override
    public void onSurfaceTextureSizeChanged(
            SurfaceTexture surface,
            int width,
            int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(
            SurfaceTexture surface) {

        releaseDisplayOnly();

        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(
            SurfaceTexture surface) {
    }
}
