package com.carassistant.v10;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Phone-side entry point. Projection activities keep their existing independent lifecycle. */
public final class V10LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, V10DashboardActivity.class));
        finish();
    }
}
