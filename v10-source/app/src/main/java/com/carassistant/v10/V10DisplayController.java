package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.os.Process;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import java.util.HashMap;
import java.util.Map;

public final class V10DisplayController {
    private static final String TAG = "CarAssistantV10DisplayCore";
    private static final Map<Integer, V10DisplayController> INSTANCES = new HashMap<>();
    private final int displayIndex;

    private final Context context;
    private final RootShellSession rootShell = new RootShellSession();
    private android.hardware.display.VirtualDisplay virtualDisplay;
    private Display display;
    private Surface surface;
    private ComponentName target;
    private boolean launched;
    private boolean destroyed;

    private V10DisplayController(Context context, int displayIndex) {
        this.context = context.getApplicationContext();
        this.displayIndex = Math.max(0, displayIndex);
    }

    public static synchronized V10DisplayController get(Context context) {
        return get(context, 0);
    }

    public static synchronized V10DisplayController get(Context context, int displayIndex) {
        int index = Math.max(0, displayIndex);
        V10DisplayController controller = INSTANCES.get(index);
        if (controller == null || controller.destroyed) {
            controller = new V10DisplayController(context, index);
            INSTANCES.put(index, controller);
        }
        return controller;
    }

    public synchronized boolean ensureDisplay(SurfaceTexture texture, int width, int height) {
        if (destroyed || texture == null || width < 1 || height < 1) return false;
        texture.setDefaultBufferSize(width, height);
        return ensureDisplay(new Surface(texture), width, height);
    }

