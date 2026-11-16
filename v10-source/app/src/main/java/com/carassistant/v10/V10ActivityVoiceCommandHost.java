package com.carassistant.v10;

import android.content.ComponentName;

import java.lang.ref.WeakReference;

/**
 * Short-lived UI adapter. It exposes projection effects only; command parsing,
 * app/provider resolution and task selection are owned by Core.
 */
final class V10ActivityVoiceCommandHost implements V10ProjectionCommandHost {
    private final WeakReference<V10CarActivity> activityRef;

    V10ActivityVoiceCommandHost(V10CarActivity activity) {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        activityRef = new WeakReference<>(activity);
    }

    @Override public void launchApp(ComponentName component) {
        V10CarActivity activity = activityRef.get();
        if (activity != null) activity.launchVoiceProjection(component);
    }

    @Override public void launchViewIntent(ComponentName component, String uri) {
        V10CarActivity activity = activityRef.get();
        if (activity != null) activity.launchVoiceViewIntent(component, uri);
    }

    @Override public void launchSearchIntent(ComponentName component, String query) {
        V10CarActivity activity = activityRef.get();
        if (activity != null) activity.launchVoiceSearchIntent(component, query);
    }

    @Override public void launchMediaSearch(ComponentName component, String uri, String query) {
        V10CarActivity activity = activityRef.get();
        if (activity != null) activity.launchVoiceMediaSearch(component, uri, query);
    }

    @Override public void showAppPicker() {
        V10CarActivity activity = activityRef.get();
        if (activity != null) activity.showAppsFromVoiceBoundary();
    }

    @Override public void goHome() {
        V10CarActivity activity = activityRef.get();
        if (activity != null) activity.goHomeFromVoiceBoundary();
    }
}
