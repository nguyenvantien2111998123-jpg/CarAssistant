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
    private static final String SPLIT_RIGHT_TARGET = "split_right_target_component";
    private static final String SPLIT_RATIO = "split_display1_ratio";
    private static final String QUICK_APPS = "home_quick_apps";
    private static final String SPLIT_LEFT_ALLOWED_APPS = "split_left_allowed_apps";
    private static final String DEFAULT_NAVIGATION = "voice_default_navigation";
    private static final String DEFAULT_MUSIC = "voice_default_music";
    private static final String DEFAULT_VIDEO = "voice_default_video";
    private static final String DEFAULT_TV = "voice_default_tv";

    private static volatile V10CarActivity currentActivity;

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

    /** App assigned to the right (70%) pane of Android Auto Display 1. */
    public static void setSplitRightTarget(Context context, ComponentName component) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (component == null) editor.remove(SPLIT_RIGHT_TARGET);
        else editor.putString(SPLIT_RIGHT_TARGET, component.flattenToString());
        editor.apply();
    }

    public static ComponentName getSplitRightTarget(Context context) {
        String value = prefs(context).getString(SPLIT_RIGHT_TARGET, null);
        return value == null || value.isEmpty() ? null : ComponentName.unflattenFromString(value);
    }

    /** Split ratio is the left pane fraction, clamped to keep both panes usable. */
    public static void setSplitRatio(Context context, float ratio) {
        prefs(context).edit().putFloat(SPLIT_RATIO, Math.max(0.15f, Math.min(0.85f, ratio))).apply();
    }

    public static float getSplitRatio(Context context) {
        return Math.max(0.15f, Math.min(0.85f, prefs(context).getFloat(SPLIT_RATIO, 0.30f)));
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

    /** Unlimited allow-list for apps that may be launched in the left 30% split pane. */
    public static void setSplitLeftAllowedApps(Context context, List<ComponentName> components) {
        StringBuilder value = new StringBuilder();
        if (components != null) {
            for (ComponentName component : components) {
                if (component == null) continue;
                boolean duplicate = false;
                for (String saved : value.toString().split("\\n")) {
                    ComponentName existing = ComponentName.unflattenFromString(saved);
                    if (existing != null && existing.getPackageName().equals(component.getPackageName())) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;
                if (value.length() > 0) value.append("\n");
                value.append(component.flattenToString());
            }
        }
        prefs(context).edit().putString(SPLIT_LEFT_ALLOWED_APPS, value.toString()).apply();
    }

    public static ArrayList<ComponentName> getSplitLeftAllowedApps(Context context) {
        String value = prefs(context).getString(SPLIT_LEFT_ALLOWED_APPS, "");
        ArrayList<ComponentName> result = new ArrayList<>();
        if (value == null || value.isEmpty()) return result;
        for (String item : value.split("\\n")) {
            ComponentName stored = ComponentName.unflattenFromString(item);
            if (stored == null) continue;
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(stored.getPackageName());
            if (launch == null) continue;
            ComponentName usable = launch.getComponent() != null ? launch.getComponent() : stored;
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

    public static void clearTarget(Context context) {
        prefs(context).edit().remove(TARGET).apply();
    }

    public static void setCurrentActivity(V10CarActivity activity) { currentActivity = activity; }
    public static V10CarActivity getCurrentActivity() { return currentActivity; }
    public static void clearCurrentActivity(V10CarActivity activity) {
        if (currentActivity == activity) currentActivity = null;
    }
}
