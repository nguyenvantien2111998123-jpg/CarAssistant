package com.carassistant.v10;

import com.carassistant.v10.voice.VoiceCommandRouter;

import java.lang.ref.WeakReference;

/**
 * UI-boundary adapter for voice commands.
 *
 * The voice service and command router depend on VoiceCommandRouter.Host,
 * not on V10CarActivity. This adapter is owned by the Activity and only keeps
 * a weak reference, so the Core/service layer cannot retain the projection UI.
 */
final class V10ActivityVoiceCommandHost implements VoiceCommandRouter.Host {
    private final WeakReference<V10CarActivity> activityRef;

    V10ActivityVoiceCommandHost(V10CarActivity activity) {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        activityRef = new WeakReference<>(activity);
    }

    @Override
    public String openApp(String spokenName) {
        V10CarActivity activity = activityRef.get();
        return activity == null ? null : activity.openApp(spokenName);
    }

    @Override
    public String openVoiceTask(String category, String query, String explicitProvider) {
        V10CarActivity activity = activityRef.get();
        return activity == null ? null
                : activity.openVoiceTask(category, query, explicitProvider);
    }

    @Override
    public void showAppPicker() {
        V10CarActivity activity = activityRef.get();
        if (activity != null) activity.showAppPickerFromVoice();
    }

    @Override
    public void goHome() {
        V10CarActivity activity = activityRef.get();
        if (activity != null) activity.goHomeFromVoice();
    }
}
