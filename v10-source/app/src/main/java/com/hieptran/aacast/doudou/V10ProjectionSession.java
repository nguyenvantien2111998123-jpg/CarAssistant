package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;

/**
 * One V10 projection session.
 *
 * This deliberately follows the proven V9 projection sequence:
 *
 * TextureView Surface available
 * -> setDefaultBufferSize
 * -> Surface
 * -> createVirtualDisplay(flags=10)
 * -> am start --display
 */
public final class V10ProjectionSession
        implements TextureView.SurfaceTextureListener {

    private static final String TAG = "CarAssistant-V10";

    private final Context context;
    private final RootShellSession shell;
    private final V10InputController input;

    private TextureView textureView;

    private VirtualDisplay display;
    private Surface surface;

    private ComponentName target;

    private int bufferW = 1280;
    private int bufferH = 720;

    private int generation;
    private boolean destroyed;

    public V10ProjectionSession(Context context) {
        this.context = context;
        this.shell = new RootShellSession();
        this.input = new V10InputController(shell);
    }

    public void attachTextureView(TextureView view) {
        if (textureView == view) {
            return;
        }

        if (textureView != null) {
            textureView.setSurfaceTextureListener(null);
        }

        textureView = view;

        if (textureView != null) {
            textureView.setSurfaceTextureListener(this);

            if (textureView.isAvailable()) {
                onSurfaceTextureAvailable(
                        textureView.getSurfaceTexture(),
                        textureView.getWidth(),
                        textureView.getHeight());
            }
        }
    }

    public void setTarget(ComponentName component) {
        target = component;
        ensureDisplay();
    }

    public ComponentName getTarget() {
        return target;
    }

    public int getDisplayId() {
        return display == null ? -1 : display.getDisplay().getDisplayId();
    }

    public boolean isActive() {
        return !destroyed && display != null;
    }

    private void ensureDisplay() {
        if (destroyed
                || target == null
                || textureView == null
                || !textureView.isAvailable()
                || textureView.getWidth() < 1
                || textureView.getHeight() < 1
                || display != null) {
            return;
        }

        int localGeneration = ++generation;

        try {
            computeBuffer(
                    textureView.getWidth(),
                    textureView.getHeight());

            SurfaceTexture texture = textureView.getSurfaceTexture();

            if (texture == null) {
                return;
            }

            texture.setDefaultBufferSize(bufferW, bufferH);

            Surface newSurface = new Surface(texture);

            VirtualDisplay newDisplay = null;

            try {
                DisplayManager dm =
                        (DisplayManager) context.getSystemService(
                                Context.DISPLAY_SERVICE);

                if (dm == null) {
                    throw new IllegalStateException(
                            "DisplayManager unavailable");
                }

                newDisplay = dm.createVirtualDisplay(
                        V10Display.nameFor(0),
                        bufferW,
                        bufferH,
                        160,
                        newSurface,
                        10);

                if (newDisplay == null) {
                    throw new IllegalStateException(
                            "Cannot create virtual display");
                }

                display = newDisplay;
                surface = newSurface;

            } catch (RuntimeException e) {
                newSurface.release();
                throw e;
            }

            if (localGeneration != generation || destroyed) {
                release();
                return;
            }

            launch(localGeneration);

        } catch (RuntimeException e) {
            release();
            Log.e(TAG,
                    "Virtual display creation failed",
                    e);
        }
    }

    private void computeBuffer(int viewW, int viewH) {
        float aspect =
                viewW > 0 && viewH > 0
                        ? (float) viewW / (float) viewH
                        : (16f / 9f);

        if (aspect >= 1f) {
            bufferW = 1280;
            bufferH = Math.max(
                    480,
                    Math.round(bufferW / aspect));
        } else {
            bufferH = 1280;
            bufferW = Math.max(
                    480,
                    Math.round(bufferH * aspect));
        }
    }

    private void launch(int localGeneration) {
        if (destroyed
                || display == null
                || target == null
                || localGeneration != generation) {
            return;
        }

        int displayId =
                display.getDisplay().getDisplayId();

        if (displayId <= 0) {
            throw new IllegalStateException(
                    "Invalid virtual display id: "
                            + displayId);
        }

        ActivityInfo info = resolveTarget(target);

        if (info == null) {
            throw new IllegalStateException(
                    "Target activity unavailable: "
                            + target.flattenToShortString());
        }

        int userId = Process.myUid() / 100000;

        String flat =
                target.flattenToString()
                        .replace(
                                "'",
                                "'\"'\"'");

        String cmd =
                "/system/bin/am start --user "
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

        final int expectedDisplay = displayId;

        RootShellSession.EXEC.execute(() -> {
            try {
                shell.run(15, cmd);

                if (destroyed
                        || generation != localGeneration
                        || display == null
                        || getDisplayId() != expectedDisplay) {
                    return;
                }

                Log.i(
                        TAG,
                        "Launched "
                                + target.flattenToShortString()
                                + " on display "
                                + expectedDisplay);

            } catch (RuntimeException e) {
                if (!destroyed
                        && generation == localGeneration) {
                    Log.e(
                            TAG,
                            "Launch failed: "
                                    + target.flattenToShortString(),
                            e);
                }
            }
        });
    }

    private ActivityInfo resolveTarget(ComponentName component) {
        try {
            PackageManager pm =
                    context.getPackageManager();

            ActivityInfo ai =
                    pm.getActivityInfo(
                            component,
                            PackageManager.GET_META_DATA);

            if (!ai.enabled
                    || !ai.exported
                    || !ai.applicationInfo.enabled) {
                return null;
            }

            if (ai.permission != null
                    && context.checkSelfPermission(ai.permission)
                            != PackageManager.PERMISSION_GRANTED) {
                return null;
            }

            if (context.getPackageName().equals(
                    component.getPackageName())) {
                return null;
            }

            return ai;

        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    public void sendTap(float viewX, float viewY) {
        if (display == null || textureView == null) {
            return;
        }

        int id = getDisplayId();

        float sx =
                bufferW
                        / (float) Math.max(
                                1,
                                textureView.getWidth());

        float sy =
                bufferH
                        / (float) Math.max(
                                1,
                                textureView.getHeight());

        input.tap(
                id,
                viewX * sx,
                viewY * sy);
    }

    public void sendSwipe(
            float x1,
            float y1,
            float x2,
            float y2,
            long durationMs) {

        if (display == null || textureView == null) {
            return;
        }

        int id = getDisplayId();

        float sx =
                bufferW
                        / (float) Math.max(
                                1,
                                textureView.getWidth());

        float sy =
                bufferH
                        / (float) Math.max(
                                1,
                                textureView.getHeight());

        input.swipe(
                id,
                x1 * sx,
                y1 * sy,
                x2 * sx,
                y2 * sy,
                durationMs);
    }

    public void sendBack() {
        if (display != null) {
            input.back(getDisplayId());
        }
    }

    public void release() {
        generation++;

        if (display != null) {
            try {
                display.release();
            } catch (RuntimeException ignored) {
            }
        }

        display = null;

        if (surface != null) {
            try {
                surface.release();
            } catch (RuntimeException ignored) {
            }
        }

        surface = null;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }

        destroyed = true;

        if (textureView != null) {
            textureView.setSurfaceTextureListener(null);
        }

        release();
        shell.destroy();
    }

    @Override
    public void onSurfaceTextureAvailable(
            SurfaceTexture surface,
            int width,
            int height) {

        if (surface != null) {
            surface.setDefaultBufferSize(
                    Math.max(1, bufferW),
                    Math.max(1, bufferH));
        }

        ensureDisplay();
    }

    @Override
    public void onSurfaceTextureSizeChanged(
            SurfaceTexture surface,
            int width,
            int height) {

        if (surface != null) {
            computeBuffer(width, height);
            surface.setDefaultBufferSize(
                    bufferW,
                    bufferH);
        }

        if (display != null) {
            try {
                display.resize(
                        bufferW,
                        bufferH,
                        160);
            } catch (RuntimeException e) {
                Log.w(
                        TAG,
                        "Display resize failed",
                        e);
            }
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(
            SurfaceTexture surface) {

        release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(
            SurfaceTexture surface) {
    }
}
