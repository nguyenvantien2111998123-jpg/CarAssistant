package com.carassistant.v10.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.carassistant.v10.V10SessionStore;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Home layout policy:
 *  - hide the legacy embedded MapLibre navigation view;
 *  - run the chosen/default phone map in the existing projection TextureView;
 *  - keep the existing transparent CarAssistant dashboard above that projection.
 *
 * V10CarActivity is a protected keyboard PASS baseline and is not modified.
 */
public final class HomeMapProjectionHook {
    private static final String TAG = "[CarAssistant HomeMap]";

    private HomeMapProjectionHook() { }

    public static void install(ClassLoader classLoader) {
        try {
            Class<?> activityClass = XposedHelpers.findClass(
                    "com.carassistant.v10.V10CarActivity", classLoader);

            XposedBridge.hookAllMethods(activityClass, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    keepMapBehindHome(param.thisObject);
                }
            });

            XposedBridge.hookAllMethods(activityClass, "returnToLauncher", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    keepMapBehindHome(param.thisObject);
                }
            });

            XposedHelpers.findAndHookMethod(activityClass, "launchProjection",
                    ComponentName.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object activityObject = param.thisObject;
                                android.app.Activity activity = (android.app.Activity) activityObject;
                                Object dashboard = XposedHelpers.getObjectField(activityObject, "dashboard");
                                Object launcher = XposedHelpers.getObjectField(activityObject, "launcherLayer");
                                ComponentName selected = V10SessionStore.getTarget(activity);
                                boolean homeMode = dashboard instanceof View
                                        && launcher instanceof View
                                        && ((View) dashboard).getVisibility() == View.VISIBLE
                                        && ((View) launcher).getVisibility() == View.VISIBLE
                                        && selected != null && selected.equals(param.args[0]);
                                param.setObjectExtra("carassistant_home_map_mode", homeMode);
                            } catch (Throwable ignored) {
                                param.setObjectExtra("carassistant_home_map_mode", false);
                            }
                        }

                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(param.getObjectExtra("carassistant_home_map_mode"))) {
                                keepMapBehindHome(param.thisObject);
                            }
                        }
                    });

            XposedBridge.log(TAG + " Home projection hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook unavailable: " + Log.getStackTraceString(t));
        }
    }

    private static void keepMapBehindHome(Object activityObject) {
        try {
            final android.app.Activity activity = (android.app.Activity) activityObject;
            final View legacyNavigation = (View)
                    XposedHelpers.getObjectField(activityObject, "navigationMap");
            final FrameLayout projectionLayer = (FrameLayout)
                    XposedHelpers.getObjectField(activityObject, "projectionLayer");
            final LinearLayout launcherLayer = (LinearLayout)
                    XposedHelpers.getObjectField(activityObject, "launcherLayer");
            final LinearLayout dashboard = (LinearLayout)
                    XposedHelpers.getObjectField(activityObject, "dashboard");
            final LinearLayout appPanel = (LinearLayout)
                    XposedHelpers.getObjectField(activityObject, "appPanel");
            final Object session = XposedHelpers.getObjectField(activityObject, "session");

            if (projectionLayer == null || launcherLayer == null
                    || dashboard == null || session == null) return;

            ComponentName target = V10SessionStore.getTarget(activity);
            if (target == null) target = resolveDefaultMapApp(activity);
            if (target == null) {
                XposedBridge.log(TAG + " no selected/default geo handler; cannot start map projection");
                return;
            }

            final ComponentName mapTarget = target;

            // Permanently remove the old in-app MapLibre view from the Home composition.
            if (legacyNavigation != null && legacyNavigation.getVisibility() != View.GONE) {
                legacyNavigation.setVisibility(View.GONE);
            }

            // Preserve Home controls as a transparent foreground above the map TextureView.
            projectionLayer.setVisibility(View.VISIBLE);
            launcherLayer.setVisibility(View.VISIBLE);
            dashboard.setVisibility(View.VISIBLE);
            if (appPanel != null) appPanel.setVisibility(View.GONE);
            projectionLayer.bringToFront();
            launcherLayer.bringToFront();

            // Keep the activity's target poller from calling launchProjection() every 500ms,
            // which would hide the Home UI again.
            XposedHelpers.setObjectField(activityObject, "activeTarget", mapTarget);
            V10SessionStore.setTarget(activity, mapTarget);

            // The TextureView may not have a surface until the layout pass finishes.
            projectionLayer.post(() -> {
                try {
                    XposedHelpers.callMethod(session, "setTarget", mapTarget);
                    XposedBridge.log(TAG + " map projected behind Home: "
                            + mapTarget.flattenToString());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " failed to start map projection: "
                            + Log.getStackTraceString(t));
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " failed to compose Home layers: "
                    + Log.getStackTraceString(t));
        }
    }

    private static ComponentName resolveDefaultMapApp(android.app.Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
            ResolveInfo resolved = activity.getPackageManager().resolveActivity(
                    intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved != null && resolved.activityInfo != null
                    && !activity.getPackageName().equals(resolved.activityInfo.packageName)) {
                return new ComponentName(resolved.activityInfo.packageName,
                        resolved.activityInfo.name);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " geo handler lookup failed: " + t);
        }
        return null;
    }
}