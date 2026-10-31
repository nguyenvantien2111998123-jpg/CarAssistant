package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.TextureView;

public final class V10ProjectionSession implements TextureView.SurfaceTextureListener {
    private static final String TAG = "CarAssistantV10Projection";

    private final V10DisplayController displayController;
    private final int displayIndex;
    private final V10SurfaceManager surfaceManager;
    private final V10TaskSession taskSession = new V10TaskSession();
    private final RootShellSession rootShell = new RootShellSession();
    private TextureView textureView;
    private ComponentName target;
    private boolean destroyed;
    private int contentWidth;
    private int contentHeight;

    public V10ProjectionSession(Context context) {
        this(context, 0);
    }

    public V10ProjectionSession(Context context, int displayIndex) {
        Context app = context.getApplicationContext();
        this.displayIndex = Math.max(0, displayIndex);
        displayController = V10DisplayController.get(app, this.displayIndex);
        surfaceManager = new V10SurfaceManager(displayController);
    }

    public void attachTextureView(TextureView view) {
        textureView = view;
        view.setSurfaceTextureListener(this);
        if (view.isAvailable()) attachCurrentSurface();
    }

    public void setTarget(ComponentName component) {
        if (destroyed) return;
        if (component == null) {
            target = null;
            taskSession.clear();
            return;
        }
        boolean changed = target == null || !component.equals(target);
        target = component;
        if (changed) {
            taskSession.setTarget(component);
            // A phone-side standby service may already have launched this exact app
            // into display 2. Keep that task and only rebind the car TextureView surface.
            boolean samePreparedTask = displayController.isLaunched()
                    && component.equals(displayController.getTarget());
            if (displayController.isLaunched() && !samePreparedTask) {
                displayController.markNotLaunched();
            }
        }
        attachCurrentSurface();
    }

    private void launchTargetTask() {
        displayController.launchNewTask(target);
    }

    public boolean isActive() {
        return !destroyed && target != null && displayController.isReady();
    }

    public int getDisplayId() { return displayController.getDisplayId(); }

    /** Sends a VIEW URI to the currently projected app on this display. */
    public boolean launchViewIntentOnDisplay(String packageName, String uri) {
        if (!isActive() || packageName == null || uri == null) return false;
        displayController.launchViewIntentOnDisplay(packageName, uri);
        return true;
    }

    /** Starts a media VIEW deep link on this session's exact display, isolating it from phone tasks. */
    public boolean launchMediaViewIntentOnDisplay(String packageName, String uri) {
        if (!isActive() || packageName == null || uri == null) return false;
        displayController.launchMediaViewIntentOnDisplay(packageName, uri);
        return true;
    }

    /** Best-effort standard search action for apps that implement Android ACTION_SEARCH. */
    public boolean launchSearchIntentOnDisplay(String packageName, String query) {
        if (!isActive() || packageName == null || query == null || query.trim().isEmpty()) return false;
        displayController.launchSearchIntentOnDisplay(packageName, query);
        return true;
    }

    public void tap(float x, float y) {
        if (!isActive()) return;
        execute("/system/bin/input -d " + displayController.getDisplayId()
                        + " tap " + Math.round(mapX(x)) + " " + Math.round(mapY(y)),
                "Tap failed", 5);
    }

    public void swipe(float startX, float startY, float endX, float endY, long duration) {
        if (!isActive()) return;
        long safe = Math.max(80L, Math.min(800L, duration));
        execute("/system/bin/input -d " + displayController.getDisplayId()
                        + " swipe " + Math.round(mapX(startX)) + " " + Math.round(mapY(startY))
                        + " " + Math.round(mapX(endX)) + " " + Math.round(mapY(endY)) + " " + safe,
                "Swipe failed", 5);
    }

