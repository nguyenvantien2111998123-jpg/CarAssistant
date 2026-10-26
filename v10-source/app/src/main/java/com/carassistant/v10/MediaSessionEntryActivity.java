package com.carassistant.v10;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * Android Auto session click target. It delegates to the already-running
 * projection activity; it deliberately does not open the phone launcher.
 */
public final class MediaSessionEntryActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            V10CarActivity carActivity = V10SessionStore.getCurrentActivity();
            if (carActivity != null) {
                carActivity.handleOpenActiveMediaOnProjection();
            } else {
                Log.i("CarAssistantMedia", "Projection is not active; ignoring media-card open request");
            }
        } catch (RuntimeException error) {
            Log.w("CarAssistantMedia", "Could not route media-card click to projection", error);
        }
        finish();
    }
}
