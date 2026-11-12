package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.carassistant.v10.voice.MediaSearchUriBuilder;
import com.carassistant.v10.voice.VoiceCommandParser;
import com.carassistant.v10.voice.VoiceCommandRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Executes voice commands without requiring V10CarActivity to exist.
 *
 * State A keeps using the already-running V10 UI/session adapter. In State B,
 * commands are resolved from PackageManager and launched on the pre-warmed
 * V10 Display 2 owned by HeadlessProjectionHost.
 */
public final class VoiceCommandExecutionGateway implements VoiceCommandRouter.Host {
    private static final String TAG = "CarAssistantVoiceGateway";
    private static final long SEARCH_HANDOFF_DELAY_MS = 650L;

    private final Context appContext;
    private final PackageManager packageManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public VoiceCommandExecutionGateway(Context context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        appContext = context.getApplicationContext();
        packageManager = appContext.getPackageManager();
    }

    @Override
    public String openApp(String spokenName) {
        V10CarActivity activity = V10SessionStore.getCurrentActivity();
        if (activity != null) return activity.executeVoiceOpenApp(spokenName);

        AppCandidate candidate = findAppByVoice(spokenName);
        if (candidate == null) return null;
        V10DisplayController display = prepareHeadlessProjection(candidate.component);
        if (display == null) return null;
        display.launchNewTask(candidate.component);
        return candidate.label;
    }

    @Override
    public String openVoiceTask(String category, String query, String explicitProvider) {
        V10CarActivity activity = V10SessionStore.getCurrentActivity();
        if (activity != null) return activity.executeVoiceTask(category, query, explicitProvider);

        String preferenceCategory = "nearby".equals(category) ? "navigation" : category;
        AppCandidate selected = selectApp(preferenceCategory, category, explicitProvider);
        if (selected == null) {
            Log.w(TAG, "No installed provider for voice category=" + category
                    + ", provider=" + explicitProvider);
            return null;
        }

        String cleanQuery = query == null ? "" : query.trim();
        String uri = null;
        if (!cleanQuery.isEmpty()) {
            if ("navigation".equals(category) || "nearby".equals(category)) {
                uri = buildMapUri(selected.component, cleanQuery, "nearby".equals(category));
            } else if ("music".equals(category) || "video".equals(category)) {
                uri = MediaSearchUriBuilder.build(
                        selected.component.getPackageName(), cleanQuery, category);
            }
            if (uri == null && ("music".equals(category) || "video".equals(category)
                    || "navigation".equals(category) || "nearby".equals(category))) {
                Log.w(TAG, "No supported search URI for " + selected.component + " category=" + category);
                return null;
            }
        }

        V10DisplayController display = prepareHeadlessProjection(selected.component);
        if (display == null) return null;

        if (!cleanQuery.isEmpty() && "tv".equals(category)) {
            final String search = cleanQuery;
            queueAfterLaunch(display, selected.component, () ->
                    display.launchSearchIntentOnDisplay(selected.component.getPackageName(), search));
        } else if (!cleanQuery.isEmpty() && ("music".equals(category) || "video".equals(category))) {
            final String searchUri = uri;
            queueAfterLaunch(display, selected.component, () -> {
                prepareAccessibilitySearch(selected.component.getPackageName(),
                        cleanQuery, display.getDisplayId());
                display.launchMediaViewIntentOnDisplay(
                        selected.component.getPackageName(), searchUri);
            });
        } else if (!cleanQuery.isEmpty()
                && ("navigation".equals(category) || "nearby".equals(category))) {
            final String mapUri = uri;
            queueAfterLaunch(display, selected.component, () ->
                    display.launchViewIntentOnDisplay(selected.component.getPackageName(), mapUri));
        }

        return selected.label;
    }

    @Override
    public void showAppPicker() {
        V10CarActivity activity = V10SessionStore.getCurrentActivity();
        if (activity != null) activity.executeVoiceShowApps();
    }

    @Override
    public void goHome() {
        V10CarActivity activity = V10SessionStore.getCurrentActivity();
        if (activity != null) activity.executeVoiceHome();
    }

    private V10DisplayController prepareHeadlessProjection(ComponentName component) {
        if (component == null) return null;
        // The AA connection service normally pre-warms this display. start() is
        // idempotent and also recovers the headless sink if the UI Activity is absent.
        if (!HeadlessProjectionHost.start(appContext)) {
            Log.w(TAG, "Headless Display 2 is not ready; refusing to launch on the phone display");
            return null;
        }
        V10DisplayController display = V10DisplayController.get(
                appContext, HeadlessProjectionHost.DISPLAY_INDEX);
        if (!display.isReady() || display.getDisplayId() <= 0) {
            Log.w(TAG, "Projection display has no valid Android display ID");
            return null;
        }
        V10SessionStore.setTarget(appContext, component);
        display.launchNewTask(component);
        return display;
    }

