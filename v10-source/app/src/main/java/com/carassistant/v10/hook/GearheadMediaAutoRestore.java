package com.carassistant.v10.hook;

import de.robv.android.xposed.XposedBridge;

/**
 * Media restoration is now driven only by BOOT_COMPLETED and the boot JobService.
 * Deliberately installs no Gearhead Activity, layout, or Android Auto connection hooks.
 */
public final class GearheadMediaAutoRestore {
    private static boolean logged;

    private GearheadMediaAutoRestore() {}

    public static synchronized void install(ClassLoader classLoader) {
        if (logged) return;
        logged = true;
        XposedBridge.log("[AACast MediaRestore] Android Auto connection trigger disabled; "
                + "media recovery is boot-driven");
    }
}
