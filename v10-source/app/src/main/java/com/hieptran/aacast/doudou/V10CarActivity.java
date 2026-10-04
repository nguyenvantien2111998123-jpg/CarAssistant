
package com.carassistant.v10;

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
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.apps.auto.sdk.CarActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class V10CarActivity
        extends CarActivity {

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private FrameLayout projectionLayer;
    private LinearLayout launcherLayer;
    private LinearLayout dashboard;
    private LinearLayout appPanel;
    private LinearLayout dock;
    private TextureView textureView;
    private V10ProjectionSession session;
    private TextView clock;
    private TextView date;

    private boolean resumed;

    private ComponentName activeTarget;

    private final ArrayList<AppInfo> apps =
            new ArrayList<>();

    private final Runnable clockTicker =
            new Runnable() {
                @Override
                public void run() {
                    updateClock();

                    if (resumed) {
                        handler.postDelayed(
                                this,
                                1000);
                    }
                }
            };

    private final Runnable targetPoller =
            new Runnable() {
                @Override
                public void run() {
                    if (!resumed) {
                        return;
                    }

                    ComponentName target =
                            V10SessionStore
                                    .getTarget(
                                            V10CarActivity.this);

                    if (target != null
                            && !target.equals(
                                    activeTarget)) {

                        launchProjection(
                                target);
                    }

                    handler.postDelayed(
                            this,
                            500);
                }
            };

    private void applyChrome() {
        try {
            getCarUiController()
                    .getStatusBarController()
                    .hideAppHeader();

            getCarUiController()
                    .getMenuController()
                    .hideMenuButton();

        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onCreate(
            Bundle state) {

        setTheme(
                R.style.Theme_AACast);

        super.onCreate(state);

        setIgnoreConfigChanges(-1);

        V10SessionStore
                .setCurrentActivity(this);

        applyChrome();
    }

    @Override
    public void onResume() {

        super.onResume();

        resumed = true;

        applyChrome();

        if (root == null) {
            buildUi();
            attachProjection();
        }

        loadApps();
        rebuildDock();

        handler.removeCallbacks(
                clockTicker);

        handler.removeCallbacks(
                targetPoller);

        handler.post(clockTicker);
        handler.post(targetPoller);

        ComponentName target =
                V10SessionStore
                        .getTarget(this);

        if (target != null) {
            launchProjection(target);
        }
    }

    private void buildUi() {

        root =
                new FrameLayout(this);

        root.setBackgroundColor(
                Color.rgb(
                        7,
                        9,
                        12));

        projectionLayer =
                new FrameLayout(this);

        projectionLayer.setVisibility(
                View.GONE);

        root.addView(
                projectionLayer,
                new FrameLayout.LayoutParams(
                        -1,
                        -1));

        launcherLayer =
                new LinearLayout(this);

        launcherLayer.setOrientation(
                LinearLayout.VERTICAL);

        launcherLayer.setPadding(
                dp(18),
                dp(12),
                dp(18),
                dp(8));

        root.addView(
                launcherLayer,
                new FrameLayout.LayoutParams(
                        -1,
                        -1));

        buildDashboard();
        buildAppPanel();

        setContentView(root);
    }

    private void buildDashboard() {

        dashboard = new LinearLayout(this);
        dashboard.setOrientation(LinearLayout.VERTICAL);
        dashboard.setBackgroundColor(Color.rgb(7, 9, 12));
        dashboard.setPadding(dp(14), dp(14), dp(14), dp(14));

        launcherLayer.addView(
                dashboard,
                new LinearLayout.LayoutParams(
                        -1,
                        -1));

        LinearLayout topBar =
                new LinearLayout(this);

        topBar.setOrientation(
                LinearLayout.HORIZONTAL);
        topBar.setGravity(
                Gravity.CENTER_VERTICAL);
        topBar.setPadding(
                dp(8),
                dp(8),
                dp(8),
                dp(8));

        LinearLayout logoWrap =
                new LinearLayout(this);

        logoWrap.setOrientation(
                LinearLayout.HORIZONTAL);
        logoWrap.setGravity(
                Gravity.CENTER_VERTICAL);

        TextView logoBadge =
                text(
                        "CA",
                        20,
                        Color.WHITE);

        logoBadge.setGravity(
                Gravity.CENTER);
        logoBadge.setPadding(
                dp(10),
                dp(8),
                dp(10),
                dp(8));

        GradientDrawable badgeBg =
                new GradientDrawable();

        badgeBg.setColor(
                Color.rgb(
                        24,
                        117,
                        196));
        badgeBg.setCornerRadius(
                dp(12));
        badgeBg.setStroke(
                dp(1),
                Color.argb(
                        180,
                        255,
                        255,
                        255));

        logoBadge.setBackground(
                badgeBg);

        TextView logoText =
                text(
                        "CarAssistant",
                        15,
                        Color.WHITE);

        logoText.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD);

        logoWrap.addView(
                logoBadge,
                new LinearLayout.LayoutParams(
                        dp(52),
                        dp(42)));

        logoWrap.addView(
                logoText,
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1));

        topBar.addView(
                logoWrap,
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1));

        LinearLayout infoColumn =
                new LinearLayout(this);

        infoColumn.setOrientation(
                LinearLayout.VERTICAL);
        infoColumn.setGravity(
                Gravity.CENTER);

        date = text(
                "",
                10,
                Color.LTGRAY);

        clock = text(
                "",
                18,
                Color.WHITE);

        clock.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD);
        clock.setGravity(
                Gravity.CENTER);

        infoColumn.addView(
                date,
                new LinearLayout.LayoutParams(
                        -2,
                        -2));

        infoColumn.addView(
                clock,
                new LinearLayout.LayoutParams(
                        -2,
                        -2));

        topBar.addView(
                infoColumn,
                new LinearLayout.LayoutParams(
                        dp(110),
                        -2));

        dashboard.addView(
                topBar,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(64)));

        LinearLayout contentRow =
                new LinearLayout(this);

        contentRow.setOrientation(
                LinearLayout.HORIZONTAL);

        LinearLayout sidebar =
                new LinearLayout(this);

        sidebar.setOrientation(
                LinearLayout.VERTICAL);
        sidebar.setPadding(
                dp(14),
                dp(14),
                dp(14),
                dp(14));

        GradientDrawable sideBg =
                new GradientDrawable();

        sideBg.setColor(
                Color.argb(
                        200,
                        18,
                        24,
                        31));
        sideBg.setCornerRadius(
                dp(22));

        sidebar.setBackground(
                sideBg);

        TextView sideTitle =
                text(
                        "HOME",
                        11,
                        Color.LTGRAY);
        sideTitle.setGravity(
                Gravity.CENTER_VERTICAL);

        sidebar.addView(
                sideTitle,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(26)));

        String[] sideItems = {
                "APPS",
                "APP 1",
                "APP 2",
                "APP 3",
                "VOICE",
                "TASKS"
        };

        for (String item : sideItems) {
            TextView itemView =
                    text(
                            item,
                            12,
                            Color.WHITE);

            itemView.setGravity(
                    Gravity.CENTER_VERTICAL);
            itemView.setPadding(
                    dp(8),
                    dp(8),
                    dp(8),
                    dp(8));

            GradientDrawable itemBg =
                    new GradientDrawable();

            itemBg.setColor(
                    Color.argb(
                            120,
                            30,
                            40,
                            52));
            itemBg.setCornerRadius(
                    dp(12));

            itemView.setBackground(
                    itemBg);

            sidebar.addView(
                    itemView,
                    new LinearLayout.LayoutParams(
                            -1,
                            dp(42)));
        }

        contentRow.addView(
                sidebar,
                new LinearLayout.LayoutParams(
                        dp(180),
                        -1));

        LinearLayout centerStage =
                new LinearLayout(this);

        centerStage.setOrientation(
                LinearLayout.VERTICAL);

        LinearLayout mapBox =
                new LinearLayout(this);

        mapBox.setOrientation(
                LinearLayout.VERTICAL);
        mapBox.setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(18));

        GradientDrawable mapBg =
                new GradientDrawable();

        mapBg.setColor(
                Color.argb(
                        160,
                        10,
                        14,
                        18));
        mapBg.setCornerRadius(
                dp(26));
        mapBg.setStroke(
                dp(1),
                Color.argb(
                        120,
                        141,
                        156,
                        168));

        mapBox.setBackground(
                mapBg);

        TextView mapLabel =
                text(
                        "MAP / NAVIGATION",
                        11,
                        Color.LTGRAY);

        TextView mapPlaceholder =
                text(
                        "Route preview",
                        22,
                        Color.WHITE);

        mapPlaceholder.setGravity(
                Gravity.CENTER);
        mapPlaceholder.setTextSize(
                24);

        mapBox.addView(
                mapLabel,
                new LinearLayout.LayoutParams(
                        -1,
                        -2));

        mapBox.addView(
                mapPlaceholder,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1));

        centerStage.addView(
                mapBox,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1));

        LinearLayout quickRow =
                new LinearLayout(this);

        quickRow.setOrientation(
                LinearLayout.HORIZONTAL);
        quickRow.setPadding(
                0,
                dp(10),
                0,
                0);

        String[][] quickApps = {
                {"NAV", "Nav"},
                {"M", "Media"},
                {"★", "Home"}
        };

        for (String[] item : quickApps) {
            LinearLayout card =
                    new LinearLayout(this);

            card.setOrientation(
                    LinearLayout.VERTICAL);
            card.setGravity(
                    Gravity.CENTER);
            card.setPadding(
                    dp(8),
                    dp(8),
                    dp(8),
                    dp(8));

            GradientDrawable quickBg =
                    new GradientDrawable();

            quickBg.setColor(
                    Color.argb(
                            180,
                            22,
                            29,
                            37));
            quickBg.setCornerRadius(
                    dp(16));

            card.setBackground(
                    quickBg);

            TextView icon =
                    text(
                            item[0],
                            22,
                            Color.WHITE);

            icon.setGravity(
                    Gravity.CENTER);

            TextView label =
                    text(
                            item[1],
                            11,
                            Color.LTGRAY);

            label.setGravity(
                    Gravity.CENTER);

            card.addView(
                    icon,
                    new LinearLayout.LayoutParams(
                            -1,
                            dp(30)));

            card.addView(
                    label,
                    new LinearLayout.LayoutParams(
                            -1,
                            -2));

            quickRow.addView(
                    card,
                    new LinearLayout.LayoutParams(
                            0,
                            dp(90),
                            1f));
        }

        centerStage.addView(
                quickRow,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(96)));

        contentRow.addView(
                centerStage,
                new LinearLayout.LayoutParams(
                        0,
                        -1,
                        1));

        dashboard.addView(
                contentRow,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1));

        LinearLayout bottomBar =
                new LinearLayout(this);

        bottomBar.setOrientation(
                LinearLayout.HORIZONTAL);
        bottomBar.setGravity(
                Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(
                dp(10),
                dp(8),
                dp(10),
                dp(8));

        GradientDrawable bottomBg =
                new GradientDrawable();

        bottomBg.setColor(
                Color.argb(
                        185,
                        16,
                        22,
                        31));
        bottomBg.setCornerRadius(
                dp(18));

        bottomBar.setBackground(
                bottomBg);

        LinearLayout trafficBox =
                new LinearLayout(this);

        trafficBox.setOrientation(
                LinearLayout.HORIZONTAL);
        trafficBox.setGravity(
                Gravity.CENTER_VERTICAL);

        TextView warningIcon =
                text(
                        "⚠",
                        20,
                        Color.WHITE);

        warningIcon.setGravity(
                Gravity.CENTER);

        GradientDrawable warnBg =
                new GradientDrawable();

        warnBg.setColor(
                Color.rgb(
                        255,
                        168,
                        0));
        warnBg.setCornerRadius(
                dp(16));

        warningIcon.setBackground(
                warnBg);
        warningIcon.setPadding(
                dp(8),
                dp(8),
                dp(8),
                dp(8));

        TextView trafficText =
                text(
                        "TRAFFIC",
                        11,
                        Color.WHITE);

        trafficBox.addView(
                warningIcon,
                new LinearLayout.LayoutParams(
                        dp(36),
                        dp(36)));

        trafficBox.addView(
                trafficText,
                new LinearLayout.LayoutParams(
                        -2,
                        -2));

        bottomBar.addView(
                trafficBox,
                new LinearLayout.LayoutParams(
                        -2,
                        -2));

        LinearLayout mediaControls =
                new LinearLayout(this);

        mediaControls.setOrientation(
                LinearLayout.HORIZONTAL);
        mediaControls.setGravity(
                Gravity.CENTER);

        String[] mediaButtons = {
                "◀",
                "⏸",
                "▶"
        };

        for (String btn : mediaButtons) {
            TextView control =
                    text(
                            btn,
                            18,
                            Color.WHITE);

            control.setGravity(
                    Gravity.CENTER);
            control.setPadding(
                    dp(8),
                    dp(8),
                    dp(8),
                    dp(8));

            mediaControls.addView(
                    control,
                    new LinearLayout.LayoutParams(
                            dp(42),
                            dp(42)));
        }

        bottomBar.addView(
                mediaControls,
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1f));

        LinearLayout nowPlaying =
                new LinearLayout(this);

        nowPlaying.setOrientation(
                LinearLayout.VERTICAL);
        nowPlaying.setGravity(
                Gravity.CENTER_VERTICAL);

        TextView mediaLabel =
                text(
                        "MEDIA",
                        10,
                        Color.LTGRAY);

        TextView currentTrack =
                text(
                        "No media",
                        12,
                        Color.WHITE);

        nowPlaying.addView(
                mediaLabel,
                new LinearLayout.LayoutParams(
                        -2,
                        -2));

        nowPlaying.addView(
                currentTrack,
                new LinearLayout.LayoutParams(
                        -2,
                        -2));

        bottomBar.addView(
                nowPlaying,
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1f));

        dashboard.addView(
                bottomBar,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(88)));
    }

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

        launcherLayer.addView(
                appPanel,
                new LinearLayout.LayoutParams(
                        -1,
                        -1));

        LinearLayout header =
                new LinearLayout(this);

        header.setGravity(
                Gravity.CENTER_VERTICAL);

        TextView back =
                buttonText("BACK");

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

        ScrollView scroll =
                new ScrollView(this);

        GridLayout grid =
                new GridLayout(this);

        grid.setColumnCount(4);

        grid.setUseDefaultMargins(false);

        scroll.addView(grid);

        appPanel.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1));

        rebuildAppGrid(grid);
    }

    private void rebuildAppGrid(
            GridLayout grid) {

        grid.removeAllViews();

        for (AppInfo app : apps) {

            LinearLayout tile =
                    appTile(app);

            GridLayout.LayoutParams params =
                    new GridLayout.LayoutParams();

            params.width = 0;
            params.height = dp(105);

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

            String pkg =
                    info.activityInfo
                            .packageName;

            if (getPackageName()
                    .equals(pkg)) {
                continue;
            }

            ComponentName component =
                    new ComponentName(
                            pkg,
                            info.activityInfo
                                    .name);

            CharSequence label =
                    info.loadLabel(
                            getPackageManager());

            String name =
                    label == null
                            ? pkg
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

        if (appPanel != null
                && appPanel
                        .getChildCount() > 1) {

            View child =
                    appPanel
                            .getChildAt(1);

            if (child
                    instanceof ScrollView) {

                View inside =
                        ((ScrollView)
                                child)
                                .getChildAt(0);

                if (inside
                        instanceof GridLayout) {

                    rebuildAppGrid(
                            (GridLayout)
                                    inside);
                }
            }
        }
    }

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

            tile.setTextSize(11);
            tile.setMaxLines(2);

            tile.setOnClickListener(
                    v ->
                            launchProjection(
                                    app.component));

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
                v -> {

                    hideApps();

                    launchProjection(
                            app.component);
                });

        return tile;
    }

    private void attachProjection() {

        textureView =
                new TextureView(this);

        projectionLayer.addView(
                textureView,
                new FrameLayout.LayoutParams(
                        -1,
                        -1));

        session =
                new V10ProjectionSession(
                        this);

        session.attachTextureView(
                textureView);

        V10InputController
                inputController =
                        new V10InputController(
                                session);

        textureView
                .setOnTouchListener(
                        inputController
                                .createListener());
    }

    private void launchProjection(
            ComponentName component) {

        if (component == null
                || session == null) {

            return;
        }

        if (activeTarget != null
                && !activeTarget.equals(component)) {

            session.release();
        }

        activeTarget =
                component;

        V10SessionStore
                .setTarget(
                        this,
                        component);

        launcherLayer
                .setVisibility(
                        View.GONE);

        appPanel
                .setVisibility(
                        View.GONE);

        dashboard
                .setVisibility(
                        View.GONE);

        projectionLayer
                .setVisibility(
                        View.VISIBLE);

        session.setTarget(
                component);
    }

    private void returnToLauncher() {

        activeTarget = null;

        V10SessionStore
                .clearTarget(this);

        if (session != null) {
            session.release();
        }

        projectionLayer
                .setVisibility(
                        View.GONE);

        launcherLayer
                .setVisibility(
                        View.VISIBLE);

        dashboard
                .setVisibility(
                        View.VISIBLE);

        appPanel
                .setVisibility(
                        View.GONE);
    }

    private void showApps() {

        if (launcherLayer == null
                || appPanel == null
                || dashboard == null) {

            return;
        }

        projectionLayer.setVisibility(
                View.GONE);

        launcherLayer.setVisibility(
                View.VISIBLE);

        dashboard.setVisibility(
                View.GONE);

        appPanel.setVisibility(
                View.VISIBLE);

        loadApps();
    }

    private void hideApps() {

        if (appPanel == null
                || dashboard == null
                || launcherLayer == null) {

            return;
        }

        appPanel.setVisibility(
                View.GONE);

        dashboard.setVisibility(
                View.VISIBLE);

        launcherLayer.setVisibility(
                View.VISIBLE);

        projectionLayer.setVisibility(
                View.GONE);
    }

    public void handleControlBack() {

        if (appPanel != null
                && appPanel
                        .getVisibility()
                == View.VISIBLE) {

            hideApps();

            return;
        }

        if (session != null
                && session.isActive()) {

            session.back();
        }
    }

    public void handleControlHome() {

        returnToLauncher();
    }

    public void handleControlRecents() {

        showApps();
    }

    public void handleControlApps() {

        showApps();
    }

    @Override
    public void onBackPressed() {

        if (appPanel != null
                && appPanel
                        .getVisibility()
                == View.VISIBLE) {

            hideApps();

            return;
        }

        if (session != null
                && session.isActive()) {

            session.back();

            return;
        }

        super.onBackPressed();
    }

    @Override
    public void onPause() {

        resumed = false;

        handler.removeCallbacks(
                clockTicker);

        handler.removeCallbacks(
                targetPoller);

        super.onPause();
    }

    @Override
    public void onStop() {

        resumed = false;

        handler.removeCallbacks(
                clockTicker);

        handler.removeCallbacks(
                targetPoller);

        if (session != null) {
            session.release();
        }

        super.onStop();
    }

    @Override
    public void onDestroy() {

        resumed = false;

        handler.removeCallbacks(
                clockTicker);

        handler.removeCallbacks(
                targetPoller);

        if (session != null) {

            session.destroy();

            session = null;
        }

        V10SessionStore
                .clearCurrentActivity(
                        this);

        root = null;
        textureView = null;
        projectionLayer = null;
        launcherLayer = null;
        dashboard = null;
        appPanel = null;
        dock = null;

        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(
            boolean hasFocus,
            boolean touchMode) {

        super.onWindowFocusChanged(
                hasFocus,
                touchMode);

        if (hasFocus) {
            applyChrome();
        }
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

        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(
                        -1,
                        -1);

        p.setMargins(
                dp(4),
                dp(4),
                dp(4),
                dp(4));

        box.setLayoutParams(p);

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
                value *
                getResources()
                        .getDisplayMetrics()
                        .density);
    }

    private static final class AppInfo {

        final ComponentName component;

        final String name;

        AppInfo(
                ComponentName component,
                String name) {

            this.component = component;
            this.name = name;
        }
    }
}
