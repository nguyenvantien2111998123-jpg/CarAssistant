package com.carassistant.v10.aabridge;

import android.os.Build;

import com.carassistant.v10.AACastDisplay;
import com.carassistant.v10.hook.DisplayHooks;
import com.carassistant.v10.hook.GearheadAllow;
import com.carassistant.v10.hook.GearheadDockPatcher;
import com.carassistant.v10.hook.InputHooks;
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

        // ---- system_server ----
        if ("android".equals(packageName)) {
            DisplayHooks.installDisplayFlags(param.classLoader);
            DisplayHooks.installInputPolicy(param.classLoader);
            InputHooks.install(param.classLoader);
            return;
        }

        // ---- Android Auto (và chính app, để mọi code path nhất quán) ----
        if (GEARHEAD.equals(packageName) || SELF.equals(packageName)) {
            InstallerSpoof.hookLegacy(param.classLoader, PLAY);

            if (GEARHEAD.equals(packageName)) {
                GearheadAllow.install(param.classLoader);
                GearheadDockPatcher.install();
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
            XposedBridge.log("[AACast] Module loaded in " + packageName);
        }
    }
}
