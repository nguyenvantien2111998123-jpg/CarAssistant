package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.TextureView;

public final class V10ProjectionSession implements TextureView.SurfaceTextureListener {
    private static final String TAG = "CarAssistantV10Projection";

    private final V10DisplayController displayController;
    private final V10SurfaceManager surfaceManager;
    private final V10TaskSession taskSession = new V10TaskSession();
    private final RootShellSession rootShell = new RootShellSession();
    private TextureView textureView;
    private ComponentName target;
    private boolean destroyed;
    private int contentWidth;
    private int contentHeight;

    public V10ProjectionSession(Context context) {
        Context app = context.getApplicationContext();
        displayController = V10DisplayController.get(app);
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
            if (displayController.isLaunched()) displayController.markNotLaunched();
        }
        attachCurrentSurface();
    }

    public boolean isActive() {
        return !destroyed && target != null && displayController.isReady();
    }

    public int getDisplayId() { return displayController.getDisplayId(); }

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
            displayController.launchNewTask(target);
            taskSession.active();
            Log.d(TAG, "[CREATE] display=" + displayController.getDisplayId());
            Log.d(TAG, "[CREATE] task=" + target.flattenToString());
            Log.d(TAG, "[ACTIVE] task=" + target.flattenToString());
        } else if (!wasLaunched) {
            displayController.launchNewTask(target);
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
