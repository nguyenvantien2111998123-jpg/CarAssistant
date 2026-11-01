package com.carassistant.v10.hook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Keeps the original Android Auto foreground/layout detection for wireless and wired AA.
 * Boot and USB-data triggers are additional independent paths; this hook is not removed.
 */
public final class GearheadMediaAutoRestore {
    private static final String TAG = "[AACast MediaRestore]";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String PACKAGE = "com.carassistant.v10";
    private static final String SERVICE = "com.carassistant.v10.MediaAutoRestoreService";
    private static final String ACTION = "com.carassistant.v10.action.RESTORE_MEDIA_ON_AA_CONNECT";
    private static final String FACET_BAR = "gh_coolwalk_vertical_facet_bar";
    private static final long REQUEST_COOLDOWN_MS = 30000L;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile long lastRequestElapsed;

    private GearheadMediaAutoRestore() {}

    public static void install(ClassLoader classLoader) {
        if (classLoader == null || !INSTALLED.compareAndSet(false, true)) return;
        try {
            XC_MethodHook activityResumeHook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        requestRestore(activity, "Gearhead Activity "
                                + activity.getClass().getName() + " resumed");
                    } catch (Throwable error) {
                        log("Activity lifecycle detection failed safely: " + error);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", activityResumeHook);
            XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", activityResumeHook);
            log("Activity lifecycle watchers installed for Android Auto foreground/reconnect events");

            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        LayoutInflater inflater = (LayoutInflater) param.thisObject;
                        Context context = inflater.getContext();
                        int resourceId = (Integer) param.args[0];
                        int expected = context.getResources().getIdentifier(
                                FACET_BAR, "layout", GEARHEAD);
                        boolean matched = expected != 0 && expected == resourceId;
                        if (!matched && resourceId != 0) {
                            try {
                                matched = FACET_BAR.equals(
                                        context.getResources().getResourceEntryName(resourceId))
                                        && GEARHEAD.equals(
                                        context.getResources().getResourcePackageName(resourceId));
                            } catch (Throwable ignored) { }
                        }
                        if (matched) requestRestore(context, "Coolwalk facet-bar layout inflated");
                    } catch (Throwable error) {
                        log("Coolwalk layout detection failed safely: " + error);
                    }
                }
            });
            log("Coolwalk watcher retained as a second Android Auto reconnect signal");
        } catch (Throwable error) {
            INSTALLED.set(false);
            log("Could not install one or more Gearhead watchers: " + error);
        }
    }

    private static void requestRestore(Context context, String reason) {
        long now = SystemClock.elapsedRealtime();
        long previous = lastRequestElapsed;
        if (previous != 0L && now - previous < REQUEST_COOLDOWN_MS) return;

        Intent restore = new Intent(ACTION)
                .setComponent(new ComponentName(PACKAGE, SERVICE));
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(restore);
            else context.startService(restore);
            lastRequestElapsed = now;
            log("Restore service request accepted; trigger=" + reason);
        } catch (Throwable error) {
            lastRequestElapsed = 0L;
            log("Restore service start rejected; will retry on next Gearhead event. trigger="
                    + reason + "; error=" + error);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + " " + message);
    }
}
