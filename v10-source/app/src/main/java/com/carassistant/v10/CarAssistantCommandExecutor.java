package com.carassistant.v10;

import android.content.Context;

import com.carassistant.v10.voice.VoiceCommandRouter;

/**
 * Core-owned command execution boundary.
 *
 * This object is created and owned by CarAssistantVoiceService, never by an
 * Android Auto Activity. It parses and routes recognized commands and delegates
 * projection-specific effects to V10ProjectionCoordinator. The coordinator
 * may use a short-lived UI bridge while the car UI is attached, or its
 * headless projection path when no Activity is available.
 */
public final class CarAssistantCommandExecutor {
    private final Context appContext;
    private final VoiceCommandRouter router;

    public CarAssistantCommandExecutor(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        appContext = context.getApplicationContext();
        router = new VoiceCommandRouter(new VoiceCommandRouter.Host() {
            @Override
            public String openApp(String spokenName) {
                return V10ProjectionCoordinator.openApp(appContext, spokenName);
            }

            @Override
            public String openVoiceTask(String category, String query, String explicitProvider) {
                return V10ProjectionCoordinator.openVoiceTask(
                        appContext, category, query, explicitProvider);
            }

            @Override
            public void showAppPicker() {
                V10ProjectionCoordinator.showAppPicker(appContext);
            }

            @Override
            public void goHome() {
                V10ProjectionCoordinator.goHome(appContext);
            }
        });
    }

    public VoiceCommandRouter.Result executeVoiceCommand(String transcript) {
        return router.route(transcript);
    }
}
