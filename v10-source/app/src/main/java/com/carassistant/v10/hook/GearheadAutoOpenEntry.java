package com.carassistant.v10.hook;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Runs passive Car Activity diagnostics inside Gearhead; automatic app launch is disabled. */
public final class GearheadAutoOpenEntry implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.google.android.projection.gearhead".equals(param.packageName)) return;
        Log.i("AACastAutoOpen", "XPOSED_ENTRY package=" + param.packageName
                + " process=" + param.processName
                + " loader=" + (param.classLoader == null ? "null" : param.classLoader.getClass().getName()));
        GearheadCarActivityTrace.install(param.classLoader);
    }
}