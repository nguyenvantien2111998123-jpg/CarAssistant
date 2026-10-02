
package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import java.util.concurrent.atomic.AtomicBoolean;

public final class V10ProjectionSession
        implements TextureView.SurfaceTextureListener {

    private static final String TAG =
            "CarAssistantV10";

    private final Context context;
    private final TextureView texture;
    private final int slot;

    /*
     * Persistent root shell.
     * Dùng chung cho launch/input.
     */
    private final RootShellSession shell;

    private final AtomicBoolean inputBusy =
            new AtomicBoolean(false);

    private volatile VirtualDisplay display;
    private volatile Surface surface;
    private volatile int displayId = -1;

    private ComponentName target;

    private boolean launching;

    public V10ProjectionSession(
            Context context,
            TextureView texture,
            int slot) {

        this.context = context;
        this.texture = texture;
        this.slot = slot;

        shell = new RootShellSession();

        texture.setSurfaceTextureListener(this);

        texture.setOnTouchListener(
                this::onTouch);
    }

    public synchronized void setTarget(
            ComponentName component) {

        stop();

        target = null;

        if (component == null) {
            return;
        }

        try {

            ActivityInfo ai =
                    context.getPackageManager()
                            .getActivityInfo(
                                    component,
                                    0);

            if (!ai.enabled) {
                return;
            }

            if (!ai.exported) {
                return;
            }

            if (!ai.applicationInfo.enabled) {
                return;
            }

            if (context.getPackageName()
                    .equals(ai.packageName)) {
                return;
            }

            target = component;

        } catch (
                PackageManager.NameNotFoundException ignored) {

            return;
        }

        if (texture.isAvailable()) {
            start();
        }
    }

    public int getDisplayId() {
        return displayId;
    }

    public boolean isRunning() {
        return display != null &&
               displayId > 0;
    }

    private synchronized void start() {

        if (display != null) {
            return;
        }

        if (target == null) {
            return;
        }

        if (!texture.isAvailable()) {
            return;
        }

        int width = texture.getWidth();
        int height = texture.getHeight();

        if (width < 1 || height < 1) {
            return;
        }

        SurfaceTexture surfaceTexture =
                texture.getSurfaceTexture();

        if (surfaceTexture == null) {
            return;
        }

        try {

            surfaceTexture.setDefaultBufferSize(
                    width,
                    height);

            Surface newSurface =
                    new Surface(surfaceTexture);

            VirtualDisplay newDisplay =
                    ((DisplayManager)
                            context.getSystemService(
                                    Context.DISPLAY_SERVICE))
                            .createVirtualDisplay(
                                    "Car Assistant V10 Slot "
                                            + (slot + 1),
                                    width,
                                    height,
                                    160,
                                    newSurface,
                                    10);

            if (newDisplay == null) {

                newSurface.release();

                throw new IllegalStateException(
                        "VirtualDisplay == null");
            }

            surface = newSurface;
            display = newDisplay;

            displayId =
                    newDisplay
                            .getDisplay()
                            .getDisplayId();

            Log.d(
                    TAG,
                    "VirtualDisplay created: "
                            + displayId);

            launch();

        } catch (RuntimeException e) {

            Log.w(
                    TAG,
                    "Projection start failed",
                    e);

            stop();
        }
    }

    private void launch() {

        if (launching) {
            return;
        }

        if (target == null) {
            return;
        }

        int id = displayId;

        if (id <= 0) {
            return;
        }

        final String flat =
                target.flattenToString();

        if (!flat.matches(
                "[A-Za-z_][A-Za-z0-9_]*" +
                "(?:\\.[A-Za-z_][A-Za-z0-9_]*)*" +
                "/\\.?[A-Za-z_][A-Za-z0-9_]*" +
                "(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")) {

            Log.w(
                    TAG,
                    "Invalid target: " + flat);

            return;
        }

        /*
         * V9 launch logic được giữ nguyên hướng:
         * chạy am start qua root shell.
         */
        final int userId = 0;

        final String command =
                "/system/bin/am start" +
                " --user " + userId +
                " --display " + id +
                " --windowingMode 1" +
                " -a android.intent.action.MAIN" +
                " -c android.intent.category.LAUNCHER" +
                " -f 0x18000000" +
                " -n '" +
                flat.replace(
                        "'",
                        "'\"'\"'") +
                "'";

        launching = true;

        RootShellSession.EXEC.execute(() -> {

            try {

                shell.run(
                        15,
                        command);

                Log.d(
                        TAG,
                        "Launched "
                                + flat
                                + " on display "
                                + id);

            } catch (Exception e) {

                Log.w(
                        TAG,
                        "Launch failed",
                        e);

            } finally {

                launching = false;
            }
        });
    }

    private boolean onTouch(
            View view,
            MotionEvent event) {

        final int id = displayId;

        if (id <= 0) {
            return true;
        }

        if (event.getActionMasked()
                != MotionEvent.ACTION_UP) {
            return true;
        }

        final float x = event.getX();
        final float y = event.getY();

        if (!inputBusy.compareAndSet(
                false,
                true)) {
            return true;
        }

        final String command =
                "input touchscreen -d "
                + id
                + " tap "
                + Math.round(x)
                + " "
                + Math.round(y);

        RootShellSession.EXEC.execute(() -> {

            try {

                shell.run(
                        8,
                        command);

            } catch (Exception e) {

                Log.w(
                        TAG,
                        "Input failed",
                        e);

            } finally {

                inputBusy.set(false);
            }
        });

        return true;
    }

    public synchronized void stop() {

        VirtualDisplay oldDisplay =
                display;

        Surface oldSurface =
                surface;

        display = null;
        surface = null;
        displayId = -1;
        launching = false;

        if (oldDisplay != null) {
            oldDisplay.release();
        }

        if (oldSurface != null) {
            oldSurface.release();
        }
    }

    public synchronized void destroy() {
        stop();
    }

    @Override
    public void onSurfaceTextureAvailable(
            SurfaceTexture surface,
            int width,
            int height) {

        start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(
            SurfaceTexture surface,
            int width,
            int height) {

        if (display == null) {
            return;
        }

        if (width < 1 || height < 1) {
            return;
        }

        try {

            surface.setDefaultBufferSize(
                    width,
                    height);

            display.resize(
                    width,
                    height,
                    160);

        } catch (RuntimeException e) {

            Log.w(
                    TAG,
                    "Resize failed",
                    e);

            stop();
            start();
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(
            SurfaceTexture surface) {

        stop();

        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(
            SurfaceTexture surface) {
    }
}

