package com.carassistant.v10;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * Entry point for the native Android Auto media card. It forwards the click to
 * the existing projection activity and intentionally never opens the phone UI.
 */
public final class MediaSessionEntryActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            V10CarActivity projection = V10SessionStore.getCurrentActivity();
            if (projection != null) {
                projection.handleShowCarAsistantOnProjection();
            } else {
                Log.i("CarAssistantMediaProxy",
                        "Projection is not active; leaving phone UI untouched");
            }
        } catch (RuntimeException error) {
            Log.w("CarAssistantMediaProxy", "Cannot route media card click to projection", error);
        }
        finish();
    }
}
