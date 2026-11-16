package com.carassistant.v10;

import android.content.ComponentName;

/**
 * Narrow UI boundary for projection effects. Command interpretation, installed-app
 * resolution and task selection stay in Core; this interface exposes only operations
 * that require the live Android Auto projection surface.
 */
interface V10ProjectionCommandHost {
    void launchApp(ComponentName component);
    void launchViewIntent(ComponentName component, String uri);
    void launchSearchIntent(ComponentName component, String query);
    void launchMediaSearch(ComponentName component, String uri, String query);
    void showAppPicker();
    void goHome();
}
