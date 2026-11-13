package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import com.carassistant.v10.voice.MediaSearchUriBuilder;
import com.carassistant.v10.voice.VoiceCommandParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Resolves installed launcher applications for voice commands.
 *
 * This class has no Activity or Android Auto UI dependency, so it can be used
 * by the voice service and by the projection UI without keeping an Activity alive.
 */
public final class V10AppManager {
    public static final class App {
        public final ComponentName component;
        public final String label;

        App(ComponentName component, String label) {
            this.component = component;
            this.label = label;
        }
    }

    private final Context appContext;

    public V10AppManager(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        appContext = context.getApplicationContext();
    }

    public List<App> getLauncherApps() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = appContext.getPackageManager()
                .queryIntentActivities(intent, 0);
        ArrayList<App> apps = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (appContext.getPackageName().equals(packageName)) continue;
            ComponentName component = new ComponentName(packageName, info.activityInfo.name);
            CharSequence label = info.loadLabel(appContext.getPackageManager());
            apps.add(new App(component, label == null ? packageName : label.toString()));
        }
        Collections.sort(apps, Comparator.comparing(a -> a.label, String.CASE_INSENSITIVE_ORDER));
        return apps;
    }

    public App findByVoice(String spokenName) {
        String query = VoiceCommandParser.normalize(spokenName);
        if (query.isEmpty()) return null;

        App best = null;
        int bestScore = 0;
        for (App app : getLauncherApps()) {
            String label = VoiceCommandParser.normalize(app.label);
            String pkg = app.component.getPackageName().toLowerCase(Locale.ROOT);
            int score = 0;

            if (label.equals(query)) score = 100;
            else if (label.length() >= 4 && query.contains(label)) score = 80;
            else if (query.length() >= 4 && label.contains(query)) score = 70;

            if (query.contains("morphe") && pkg.contains("morphe")) score = Math.max(score, 120);
            if (query.contains("revanced") && pkg.contains("revanced")) score = Math.max(score, 120);
            if (query.contains("youtube music")) {
                if (MediaSearchUriBuilder.isYouTubeMusicPackage(pkg)) score = Math.max(score, 115);
                else if (pkg.contains("youtube")) score = Math.max(score, 60);
            } else if (query.equals("youtube") && pkg.contains("youtube")) {
                score = Math.max(score, 90);
            }
            if ((query.equals("maps") || query.equals("google maps"))
                    && (pkg.equals("com.google.android.apps.maps") || label.contains("maps"))) {
                score = Math.max(score, 95);
            }
            if (query.contains("spotify") && pkg.contains("spotify")) score = Math.max(score, 110);
            if (query.contains("zing mp3") && (pkg.contains("zing") || label.contains("zing mp3"))) {
                score = Math.max(score, 110);
            }
            if (query.contains("vietmap") && (pkg.contains("vietmap") || label.contains("vietmap"))) {
                score = Math.max(score, 95);
            }
            if (query.contains("navitel") && (pkg.contains("navitel") || label.contains("navitel"))) {
                score = Math.max(score, 95);
            }

            if (score > bestScore) {
                bestScore = score;
                best = app;
            }
        }
        return bestScore >= 60 ? best : null;
    }

    public String labelFor(ComponentName component) {
        if (component == null) return null;
        for (App app : getLauncherApps()) {
            if (component.getPackageName().equals(app.component.getPackageName())) return app.label;
        }
        return component.getPackageName();
    }
}
