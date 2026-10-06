
package com.carassistant.v10;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class V10LauncherActivity
        extends Activity {

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final ArrayList<AppInfo> apps =
            new ArrayList<>();

    private LinearLayout root;
    private LinearLayout dashboard;
    private LinearLayout appPanel;
    private LinearLayout dock;

    private TextView clock;
    private TextView date;
    private TextView status;

    private final Runnable clockTicker =
            new Runnable() {

                @Override
                public void run() {

                    updateClock();

                    if (!isFinishing()) {

                        handler.postDelayed(
                                this,
                                1000);
                    }
                }
            };

    @Override
    protected void onCreate(
            Bundle state) {

        super.onCreate(state);

        buildUi();

        loadApps();

        updateClock();

        handler.post(
                clockTicker);
    }

    @Override
    protected void onDestroy() {

        handler.removeCallbacks(
                clockTicker);

        super.onDestroy();
    }

    // ====================================================
    // ROOT UI
    // ====================================================

    private void buildUi() {

        root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL);

        root.setPadding(
                dp(18),
                dp(12),
                dp(18),
                dp(8));

        root.setBackgroundColor(
                Color.rgb(
                        7,
                        9,
                        12));

        dashboard =
                new LinearLayout(this);

        dashboard.setOrientation(
                LinearLayout.VERTICAL);

        root.addView(
                dashboard,
                new LinearLayout.LayoutParams(
                        -1,
                        -1));

        buildDashboard();

        buildAppPanel();

        setContentView(root);
    }

    // ====================================================
    // DASHBOARD
    // ====================================================

    private void buildDashboard() {

        LinearLayout header =
                new LinearLayout(this);

        header.setGravity(
                Gravity.CENTER_VERTICAL);

        LinearLayout titleBox =
                new LinearLayout(this);

        titleBox.setOrientation(
                LinearLayout.VERTICAL);

        TextView title =
                text(
                        "CAR ASSISTANT",
                        21,
                        Color.WHITE);

        title.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD);

        date =
                text(
                        "",
                        12,
                        Color.LTGRAY);

        titleBox.addView(title);

        titleBox.addView(date);

        header.addView(
                titleBox,
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1));

        clock =
                text(
                        "",
                        27,
                        Color.WHITE);

        clock.setGravity(
                Gravity.CENTER);

        header.addView(
                clock,
                new LinearLayout.LayoutParams(
                        dp(130),
                        dp(58)));

        dashboard.addView(
                header,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(65)));

        // ----------------------------------------------
        // CONTENT
        // ----------------------------------------------

        LinearLayout content =
                new LinearLayout(this);

        content.setOrientation(
                LinearLayout.VERTICAL);

        // MAP

        LinearLayout map =
                card();

        map.addView(
                text(
                        "MAP",
                        12,
                        Color.LTGRAY));

        TextView mapText =
                text(
                        "Navigation",
                        28,
                        Color.WHITE);

        mapText.setGravity(
                Gravity.CENTER);

        map.addView(
                mapText,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1));

        content.addView(
                map,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        2));

        // ----------------------------------------------
        // INFORMATION CARDS
        // ----------------------------------------------

        LinearLayout info =
                new LinearLayout(this);

        info.setOrientation(
                LinearLayout.HORIZONTAL);

        info.addView(
                infoCard(
                        "SPEED",
                        "0 km/h"),
                weightParams());

        info.addView(
                infoCard(
                        "WEATHER",
                        "-- °C"),
                weightParams());

        info.addView(
                infoCard(
                        "MEDIA",
                        "No media"),
                weightParams());

        content.addView(
                info,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(120)));

        dashboard.addView(
                content,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1));

        // ----------------------------------------------
        // DOCK
        // ----------------------------------------------

        LinearLayout dockRow =
                new LinearLayout(this);

        dockRow.setGravity(
                Gravity.CENTER_VERTICAL);

        dock =
                new LinearLayout(this);

        dock.setGravity(
                Gravity.CENTER_VERTICAL);

        dockRow.addView(
                dock,
                new LinearLayout.LayoutParams(
                        0,
                        -1,
                        1));

        TextView appsButton =
                buttonText(
                        "APPS");

        appsButton.setOnClickListener(
                v -> showApps());

        dockRow.addView(
                appsButton,
                new LinearLayout.LayoutParams(
                        dp(90),
                        dp(58)));

        dashboard.addView(
                dockRow,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(70)));
    }

    // ====================================================
    // APPLICATIONS PANEL
    // ====================================================

    private void buildAppPanel() {

        appPanel =
                new LinearLayout(this);

        appPanel.setOrientation(
                LinearLayout.VERTICAL);

        appPanel.setBackgroundColor(
                Color.rgb(
                        7,
                        9,
                        12));

        appPanel.setVisibility(
                View.GONE);

        root.addView(
                appPanel,
                new LinearLayout.LayoutParams(
                        -1,
                        -1));

        // HEADER

        LinearLayout header =
                new LinearLayout(this);

        header.setGravity(
                Gravity.CENTER_VERTICAL);

        TextView back =
                buttonText(
                        "BACK");

        back.setOnClickListener(
                v -> hideApps());

        header.addView(
                back,
                new LinearLayout.LayoutParams(
                        dp(90),
                        dp(58)));

        TextView title =
                text(
                        "APPLICATIONS",
                        21,
                        Color.WHITE);

        title.setGravity(
                Gravity.CENTER);

        header.addView(
                title,
                new LinearLayout.LayoutParams(
                        0,
                        dp(58),
                        1));

        appPanel.addView(header);

        // STATUS

        status =
                text(
                        "Select an application",
                        12,
                        Color.LTGRAY);

        status.setGravity(
                Gravity.CENTER_VERTICAL);

        appPanel.addView(
                status,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(38)));

        // GRID

        ScrollView scroll =
                new ScrollView(this);

        GridLayout grid =
                new GridLayout(this);

        grid.setColumnCount(4);

        grid.setUseDefaultMargins(
                false);

        scroll.addView(grid);

        appPanel.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1));

        rebuildAppGrid(grid);
    }

    // ====================================================
    // APP DISCOVERY
    // ====================================================

    private void loadApps() {

        apps.clear();

        Intent intent =
                new Intent(
                        Intent.ACTION_MAIN);

        intent.addCategory(
                Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> result =
                getPackageManager()
                        .queryIntentActivities(
                                intent,
                                0);

        for (ResolveInfo info :
                result) {

            if (info.activityInfo == null) {
                continue;
            }

            String packageName =
                    info.activityInfo
                            .packageName;

            if (getPackageName()
                    .equals(packageName)) {
                continue;
            }

            ComponentName component =
                    new ComponentName(
                            packageName,
                            info.activityInfo.name);

            CharSequence label =
                    info.loadLabel(
                            getPackageManager());

            String name =
                    label == null
                            ? packageName
                            : label.toString();

            apps.add(
                    new AppInfo(
                            component,
                            name));
        }

        Collections.sort(
                apps,
                new Comparator<AppInfo>() {

                    @Override
                    public int compare(
                            AppInfo a,
                            AppInfo b) {

                        return a.name
                                .compareToIgnoreCase(
                                        b.name);
                    }
                });

        rebuildDock();

        rebuildGridFromPanel();
    }

    // ====================================================
    // DOCK
    // ====================================================

    private void rebuildDock() {

        if (dock == null) {
            return;
        }

        dock.removeAllViews();

        int count =
                Math.min(
                        5,
                        apps.size());

        for (int i = 0;
             i < count;
             i++) {

            final AppInfo app =
                    apps.get(i);

            TextView tile =
                    buttonText(
                            app.name);

            tile.setTextSize(
                    11);

            tile.setMaxLines(2);

            tile.setOnClickListener(
                    v -> selectApp(app));

            LinearLayout.LayoutParams
                    params =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(58),
                            1f);

            params.setMargins(
                    dp(3),
                    0,
                    dp(3),
                    0);

            dock.addView(
                    tile,
                    params);
        }
    }

    // ====================================================
    // APP GRID
    // ====================================================

    private void rebuildGridFromPanel() {

        if (appPanel == null) {
            return;
        }

        if (appPanel.getChildCount()
                < 3) {
            return;
        }

        View child =
                appPanel.getChildAt(2);

        if (!(child instanceof ScrollView)) {
            return;
        }

        View inside =
                ((ScrollView) child)
                        .getChildAt(0);

        if (!(inside instanceof GridLayout)) {
            return;
        }

        rebuildAppGrid(
                (GridLayout) inside);
    }

    private void rebuildAppGrid(
            GridLayout grid) {

        grid.removeAllViews();

        for (AppInfo app :
                apps) {

            LinearLayout tile =
                    appTile(app);

            GridLayout.LayoutParams
                    params =
                    new GridLayout.LayoutParams();

            params.width = 0;

            params.height =
                    dp(105);

            params.columnSpec =
                    GridLayout.spec(
                            GridLayout.UNDEFINED,
                            1f);

            params.setMargins(
                    dp(5),
                    dp(5),
                    dp(5),
                    dp(5));

            grid.addView(
                    tile,
                    params);
        }
    }

    private LinearLayout appTile(
            AppInfo app) {

        LinearLayout tile =
                new LinearLayout(this);

        tile.setOrientation(
                LinearLayout.VERTICAL);

        tile.setGravity(
                Gravity.CENTER);

        GradientDrawable bg =
                new GradientDrawable();

        bg.setColor(
                Color.rgb(
                        22,
                        27,
                        33));

        bg.setCornerRadius(
                dp(16));

        tile.setBackground(bg);

        TextView icon =
                text(
                        initial(app.name),
                        27,
                        Color.WHITE);

        icon.setGravity(
                Gravity.CENTER);

        tile.addView(
                icon,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(52)));

        TextView name =
                text(
                        app.name,
                        12,
                        Color.WHITE);

        name.setGravity(
                Gravity.CENTER);

        name.setMaxLines(2);

        tile.addView(
                name,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(42)));

        tile.setOnClickListener(
                v -> selectApp(app));

        return tile;
    }

    // ====================================================
    // SELECT APP
    // ====================================================

    private void selectApp(
            AppInfo app) {

        V10SessionStore.setTarget(
                this,
                app.component);

        if (status != null) {

            status.setText(
                    "Selected: "
                            + app.name);
        }
    }

    // ====================================================
    // SHOW / HIDE APPLICATIONS
    // ====================================================

    private void showApps() {

        dashboard.setVisibility(
                View.GONE);

        appPanel.setVisibility(
                View.VISIBLE);

        loadApps();
    }

    private void hideApps() {

        appPanel.setVisibility(
                View.GONE);

        dashboard.setVisibility(
                View.VISIBLE);
    }

    // ====================================================
    // UI HELPERS
    // ====================================================

    private LinearLayout card() {

        LinearLayout box =
                new LinearLayout(this);

        box.setOrientation(
                LinearLayout.VERTICAL);

        box.setPadding(
                dp(14),
                dp(10),
                dp(14),
                dp(10));

        GradientDrawable bg =
                new GradientDrawable();

        bg.setColor(
                Color.rgb(
                        22,
                        27,
                        33));

        bg.setCornerRadius(
                dp(18));

        box.setBackground(bg);

        LinearLayout.LayoutParams
                params =
                new LinearLayout.LayoutParams(
                        -1,
                        -1);

        params.setMargins(
                dp(4),
                dp(4),
                dp(4),
                dp(4));

        box.setLayoutParams(
                params);

        return box;
    }

    private LinearLayout infoCard(
            String title,
            String value) {

        LinearLayout box =
                card();

        box.addView(
                text(
                        title,
                        11,
                        Color.LTGRAY));

        TextView valueView =
                text(
                        value,
                        21,
                        Color.WHITE);

        valueView.setGravity(
                Gravity.CENTER);

        box.addView(
                valueView,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1));

        return box;
    }

    private TextView buttonText(
            String value) {

        TextView view =
                text(
                        value,
                        13,
                        Color.WHITE);

        view.setGravity(
                Gravity.CENTER);

        GradientDrawable bg =
                new GradientDrawable();

        bg.setColor(
                Color.rgb(
                        29,
                        35,
                        42));

        bg.setCornerRadius(
                dp(16));

        view.setBackground(bg);

        return view;
    }

    private TextView text(
            String value,
            float size,
            int color) {

        TextView view =
                new TextView(this);

        view.setText(value);

        view.setTextSize(size);

        view.setTextColor(color);

        return view;
    }

    private LinearLayout.LayoutParams
            weightParams() {

        return new LinearLayout.LayoutParams(
                0,
                -1,
                1f);
    }

    private String initial(
            String value) {

        if (value == null
                || value.isEmpty()) {

            return "?";
        }

        return value
                .substring(0, 1)
                .toUpperCase(
                        Locale.getDefault());
    }

    private int dp(
            float value) {

        return Math.round(
                value
                        * getResources()
                                .getDisplayMetrics()
                                .density);
    }

    private void updateClock() {

        if (clock == null
                || date == null) {
            return;
        }

        Date now =
                new Date();

        clock.setText(
                new SimpleDateFormat(
                        "HH:mm",
                        Locale.getDefault())
                        .format(now));

        date.setText(
                new SimpleDateFormat(
                        "EEEE, dd MMMM",
                        Locale.getDefault())
                        .format(now));
    }

    // ====================================================
    // APP MODEL
    // ====================================================

    private static final class AppInfo {

        final ComponentName component;

        final String name;

        AppInfo(
                ComponentName component,
                String name) {

            this.component =
                    component;

            this.name =
                    name;
        }
    }
}

