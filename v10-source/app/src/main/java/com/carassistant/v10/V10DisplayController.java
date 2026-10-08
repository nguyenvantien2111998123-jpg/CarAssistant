package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.os.Process;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

public final class V10DisplayController {
    private static final String TAG = "CarAssistantV10DisplayCore";
    private static V10DisplayController instance;

    private final Context context;
    private final RootShellSession rootShell = new RootShellSession();
    private android.hardware.display.VirtualDisplay virtualDisplay;
    private Display display;
    private Surface surface;
    private ComponentName target;
    private boolean launched;
    private boolean destroyed;

    private V10DisplayController(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized V10DisplayController get(Context context) {
        if (instance == null) instance = new V10DisplayController(context);
        return instance;
    }

    public synchronized boolean ensureDisplay(SurfaceTexture texture, int width, int height) {
        if (destroyed || texture == null || width < 1 || height < 1) return false;
        if (virtualDisplay != null && display != null) {
            attachSurface(texture, width, height);
            return true;
        }
        try {
            texture.setDefaultBufferSize(width, height);
            Surface newSurface = new Surface(texture);
            DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (manager == null) {
                newSurface.release();
                throw new IllegalStateException("DisplayManager unavailable");
            }
            android.hardware.display.VirtualDisplay created = manager.createVirtualDisplay(
                    V10Display.nameFor(0), width, height, 160, newSurface, 10);
            if (created == null) {
                newSurface.release();
                throw new IllegalStateException("Cannot create virtual display");
            }
            Display createdDisplay = created.getDisplay();
            if (createdDisplay == null) {
                created.release();
                newSurface.release();
                throw new IllegalStateException("Virtual display has no Display");
            }
            virtualDisplay = created;
            display = createdDisplay;
            replaceSurface(newSurface);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Display creation failed", e);
            return false;
        }
    }

    public synchronized void attachSurface(SurfaceTexture texture, int width, int height) {
        if (destroyed || virtualDisplay == null || texture == null) return;
        texture.setDefaultBufferSize(width, height);
        Surface newSurface = new Surface(texture);
        replaceSurface(newSurface);
        virtualDisplay.setSurface(newSurface);
    }

    public synchronized void detachSurface() {
        if (virtualDisplay != null) {
            try { virtualDisplay.setSurface(null); }
            catch (Throwable t) { Log.w(TAG, "Display surface detach failed", t); }
        }
        releaseSurfaceOnly();
    }

    private void replaceSurface(Surface newSurface) {
        releaseSurfaceOnly();
        surface = newSurface;
    }

    private void releaseSurfaceOnly() {
        if (surface != null) {
            try { surface.release(); } catch (Throwable ignored) { }
        }
        surface = null;
    }

    public synchronized int getDisplayId() {
        return display == null ? -1 : display.getDisplayId();
    }

    public synchronized boolean isReady() {
        return !destroyed && virtualDisplay != null && display != null;
    }

    public synchronized boolean isLaunched() { return launched; }
    public synchronized ComponentName getTarget() { return target; }

    public synchronized void launchNewTask(ComponentName component) {
        if (!isReady() || component == null) return;
        target = component;
        launched = true;
        final int displayId = display.getDisplayId();
        final int userId = Process.myUid() / 100000;
        final String flat = component.flattenToString().replace("'", "'\"'\"'");
        final String command = "/system/bin/am start"
                + " --user " + userId
                + " --display " + displayId
                + " --windowingMode 1"
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.LAUNCHER"
                + " -f 0x10000000"
                + " -n '" + flat + "'";
        execute(command, "Launch failed");
    }

    public synchronized void resumeExistingTask(ComponentName component) {
        if (!isReady() || component == null) return;
        target = component;
        final int displayId = display.getDisplayId();
        final int userId = Process.myUid() / 100000;
        final String flat = component.flattenToString().replace("'", "'\"'\"'");
        final String command = "/system/bin/am start"
                + " --user " + userId
                + " --display " + displayId
                + " --windowingMode 1"
                + " --activity-reorder-to-front"
                + " -f 0x10000000"
                + " -n '" + flat + "'";
        execute(command, "Resume failed");
    }

    private void execute(String command, String message) {
        RootShellSession.EXEC.execute(() -> {
            try { rootShell.run(10, command); }
            catch (Throwable t) { Log.w(TAG, message, t); }
        });
    }

    public synchronized void markNotLaunched() {
        launched = false;
        target = null;
    }

    public synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) { }
        }
        virtualDisplay = null;
        display = null;
        releaseSurfaceOnly();
        rootShell.destroy();
        if (instance == this) instance = null;
    }
}
