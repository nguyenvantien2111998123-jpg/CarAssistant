package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

public final class V10SessionStore {

    private static final String PREFS =
            "v10_session";

    private static final String TARGET =
            "target_component";

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