    /**
     * Injects a native two-pointer MotionEvent sequence on the Android Auto virtual display.
     * This requires the existing LSPosed InputHooks in system_server.
     */
    public void pinch(
            float startX1, float startY1, float endX1, float endY1,
            float startX2, float startY2, float endX2, float endY2,
            long duration) {
        if (!isActive()) return;

        int displayId = displayController.getDisplayId();
        if (displayId <= 0) {
            Log.w(TAG, "Refusing multi-touch injection: invalid virtual display " + displayId);
            return;
        }

        String apkPath = "$( /system/bin/pm path com.carassistant.v10 2>/dev/null | /system/bin/sed -n 's/^package://p' | /system/bin/head -n 1 )";
        String command = "APK_PATH=" + apkPath
                + "; if [ -z \"$APK_PATH\" ]; then echo 'ERROR: CarAssistant APK path not found'; exit 2; fi"
                + "; /system/bin/app_process -Djava.class.path=\"$APK_PATH\" /system/bin"
                + " com.carassistant.v10.RootMultiTouchInjector "
                + displayId + " "
                + Math.round(mapX(startX1)) + " " + Math.round(mapY(startY1)) + " "
                + Math.round(mapX(endX1)) + " " + Math.round(mapY(endY1)) + " "
                + Math.round(mapX(startX2)) + " " + Math.round(mapY(startY2)) + " "
                + Math.round(mapX(endX2)) + " " + Math.round(mapY(endY2)) + " "
                + Math.max(80L, Math.min(800L, duration));

        execute(command, "Native multi-touch injection failed", 8);
    }

    public void back() {
        if (!isActive()) return;
        execute("/system/bin/input -d " + displayController.getDisplayId() + " keyevent 4",
                "Back failed", 5);
    }

    private void attachCurrentSurface() {
        if (destroyed || target == null || textureView == null || !textureView.isAvailable()) return;
        SurfaceTexture st = textureView.getSurfaceTexture();
        if (st == null) return;
        int width = textureView.getWidth();
        int height = textureView.getHeight();
        if (width < 1 || height < 1) return;
        contentWidth = width;
        contentHeight = height;

        boolean wasReady = displayController.isReady();
        boolean wasLaunched = displayController.isLaunched();
        ComponentName displayTarget = displayController.getTarget();
        surfaceManager.attach(st, width, height);

        if (!wasReady) {
            launchTargetTask();
            taskSession.active();
            Log.d(TAG, "[CREATE] layer=" + displayIndex + " display=" + displayController.getDisplayId());
            Log.d(TAG, "[CREATE] task=" + target.flattenToString());
            Log.d(TAG, "[ACTIVE] task=" + target.flattenToString());
        } else if (!wasLaunched) {
            launchTargetTask();
            taskSession.active();
            Log.d(TAG, "[LAUNCH] task=" + target.flattenToString());
        } else if (taskSession.getState() == V10TaskSession.State.HIDDEN
                || displayTarget == null || !target.equals(displayTarget)) {
            displayController.resumeExistingTask(target);
            taskSession.active();
            Log.d(TAG, "[RESUME] task=" + target.flattenToString());
        } else {
            taskSession.active();
        }
    }

    /** Closes a specific package task only when it is confirmed on this projection display. */
    public void closeTaskOnDisplay(ComponentName component, java.util.function.Consumer<Boolean> callback) {
        if (destroyed || component == null) {
            if (callback != null) callback.accept(false);
            return;
        }
        displayController.closeTaskOnDisplay(component, closed -> {
            if (closed && component.equals(target)) {
                target = null;
                taskSession.clear();
                displayController.markNotLaunched();
            } else if (!closed && component.equals(target)) {
                // Roll back the surface detach if the phone-side Accessibility/root
                // request failed, so a failed close does not leave a black projection.
                attachCurrentSurface();
            }
            if (callback != null) callback.accept(closed);
        });
    }

    public void hideTask() {
        if (target == null || !displayController.isReady()) return;
        taskSession.hidden();
        surfaceManager.detach();
        Log.d(TAG, "[HIDE] task=" + target.flattenToString());
        Log.d(TAG, "[DETACH] surface");
    }

    public void release() { hideTask(); }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        surfaceManager.detach();
        rootShell.destroy();
    }

    private float mapX(float x) {
        if (textureView == null || contentWidth < 1) return x;
        float width = textureView.getWidth();
        if (width <= 0) return x;
        return clamp(x * contentWidth / width, 0, contentWidth - 1);
    }

    private float mapY(float y) {
        if (textureView == null || contentHeight < 1) return y;
        float height = textureView.getHeight();
        if (height <= 0) return y;
        return clamp(y * contentHeight / height, 0, contentHeight - 1);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void execute(String command, String message, int timeout) {
        RootShellSession.EXEC.execute(() -> {
            try { rootShell.run(timeout, command); }
            catch (Throwable t) { Log.w(TAG, message, t); }
        });
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        attachCurrentSurface();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        contentWidth = width;
        contentHeight = height;
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        taskSession.hidden();
        surfaceManager.detach();
        Log.d(TAG, "[DETACH] surface");
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
}
