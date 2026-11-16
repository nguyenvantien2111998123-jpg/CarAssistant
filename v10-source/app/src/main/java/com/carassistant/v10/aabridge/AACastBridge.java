package com.carassistant.v10.aabridge;

import android.os.Build;

import com.carassistant.v10.AACastDisplay;
import com.carassistant.v10.hook.DisplayHooks;
import com.carassistant.v10.hook.GearheadAllow;
import com.carassistant.v10.hook.GearheadDockPatcher;
import com.carassistant.v10.hook.GearheadMediaSessionFilter;
import com.carassistant.v10.hook.GearheadSteeringKeyHooks;
import com.carassistant.v10.hook.InputHooks;
import com.carassistant.v10.hook.OrientationHooks;
import com.carassistant.v10.hook.SystemMediaKeyHooks;
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
            DisplayHooks.installV10RootLaunchPolicy(param.classLoader);
            OrientationHooks.install(param.classLoader);
            InputHooks.install(param.classLoader);
            // Capture AVRCP steering-wheel media keys at the system media-session
            // dispatch point so single/double/hold gestures can be distinguished.
            SystemMediaKeyHooks.install(param.classLoader);
            return;
        }

        // ---- Android Auto ----
        if (GEARHEAD.equals(packageName)) {
            String processName = param.processName == null ? "" : param.processName;

            // Only spoof source information for CarAsistant, and only in Gearhead.
            InstallerSpoof.hookLegacy(param.classLoader, PLAY);
            if (Build.VERSION.SDK_INT >= 30) {
                InstallerSpoof.hookInstallSourceInfo(param.classLoader, PLAY);
            }

            GearheadAllow.install(param.classLoader);
            GearheadMediaSessionFilter.install();
            GearheadDockPatcher.install();

            // Receiver ownership differs between AA releases. Keep system_server as
            // the primary key path; observe both candidate Gearhead processes as fallback.
            if (processName.endsWith(":projection") || processName.endsWith(":car")) {
                GearheadSteeringKeyHooks.install(processName);
            } else {
                XposedBridge.log("[AACast] Steering receiver fallback not installed in "
                        + processName);
            }
            XposedBridge.log("[AACast] Module loaded in " + packageName
                    + " process=" + processName);
            return;
        }

        if (SELF.equals(packageName)) {
            XposedBridge.log("[AACast] App process loaded: " + param.processName);
        }
    }
}
