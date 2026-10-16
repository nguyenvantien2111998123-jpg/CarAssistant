package com.carassistant.v10;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Phone-side entry point.
 * The phone UI is intentionally limited to SETUPS; the dashboard and app
 * picker are not shown on the phone. App selection for projection remains
 * available on the car display through its existing independent flow.
 */
public final class V10LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, V10SettingsActivity.class));
        finish();
    }
}
