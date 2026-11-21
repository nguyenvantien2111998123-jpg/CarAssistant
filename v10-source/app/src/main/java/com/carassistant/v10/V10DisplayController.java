package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class V10DisplayController {
    private static final String TAG = "CarAssistantV10DisplayCore";
    private static final Map<Integer, V10DisplayController> INSTANCES = new HashMap<>();
    // Only packages this controller has launched into a projection flow may be closed.
    private final Set<String> managedPackages = new HashSet<>();
    private final int displayIndex;

    private final Context context;
    private final RootShellSession rootShell = new RootShellSession();
    private android.hardware.display.VirtualDisplay virtualDisplay;
    private Display display;
    private Surface surface;
    private ComponentName target;
    private boolean launched;
    private boolean launchInProgress;
    private int launchAttempts;
    private long lastLaunchAttemptAt;
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
            return bindSurface(newSurface);
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
            surface = newSurface; // createVirtualDisplay already attached this Surface.
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Display creation failed", e);
            return false;
        }
    }

    public synchronized boolean attachSurface(SurfaceTexture texture, int width, int height) {
        if (destroyed || virtualDisplay == null || texture == null || width < 1 || height < 1) {
            return false;
        }
        texture.setDefaultBufferSize(width, height);
        try {
            // Headless mode starts at a safe 16:9 size. Once Android Auto creates
            // the real TextureView, resize Display 2 to its exact content bounds
            // before swapping producers.
            virtualDisplay.resize(width, height, 160);
        } catch (RuntimeException error) {
            Log.w(TAG, "Virtual display resize deferred; keeping current bounds", error);
        }
        return bindSurface(new Surface(texture));
    }

    /** Resize the pane's VirtualDisplay when its TextureView bounds change. */
    public synchronized void resizeDisplay(int width, int height) {
        if (destroyed || virtualDisplay == null || width < 1 || height < 1) return;
        try {
            virtualDisplay.resize(width, height, 160);
        } catch (RuntimeException error) {
            Log.w(TAG, "Virtual display resize deferred for pane " + displayIndex, error);
        }
    }

    public synchronized void detachSurface() {
        if (virtualDisplay != null) {
            try { virtualDisplay.setSurface(null); }
            catch (Throwable t) { Log.w(TAG, "Display surface detach failed", t); }
        }
        releaseSurfaceOnly();
    }

    /**
     * Bind the new producer before releasing the previous one. Releasing the
     * ImageReader Surface first can leave the virtual display without a producer
     * for a frame and cause a black screen during headless-to-TextureView handoff.
     */
    private boolean bindSurface(Surface newSurface) {
        if (newSurface == null || virtualDisplay == null) return false;
        Surface previous = surface;
        try {
            virtualDisplay.setSurface(newSurface);
            surface = newSurface;
            if (previous != null && previous != newSurface) {
                try { previous.release(); } catch (Throwable ignored) { }
            }
            return true;
        } catch (Throwable error) {
            try { newSurface.release(); } catch (Throwable ignored) { }
            Log.w(TAG, "Could not bind virtual display Surface", error);
            return false;
        }
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
        // Reuse an existing task when possible. This is essential for apps whose
        // launcher Activity is singleTask/singleInstance: a second am start can
        // simply route to the phone-side instance instead of creating a task on
        // the requested virtual display.
        launchMediaTaskOnDisplay(component, -1);
    }

    /** Starts a VIEW URI inside this exact virtual display, never the phone display. */
    public synchronized void launchViewIntentOnDisplay(String packageName, String uri) {
        if (!isReady() || packageName == null || packageName.trim().isEmpty()
                || uri == null || uri.trim().isEmpty()) {
            Log.w(TAG, "Refusing invalid background VIEW intent");
            return;
        }
        managedPackages.add(packageName);
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
     * Opens the exact checkpoint URI in the existing task on this display.
     * CLEAR_TOP reuses the just-launched source task instead of creating a second
     * MULTIPLE_TASK root or routing the URI to the phone's default display.
     */
    public synchronized void launchCheckpointUriOnDisplay(String packageName, String uri) {
        if (!isReady() || packageName == null || packageName.trim().isEmpty()
                || uri == null || uri.trim().isEmpty()) {
            Log.w(TAG, "Refusing invalid checkpoint URI");
            return;
        }
        managedPackages.add(packageName);
        final int displayId = display.getDisplayId();
        final int userId = Process.myUid() / 100000;
        final String quotedPackage = shellQuote(packageName);
        final String quotedUri = shellQuote(uri);
        final String command = "RESOLVED=$(/system/bin/cmd package resolve-activity --brief --user "
                + userId + " -a android.intent.action.VIEW -d " + quotedUri
                + " -p " + quotedPackage + " 2>/dev/null | /system/bin/tail -n 1); "
                + "if [ -n \"$RESOLVED\" ] && [ \"$RESOLVED\" != \"No activity found\" ]; then "
                + "/system/bin/am start --user " + userId + " --display " + displayId
                + " --windowingMode 1 -n \"$RESOLVED\""
                + " -a android.intent.action.VIEW -d " + quotedUri + " -f 0x14000000; "
                + "else /system/bin/am start --user " + userId + " --display " + displayId
                + " --windowingMode 1 -a android.intent.action.VIEW -d " + quotedUri
                + " -p " + quotedPackage + " -f 0x14000000; fi";
        execute(command, "Checkpoint URI launch failed");
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
        managedPackages.add(packageName);
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
        managedPackages.add(packageName);
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
        managedPackages.add(component.getPackageName());
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
     * Moves the existing media root task from the phone display to this
     * projection display. Android 12 exposes this operation as
     * "am display move-stack <ROOT_TASK_ID> <DISPLAY_ID>".
     *
     * Do not launch a replacement task when lookup or move fails: doing so
     * leaves the original phone-side UI visible and creates a second task.
     */
    public synchronized void launchMediaTaskOnDisplay(ComponentName component) {
        launchMediaTaskOnDisplay(component, -1);
    }

    /**
     * Match the selected ActivityRecord inside a task, not just its package.
     * A package can own several independent tasks on the same or different displays.
     */
    public synchronized void launchMediaTaskOnDisplay(ComponentName component, int sourceDisplayId) {
        if (!isReady() || component == null) return;
        if (!component.equals(target)) launchAttempts = 0;
        managedPackages.add(component.getPackageName());
        target = component;
        launched = false;
        if (launchInProgress || launchAttempts >= 3) return;

        launchInProgress = true;
        launchAttempts++;
        lastLaunchAttemptAt = SystemClock.uptimeMillis();

        final int displayId = display.getDisplayId();
        final int userId = Process.myUid() / 100000;
        final String packageName = component.getPackageName();
        final String className = component.getClassName();
        RootShellSession.EXEC.execute(() -> {
            try {
                if (!isCurrentTarget(component)) return;

                ActivityRecordTaskParser.Snapshot snapshot = readActivitySnapshot(
                        packageName, className);
                java.util.List<Integer> destinationTasks = snapshot.matchingTaskIds(displayId);
                if (destinationTasks.size() > 1) {
                    throw new IllegalStateException(
                            "Multiple exact ActivityRecord tasks already exist on destination display");
                }

                int selectedTaskId = -1;
                boolean alreadyOnDestination = destinationTasks.size() == 1;
                if (alreadyOnDestination) {
                    selectedTaskId = destinationTasks.get(0);
                } else {
                    java.util.List<Integer> sourceTasks = sourceDisplayId >= 0
                            ? snapshot.matchingTaskIds(sourceDisplayId)
                            : snapshot.allMatchingTaskIds();

                    if (sourceTasks.size() > 1) {
                        throw new IllegalStateException(
                                "Multiple exact ActivityRecord tasks match the requested source");
                    }
                    if (sourceTasks.size() == 1) {
                        selectedTaskId = sourceTasks.get(0);
                    } else if (!snapshot.allMatchingTaskIds().isEmpty()) {
                        throw new IllegalStateException(
                                "Exact ActivityRecord exists, but not on the requested source display");
                    } else if (snapshot.hasPackageTask()) {
                        throw new IllegalStateException(
                                "Package task exists but selected ActivityRecord was not found; refusing duplicate");
                    } else {
                        if (!isCurrentTarget(component)) return;
                        String launchCommand = "/system/bin/am start --user " + userId
                                + " --display " + displayId
                                + " --windowingMode 1"
                                + " -a android.intent.action.MAIN"
                                + " -c android.intent.category.LAUNCHER"
                                + " -f 0x10000000 -n " + shellQuote(
                                        packageName + "/" + className);
                        rootShell.run(12, launchCommand);
                        selectedTaskId = awaitExactTaskOnDisplay(
                                packageName, className, displayId, -1);
                    }
                }

                if (selectedTaskId < 0) {
                    throw new IllegalStateException("No exact ActivityRecord task could be selected");
                }

                if (!alreadyOnDestination && !isTaskOnDisplay(
                        packageName, className, displayId, selectedTaskId)) {
                    if (!isCurrentTarget(component)) return;
                    rootShell.run(12, "/system/bin/am display move-stack "
                            + selectedTaskId + " " + displayId);
                }

                int verifiedTaskId = awaitExactTaskOnDisplay(
                        packageName, className, displayId, selectedTaskId);
                if (verifiedTaskId != selectedTaskId) {
                    throw new IllegalStateException(
                            "ActivityRecord task verification did not preserve the selected Task ID");
                }

                synchronized (V10DisplayController.this) {
                    if (!destroyed && component.equals(target)) launched = true;
                }
                Log.i(TAG, "Verified exact ActivityRecord task " + selectedTaskId
                        + " on display " + displayId + " for " + component.flattenToShortString());
            } catch (Throwable error) {
                synchronized (V10DisplayController.this) {
                    if (component.equals(target)) launched = false;
                }
                Log.w(TAG, "Exact ActivityRecord task was not verified on projection display", error);
            } finally {
                synchronized (V10DisplayController.this) {
                    launchInProgress = false;
                }
            }
        });
    }

    private boolean isCurrentTarget(ComponentName component) {
        synchronized (this) {
            return !destroyed && component.equals(target);
        }
    }

    private ActivityRecordTaskParser.Snapshot readActivitySnapshot(
            String packageName, String className) {
        String command = "/system/bin/dumpsys activity activities 2>/dev/null"
                + " | /system/bin/grep -E 'Display #[0-9]+|Task[{]|ActivityRecord'";
        String dump = rootShell.run(12, command, 4_000_000);
        return ActivityRecordTaskParser.parse(dump, packageName, className);
    }

    private boolean isTaskOnDisplay(
            String packageName, String className, int displayId, int taskId) {
        ActivityRecordTaskParser.Snapshot snapshot = readActivitySnapshot(packageName, className);
        java.util.List<Integer> matches = snapshot.matchingTaskIds(displayId);
        return matches.size() == 1 && matches.get(0) == taskId;
    }

    private int awaitExactTaskOnDisplay(
            String packageName, String className, int displayId, int expectedTaskId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            ActivityRecordTaskParser.Snapshot snapshot = readActivitySnapshot(packageName, className);
            java.util.List<Integer> matches = snapshot.matchingTaskIds(displayId);
            if (matches.size() > 1) {
                throw new IllegalStateException(
                        "Ambiguous ActivityRecord verification: multiple matching tasks on destination");
            }
            if (matches.size() == 1) {
                int found = matches.get(0);
                if (expectedTaskId < 0 || expectedTaskId == found) return found;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while verifying ActivityRecord task", interrupted);
            }
        }
        throw new IllegalStateException("Timed out waiting for exact ActivityRecord on display " + displayId);
    }

    /** True when the session may safely retry a failed or not-yet-started task launch. */
    public synchronized boolean canRetryTaskLaunch() {
        return !destroyed && !launched && !launchInProgress && launchAttempts < 3
                && SystemClock.uptimeMillis() - lastLaunchAttemptAt >= 1500L;
    }

    /**
     * Removes only the task belonging to this controller's projection display.
     * Never force-stops the package, because the same package may have a phone-side task.
     */
    public void closeTaskOnDisplay(ComponentName component, java.util.function.Consumer<Boolean> callback) {
        if (component == null) {
            if (callback != null) callback.accept(false);
            return;
        }
        final String packageName = component.getPackageName();
        final int displayId;
        final boolean isActiveProjection;
        synchronized (this) {
            // Only a package launched by this controller into the projection flow is eligible.
            if (!isReady() || display == null || display.getDisplayId() <= 0
                    || !managedPackages.contains(packageName)) {
                if (callback != null) callback.accept(false);
                return;
            }
            displayId = display.getDisplayId();
            isActiveProjection = component.equals(target);
        }
        // The package is eligible only because this controller launched it into the
        // projection flow. Route the close through the phone AccessibilityService; once
        // accepted, detach only if this package is the currently projected target. Closing
        // a historical task must not blank a different app that is currently being projected.
        boolean accepted = MediaPlaybackAccessibilityService.requestCloseProjectedTask(
                packageName, displayId, callback);
        if (!accepted) {
            if (callback != null) callback.accept(false);
            return;
        }
        if (isActiveProjection) detachSurface();
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
        launchAttempts = 0;
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
