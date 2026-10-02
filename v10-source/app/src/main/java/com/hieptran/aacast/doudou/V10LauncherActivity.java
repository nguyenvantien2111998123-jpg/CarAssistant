package com.carassistant.v10;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class V10LauncherActivity extends Activity {

    private LinearLayout list;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        buildUi();
        loadApps();
    }

    private void buildUi() {
        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL);

        root.setPadding(
                32,
                32,
                32,
                32);

        root.setBackgroundColor(
                Color.BLACK);

        TextView title =
                new TextView(this);

        title.setText(
                "Car Assistant V10");

        title.setTextColor(
                Color.WHITE);

        title.setTextSize(26);

        title.setGravity(
                Gravity.CENTER);

        title.setPadding(
                0,
                0,
                0,
                24);

        root.addView(
                title,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        status =
                new TextView(this);

        status.setText(
                "Choose an app for Android Auto");

        status.setTextColor(
                Color.LTGRAY);

        status.setTextSize(16);

        status.setPadding(
                0,
                0,
                0,
                20);

        root.addView(
                status,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        Button refresh =
                new Button(this);

        refresh.setText("Refresh");

        refresh.setOnClickListener(
                v -> loadApps());

        root.addView(
                refresh,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll =
                new ScrollView(this);

        list =
                new LinearLayout(this);

        list.setOrientation(
                LinearLayout.VERTICAL);

        list.setPadding(
                0,
                24,
                0,
                0);

        scroll.addView(list);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));

        setContentView(root);
    }

    private void loadApps() {
        if (list == null) {
            return;
        }

        list.removeAllViews();

        Intent intent =
                new Intent(
                        Intent.ACTION_MAIN);

        intent.addCategory(
                Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> apps =
                getPackageManager()
                        .queryIntentActivities(
                                intent,
                                0);

        List<ResolveInfo> filtered =
                new ArrayList<>();

        for (ResolveInfo info : apps) {
            if (info.activityInfo == null) {
                continue;
            }

            if (getPackageName().equals(
                    info.activityInfo.packageName)) {
                continue;
            }

            filtered.add(info);
        }

        Collections.sort(
                filtered,
                new Comparator<ResolveInfo>() {
                    @Override
                    public int compare(
                            ResolveInfo a,
                            ResolveInfo b) {

                        String la =
                                String.valueOf(
                                        a.loadLabel(
                                                getPackageManager()));

                        String lb =
                                String.valueOf(
                                        b.loadLabel(
                                                getPackageManager()));

                        return la.compareToIgnoreCase(
                                lb);
                    }
                });

        for (ResolveInfo info : filtered) {

            ComponentName component =
                    new ComponentName(
                            info.activityInfo.packageName,
                            info.activityInfo.name);

            Button button =
                    new Button(this);

            button.setText(
                    String.valueOf(
                            info.loadLabel(
                                    getPackageManager())));

            button.setOnClickListener(v -> {

                V10SessionStore.setTarget(
                        V10LauncherActivity.this,
                        component);

                status.setText(
                        "Selected: "
                                + component
                                .flattenToShortString());
            });

            list.addView(
                    button,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }
}