    /**
     * Creates or rebinds a virtual display to an owned Surface.
     * Ownership of the supplied Surface transfers to this controller.
     */
    public synchronized boolean ensureDisplay(Surface newSurface, int width, int height) {
        if (destroyed || newSurface == null || width < 1 || height < 1) {
            if (newSurface != null) {
                try { newSurface.release(); } catch (Throwable ignored) { }
            }
            return false;
        }
        if (virtualDisplay != null && display != null) {
            replaceSurface(newSurface);
            virtualDisplay.setSurface(newSurface);
            return true;
        }
        try {
            DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (manager == null) {
                newSurface.release();
                throw new IllegalStateException("DisplayManager unavailable");
            }
            android.hardware.display.VirtualDisplay created = manager.createVirtualDisplay(
                    V10Display.nameFor(displayIndex), width, height, 160, newSurface, 10);
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

    /** Starts a VIEW URI inside this exact virtual display, never the phone display. */
    public synchronized void launchViewIntentOnDisplay(String packageName, String uri) {
        if (!isReady() || packageName == null || packageName.trim().isEmpty()
                || uri == null || uri.trim().isEmpty()) {
            Log.w(TAG, "Refusing invalid background VIEW intent");
            return;
        }
        final int displayId = display.getDisplayId();
        final int userId = Process.myUid() / 100000;
        final String command = "/system/bin/am start"
                + " --user " + userId
                + " --display " + displayId
                + " --windowingMode 1"
                + " -a android.intent.action.VIEW"
                + " -d " + shellQuote(uri)
                + " -p " + shellQuote(packageName)
                // Force an independent task on the requested projection display. Without MULTIPLE_TASK,
                // Android may reuse the app's existing phone task and deliver the search there.
                + " -f 0x18000000";
        execute(command, "Background VIEW intent failed");
    }

    /**
     * Starts a media deep link only on the requested projection display.
     *
     * Media apps sometimes reuse a singleTask/singleInstance activity that is already
     * running on the phone, even when am start includes --display and MULTIPLE_TASK.
     * For voice-driven media only, stop that package first, resolve the VIEW handler
     * inside the package, and start that exact component on the projection display.
     * This prevents the phone-side task from stealing the search flow.
     */
    public synchronized void launchMediaViewIntentOnDisplay(String packageName, String uri) {
        if (!isReady() || packageName == null || packageName.trim().isEmpty()
                || uri == null || uri.trim().isEmpty()) {
            Log.w(TAG, "Refusing invalid media VIEW intent");
            return;
        }
        final int displayId = display.getDisplayId();
        final int userId = Process.myUid() / 100000;
        final String quotedPackage = shellQuote(packageName);
        final String quotedUri = shellQuote(uri);
        final String command = "/system/bin/am force-stop --user " + userId + " " + quotedPackage
                + "; RESOLVED=$(/system/bin/cmd package resolve-activity --brief --user " + userId
                + " -a android.intent.action.VIEW -d " + quotedUri + " -p " + quotedPackage
                + " 2>/dev/null | /system/bin/tail -n 1); "
                + "if [ -n \"$RESOLVED\" ] && [ \"$RESOLVED\" != \"No activity found\" ]; then "
                + "/system/bin/am start --user " + userId
                + " --display " + displayId
                + " --windowingMode 1"
                + " -n \"$RESOLVED\""
                + " -a android.intent.action.VIEW"
                + " -d " + quotedUri
                + " -f 0x18000000"
                + "; else /system/bin/am start --user " + userId
                + " --display " + displayId
                + " --windowingMode 1"
                + " -a android.intent.action.VIEW"
                + " -d " + quotedUri
                + " -p " + quotedPackage
                + " -f 0x18000000; fi";
        execute(command, "Media VIEW intent failed");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /** Sends a standard Android ACTION_SEARCH request to an app on this display. */
    public synchronized void launchSearchIntentOnDisplay(String packageName, String query) {
        if (!isReady() || packageName == null || packageName.trim().isEmpty()
                || query == null || query.trim().isEmpty()) {
            Log.w(TAG, "Refusing invalid SEARCH intent");
            return;
        }
        final int displayId = display.getDisplayId();
        final int userId = Process.myUid() / 100000;
        final String command = "/system/bin/am start"
                + " --user " + userId
                + " --display " + displayId
                + " --windowingMode 1"
                + " -a android.intent.action.SEARCH"
                + " --es query " + shellQuote(query.trim())
                + " -p " + shellQuote(packageName)
                + " -f 0x10000000";
        execute(command, "Display SEARCH intent failed");
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

    /**
     * Prefer moving the existing task for the active media package onto the
     * projection display. If Android does not support the task move command,
     * fall back to launching the selected component on the projection display.
     */
    public synchronized void launchMediaTaskOnDisplay(ComponentName component) {
        if (!isReady() || component == null) return;
        target = component;
        launched = true;
        final int displayId = display.getDisplayId();
        final int userId = Process.myUid() / 100000;
        final String flat = shellQuote(component.flattenToString());
        final String packageSelector = shellQuote("A=" + component.getPackageName());
        final String command = "TASK_ID=$(/system/bin/dumpsys activity recents 2>/dev/null"
                + " | /system/bin/grep -F " + packageSelector
                + " | /system/bin/sed -n 's/.*#[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p'"
                + " | /system/bin/head -n 1); "
                + "if [ -n \"$TASK_ID\" ] && /system/bin/am task move-to-display"
                + " \"$TASK_ID\" " + displayId + " >/dev/null 2>&1; then "
                + "echo 'CarAsistant: restored existing media task on projection display'; "
                + "else /system/bin/am start --user " + userId
                + " --display " + displayId
                + " --windowingMode 1"
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.LAUNCHER"
                + " -f 0x10000000"
                + " -n " + flat + "; fi";
        execute(command, "Media task restore failed; attempted projection launch");
    }

    /**
     * Removes only the task belonging to this controller's projection display.
     * Never force-stops the package, because the same package may have a phone-side task.
     */
    public void closeTaskOnDisplay(ComponentName component, java.util.function.Consumer<Boolean> callback) {
        if (!isReady() || component == null) {
            if (callback != null) callback.accept(false);
            return;
        }
        final int displayId = display.getDisplayId();
        final String packageName = shellQuote(component.getPackageName());
        final String command = "DISPLAY_ID=" + displayId
                + "; PACKAGE=" + packageName
                + "; TASK_ID=$(/system/bin/dumpsys activity activities 2>/dev/null"
                + " | /system/bin/awk -v wantedDisplay=\"$DISPLAY_ID\" -v wantedPackage=\"$PACKAGE\" '"
                + "/^[[:space:]]*Display #[[:space:]]*[0-9]+/ { d=\\$0; sub(/^.*Display #[[:space:]]*/, \"\", d); sub(/[^0-9].*$/, \"\", d); display=d+0 }"
                + " /^[[:space:]]*Task[{]/ { if (taskId != \"\" && taskDisplay == wantedDisplay && taskHasPackage) { print taskId; done=1; exit }"
                + " taskId=\\$0; sub(/^.*#[[:space:]]*/, \"\", taskId); sub(/[^0-9].*$/, \"\", taskId);"
                + " taskDisplay=display; taskHasPackage=0 }"
                + " /mDisplayId=/ { d=\\$0; sub(/^.*mDisplayId=/, \"\", d); sub(/[^0-9].*$/, \"\", d); if (d != \"\") taskDisplay=d+0 }"
                + " { if (taskId != \"\" && index(\\$0, wantedPackage) > 0) taskHasPackage=1 }"
                + " END { if (!done && taskId != \"\" && taskDisplay == wantedDisplay && taskHasPackage) print taskId }'"
                + " | /system/bin/head -n 1); "
                + "if [ -n \"$TASK_ID\" ] && /system/bin/am task remove \"$TASK_ID\" >/dev/null 2>&1; then"
                + " echo TASK_CLOSED; else echo TASK_CLOSE_FAILED; exit 3; fi";
        RootShellSession.EXEC.execute(() -> {
            boolean closed = false;
            try {
                String output = rootShell.run(12, command);
                closed = output.contains("TASK_CLOSED");
            } catch (Throwable t) {
                Log.w(TAG, "Could not close the matching projection task", t);
            }
            final boolean result = closed;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (callback != null) callback.accept(result);
            });
        });
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
        synchronized (V10DisplayController.class) {
            if (INSTANCES.get(displayIndex) == this) INSTANCES.remove(displayIndex);
        }
    }
}
