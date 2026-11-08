package com.carassistant.v10.hook;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Installs the WYN asset mirror before Flutter creates its first frame.
 *
 * Flutter may resolve/decode the current warning image while MainActivity is
 * being created. Installing only from Activity.onResume misses that first
 * lookup, and a cached asset may never be requested again while driving.
 */
public final class WynHomeProbe {
    private static final String PACKAGE = "com.wynauto.wyn";
    private static final Set<String> INSTALLED =
            Collections.synchronizedSet(new HashSet<String>());

    private WynHomeProbe() { }

    public static void install(ClassLoader ignored) {
        String key = Integer.toHexString(System.identityHashCode(ignored));
        if (!INSTALLED.add(key)) return;

        // Application.attach runs before Application.onCreate and before the
        // launcher Activity creates/starts the Flutter engine. Install the
        // FlutterJNI/FlutterLoader/AssetManager hooks at this earlier point.
        try {
            XposedBridge.hookAllMethods(Application.class, "attach",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Application)) return;
                            Application application = (Application) param.thisObject;
                            try {
                                if (!PACKAGE.equals(application.getPackageName())) return;
                                Context appContext = application.getApplicationContext();
                                WynWarningLinkProbe.install(
                                        application.getClassLoader(), appContext);
                            } catch (Throwable ignored) { }
                        }
                    });
        } catch (Throwable ignoredError) { }

        // Keep a late-resume fallback for ROMs that defer Application attach
        // or replace the Activity class loader. WynWarningLinkProbe guards
        // duplicate installation for the same class loader.
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    Activity activity = (Activity) param.thisObject;
                    try {
                        if (!PACKAGE.equals(activity.getPackageName())) return;
                        WynWarningLinkProbe.install(
                                activity.getClassLoader(), activity.getApplicationContext());
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignoredError) { }
    }
}