    private void queueAfterLaunch(V10DisplayController display,
                                  ComponentName target, Runnable action) {
        mainHandler.postDelayed(() -> {
            if (display == null || !display.isReady()
                    || !target.equals(display.getTarget())) {
                Log.w(TAG, "Discarding stale voice search handoff for " + target);
                return;
            }
            action.run();
        }, SEARCH_HANDOFF_DELAY_MS);
    }

    private void prepareAccessibilitySearch(String packageName, String query, int displayId) {
        SharedPreferences prefs = appContext.getSharedPreferences("media_autoplay", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("target_package", packageName)
                .putString("query", query)
                .putString("phase", "search")
                .putInt("target_display_id", displayId)
                .putLong("started_at", System.currentTimeMillis())
                .putLong("search_submitted_at", 0L)
                .putInt("youtube_history_stage", 2)
                .putBoolean("mini_engine_active", false)
                .putBoolean("accessibility_selection_done", false)
                .apply();
    }

    private AppCandidate selectApp(String preferenceCategory, String category,
                                   String explicitProvider) {
        String provider = explicitProvider == null ? "" : explicitProvider.trim();
        if (!provider.isEmpty()) {
            String normalized = VoiceCommandParser.normalize(provider);
            // "YouTube" after a music request means the video app unless the user
            // explicitly names YouTube Music, ReVanced or Morphe.
            if ("youtube".equals(normalized)
                    && ("music".equals(category) || "video".equals(category))) {
                ComponentName configuredVideo = V10SessionStore.getDefaultVoiceApp(
                        appContext, "video");
                AppCandidate configured = candidateFor(configuredVideo);
                if (configured != null) return configured;
                AppCandidate video = findAppByVoice("youtube");
                if (video != null && MediaSearchUriBuilder.isYouTubeVideoPackage(
                        video.component.getPackageName())) return video;
            }
            return findAppByVoice(provider);
        }

        ComponentName configured = V10SessionStore.getDefaultVoiceApp(
                appContext, preferenceCategory);
        AppCandidate configuredCandidate = candidateFor(configured);
        if (configuredCandidate != null) return configuredCandidate;

        String[] candidates;
        if ("navigation".equals(preferenceCategory)) {
            candidates = new String[]{"Google Maps", "Vietmap", "Navitel", "Maps"};
        } else if ("music".equals(preferenceCategory)) {
            candidates = new String[]{"Zing MP3", "Spotify", "YouTube Music", "NhacCuaTui"};
        } else if ("video".equals(preferenceCategory)) {
            candidates = new String[]{"YouTube"};
        } else if ("tv".equals(preferenceCategory)) {
            candidates = new String[]{"VTV Go", "FPT Play", "TV"};
        } else {
            candidates = new String[0];
        }
        for (String name : candidates) {
            AppCandidate candidate = findAppByVoice(name);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private AppCandidate candidateFor(ComponentName stored) {
        if (stored == null) return null;
        Intent launch = packageManager.getLaunchIntentForPackage(stored.getPackageName());
        if (launch == null) return null;
        ComponentName component = launch.getComponent() == null ? stored : launch.getComponent();
        try {
            CharSequence label = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(component.getPackageName(), 0));
            return new AppCandidate(component, label == null
                    ? component.getPackageName() : label.toString());
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    private AppCandidate findAppByVoice(String spokenName) {
        String query = VoiceCommandParser.normalize(spokenName);
        if (query.isEmpty()) return null;

        AppCandidate best = null;
        int bestScore = 0;
        for (AppCandidate app : getLaunchableApps()) {
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
                else if (MediaSearchUriBuilder.isYouTubeVideoPackage(pkg)) score = Math.max(score, 60);
            } else if (query.equals("youtube")) {
                if (MediaSearchUriBuilder.isYouTubeVideoPackage(pkg)) score = Math.max(score, 110);
                else if (MediaSearchUriBuilder.isYouTubeMusicPackage(pkg)) score = Math.max(score, 45);
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
        return bestScore < 60 ? null : best;
    }

    private List<AppCandidate> getLaunchableApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(launcher, 0);
        List<AppCandidate> result = new ArrayList<>();
        List<String> seenPackages = new ArrayList<>();
        if (resolved == null) return result;

        for (ResolveInfo info : resolved) {
            if (info == null || info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (packageName == null || seenPackages.contains(packageName)) continue;
            seenPackages.add(packageName);
            ComponentName component = new ComponentName(packageName, info.activityInfo.name);
            CharSequence label = info.loadLabel(packageManager);
            result.add(new AppCandidate(component,
                    label == null ? packageName : label.toString()));
        }
        return result;
    }

    private String buildMapUri(ComponentName component, String query, boolean nearby) {
        if (component == null || query == null || query.trim().isEmpty()) return null;
        String encoded = Uri.encode(query.trim());
        if ("com.google.android.apps.maps".equals(component.getPackageName())) {
            return nearby ? "geo:0,0?q=" + encoded : "google.navigation:q=" + encoded;
        }
        return "geo:0,0?q=" + encoded;
    }

    private static final class AppCandidate {
        final ComponentName component;
        final String label;

        AppCandidate(ComponentName component, String label) {
            this.component = component;
            this.label = label;
        }
    }
}
