package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
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
        if (value == null) {
            // Preserve familiar shortcuts as the initial defaults; they can be changed in phone SETUPS.
            result.add(ComponentName.unflattenFromString("com.google.android.apps.maps/com.google.android.maps.MapsActivity"));
            result.add(ComponentName.unflattenFromString("com.google.android.youtube/com.google.android.youtube.HomeActivity"));
            result.add(ComponentName.unflattenFromString("com.zing.zalo/com.zing.zalo.ui.ZaloLauncherActivity"));
            return result;
        }
        if (value.isEmpty()) return result;
        for (String item : value.split("\n")) {
            ComponentName component = ComponentName.unflattenFromString(item);
            if (component != null && result.size() < 3) result.add(component);
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
