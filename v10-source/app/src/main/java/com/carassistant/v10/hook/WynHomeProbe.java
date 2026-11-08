package com.carassistant.v10.hook;

import android.app.Activity;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Installs the low-noise WYN asset mirror when WYN resumes.
 * It deliberately does not inspect or dump the Flutter view tree.
 */
public final class WynHomeProbe {
    private static final String PACKAGE = "com.wynauto.wyn";
    private static final Set<String> INSTALLED =
            Collections.synchronizedSet(new HashSet<String>());

    private WynHomeProbe() { }

    public static void install(ClassLoader ignored) {
        String key = Integer.toHexString(System.identityHashCode(ignored));
        if (!INSTALLED.add(key)) return;
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
        } catch (Throwable ignored) { }
    }
}
