package com.carassistant.v10.hook;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Read-only Gearhead layout diagnostics. This hook never changes an inflated view.
 * It intentionally hooks only inflate(int, ViewGroup, boolean) to avoid duplicate
 * logging through LayoutInflater overload delegation.
 */
final class DiagnosticLayoutInflaterHook {
    private static final String TAG = "[AACast LayoutDiag]";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final String[] KEYWORDS = {
            "coolwalk", "facet", "media", "dashboard", "dock", "map",
            "navigation", "player", "playback", "rail"
    };

    private DiagnosticLayoutInflaterHook() { }

    static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod(
                    LayoutInflater.class,
                    "inflate",
                    int.class,
                    ViewGroup.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.args[0] instanceof Integer)) return;
                                Object result = param.getResult();
                                if (!(result instanceof View)) return;

                                int resourceId = (Integer) param.args[0];
                                View inflated = (View) result;
                                ViewGroup parent = param.args[1] instanceof ViewGroup
                                        ? (ViewGroup) param.args[1] : null;
                                logCandidate(resourceId, parent, inflated);
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + " inflate diagnostic error="
                                        + error.getClass().getSimpleName());
                            }
                        }
                    });
            XposedBridge.log(TAG + " installed (read-only, inflate(int, ViewGroup, boolean))");
        } catch (Throwable error) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + " install failed: " + error);
        }
    }

    private static void logCandidate(int resourceId, ViewGroup parent, View view) {
        Context context = view.getContext();
        String resourcePackage = "?";
        String resourceName = "?";
        try {
            Resources resources = context.getResources();
            resourcePackage = resources.getResourcePackageName(resourceId);
            resourceName = resources.getResourceEntryName(resourceId);
        } catch (Throwable ignored) {
            // Some layouts are inflated using a foreign or wrapped Resources object.
        }

        String lowerName = resourceName.toLowerCase(Locale.ROOT);
        boolean candidate = false;
        for (String keyword : KEYWORDS) {
            if (lowerName.contains(keyword)) {
                candidate = true;
                break;
            }
        }
        if (!candidate) return;

        Activity activity = findActivity(context);
        Display viewDisplay = safeDisplay(view);
        View root = view.getRootView();
        Display rootDisplay = safeDisplay(root);
        Display activityDisplay = activity == null ? null : safeActivityDisplay(activity);

        XposedBridge.log(TAG
                + " INFLATE resId=0x" + Integer.toHexString(resourceId)
                + " resPackage=" + resourcePackage
                + " resName=" + resourceName
                + " view=" + view.getClass().getName()
                + " parent=" + (parent == null ? "null" : parent.getClass().getName())
                + " parentAttached=" + (parent != null && parent.isAttachedToWindow())
                + " attached=" + view.isAttachedToWindow()
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " viewDisplay=" + displayInfo(viewDisplay)
                + " root=" + (root == null ? "null" : root.getClass().getName())
                + " rootDisplay=" + displayInfo(rootDisplay)
                + " context=" + (context == null ? "null" : context.getClass().getName())
                + " activity=" + (activity == null ? "unknown" : activity.getClass().getName())
                + " activityDisplay=" + displayInfo(activityDisplay));
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        // Guard against malformed ContextWrapper chains and cycles.
        for (int i = 0; i < 12 && current != null; i++) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) return null;
            current = next;
        }
        return null;
    }

    private static Display safeDisplay(View view) {
        if (view == null) return null;
        try {
            return view.getDisplay();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Display safeActivityDisplay(Activity activity) {
        try {
            return activity.getDisplay();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String displayInfo(Display display) {
        if (display == null) return "unknown";
        try {
            return display.getDisplayId() + ":" + display.getName();
        } catch (Throwable ignored) {
            return "id=" + display.getDisplayId();
        }
    }
}
