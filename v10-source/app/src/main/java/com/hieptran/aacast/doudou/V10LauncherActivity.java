
package com.carassistant.v10;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public final class V10LauncherActivity
        extends Activity {

    @Override
    protected void onCreate(Bundle bundle) {

        super.onCreate(bundle);

        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL);

        root.setPadding(
                32,
                32,
                32,
                32);

        TextView title =
                new TextView(this);

        title.setText(
                "CAR ASSISTANT V10");

        title.setTextSize(24);

        title.setGravity(
                Gravity.CENTER);

        root.addView(
                title,
                new LinearLayout.LayoutParams(
                        -1,
                        80));

        TextView status =
                new TextView(this);

        status.setText(
                "V10 Projection Engine\n" +
                "Android 12 compatible");

        root.addView(
                status,
                new LinearLayout.LayoutParams(
                        -1,
                        100));

        Intent launcherIntent =
                new Intent(
                        Intent.ACTION_MAIN);

        launcherIntent.addCategory(
                Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> apps =
                getPackageManager()
                        .queryIntentActivities(
                                launcherIntent,
                                0);

        int shown = 0;

        for (ResolveInfo app : apps) {

            String packageName =
                    app.activityInfo.packageName;

            if (packageName.equals(
                    getPackageName())) {
                continue;
            }

            Button button =
                    new Button(this);

            button.setText(
                    app.loadLabel(
                            getPackageManager()));

            ComponentName component =
                    new ComponentName(
                            app.activityInfo.packageName,
                            app.activityInfo.name);

            button.setOnClickListener(
                    view -> {

                        Intent intent =
                                new Intent(
                                        this,
                                        V10CarActivity.class);

                        intent.putExtra(
                                "target",
                                component
                                        .flattenToString());

                        startActivity(intent);
                    });

            root.addView(
                    button,
                    new LinearLayout.LayoutParams(
                            -1,
                            60));

            shown++;

            if (shown >= 12) {
                break;
            }
        }

        setContentView(root);
    }
}

