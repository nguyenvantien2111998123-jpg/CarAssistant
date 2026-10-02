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

    public V10ProjectionSession(Context context) {
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

            // Proven V9 sequence.
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

    public void tap(
            float x,
            float y) {

        if (!isActive()) {
            return;
        }

        final int displayId =
                display.getDisplayId();

        final int ix =
                Math.max(
                        0,
                        Math.round(x));

        final int iy =
                Math.max(
                        0,
                        Math.round(y));

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
