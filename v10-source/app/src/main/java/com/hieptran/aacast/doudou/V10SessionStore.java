package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public final class V10SessionStore {

    private static final String PREFS = "v10_session";

    private static final String KEY_TARGET =
            "target_component";

    private static final String KEY_RECENT_PREFIX =
            "recent_";

    private static final int MAX_RECENTS = 4;

    private V10SessionStore() {
    }

    public static void setTarget(
            Context context,
            ComponentName component) {

        if (context == null || component == null) {
            return;
        }

        SharedPreferences prefs =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE);

        prefs.edit()
                .putString(
                        KEY_TARGET,
                        component.flattenToString())
                .apply();

        touchRecent(
                context,
                component);
    }

    public static ComponentName getTarget(
            Context context) {

        if (context == null) {
            return null;
        }

        SharedPreferences prefs =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE);

        String value =
                prefs.getString(
                        KEY_TARGET,
                        null);

        if (value == null || value.isEmpty()) {
            return null;
        }

        return ComponentName.unflattenFromString(
                value);
    }

    public static void clearTarget(
            Context context) {

        if (context == null) {
            return;
        }

        context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_TARGET)
                .apply();
    }

    public static void touchRecent(
            Context context,
            ComponentName component) {

        if (context == null || component == null) {
            return;
        }

        List<String> current =
                readRecentStrings(context);

        String value =
                component.flattenToString();

        current.remove(value);
        current.add(0, value);

        while (current.size() > MAX_RECENTS) {
            current.remove(
                    current.size() - 1);
        }

        SharedPreferences.Editor editor =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE)
                        .edit();

        for (int i = 0;
                i < MAX_RECENTS;
                i++) {

            String key =
                    KEY_RECENT_PREFIX + i;

            if (i < current.size()) {
                editor.putString(
                        key,
                        current.get(i));
            } else {
                editor.remove(key);
            }
        }

        editor.apply();
    }

    public static List<ComponentName> getRecents(
            Context context) {

        List<ComponentName> result =
                new ArrayList<>();

        if (context == null) {
            return result;
        }

        for (String value :
                readRecentStrings(context)) {

            ComponentName component =
                    ComponentName.unflattenFromString(
                            value);

            if (component != null) {
                result.add(component);
            }
        }

        return result;
    }

    public static boolean isRecent(
            Context context,
            ComponentName component) {

        if (context == null || component == null) {
            return false;
        }

        String value =
                component.flattenToString();

        return readRecentStrings(context)
                .contains(value);
    }

    public static void clearRecents(
            Context context) {

        if (context == null) {
            return;
        }

        SharedPreferences.Editor editor =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE)
                        .edit();

        for (int i = 0;
                i < MAX_RECENTS;
                i++) {

            editor.remove(
                    KEY_RECENT_PREFIX + i);
        }

        editor.apply();
    }

    private static List<String> readRecentStrings(
            Context context) {

        List<String> result =
                new ArrayList<>();

        if (context == null) {
            return result;
        }

        SharedPreferences prefs =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE);

        for (int i = 0;
                i < MAX_RECENTS;
                i++) {

            String value =
                    prefs.getString(
                            KEY_RECENT_PREFIX + i,
                            null);

            if (value != null
                    && !value.isEmpty()
                    && !result.contains(value)) {

                result.add(value);
            }
        }

        return result;
    }
}
