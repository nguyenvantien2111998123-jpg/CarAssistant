package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

public final class V10SessionStore {

    private static final String PREFS = "v10_session";
    private static final String TARGET = "target_component";
    private static final String BACKGROUND_TARGET = "background_target_component";
    private static final String QUICK_APPS = "home_quick_apps";
    private static final String DEFAULT_NAVIGATION = "voice_default_navigation";
    private static final String DEFAULT_MUSIC = "voice_default_music";
    private static final String DEFAULT_VIDEO = "voice_default_video";
    private static final String DEFAULT_TV = "voice_default_tv";
    private static final String VOICE_REQUESTED = "steering_voice_requested";

    private static volatile V10CarActivity currentActivity;
    private static volatile boolean showDashboardRequested;

    private V10SessionStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void setTarget(Context context, ComponentName component) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (component == null) editor.remove(TARGET);
        else editor.putString(TARGET, component.flattenToString());
        editor.apply();
    }

    public static ComponentName getTarget(Context context) {
        String value = prefs(context).getString(TARGET, null);
        return value == null || value.isEmpty() ? null : ComponentName.unflattenFromString(value);
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
        if (value == null || value.isEmpty()) return result;
        for (String item : value.split("\\n")) {
            ComponentName stored = ComponentName.unflattenFromString(item);
            if (stored == null || result.size() >= 3) continue;
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(stored.getPackageName());
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

    /** Category must be navigation, music, video, or tv. */
    public static void setDefaultVoiceApp(Context context, String category, ComponentName component) {
        String key = defaultKey(category);
        if (key == null) return;
        SharedPreferences.Editor editor = prefs(context).edit();
        if (component == null) editor.remove(key);
        else editor.putString(key, component.flattenToString());
        editor.apply();
    }

    /** Returns the selected default, or null when none is configured or installed. */
    public static ComponentName getDefaultVoiceApp(Context context, String category) {
        String key = defaultKey(category);
        if (key == null) return null;
        String value = prefs(context).getString(key, null);
        if (value == null || value.isEmpty()) return null;
        ComponentName stored = ComponentName.unflattenFromString(value);
        if (stored == null) return null;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(stored.getPackageName());
        if (launch == null) return null;
        return launch.getComponent() != null ? launch.getComponent() : stored;
    }

    private static String defaultKey(String category) {
        if ("navigation".equals(category)) return DEFAULT_NAVIGATION;
        if ("music".equals(category)) return DEFAULT_MUSIC;
        if ("video".equals(category)) return DEFAULT_VIDEO;
        if ("tv".equals(category)) return DEFAULT_TV;
        return null;
    }

    /** Persist a steering-wheel voice request until the car UI is resumed. */
    public static void requestVoiceOnNextActivity(Context context) {
        if (context == null) return;
        prefs(context).edit().putBoolean(VOICE_REQUESTED, true).apply();
    }

    public static boolean consumeVoiceOnNextActivity(Context context) {
        if (context == null) return false;
        SharedPreferences preferences = prefs(context);
        boolean requested = preferences.getBoolean(VOICE_REQUESTED, false);
        if (requested) preferences.edit().remove(VOICE_REQUESTED).apply();
        return requested;
    }

    public static void clearTarget(Context context) {
        prefs(context).edit().remove(TARGET).apply();
    }

    public static void setCurrentActivity(V10CarActivity activity) { currentActivity = activity; }
    public static V10CarActivity getCurrentActivity() { return currentActivity; }
    public static void requestShowDashboard() { showDashboardRequested = true; }
    public static boolean consumeShowDashboardRequest() {
        boolean requested = showDashboardRequested;
        showDashboardRequested = false;
        return requested;
    }
    public static void clearCurrentActivity(V10CarActivity activity) {
        if (currentActivity == activity) currentActivity = null;
    }
}
