package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

public final class V10SessionStore {

    private static final String PREFS =
            "v10_session";

    private static final String TARGET =
            "target_component";
    private static final String BACKGROUND_TARGET = "background_target_component";
    private static final String QUICK_APPS = "home_quick_apps";
    private static final String AUTO_OPEN_ANDROID_AUTO = "auto_open_android_auto";

    private static volatile V10CarActivity currentActivity;

    private V10SessionStore() {
    }

    private static SharedPreferences prefs(
            Context context) {

        return context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE);
    }

    public static void setTarget(
            Context context,
            ComponentName component) {

        SharedPreferences.Editor editor =
                prefs(context).edit();

        if (component == null) {
            editor.remove(TARGET);
        } else {
            editor.putString(
                    TARGET,
                    component.flattenToString());
        }

        editor.apply();
    }

    public static ComponentName getTarget(
            Context context) {

        String value =
                prefs(context).getString(
                        TARGET,
                        null);

        if (value == null ||
                value.isEmpty()) {
            return null;
        }

        return ComponentName.unflattenFromString(
                value);
    }

    public static void setBackgroundTarget(Context context, ComponentName component) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (component == null) editor.remove(BACKGROUND_TARGET);
        else editor.putString(BACKGROUND_TARGET, component.flattenToString());
        editor.apply();
    }

    public static ComponentName getBackgroundTarget(Context context) {
        String value = prefs(context).getString(BACKGROUND_TARGET, null);
        return value == null || value.isEmpty() ? null : ComponentName.unflattenFromString(value);
    }

    /** Exactly three configurable app shortcuts for the Android Auto Home sidebar. */
    public static void setQuickLaunchApps(Context context, List<ComponentName> components) {
        StringBuilder value = new StringBuilder();
        if (components != null) {
            int count = 0;
            for (ComponentName component : components) {
                if (component == null || count >= 3) continue;
                if (count > 0) value.append("\n");
                value.append(component.flattenToString());
                count++;
            }
        }
        prefs(context).edit().putString(QUICK_APPS, value.toString()).apply();
    }

    public static ArrayList<ComponentName> getQuickLaunchApps(Context context) {
        String value = prefs(context).getString(QUICK_APPS, null);
        ArrayList<ComponentName> result = new ArrayList<>();

        // Start empty so stale hard-coded defaults cannot occupy slots when those apps
        // are absent from the user's phone.
        if (value == null || value.isEmpty()) return result;

        for (String item : value.split("\\n")) {
            ComponentName stored = ComponentName.unflattenFromString(item);
            if (stored == null || result.size() >= 3) continue;

            // Ignore removed/unlaunchable packages and normalize stale activity names
            // to the currently installed launcher activity.
            Intent launch = context.getPackageManager()
                    .getLaunchIntentForPackage(stored.getPackageName());
            if (launch == null) continue;
            ComponentName launchComponent = launch.getComponent();
            ComponentName usable = launchComponent != null ? launchComponent : stored;

            boolean duplicate = false;
            for (ComponentName existing : result) {
                if (existing.getPackageName().equals(usable.getPackageName())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) result.add(usable);
        }
        return result;
    }

    public static void clearTarget(
            Context context) {

        prefs(context)
                .edit()
                .remove(TARGET)
                .apply();
    }

    public static void setCurrentActivity(
            V10CarActivity activity) {

        currentActivity = activity;
    }

    public static V10CarActivity getCurrentActivity() {

        return currentActivity;
    }

    public static void clearCurrentActivity(
            V10CarActivity activity) {

        if (currentActivity == activity) {
            currentActivity = null;
        }
    }
}
