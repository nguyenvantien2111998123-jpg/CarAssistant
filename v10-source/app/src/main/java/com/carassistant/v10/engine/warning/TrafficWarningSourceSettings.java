package com.carassistant.v10.engine.warning;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.EnumSet;
import java.util.Set;

/** Persists independent source toggles; unlike CarStream's single-source selector, CarAsistant allows multiple sources. */
public final class TrafficWarningSourceSettings {
    private static final String PREFS = "traffic_warning_sources";
    private static final String PREFIX = "enabled_";

    private TrafficWarningSourceSettings() { }

    public static boolean isEnabled(Context context, TrafficWarningSource source) {
        if (context == null || source == null) return false;
        return preferences(context).getBoolean(key(source), source.isEnabledByDefault());
    }

    public static boolean setEnabled(Context context, TrafficWarningSource source, boolean enabled) {
        if (context == null || source == null) return false;
        return preferences(context).edit().putBoolean(key(source), enabled).commit();
    }

    public static Set<TrafficWarningSource> getEnabledSources(Context context) {
        EnumSet<TrafficWarningSource> enabled = EnumSet.noneOf(TrafficWarningSource.class);
        if (context == null) return enabled;
        SharedPreferences prefs = preferences(context);
        for (TrafficWarningSource source : TrafficWarningSource.values()) {
            if (prefs.getBoolean(key(source), source.isEnabledByDefault())) {
                enabled.add(source);
            }
        }
        return enabled;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(TrafficWarningSource source) {
        return PREFIX + source.getCarStreamId();
    }
}
