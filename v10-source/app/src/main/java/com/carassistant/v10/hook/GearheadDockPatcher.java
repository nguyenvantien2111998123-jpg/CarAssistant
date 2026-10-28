package com.carassistant.v10.hook;

import android.app.Application;
import android.app.Activity;
import android.view.KeyEvent;
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
import java.lang.reflect.Method;
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
    // Read-only diagnostics: log each Window root once, without adding another addView hook.
    private final Set<View> diagnosedRoots = Collections.newSetFromMap(new WeakHashMap<>());
    private final IdentityHashMap<View, ViewSnapshot> hidden = new IdentityHashMap<>();
    private final IdentityHashMap<ViewGroup, View> covered = new IdentityHashMap<>();
    private final IdentityHashMap<ViewGroup, FullscreenSnapshot> fullscreen =
            new IdentityHashMap<>();
    private final ExecutorService providerExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean readInFlight = new AtomicBoolean();

    private volatile Context context;
    private volatile long visibleUntil;
    // Exact Coolwalk media-card tappable regions discovered in Gearhead 17.9.664044.
    private final Map<View, Boolean> mediaInfoRegions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private boolean mediaCardClickHookInstalled;
    private boolean leaseActive;
    private boolean rootsDirty = true;
    private boolean loggedNoDock;
    private boolean loggedRootFailure;
    private final Set<String> diagnosedClasses = Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private boolean navigationDiagnosticsInstalled;

    private GearheadDockPatcher() {
    }

    /** Cài hook theo dõi root view + vòng lặp lease. */
    public static void install() {
        GearheadDockPatcher patcher = new GearheadDockPatcher();

        // Install the listener-level route independently of the dock/root hooks.
        // A failure in WindowManager or layout diagnostics must not disable media-card taps.
        patcher.installMediaListenerHook();

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
                            if (target instanceof View && patcher.isMediaInfoClick((View) target)) {
                                // Consume the Android Auto media-card click before its
                                // native handler can launch the player on the phone.
                                param.setResult(true);
                                patcher.dispatchMediaOpen();
                                log("Consumed media metadata click; forwarding to projected display");
                            }
                        }
                    });
            // Keep CarAssistantMediaBrowserService discoverable by Gearhead.
            // Hide any duplicate source entry at the UI layer instead of filtering this service.
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
                                    patcher.logRootDiagnostic(view);
                                    patcher.attachContext(view.getContext());
                                });
                            }
                        }
                    });
            // Read-only layout tracing. Reuse the existing addView hook above; do not install a second one.
            DiagnosticLayoutInflaterHook.install();
            patcher.main.post(patcher::refreshRoots);
            log("Launcher dock hook v2 installed (GhFacetBar + embedded dock)");
        } catch (Throwable t) {
            log("Dock hook unavailable: " + t);
        }
    }

    /**
     * AADisplay-style listener interception: wrap the actual listener installed by
     * Gearhead, then decide at click time (after the view tree is attached) whether
     * the target is the metadata surface. This avoids relying only on performClick(),
     * which is not the callback Gearhead necessarily uses for its focus-ring targets.
     */
    private void installMediaListenerHook() {
        try {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "setOnClickListener",
                    View.OnClickListener.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object target = param.thisObject;
                            Object supplied = param.args[0];
                            if (!(target instanceof View) || !(supplied instanceof View.OnClickListener)
                                    || supplied instanceof MediaClickForwarder) {
                                return;
                            }
                            View view = (View) target;
                            String className = view.getClass().getName();
                            if (!className.equals(
                                    "com.google.android.apps.auto.components.coolwalk.focusring.TappableRegion")
                                    && !className.equals(
                                    "com.google.android.apps.auto.components.metadataview.MetadataView")) {
                                return;
                            }

                            final View.OnClickListener original = (View.OnClickListener) supplied;
                            param.args[0] = new MediaClickForwarder() {
                                @Override
                                public void onClick(View clicked) {
                                    boolean metadata = isMediaInfoClick(clicked);
                                    if (!metadata && clicked != null
                                            && clicked.getClass().getName().equals(
                                            "com.google.android.apps.auto.components.coolwalk.focusring.TappableRegion")) {
                                        metadata = containsMediaText(clicked)
                                                && !isTransportControlRegion(clicked);
                                    }
                                    if (metadata && !patcher.isTransportControlRegion(clicked)) {
                                        log("MEDIACLICK listener intercepted metadata target="
                                                + clicked.getClass().getName()
                                                + " id=" + resourceEntryName(clicked));
                                        dispatchMediaOpen();
                                        return;
                                    }
                                    // Preserve Gearhead's original behavior for all non-metadata clicks.
                                    original.onClick(clicked);
                                }
                            };
                        }
                    });
            log("MEDIACLICK listener-level hook installed for Gearhead TappableRegion/MetadataView");
        } catch (Throwable error) {
            log("MEDIACLICK listener-level hook unavailable: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private interface MediaClickForwarder extends View.OnClickListener {
    }

    /**
     * Only forward taps on media metadata/card surfaces, not transport controls.
     * Resource names differ between Android Auto releases, so inspect a short
     * ancestor chain and accept known media/player containers.
     */
    private boolean isMediaInfoClick(View clicked) {
        // Reject transport-control ancestry as well as the clicked child itself.
        // Some AA versions place the icon inside a generic media container, so
        // checking only the clicked view would accidentally consume Play/Next.
        String[] controls = {"play_pause", "playpause", "play_button", "pause_button",
                "next", "previous", "skip", "fast_forward", "rewind", "transport",
                "seek_bar", "seekbar", "progress"};
        View current = clicked;
        boolean metadataSurface = false;
        for (int depth = 0; depth < 6 && current != null; depth++) {
            // Detect the actual Coolwalk metadata hit target directly as well as
            // the regions marked by the version-specific bind hook. If the bind hook
            // signature changes, a TappableRegion ancestor must still be intercepted
            // before Gearhead opens its native "CarAsistant Media" browser screen.
            String className = current.getClass().getName();
            // Android Auto 17.9.664044 inflates the metadata surface as a dedicated
            // MetadataView. The media card may not pass through MediaPlayerFragment.e(View)
            // on this release, so recognize this concrete metadata ancestor as a fallback.
            // Transport controls live in the playback template and are rejected below.
            if (className.equals(
                    "com.google.android.apps.auto.components.metadataview.MetadataView")) {
                metadataSurface = true;
            }
            // Do not classify every generic TappableRegion as metadata: Gearhead also
            // uses this wrapper for unrelated controls. Only regions identified while
            // binding the media card may be consumed here.
            if (mediaInfoRegions.containsKey(current)) {
                metadataSurface = true;
            }
            String name = resourceEntryName(current);
            if (name != null) {
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                for (String control : controls) {
                    if (lower.contains(control)) return false;
                }
                if (lower.contains("media_item_title") || lower.contains("media_title")
                        || lower.contains("media_metadata") || lower.contains("now_playing")
                        || lower.contains("media_info") || lower.contains("playback_metadata")
                        || lower.contains("song_title") || lower.contains("album_art")
                        || lower.contains("media_artwork")) {
                    metadataSurface = true;
                }
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        if (metadataSurface) return true;
        if (clicked instanceof TextView) {
            CharSequence description = clicked.getContentDescription();
            if (description != null) {
                String value = description.toString().toLowerCase(java.util.Locale.ROOT);
                return value.contains("now playing") || value.contains("đang phát")
                        || value.contains("currently playing");
            }
        }
        return false;
    }

    /**
     * Route the media-card tap through Gearhead's own Car App launcher, as used
     * by AADisplay's AaUiHook. This lets Gearhead resolve AACastCarService from
     * the installed manifest and call getCarActivity(), instead of opening the
     * media browser UI or launching the phone Activity.
     */
    private void dispatchMediaOpen() {
        Context appContext = context;
        if (appContext == null) {
            log("Media-card tap ignored: Gearhead application context is not ready");
            return;
        }
        try {
            // Match AADisplay Beta: resolve the exact static a(Intent) launcher,
            // then pass the CarActivityService component and owning package.
            ClassLoader loader = appContext.getClassLoader();
            Class<?> controller = XposedHelpers.findClass(
                    "com.google.android.projection.gearhead.service.CarSystemUiControllerService",
                    loader);
            Method launcher = null;
            for (Class<?> type = controller; type != null && launcher == null;
                    type = type.getSuperclass()) {
                try {
                    Method candidate = type.getDeclaredMethod("a", Intent.class);
                    if (Modifier.isStatic(candidate.getModifiers())) {
                        launcher = candidate;
                    }
                } catch (NoSuchMethodException ignored) {
                    // Continue up the hierarchy, matching the resolved Gearhead method.
                }
            }
            if (launcher == null) {
                throw new NoSuchMethodException(
                        "CarSystemUiControllerService static a(Intent) not found");
            }

            launcher.setAccessible(true);
            Intent carAppIntent = new Intent();
            carAppIntent.setComponent(new android.content.ComponentName(
                    "com.carassistant.v10", "com.carassistant.v10.AACastCarService"));
            carAppIntent.putExtra("android.intent.extra.PACKAGE_NAME", "com.carassistant.v10");
            log("Media-card tap detected; invoking Gearhead Car App launcher for AACastCarService");
            launcher.invoke(null, carAppIntent);
            log("Gearhead Car App launcher invoked for AACastCarService; service returns V10CarActivity");
        } catch (Throwable error) {
            log("Gearhead Car App launch failed; forwarding fallback event: " + error);
            try {
                Intent fallback = new Intent("com.carassistant.v10.STEERING_MEDIA_OPEN");
                fallback.setPackage("com.carassistant.v10");
                appContext.sendBroadcast(fallback);
            } catch (Throwable broadcastError) {
                log("Could not forward fallback media-open event: " + broadcastError);
            }
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
        installNavigationDiagnostics();
        installMediaCardClickHook(context.getClassLoader());
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(GEARHEAD, 0);
            log("Gearhead media hook diagnostics: Android Auto version=" + info.versionName
                    + " versionCode=" + info.getLongVersionCode());
        } catch (Throwable error) {
            log("Could not read Android Auto version: " + error.getClass().getSimpleName());
        }
        main.post(this::tick);
    }

    /**
     * Temporary, read-only diagnostics for identifying Gearhead's actual media
     * navigation owner before implementing any navigation override. This hook
     * records runtime Activity transitions and signatures of relevant loaded
     * Gearhead classes; it deliberately does not change navigation behavior.
     */
    private void installNavigationDiagnostics() {
        if (navigationDiagnosticsInstalled) return;
        navigationDiagnosticsInstalled = true;
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Activity activity = (Activity) param.thisObject;
                                if (activity.getPackageName().equals(GEARHEAD)) {
                                    log("NAVDIAG ACTIVITY_RESUMED class="
                                            + activity.getClass().getName()
                                            + " taskId=" + activity.getTaskId()
                                            + " finishing=" + activity.isFinishing());
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
            XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent",
                    android.view.KeyEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Activity activity = (Activity) param.thisObject;
                                KeyEvent event = (KeyEvent) param.args[0];
                                if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK
                                        && event.getAction() == KeyEvent.ACTION_UP
                                        && GEARHEAD.equals(activity.getPackageName())) {
                                    log("NAVDIAG BACK_KEY activity="
                                            + activity.getClass().getName());
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
            // Discover relevant obfuscated classes as Gearhead loads them. Only
            // class names/signatures are logged; no method is invoked or modified.
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass",
                    String.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object result = param.getResult();
                                if (!(result instanceof Class)) return;
                                Class<?> type = (Class<?>) result;
                                String name = type.getName();
                                if (!name.startsWith("com.google.android.apps.auto.")) return;
                                String lower = name.toLowerCase(java.util.Locale.ROOT);
                                if (!(lower.contains("media") || lower.contains("player")
                                        || lower.contains("dashboard") || lower.contains("navigator")
                                        || lower.contains("navigation") || lower.contains("router")
                                        || lower.contains("back") || lower.contains("fragment")
                                        || lower.contains("activity") || lower.contains("carapp"))) return;
                                if (!diagnosedClasses.add(name)) return;
                                logClassSignatures(type);
                            } catch (Throwable ignored) { }
                        }
                    });
            log("NAVDIAG installed: read-only Activity/back/class-signature tracing enabled");
        } catch (Throwable error) {
            log("NAVDIAG install failed: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage());
        }
    }

    private void logClassSignatures(Class<?> type) {
        try {
            Method[] methods = type.getDeclaredMethods();
            StringBuilder out = new StringBuilder();
            out.append("NAVDIAG CLASS ").append(type.getName())
                    .append(" methods=").append(methods.length);
            int emitted = 0;
            for (Method method : methods) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (!(name.contains("navigate") || name.contains("nav") || name.contains("media")
                        || name.contains("player") || name.contains("back") || name.contains("pop")
                        || name.contains("open") || name.contains("show") || name.contains("launch")
                        || name.contains("route") || name.contains("fragment") || name.contains("task")
                        || name.equals("e") || name.equals("f"))) continue;
                out.append(" | ").append(Modifier.toString(method.getModifiers())).append(' ')
                        .append(method.getName()).append('(');
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) out.append(',');
                    out.append(params[i].getSimpleName());
                }
                out.append("):").append(method.getReturnType().getSimpleName());
                if (++emitted >= 16) break;
            }
            log(out.toString());
        } catch (Throwable error) {
            log("NAVDIAG CLASS " + type.getName() + " signatureError="
                    + error.getClass().getSimpleName());
        }
    }

    /**
     * Android Auto 17.9.664044 wires the Coolwalk media card in
     * MediaPlayerFragment.e(View). Mark its TappableRegion views at bind time,
     * then consume only clicks on those regions. Transport buttons use separate
     * button classes and are deliberately left to Gearhead.
     */
    private void installMediaCardClickHook(ClassLoader loader) {
        if (mediaCardClickHookInstalled) return;
        boolean anyHookInstalled = false;

        // Keep binder and click-target hooks independent. A changed binder signature
        // must not prevent direct metadata click hooks from being installed.
        try {
            XposedHelpers.findAndHookMethod(
                    "com.google.android.apps.auto.components.system.dashboard.media.MediaPlayerFragment",
                    loader,
                    "e",
                    View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            log("MEDIACLICKDIAG MediaPlayerFragment.e(View) entered arg0="
                                    + (param.args.length > 0 && param.args[0] != null
                                    ? param.args[0].getClass().getName() : "null"));
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0 && param.args[0] instanceof View) {
                                View root = (View) param.args[0];
                                log("MEDIACLICKDIAG MediaPlayerFragment.e(View) completed root="
                                        + root.getClass().getName() + " id=" + resourceEntryName(root)
                                        + " childCount=" + (root instanceof ViewGroup
                                        ? ((ViewGroup) root).getChildCount() : 0));
                                markMediaInfoRegions(root);
                            }
                        }
                    });
            anyHookInstalled = true;
            log("MEDIACLICKDIAG installed MediaPlayerFragment.e(View) binder hook");
        } catch (Throwable error) {
            log("MEDIACLICKDIAG MediaPlayerFragment binder hook unavailable: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.google.android.apps.auto.components.coolwalk.focusring.TappableRegion",
                    loader,
                    "performClick",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object target = param.thisObject;
                            if (!(target instanceof View)) return;
                            View view = (View) target;
                            boolean metadata = mediaInfoRegions.containsKey(view)
                                    || isMetadataViewOrChild(view);
                            log("MEDIACLICKDIAG TappableRegion.performClick id="
                                    + resourceEntryName(view)
                                    + " desc=" + view.getContentDescription()
                                    + " size=" + view.getWidth() + "x" + view.getHeight()
                                    + " markedMetadata=" + metadata
                                    + " containsMediaText=" + containsMediaText(view)
                                    + " transportRegion=" + isTransportControlRegion(view));
                            if (metadata && !isTransportControlRegion(view)) {
                                param.setResult(true);
                                log("MEDIACLICKDIAG consumed metadata TappableRegion click; dispatching V10 open");
                                dispatchMediaOpen();
                            }
                        }
                    });
            anyHookInstalled = true;
            log("MEDIACLICKDIAG installed concrete TappableRegion.performClick hook");
        } catch (Throwable error) {
            log("MEDIACLICKDIAG TappableRegion click hook unavailable: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        }

        // Some AA versions may route the click directly through MetadataView rather
        // than the fragment binder. Hook it independently and leave playback controls alone.
        try {
            XposedHelpers.findAndHookMethod(
                    "com.google.android.apps.auto.components.metadataview.MetadataView",
                    loader,
                    "performClick",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object target = param.thisObject;
                            if (!(target instanceof View)) return;
                            View view = (View) target;
                            if (isTransportControlRegion(view)) return;
                            log("MEDIACLICKDIAG MetadataView.performClick size="
                                    + view.getWidth() + "x" + view.getHeight()
                                    + " desc=" + view.getContentDescription());
                            param.setResult(true);
                            log("MEDIACLICKDIAG consumed MetadataView click; dispatching V10 open");
                            dispatchMediaOpen();
                        }
                    });
            anyHookInstalled = true;
            log("MEDIACLICKDIAG installed concrete MetadataView.performClick hook");
        } catch (Throwable error) {
            log("MEDIACLICKDIAG MetadataView click hook unavailable: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        }

        mediaCardClickHookInstalled = anyHookInstalled;
        log("MEDIACLICKDIAG media click hook installation finished; anyHookInstalled="
                + anyHookInstalled);
    }

    private boolean isMetadataViewOrChild(View view) {
        View current = view;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (current.getClass().getName().equals(
                    "com.google.android.apps.auto.components.metadataview.MetadataView")) {
                return true;
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private void markMediaInfoRegions(View root) {
        if (root == null) return;
        if ("com.google.android.apps.auto.components.coolwalk.focusring.TappableRegion"
                .equals(root.getClass().getName())) {
            // Do not reject a metadata hit target merely because Gearhead nests
            // transport controls elsewhere inside the same card container. The
            // old subtree-wide test could discard the title/artwork target and
            // leave Gearhead's native media-browser click handler in control.
            boolean transport = isTransportControlRegion(root);
            boolean hasMediaText = containsMediaText(root);
            log("MEDIACLICKDIAG inspect TappableRegion id=" + resourceEntryName(root)
                    + " desc=" + root.getContentDescription()
                    + " childCount=" + (root instanceof ViewGroup
                    ? ((ViewGroup) root).getChildCount() : 0)
                    + " containsMediaText=" + hasMediaText
                    + " transportRegion=" + transport);
            if (transport || !hasMediaText) {
                mediaInfoRegions.remove(root);
            } else {
                mediaInfoRegions.put(root, Boolean.TRUE);
                log("MEDIACLICKDIAG registered media metadata region id=" + resourceEntryName(root));
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                markMediaInfoRegions(group.getChildAt(i));
            }
        }
    }

    /** Reject a region only when the region itself identifies a transport action. */
    private boolean isTransportControlRegion(View view) {
        String name = resourceEntryName(view);
        if (name != null) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            String[] controls = {"play_pause", "playpause", "play_button", "pause_button",
                    "next", "previous", "skip", "fast_forward", "rewind", "transport",
                    "seek_bar", "seekbar", "progress"};
            for (String control : controls) {
                if (lower.contains(control)) return true;
            }
        }
        CharSequence description = view.getContentDescription();
        if (description != null) {
            String lower = description.toString().toLowerCase(java.util.Locale.ROOT);
            String[] controls = {"play", "pause", "next", "previous", "skip",
                    "forward", "rewind", "seek", "phát", "dừng", "tiếp"};
            for (String control : controls) {
                if (lower.contains(control)) return true;
            }
        }
        return false;
    }

    /** Metadata hit regions normally contain title/artist TextViews, unlike icon buttons. */
    private boolean containsMediaText(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.toString().trim().length() > 0
                    && !isTransportControlRegion(view)) {
                return true;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsMediaText(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /**
     * Legacy subtree helper retained for click classification of an already
     * identified target; this is not used to discard an entire metadata region.
     */
    private boolean containsTransportControl(View view) {
        if (view == null) return false;
        String name = resourceEntryName(view);
        if (name != null) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            String[] controls = {"play_pause", "playpause", "play_button", "pause_button",
                    "next", "previous", "skip", "fast_forward", "rewind", "transport",
                    "seek_bar", "seekbar", "progress"};
            for (String control : controls) {
                if (lower.contains(control)) return true;
            }
        }
        if (view instanceof TextView) {
            CharSequence description = view.getContentDescription();
            if (description != null) {
                String lower = description.toString().toLowerCase(java.util.Locale.ROOT);
                String[] controls = {"play", "pause", "next", "previous", "skip",
                        "forward", "rewind", "seek", "tua", "phát", "dừng", "tiếp"};
                for (String control : controls) {
                    if (lower.contains(control)) return true;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsTransportControl(group.getChildAt(i))) return true;
            }
        }
        return false;
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
                        View rootView = (View) root;
                        roots.add(rootView);
                        logRootDiagnostic(rootView);
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

    /** Log Window roots separately from descendant containers; never modifies their UI. */
    private void logRootDiagnostic(View root) {
        if (root == null || !diagnosedRoots.add(root)) return;
        try {
            Display display = root.getDisplay();
            String displayInfo = display == null ? "unknown"
                    : display.getDisplayId() + ":" + display.getName();
            log("LAYOUTDIAG ROOT class=" + root.getClass().getName()
                    + " attached=" + root.isAttachedToWindow()
                    + " size=" + root.getWidth() + "x" + root.getHeight()
                    + " display=" + displayInfo
                    + " context=" + root.getContext().getClass().getName());
        } catch (Throwable error) {
            log("LAYOUTDIAG ROOT log failed: " + error.getClass().getSimpleName());
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
