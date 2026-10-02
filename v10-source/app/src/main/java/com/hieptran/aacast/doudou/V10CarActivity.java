
package com.carassistant.v10;

import android.content.ComponentName;
import android.os.Bundle;
import android.view.TextureView;
import android.widget.FrameLayout;

import com.google.android.apps.auto.sdk.CarActivity;

public final class V10CarActivity
        extends CarActivity {

    private V10ProjectionSession session;

    @Override
    public void onCreate(Bundle bundle) {

        setTheme(
                R.style.Theme_AACast);

        super.onCreate(bundle);

        FrameLayout root =
                new FrameLayout(this);

        TextureView texture =
                new TextureView(this);

        root.addView(
                texture,
                new FrameLayout.LayoutParams(
                        -1,
                        -1));

        session =
                new V10ProjectionSession(
                        this,
                        texture,
                        0);

        String flat =
                getIntent()
                        .getStringExtra(
                                "target");

        if (flat != null) {

            ComponentName target =
                    ComponentName
                            .unflattenFromString(
                                    flat);

            session.setTarget(target);
        }

        setContentView(root);
    }

    @Override
    public void onDestroy() {

        if (session != null) {
            session.destroy();
        }

        super.onDestroy();
    }
}

