package com.carassistant.v10.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Routes taps on Android Auto's media metadata surface to the CarAsistant Car App.
 *
 * Installed only in Gearhead's :projection process. It wraps click listeners at
 * bind time instead of consuming every View.performClick(), and deliberately
 * excludes transport controls. When a metadata tap is recognized, it asks
 * Gearhead's own CarSystemUiControllerService to open AACastCarService; a
 * package-scoped broadcast is only a fallback signal.
 */
public final class GearheadMediaCardHook {
    private static final String TAG = "[AACast MediaTap]";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String PACKAGE = "com.carassistant.v10";
    private static final String CAR_SERVICE = "com.carassistant.v10.AACastCarService";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile ClassLoader hostLoader;

    private GearheadMediaCardHook() {}

    public static void install(ClassLoader classLoader) {
        if (classLoader == null || !INSTALLED.compareAndSet(false, true)) return;
        hostLoader = classLoader;
        try {
            XposedHelpers.findAndHookMethod(
                    View.class, "setOnClickListener", View.OnClickListener.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object raw = param.args[0];
                            if (!(raw instanceof View.OnClickListener)
                                    || raw instanceof RoutedListener) return;
                            View.OnClickListener original = (View.OnClickListener) raw;
                            param.args[0] = new RoutedListener(original);
                        }
                    });
            log("Installed listener wrapper in process classloader=" + classLoader);
        } catch (Throwable error) {
            INSTALLED.set(false);
            log("Listener hook install failed: " + error);
        }
    }

    private static final class RoutedListener implements View.OnClickListener {
        private final View.OnClickListener original;
        RoutedListener(View.OnClickListener original) { this.original = original; }

        @Override public void onClick(View view) {
            if (isMediaMetadataSurface(view)) {
                log("Recognized media metadata tap: " + describe(view));
                if (!requestCarActivity(view.getContext())) {
                    sendFallback(view.getContext());
                }
                // Do not call the original metadata listener: it can open the
                // phone-side player or AA's generic media detail page.
                return;
            }
            original.onClick(view);
        }
    }

    private static boolean isMediaMetadataSurface(View clicked) {
        if (clicked == null) return false;
        View current = clicked;
        for (int depth = 0; depth < 7 && current != null; depth++) {
            String className = current.getClass().getName().toLowerCase(Locale.ROOT);
            String idName = resourceName(current);
            String id = idName == null ? "" : idName.toLowerCase(Locale.ROOT);
            String combined = className + " " + id;

            if (isTransportControl(combined)) return false;
            if (containsAny(combined,
                    "metadata_view", "metadataview", "tappableregion",
                    "media_item_title", "media_title", "media_metadata",
                    "now_playing", "media_card", "media_info",
                    "playback_metadata", "song_title", "album_art",
                    "media_artwork", "media_content")) {
                // TappableRegion alone is too broad; require media-related ancestry
                // or metadata-specific ID/class for that generic host class.
                if (!combined.contains("tappableregion")
                        || hasMediaAncestor(clicked)) return true;
                if (containsAny(combined, "metadata", "media_item_title", "song_title")) return true;
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }

        if (clicked instanceof TextView) {
            CharSequence desc = clicked.getContentDescription();
            if (desc != null) {
                String text = desc.toString().toLowerCase(Locale.ROOT);
                return text.contains("now playing") || text.contains("đang phát");
            }
        }
        return false;
    }

    private static boolean hasMediaAncestor(View view) {
        View current = view;
        for (int depth = 0; depth < 7 && current != null; depth++) {
            String value = (current.getClass().getName() + " " + resourceName(current))
                    .toLowerCase(Locale.ROOT);
            if (containsAny(value, "metadata", "media_card", "media_info",
                    "now_playing", "playback", "album_art", "media_content")) return true;
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static boolean isTransportControl(String value) {
        return containsAny(value, "play_pause", "playpause", "next_button",
                "previous_button", "skip_next", "skip_previous", "fast_forward",
                "rewind", "transport", "play_button", "pause_button",
                "seek_bar", "progress_bar", "media_next", "media_previous");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String resourceName(View view) {
        if (view.getId() == View.NO_ID) return "";
        try {
            Resources resources = view.getResources();
            String pkg = resources.getResourcePackageName(view.getId());
            if (!GEARHEAD.equals(pkg)) return "";
            return resources.getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean requestCarActivity(Context context) {
        try {
            ClassLoader loader = hostLoader;
            Class<?> controller = XposedHelpers.findClassIfExists(
                    "com.google.android.projection.gearhead.service.CarSystemUiControllerService",
                    loader);
            if (controller == null) throw new ClassNotFoundException("CarSystemUiControllerService");
            Method launcher = findLauncher(controller);
            if (launcher == null) throw new NoSuchMethodException("static launcher(Intent)");
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(PACKAGE, CAR_SERVICE));
            intent.putExtra("android.intent.extra.PACKAGE_NAME", PACKAGE);
            launcher.setAccessible(true);
            launcher.invoke(null, intent);
            log("Gearhead accepted CarAsistant Car App launch request");
            return true;
        } catch (Throwable error) {
            log("Direct Gearhead launch failed; using receiver fallback: " + error);
            return false;
        }
    }

    private static Method findLauncher(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        && method.getParameterTypes().length == 1
                        && Intent.class.equals(method.getParameterTypes()[0])
                        && "a".equals(method.getName())) return method;
            }
        }
        return null;
    }

    private static void sendFallback(Context context) {
        try {
            Intent intent = new Intent("com.carassistant.v10.STEERING_MEDIA_OPEN");
            intent.setPackage(PACKAGE);
            context.sendBroadcast(intent);
            log("Sent fallback media-open broadcast");
        } catch (Throwable error) {
            log("Fallback broadcast failed: " + error);
        }
    }

    private static String describe(View view) {
        return view.getClass().getName() + " id=" + resourceName(view)
                + " display=" + (view.getDisplay() == null ? "?" : view.getDisplay().getDisplayId())
                + " uptime=" + SystemClock.uptimeMillis();
    }

    private static void log(String message) {
        Log.i("AACastMediaTap", message);
        XposedBridge.log(TAG + " " + message);
    }
}
