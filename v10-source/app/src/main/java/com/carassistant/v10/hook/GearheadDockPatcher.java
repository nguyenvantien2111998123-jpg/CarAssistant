package com.carassistant.v10.hook;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.carassistant.v10.DockStateProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Ẩn dock (facet bar) của Android Auto khi split screen đang hiển thị, và khôi phục
 * khi người dùng thoát — dựa trên lease `visible_until` do app ghi qua DockStateProvider.
 */
public final class GearheadDockPatcher {

    private static final String TAG = "[AACast Dock]";
    private static final String LOG_TAG = "AACastDock";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final long LEASE_MS = 3000L;
    private static final long POLL_MS = 1000L;

    /** Tên resource-entry của dock/nav bar trong gearhead. */
    private static final String[] DOCK_NAMES = {
            "nav_bar_container",
            "car_navigation_bar",
            "dock_container",
            "nav_bar",
            "floating_nav_bar_view",
            "navigation_bar_container",
            "navigation_bar",
            "bottom_navigation_bar",
            "system_navigation_bar",
            "navbar",
            "car_sys_ui_navbar"
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<View> roots = Collections.newSetFromMap(new WeakHashMap<>());
    private final IdentityHashMap<View, ViewSnapshot> hidden = new IdentityHashMap<>();
    private final IdentityHashMap<ViewGroup, View> covered = new IdentityHashMap<>();
    private final IdentityHashMap<ViewGroup, FullscreenSnapshot> fullscreen =
            new IdentityHashMap<>();
    private final ExecutorService providerExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean readInFlight = new AtomicBoolean();

    private volatile Context context;
    private volatile long visibleUntil;
    private boolean leaseActive;
    private boolean rootsDirty = true;
    private boolean loggedNoDock;
    private boolean loggedRootFailure;
    private boolean projectionConnected;

    private GearheadDockPatcher() {
    }

    /** Cài hook theo dõi root view + vòng lặp lease. */
    public static void install() {
        GearheadDockPatcher patcher = new GearheadDockPatcher();
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context appContext = (Context) param.args[0];
                            patcher.main.post(() -> patcher.attachContext(appContext));
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "performClick",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object target = param.thisObject;
                            if (target instanceof View && isMediaInfoClick((View) target)) {
                                // Consume the Android Auto media-card click before its
                                // native handler can launch the player on the phone.
                                param.setResult(true);
                                patcher.dispatchMediaOpen();
                                log("Consumed media metadata click; forwarding to projected display");
                            }
                        }
                    });
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.view.WindowManagerGlobal", (ClassLoader) null),
                    "addView",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0 && param.args[0] instanceof View) {
                                View view = (View) param.args[0];
                                patcher.main.post(() -> {
                                    patcher.roots.add(view);
                                    patcher.rootsDirty = true;
                                    patcher.attachContext(view.getContext());
                                    if (isAndroidAutoDisplay(view)) {
                                        patcher.dispatchProjectionState(true);
                                    }
                                });
                            }
                        }
                    });
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.view.WindowManagerGlobal", (ClassLoader) null),
                    "removeView",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0 && param.args[0] instanceof View) {
                                View view = (View) param.args[0];
                                patcher.main.post(() -> {
                                    patcher.roots.remove(view);
                                    patcher.main.postDelayed(() -> {
                                        if (!patcher.hasAndroidAutoDisplay()) {
                                            patcher.dispatchProjectionState(false);
                                        }
                                    }, 2500L);
                                });
                            }
                        }
                    });
            patcher.main.post(patcher::refreshRoots);
            log("Launcher dock hook v2 installed (GhFacetBar + embedded dock)");
        } catch (Throwable t) {
            log("Dock hook unavailable: " + t);
        }
    }

    /**
     * Only forward taps on media metadata/card surfaces, not transport controls.
     * Resource names differ between Android Auto releases, so inspect a short
     * ancestor chain and accept known media/player containers.
     */
    private static boolean isMediaInfoClick(View clicked) {
        String clickedName = resourceEntryName(clicked);
        String lowerClicked = clickedName == null ? "" : clickedName.toLowerCase(java.util.Locale.ROOT);
        String[] controls = {"play_pause", "playpause", "next", "previous", "skip",
                "fast_forward", "rewind", "transport", "play_button", "pause_button"};
        for (String control : controls) {
            if (lowerClicked.contains(control)) return false;
        }

        View current = clicked;
        for (int depth = 0; depth < 6 && current != null; depth++) {
            String name = resourceEntryName(current);
            if (name != null) {
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("media_item_title") || lower.contains("media_title")
                        || lower.contains("media_metadata") || lower.contains("now_playing")
                        || lower.contains("media_card") || lower.contains("media_info")
                        || lower.contains("playback_metadata") || lower.contains("song_title")
                        || lower.contains("album_art") || lower.contains("media_artwork")) {
                    return true;
                }
                if (depth > 0 && (lower.contains("media") || lower.contains("playback")
                        || lower.contains("now_playing") || lower.contains("player"))) {
                    return true;
                }
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        if (clicked instanceof TextView) {
            CharSequence description = clicked.getContentDescription();
            if (description != null) {
                String value = description.toString().toLowerCase(java.util.Locale.ROOT);
                return value.contains("now playing") || value.contains("đang phát");
            }
        }
        return false;
    }

    private void dispatchMediaOpen() {
        // Ask Gearhead to open the CarAsistant CarActivity. The phone-side standby
        // service has already prepared the same display/task; V10CarActivity rebinds it.
        GearheadCarAppLauncher.open();
        Context appContext = context;
        if (appContext == null) return;
        try {
            Intent intent = new Intent("com.carassistant.v10.STEERING_MEDIA_OPEN");
            intent.setPackage("com.carassistant.v10");
            appContext.sendBroadcast(intent);
            log("Forwarded media-card tap to the prepared CarAsistant projection");
        } catch (Throwable error) {
            log("Could not forward media metadata click: " + error);
        }
    }

    private static boolean isAndroidAutoDisplay(View view) {
        if (view == null || view.getDisplay() == null) return false;
        android.view.Display display = view.getDisplay();
        if (display.getDisplayId() <= 0) return false;
        String name = display.getName();
        return name != null && (name.contains("GhFacetBar")
                || name.contains("TemplateNavigationService")
                || name.toLowerCase(java.util.Locale.ROOT).contains("gearhead"));
    }

    private boolean hasAndroidAutoDisplay() {
        for (View root : new ArrayList<>(roots)) {
            if (root != null && root.isAttachedToWindow() && isAndroidAutoDisplay(root)) return true;
        }
        return false;
    }

    private void dispatchProjectionState(boolean connected) {
        if (projectionConnected == connected) return;
        projectionConnected = connected;
        Context appContext = context;
        if (appContext == null) return;
        try {
            String action = connected
                    ? "com.carassistant.v10.PROJECTION_CONNECTED"
                    : "com.carassistant.v10.PROJECTION_DISCONNECTED";
            Intent intent = new Intent(action);
            intent.setPackage("com.carassistant.v10");
            appContext.sendBroadcast(intent);
            log(connected ? "Android Auto display detected; preparing phone-side projection"
                    : "Android Auto display removed; closing standby service");
        } catch (Throwable error) {
            log("Could not notify phone-side projection service: " + error);
        }
    }

    private static void log(String message) {
        Log.i(LOG_TAG, message);
        XposedBridge.log(TAG + " " + message);
    }

    private void attachContext(Context newContext) {
        if (context != null) {
            return;
        }
        context = newContext.getApplicationContext() != null
                ? newContext.getApplicationContext()
                : newContext;
        main.post(this::tick);
    }

    /** Vòng lặp lease: ẩn dock khi active, khôi phục khi hết hạn. */
    private void tick() {
        try {
            long until = visibleUntil;
            long now = SystemClock.uptimeMillis();
            boolean active = until > now && until - now <= LEASE_MS;
            boolean leaseChanged = active != leaseActive;
            if (leaseChanged) {
                leaseActive = active;
                log(active ? "Launcher lease active" : "Launcher lease ended; restoring dock");
                if (active) {
                    rootsDirty = true;
                }
            }
            if (active) {
                boolean stale = removeStaleTargets();
                if (rootsDirty || stale
                        || (hidden.isEmpty() && covered.isEmpty() && fullscreen.isEmpty())) {
                    applyHide();
                } else {
                    applySnapshots();
                }
            } else if (leaseChanged || !hidden.isEmpty() || !covered.isEmpty()
                    || !fullscreen.isEmpty()) {
                restoreAll();
            }
        } catch (Throwable t) {
            log("Dock update failed: " + t);
        } finally {
            if (context != null && readInFlight.compareAndSet(false, true)) {
                providerExecutor.execute(this::readLeaseFromProvider);
            }
            main.postDelayed(this::tick, POLL_MS);
        }
    }

    /** Đọc lease từ DockStateProvider (chạy ở thread nền). */
    private void readLeaseFromProvider() {
        long value = 0L;
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri uri = Uri.parse("content://" + DockStateProvider.AUTHORITY + "/state");
            cursor = resolver.query(uri, new String[]{"visible_until"}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                value = cursor.getLong(0);
            }
        } catch (Exception e) {
            value = 0L;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {
                }
            }
            visibleUntil = value;
            readInFlight.set(false);
        }
    }

    /** Quét lại các root view hiện có. */
    private void refreshRoots() {
        try {
            Object global = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.view.WindowManagerGlobal", (ClassLoader) null),
                    "getInstance");
            String[] names = (String[]) XposedHelpers.callMethod(global, "getViewRootNames");
            if (names != null) {
                for (String name : names) {
                    Object root = XposedHelpers.callMethod(global, "getRootView", name);
                    if (root instanceof View) {
                        roots.add((View) root);
                        if (isAndroidAutoDisplay((View) root)) {
                            dispatchProjectionState(true);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (!loggedRootFailure) {
                loggedRootFailure = true;
                log("Existing roots unavailable; using addView hook: " + t);
            }
        }
    }

    /** Áp dụng ẩn dock/fullscreen lên toàn bộ root view. */
    private void applyHide() {
        refreshRoots();
        rootsDirty = false;
        for (View root : roots) {
            if (!root.isAttachedToWindow() || root.getDisplay() == null
                    || root.getDisplay().getDisplayId() <= 0) {
                continue;
            }
            if (root instanceof ViewGroup && isDockDisplay((ViewGroup) root)) {
                coverDockDisplay((ViewGroup) root);
            }
            if (root instanceof ViewGroup && isSystemUiDisplay(root)) {
                expandActivityInTree((ViewGroup) root, new int[]{2000});
            }
            hideDockInTree(root, root, new int[]{2000});
        }
        applySnapshots();
        if (hidden.isEmpty() && covered.isEmpty() && fullscreen.isEmpty() && !loggedNoDock) {
            loggedNoDock = true;
            log("No supported dock or fullscreen host found; host UI unchanged. "
                    + "Check resource IDs for this AA version.");
        }
    }

    private void applySnapshots() {
        for (ViewSnapshot snapshot : hidden.values()) {
            snapshot.apply();
        }
        for (FullscreenSnapshot snapshot : fullscreen.values()) {
            snapshot.apply();
        }
    }

    private boolean removeStaleTargets() {
        boolean stale = false;
        for (Map.Entry<View, ViewSnapshot> entry : new ArrayList<>(hidden.entrySet())) {
            ViewSnapshot snapshot = entry.getValue();
            if (snapshot.isStale()) {
                hidden.remove(entry.getKey());
                try {
                    snapshot.restore();
                } catch (RuntimeException ignored) {
                }
                stale = true;
            }
        }
        for (Map.Entry<ViewGroup, View> entry : new ArrayList<>(covered.entrySet())) {
            if (!entry.getKey().isAttachedToWindow()) {
                covered.remove(entry.getKey());
                stale = true;
            }
        }
        for (Map.Entry<ViewGroup, FullscreenSnapshot> entry
                : new ArrayList<>(fullscreen.entrySet())) {
            FullscreenSnapshot snapshot = entry.getValue();
            if (snapshot.isStale()) {
                fullscreen.remove(entry.getKey());
                try {
                    snapshot.restore();
                } catch (RuntimeException ignored) {
                }
                stale = true;
            }
        }
        return stale;
    }

    private static boolean isSystemUiDisplay(View root) {
        Display display = root.getDisplay();
        return display != null && display.getDisplayId() > 0
                && display.getName().contains("TemplateNavigationService");
    }

    /** Mở rộng region activity tới mép phải thay cho dashboard Coolwalk. */
    private void expandActivityInTree(ViewGroup group, int[] budget) {
        if (budget[0]-- < 0 || fullscreen.containsKey(group)) {
            return;
        }
        View activity = null;
        View dashboard = null;
        View contentRight = null;
        List<Integer> dashboardBoundaries = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String name = resourceEntryName(child);
            if ("activity".equals(name)) {
                activity = child;
            } else if ("dashboard".equals(name)) {
                dashboard = child;
            } else if ("contentInset_right".equals(name)) {
                contentRight = child;
            } else if ("dashboard_guideline".equals(name)
                    || "activity_guideline".equals(name)) {
                dashboardBoundaries.add(child.getId());
            }
        }
        if (activity != null && dashboard != null && contentRight != null
                && !dashboardBoundaries.isEmpty()) {
            try {
                FullscreenSnapshot snapshot = new FullscreenSnapshot(group, activity, dashboard,
                        contentRight.getId(), dashboardBoundaries);
                fullscreen.put(group, snapshot);
                snapshot.apply();
                log("Fullscreen activity region enabled: " + activity.getWidth() + "x"
                        + activity.getHeight());
                return;
            } catch (Throwable t) {
                log("Fullscreen host found but constraints are unsupported: " + t);
                return;
            }
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                expandActivityInTree((ViewGroup) child, budget);
            }
        }
    }

    private static String resourceEntryName(View view) {
        if (view.getId() == View.NO_ID) {
            return null;
        }
        try {
            if (!GEARHEAD.equals(view.getResources().getResourcePackageName(view.getId()))) {
                return null;
            }
            return view.getResources().getResourceEntryName(view.getId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Display này có phải dock riêng (GhFacetBar)? */
    private static boolean isDockDisplay(ViewGroup group) {
        Display display = group.getDisplay();
        return display != null && display.getDisplayId() > 0
                && "GhFacetBar".equals(display.getName())
                && group.getWidth() > 0 && group.getHeight() > 0
                && group.getWidth() >= group.getHeight() * 4L;
    }

    /** Phủ overlay đen chặn touch lên display dock. */
    private void coverDockDisplay(ViewGroup group) {
        View cover = covered.get(group);
        if (cover == null) {
            cover = new View(group.getContext());
            cover.setBackgroundColor(Color.BLACK);
            cover.setOnTouchListener((v, event) -> true);
            cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            group.addView(cover, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            covered.put(group, cover);
            log("Covered dedicated dock display: " + group.getDisplay().getName()
                    + " " + group.getWidth() + "x" + group.getHeight());
        }
        cover.setElevation(1000f);
        cover.bringToFront();
    }

    /** Ẩn các view dock trong cây. */
    private void hideDockInTree(View view, View root, int[] budget) {
        if (budget[0]-- < 0) {
            return;
        }
        if (hidden.containsKey(view)) {
            return;
        }
        if (!(view instanceof ViewGroup) || view.getVisibility() != View.VISIBLE
                || view.getId() == View.NO_ID) {
            return;
        }
        try {
            String packageName = view.getResources().getResourcePackageName(view.getId());
            String entryName = view.getResources().getResourceEntryName(view.getId());
            if (GEARHEAD.equals(packageName)
                    && view.getDisplay() != null && view.getDisplay().getDisplayId() > 0
                    && view.getWidth() > 0 && view.getHeight() > 0
                    && isDockName(entryName)) {
                hidden.put(view, new ViewSnapshot(view, root));
                log("Hiding bottom dock: " + entryName);
            }
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                hideDockInTree(group.getChildAt(i), root, budget);
            }
        } catch (Throwable ignored) {
            // Resources.NotFoundException hoặc view đang bị thay đổi
        }
    }

    private static boolean isDockName(String entryName) {
        for (String name : DOCK_NAMES) {
            if (name.equals(entryName)) {
                return true;
            }
        }
        return false;
    }

    /** Khôi phục toàn bộ. */
    private void restoreAll() {
        for (Map.Entry<ViewGroup, View> entry : new ArrayList<>(covered.entrySet())) {
            try {
                entry.getKey().removeView(entry.getValue());
            } catch (RuntimeException ignored) {
            }
        }
        covered.clear();
        for (FullscreenSnapshot snapshot : new ArrayList<>(fullscreen.values())) {
            try {
                snapshot.restore();
            } catch (RuntimeException ignored) {
            }
        }
        fullscreen.clear();
        for (View view : new ArrayList<>(hidden.keySet())) {
            ViewSnapshot snapshot = hidden.remove(view);
            if (snapshot != null) {
                try {
                    snapshot.restore();
                } catch (RuntimeException ignored) {
                }
            }
        }
        loggedNoDock = false;
        rootsDirty = true;
    }

    /** Trạng thái constraint gốc của Coolwalk để lease hết có thể khôi phục chính xác. */
    private static final class FullscreenSnapshot {
        private final ViewGroup parent;
        private final View activity;
        private final View dashboard;
        private final Object activityParams;
        private final List<IntFieldSnapshot> boundaryFields = new ArrayList<>();
        private final int dashboardVisibility;
        private final int contentRightId;

        FullscreenSnapshot(ViewGroup parent, View activity, View dashboard,
                           int contentRightId, List<Integer> dashboardBoundaries)
                throws IllegalAccessException {
            this.parent = parent;
            this.activity = activity;
            this.dashboard = dashboard;
            this.activityParams = activity.getLayoutParams();
            this.dashboardVisibility = dashboard.getVisibility();
            this.contentRightId = contentRightId;
            Class<?> type = activityParams.getClass();
            while (type != null && type != Object.class) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType() != int.class
                            || Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    int value = field.getInt(activityParams);
                    if (dashboardBoundaries.contains(value)) {
                        boundaryFields.add(new IntFieldSnapshot(field, value));
                    }
                }
                type = type.getSuperclass();
            }
            if (boundaryFields.isEmpty()) {
                throw new IllegalStateException("activity has no dashboard boundary constraint");
            }
        }

        boolean isStale() {
            return !parent.isAttachedToWindow()
                    || activity.getParent() != parent
                    || dashboard.getParent() != parent
                    || activity.getLayoutParams() != activityParams;
        }

        void apply() {
            boolean changed = false;
            if (dashboard.getVisibility() != View.GONE) {
                dashboard.setVisibility(View.GONE);
                changed = true;
            }
            for (IntFieldSnapshot snapshot : boundaryFields) {
                try {
                    if (snapshot.field.getInt(activityParams) != contentRightId) {
                        snapshot.field.setInt(activityParams, contentRightId);
                        changed = true;
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            if (changed) {
                activity.setLayoutParams((ViewGroup.LayoutParams) activityParams);
                parent.requestLayout();
            }
        }

        void restore() {
            for (IntFieldSnapshot snapshot : boundaryFields) {
                try {
                    snapshot.field.setInt(activityParams, snapshot.value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            activity.setLayoutParams((ViewGroup.LayoutParams) activityParams);
            dashboard.setVisibility(dashboardVisibility);
            parent.requestLayout();
        }
    }

    private static final class IntFieldSnapshot {
        final Field field;
        final int value;

        IntFieldSnapshot(Field field, int value) {
            this.field = field;
            this.value = value;
        }
    }
}
