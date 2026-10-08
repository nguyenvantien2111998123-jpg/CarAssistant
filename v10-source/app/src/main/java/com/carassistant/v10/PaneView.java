package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Một ô (pane) của màn hình chia: TextureView + VirtualDisplay + app con.
 *
 * Vòng đời: TextureView có Surface -> tạo VirtualDisplay -> `su am start --display`
 * -> nhận touch -> chuyển thành `su input touchscreen -d <id> tap|swipe`.
 */
public final class PaneView extends LinearLayout implements TextureView.SurfaceTextureListener {

    private static final String TAG = "AACastDoudou";

    final int index;
    private final RootView root;

    AppEntry app;
    private ComponentName target;

    private volatile VirtualDisplay display;
    private Surface surface;
    private volatile int generation;
    private int bufferW;
    private int bufferH;

    private boolean launching;
    private boolean launched;
    private final AtomicBoolean inputBusy = new AtomicBoolean();
    private final Runnable resizeRunnable;

    final TextView status;
    final LinearLayout overlay;
    final Button retry;
    private final Button menu;
    private final TextureView texture;

    private float downX;
    private float downY;
    private long downTime;
    private boolean cancelled;

    public PaneView(RootView root, int index) {
        super(root.getContext());
        this.root = root;
        this.index = index;
        resizeRunnable = () -> {
            if (!root.container.isDividerResizeActive()) {
                resize();
            }
        };

        SharedPreferences prefs = root.prefs;
        ComponentName saved = ComponentName.unflattenFromString(prefs.getString("app_" + index, ""));
        if (saved == null || getContext().getPackageName().equals(saved.getPackageName())) {
            app = new AppEntry(getContext().getString(R.string.choose_app), "", null);
        } else {
            app = new AppEntry(prefs.getString("label_" + index, saved.getPackageName()),
                    saved.getPackageName(), saved);
        }
        resolveTarget();

        setOrientation(VERTICAL);
        setBackgroundColor(0xFF0B1321);

        FrameLayout frame = new FrameLayout(getContext());
        texture = new TextureView(getContext());
        texture.setSurfaceTextureListener(this);
        texture.setOnTouchListener(this::onTextureTouch);
        frame.addView(texture, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(frame, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        overlay = new LinearLayout(getContext());
        overlay.setOrientation(VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(0xFF101B2E);
        overlay.setClickable(true);
        status = new TextView(getContext());
        status.setGravity(Gravity.CENTER);
        status.setTextColor(0xFFF0F8FF);
        status.setTextSize(14f);
        overlay.addView(status, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        retry = makeButton(getContext().getString(R.string.launcher_retry), this::onRetryClicked);
        overlay.addView(retry, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)));
        overlay.setOnClickListener(v -> {
            if (app.component == null) {
                root.showAppChooser(this);
            }
        });
        frame.addView(overlay, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        menu = makeButton("⋮", this::onMenuClicked);
        menu.setTextSize(22f);
        menu.setOnTouchListener(this::onMenuTouch);
        menu.setContentDescription(getContext().getString(R.string.pane_menu, app.label));
        FrameLayout.LayoutParams menuParams =
                new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.END);
        frame.addView(menu, menuParams);

        showInitialState();
    }

    // ------------------------------------------------------------------ state

    /** Tạo VirtualDisplay khi TextureView đã sẵn sàng. */
    void ensureDisplay() {
        if (root.destroyed || !root.isPaneVisible(index) || !root.rootAvailable
                || display != null || target == null || !texture.isAvailable()
                || texture.getWidth() < 1 || texture.getHeight() < 1) {
            return;
        }
        try {
            computeBuffer(texture.getWidth(), texture.getHeight());
            SurfaceTexture surfaceTexture = texture.getSurfaceTexture();
            if (surfaceTexture == null) {
                return;
            }
            Surface newSurface = new Surface(surfaceTexture);
            VirtualDisplay newDisplay;
            try {
                newDisplay = ((DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE))
                        .createVirtualDisplay(AACastDisplay.nameFor(index),
                                bufferW, bufferH, 160, newSurface, 10);
                if (newDisplay == null) {
                    throw new IllegalStateException("Cannot create virtual display");
                }
            } catch (RuntimeException e) {
                // createVirtualDisplay ném lỗi -> phải giải phóng Surface local,
                // nếu không field `surface` còn null và release() sẽ không thấy nó.
                newSurface.release();
                throw e;
            }
            display = newDisplay;
            surface = newSurface;
            launch();
        } catch (RuntimeException e) {
            release();
            Log.w(TAG, "Display creation failed: " + app.pkg, e);
            showError();
        }
    }

    /** Callback đến từ display cũ (đã teardown) thì bỏ qua. */
    boolean isCurrent(VirtualDisplay virtualDisplay, int gen) {
        return !root.destroyed && display == virtualDisplay && generation == gen;
    }

    // ----------------------------------------------------------------- launch

    /** Mở app con trên display ảo bằng `su am start --display`. */
    void launch() {
        if (root.destroyed || !root.isPaneVisible(index) || !root.rootAvailable
                || target == null || launching || launched) {
            return;
        }
        if (display == null) {
            ensureDisplay();
            return;
        }

        VirtualDisplay currentDisplay = display;
        int gen = generation;
        int displayId = currentDisplay.getDisplay().getDisplayId();
        String flat = target.flattenToString();
        if (displayId <= 0) {
            throw new IllegalStateException("Expected a secondary display");
        }
        if (flat.length() > 4096 || !flat.matches(
                "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*/\\.?[\\p{javaJavaIdentifierStart}]"
                        + "[\\p{javaJavaIdentifierPart}]*(?:\\.[\\p{javaJavaIdentifierStart}]"
                        + "[\\p{javaJavaIdentifierPart}]*)*")) {
            throw new IllegalStateException("Invalid launch target");
        }

        int userId = Process.myUid() / 100000;
        String cmd = "/system/bin/am start --user " + userId
                + " --display " + displayId
                + " --windowingMode 1"
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " -f 0x18000000"
                + " -n '" + flat.replace("'", "'\"'\"'") + "'";

        launching = true;
        showLoading();
        RootShellSession.EXEC.execute(() -> {
            if (!isCurrent(currentDisplay, gen)) {
                return;
            }
            try {
                root.shell.run(15, cmd);
                post(() -> {
                    if (isCurrent(currentDisplay, gen)) {
                        launching = false;
                        launched = true;
                        overlay.setVisibility(GONE);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Launch failed: " + app.pkg, e);
                post(() -> {
                    if (isCurrent(currentDisplay, gen)) {
                        launching = false;
                        showError();
                    }
                });
            }
        });
    }

    /** Teardown display + surface. */
    void release() {
        cancelScheduledResize();
        generation++;
        VirtualDisplay oldDisplay = display;
        Surface oldSurface = surface;
        display = null;
        surface = null;
        launching = false;
        launched = false;
        if (oldDisplay == null && oldSurface == null) {
            return;
        }
        RootShellSession.EXEC.execute(() -> {
            if (oldDisplay != null) {
                oldDisplay.release();
            }
            if (oldSurface != null) {
                oldSurface.release();
            }
        });
    }

    /** Resize display theo kích thước mới của TextureView. */
    void resize() {
        cancelScheduledResize();
        if (root.container.isDividerResizeActive()) {
            return;
        }
        if (root.destroyed || !root.isPaneVisible(index) || !texture.isAvailable()) {
            return;
        }
        int w = texture.getWidth();
        int h = texture.getHeight();
        if (w < 1 || h < 1) {
            return;
        }
        if (display == null) {
            ensureDisplay();
            return;
        }
        try {
            int[] targetSize = LayoutMath.bufferSize(index, w, h);
            if (bufferW == targetSize[0] && bufferH == targetSize[1]) {
                return;
            }
            int oldWidth = bufferW;
            int oldHeight = bufferH;
            SurfaceTexture surfaceTexture = texture.getSurfaceTexture();
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(targetSize[0], targetSize[1]);
            }
            display.resize(targetSize[0], targetSize[1], 160);
            bufferW = targetSize[0];
            bufferH = targetSize[1];
            Log.d(TAG, "Resized pane " + index + " from " + oldWidth + "x" + oldHeight
                    + " to " + bufferW + "x" + bufferH);
        } catch (RuntimeException e) {
            release();
            Log.w(TAG, "Resize failed: " + app.pkg, e);
            showError();
        }
    }

    /** Kiểm tra ActivityInfo có mở được trên display ảo không. */
    void resolveTarget() {
        target = null;
        if (app == null || app.component == null) {
            return;
        }
        try {
            ActivityInfo ai = getContext().getPackageManager().getActivityInfo(app.component, 0);
            if (ai.enabled && ai.exported && ai.applicationInfo.enabled
                    && (ai.permission == null || getContext().checkSelfPermission(ai.permission) == 0)
                    && !getContext().getPackageName().equals(ai.packageName)) {
                target = app.component;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    /** Đổi app cho pane này (do RootView gọi). */
    void setApp(AppEntry entry) {
        release();
        app = entry;
        menu.setContentDescription(getContext().getString(R.string.pane_menu, entry.label));
        resolveTarget();
        showLoading();
        if (root.rootAvailable) {
            ensureDisplay();
            launch();
        } else {
            root.startRootCheck();
        }
    }

    // ------------------------------------------------------------------ input

    /** Gửi chạm/vuốt/phím qua `su input`. */
    void sendInput(boolean back, int x1, int y1, int x2, int y2, long durationMs, boolean tap) {
        VirtualDisplay currentDisplay = display;
        int gen = generation;
        if (root.destroyed || !root.isPaneVisible(index) || !root.rootAvailable
                || currentDisplay == null || !launched || !inputBusy.compareAndSet(false, true)) {
            return;
        }
        int displayId = currentDisplay.getDisplay().getDisplayId();
        if (displayId <= 0) {
            throw new IllegalStateException("Expected a secondary display");
        }
        String cmd;
        if (back) {
            cmd = "/system/bin/input -d " + displayId + " keyevent 4";
        } else {
            if (Math.min(Math.min(x1, y1), Math.min(x2, y2)) < 0) {
                throw new IllegalStateException("Invalid coordinates");
            }
            String base = "/system/bin/input touchscreen -d " + displayId;
            cmd = tap
                    ? base + " tap " + x2 + " " + y2
                    : base + " swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " "
                            + Math.max(100L, Math.min(1500L, durationMs));
        }
        RootShellSession.EXEC.execute(() -> {
            try {
                if (isCurrent(currentDisplay, gen)) {
                    root.shell.run(8, cmd);
                }
            } catch (Exception e) {
                Log.w(TAG, "Input failed: " + app.pkg, e);
                post(() -> {
                    if (isCurrent(currentDisplay, gen)) {
                        showError();
                    }
                });
            } finally {
                inputBusy.set(false);
            }
        });
    }

    /** Touch trên TextureView -> tap/swipe. */
    private boolean onTextureTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downTime = event.getEventTime();
                cancelled = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                cancelled = true;
                break;
            case MotionEvent.ACTION_UP:
                if (!cancelled) {
                    long duration = event.getEventTime() - downTime;
                    float distance = (float) Math.hypot(event.getX() - downX, event.getY() - downY);
                    boolean isTap = distance < ViewConfiguration.get(getContext()).getScaledTouchSlop()
                            && duration < 450;
                    sendInput(false, mapX(downX), mapY(downY), mapX(event.getX()), mapY(event.getY()),
                            duration, isTap);
                }
                view.performClick();
                break;
            default:
                break;
        }
        return true;
    }

    private int mapX(float px) {
        return Math.max(0, Math.min(bufferW - 1,
                Math.round(px * bufferW / Math.max(1, texture.getWidth()))));
    }

    private int mapY(float py) {
        return Math.max(0, Math.min(bufferH - 1,
                Math.round(py * bufferH / Math.max(1, texture.getHeight()))));
    }

    /** Tính buffer + density cố định 160. */
    private void computeBuffer(int w, int h) {
        int[] size = LayoutMath.bufferSize(index, w, h);
        bufferW = size[0];
        bufferH = size[1];
        SurfaceTexture surfaceTexture = texture.getSurfaceTexture();
        if (surfaceTexture != null) {
            surfaceTexture.setDefaultBufferSize(bufferW, bufferH);
        }
    }

    // ---------------------------------------------------------- surface cycle

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        ensureDisplay();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        release();
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        if (width < 1 || height < 1 || root.destroyed || !root.isPaneVisible(index)) {
            return;
        }
        if (display == null) {
            ensureDisplay();
            return;
        }
        if (root.container.isDividerResizeActive()) {
            cancelScheduledResize();
            return;
        }
        removeCallbacks(resizeRunnable);
        postDelayed(resizeRunnable, 80L);
    }

    void cancelScheduledResize() {
        removeCallbacks(resizeRunnable);
    }

    void resizeAfterDivider() {
        cancelScheduledResize();
        resize();
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
    }

    // ------------------------------------------------------------- pane state

    /** Menu ⋮ / kéo-thả đổi chỗ. */
    private boolean onMenuTouch(View view, MotionEvent event) {
        if (root.destroyed) {
            return false;
        }
        boolean[] state = menuDragState;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                state[0] = false;   // dragging
                state[1] = false;   // cancelled
                menuDragStart[0] = event.getRawX();
                menuDragStart[1] = event.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!state[1]) {
                    if (!state[0] && Math.hypot(event.getRawX() - menuDragStart[0],
                            event.getRawY() - menuDragStart[1])
                            > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                        state[0] = true;
                        root.container.beginDrag(index);
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (state[0]) {
                        root.container.onDragMove(event.getRawX(), event.getRawY());
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (state[0] && !state[1]) {
                    root.container.onDragMove(event.getRawX(), event.getRawY());
                    root.container.commitDrag(true);
                } else if (!state[1]) {
                    view.performClick();     // -> OnClickListener -> onMenuClicked()
                }
                state[0] = false;
                view.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                state[0] = false;
                state[1] = true;
                view.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    private final boolean[] menuDragState = new boolean[2];
    private final float[] menuDragStart = new float[2];

    private void onRetryClicked() {
        if (root.destroyed || launching) {
            return;
        }
        if (app.component == null) {
            root.showAppChooser(this);
            return;
        }
        resolveTarget();
        release();
        if (root.rootAvailable) {
            ensureDisplay();
            launch();
        } else {
            root.startRootCheck();
        }
    }

    private void onMenuClicked() {
        root.showPaneMenu(this);
    }

    // ------------------------------------------------------------------- view

    private void showInitialState() {
        if (app.component == null) {
            showError();
        } else {
            showLoading();
        }
    }

    private void showLoading() {
        status.setText(getContext().getString(R.string.launcher_loading, app.label));
        retry.setVisibility(GONE);
        overlay.setVisibility(VISIBLE);
    }

    void showError() {
        if (app.component == null) {
            status.setText(R.string.choose_app_empty);
            retry.setText(R.string.choose_app);
            retry.setVisibility(VISIBLE);
            overlay.setVisibility(VISIBLE);
            overlay.setContentDescription(getContext().getString(R.string.choose_app));
            return;
        }
        int message = (target == null) ? R.string.launcher_missing : R.string.launcher_unavailable;
        status.setText(getContext().getString(message, app.label));
        retry.setText(R.string.launcher_retry);
        retry.setVisibility(VISIBLE);
        overlay.setVisibility(VISIBLE);
        overlay.setContentDescription(status.getText());
    }

    private Button makeButton(String label, Runnable action) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13f);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF163254);
        background.setCornerRadius(dp(12));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3300E5FF), background, null));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
