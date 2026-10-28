package com.carassistant.v10.aabridge;

import android.os.Build;

import com.carassistant.v10.AACastDisplay;
import com.carassistant.v10.hook.DisplayHooks;
import com.carassistant.v10.hook.GearheadAllow;
import com.carassistant.v10.hook.GearheadDockPatcher;
import com.carassistant.v10.hook.InputHooks;
import com.carassistant.v10.hook.OrientationHooks;
import com.carassistant.v10.hook.InstallerSpoof;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entry point của module Xposed — được khai báo trong assets/xposed_init.
 *
 * Scope cần bật trong LSPosed:
 *   • android                                  (system_server)
 *   • com.google.android.projection.gearhead   (Android Auto)
 */
public final class AACastBridge implements IXposedHookLoadPackage {

    private static final String SELF = AACastDisplay.PACKAGE;
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String PLAY = "com.android.vending";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        String packageName = param.packageName;

        // Earliest module-entry trace: proves LSPosed reached this package/process
        // even if a later hook installation throws or diagnostic filters omit other tags.
        XposedBridge.log("[AACastBridge] handleLoadPackage package=" + packageName
                + " process=" + param.processName
                + " loader=" + String.valueOf(param.classLoader));

        // ---- system_server ----
        if ("android".equals(packageName)) {
            DisplayHooks.installDisplayFlags(param.classLoader);
            DisplayHooks.installInputPolicy(param.classLoader);
            OrientationHooks.install(param.classLoader);
            InputHooks.install(param.classLoader);
            return;
        }

        // ---- Android Auto (và chính app, để mọi code path nhất quán) ----
        if (GEARHEAD.equals(packageName) || SELF.equals(packageName)) {
            InstallerSpoof.hookLegacy(param.classLoader, PLAY);

            if (GEARHEAD.equals(packageName)) {
                XposedBridge.log("[AACastBridge] installing GearheadAllow process="
                        + param.processName);
                try {
                    GearheadAllow.install(param.classLoader);
                    XposedBridge.log("[AACastBridge] GearheadAllow.install returned");
                } catch (Throwable t) {
                    XposedBridge.log("[AACastBridge] GearheadAllow.install FAILED: " + t);
                }
                try {
                    XposedBridge.log("[AACastBridge] calling GearheadDockPatcher.install");
                    GearheadDockPatcher.install();
                    XposedBridge.log("[AACastBridge] GearheadDockPatcher.install returned");
                } catch (Throwable t) {
                    XposedBridge.log("[AACastBridge] GearheadDockPatcher.install FAILED: " + t);
                }

                // No experimental Gearhead auto-open or steering/media-key interception.
                // Android Auto projection and display/input hooks remain enabled.
            }

            if (Build.VERSION.SDK_INT >= 30) {
                InstallerSpoof.hookInstallSource("getInitiatingPackageName", PLAY);
                InstallerSpoof.hookInstallSource("getInstallingPackageName", PLAY);
                if (Build.VERSION.SDK_INT >= 33) {
                    InstallerSpoof.hookInstallSource("getPackageSource", 2);
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    InstallerSpoof.hookInstallSource("getUpdateOwnerPackageName", PLAY);
                }
            }
            XposedBridge.log("[AACast] Module loaded in " + packageName
                    + " process=" + param.processName);
        }
    }
}
