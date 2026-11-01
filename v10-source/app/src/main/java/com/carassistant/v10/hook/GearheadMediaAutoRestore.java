package com.carassistant.v10.hook;

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

/** Watches Coolwalk connection UI and starts headless restore without auto-opening V10. */
public final class GearheadMediaAutoRestore {
    private static final String TAG = "[AACast MediaRestore]";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String PACKAGE = "com.carassistant.v10";
    private static final String SERVICE = "com.carassistant.v10.MediaAutoRestoreService";
    private static final String ACTION = "com.carassistant.v10.action.RESTORE_MEDIA_ON_AA_CONNECT";
    private static final String FACET_BAR = "gh_coolwalk_vertical_facet_bar";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile long lastRequestElapsed;
    private GearheadMediaAutoRestore() {}

    public static void install(ClassLoader classLoader) {
        if (classLoader == null || !INSTALLED.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        LayoutInflater inflater = (LayoutInflater) param.thisObject;
                        Context context = inflater.getContext();
                        int resourceId = (Integer) param.args[0];
                        int expected = context.getResources().getIdentifier(FACET_BAR, "layout", GEARHEAD);
                        boolean matched = expected != 0 && expected == resourceId;
                        if (!matched && resourceId != 0) {
                            try { matched = FACET_BAR.equals(context.getResources().getResourceEntryName(resourceId))
                                    && GEARHEAD.equals(context.getResources().getResourcePackageName(resourceId)); }
                            catch (Throwable ignored) { }
                        }
                        if (!matched) return;
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastRequestElapsed < 30000L) return;
                        Intent restore = new Intent(ACTION)
                                .setComponent(new ComponentName(PACKAGE, SERVICE));
                        try {
                            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(restore);
                            else context.startService(restore);
                            // Start the cooldown only after Android accepted the service request.
                            // If Android 12 rejects a background FGS start, allow the next AA
                            // layout inflation to retry instead of suppressing it for 30 seconds.
                            lastRequestElapsed = now;
                            log("Detected Android Auto connection; requested saved-session resume in CarAsistant Media");
                        } catch (Throwable error) {
                            lastRequestElapsed = Math.max(0L, now - 25000L);
                            log("Could not start restore service; a later AA UI event will retry: " + error);
                        }
                    } catch (Throwable error) { log("Connection detection failed safely: " + error); }
                }
            });
            log("Coolwalk watcher installed; controller resumes without launching external app UI");
        } catch (Throwable error) { INSTALLED.set(false); log("Could not install watcher: " + error); }
    }
    private static void log(String message) { XposedBridge.log(TAG + " " + message); }
}
