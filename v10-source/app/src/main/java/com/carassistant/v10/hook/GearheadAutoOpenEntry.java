package com.carassistant.v10.hook;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
/** Separate LSPosed entry so auto-open remains isolated from media and steering hooks. */
public final class GearheadAutoOpenEntry implements IXposedHookLoadPackage {
 @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p){
  if("com.google.android.projection.gearhead".equals(p.packageName)) GearheadAutoOpen.install();
 }
}
