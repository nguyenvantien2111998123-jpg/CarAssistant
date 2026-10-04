
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
    private LinearLayout bottomBar;
    private TextureView textureView;
    private V10ProjectionSession session;

    private boolean resumed;

    private ComponentName activeTarget;

    private final ArrayList<AppInfo> apps =
            new ArrayList<>();

    private final Runnable clockTicker =
            new Runnable() {
                @Override
                public void run() {
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
                dp(10),
                dp(10),
                dp(10),
                dp(10));

        root.addView(
                launcherLayer,
                new FrameLayout.LayoutParams(
                        -1,
                        -1));

        buildMainLayout();
        buildAppPanel();

        setContentView(root);
    }

    private void buildMainLayout() {

        dashboard = new LinearLayout(this);
        dashboard.setOrientation(LinearLayout.HORIZONTAL);
        dashboard.setBackgroundColor(Color.TRANSPARENT);

        launcherLayer.addView(
                dashboard,
                new LinearLayout.LayoutParams(
                        -1,
                        -1));

        LinearLayout sidebar =
                new LinearLayout(this);

        sidebar.setOrientation(
                LinearLayout.VERTICAL);
        sidebar.setPadding(
                dp(8),
                dp(8),
                dp(8),
                dp(8));
        sidebar.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable sideBg =
                new GradientDrawable();

        sideBg.setColor(
                Color.argb(
                        80,
                        18,
                        24,
                        31));
        sideBg.setCornerRadius(
                dp(18));

        sidebar.setBackground(
                sideBg);

        TextView menuAppsBtn =
                sidebarButton("📱");
        menuAppsBtn.setOnClickListener(v -> showApps());
        sidebar.addView(
                menuAppsBtn,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(50)));

        sidebar.addView(
                spacer(dp(8)),
                new LinearLayout.LayoutParams(
                        -1,
                        dp(8)));

        for (int i = 0; i < 3; i++) {
            TextView favApp =
                    sidebarButton("⭐");
            favApp.setOnClickListener(v -> {
                // Load favorite app at index
            });
            sidebar.addView(
                    favApp,
                    new LinearLayout.LayoutParams(
                            -1,
                            dp(50)));

            if (i < 2) {
                sidebar.addView(
                        spacer(dp(6)),
                        new LinearLayout.LayoutParams(
                                -1,
                                dp(6)));
            }
        }

        sidebar.addView(
                spacer(dp(12)),
                new LinearLayout.LayoutParams(
                        -1,
                        dp(12)));

        TextView voiceBtn =
                sidebarButton("🎤");
        voiceBtn.setOnClickListener(v -> {
            // Handle voice assistant
        });
        sidebar.addView(
                voiceBtn,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(50)));

        sidebar.addView(
                spacer(dp(8)),
                new LinearLayout.LayoutParams(
                        -1,
                        dp(8)));

        TextView tasksBtn =
                sidebarButton("📋");
        tasksBtn.setOnClickListener(v -> {
            // Show running apps
        });
        sidebar.addView(
                tasksBtn,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(50)));

        dashboard.addView(
                sidebar,
                new LinearLayout.LayoutParams(
                        dp(80),
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
                dp(16),
                dp(16),
                dp(16),
                dp(16));

        GradientDrawable mapBg =
                new GradientDrawable();

        mapBg.setColor(
                Color.argb(
                        120,
                        10,
                        14,
                        18));
        mapBg.setCornerRadius(
                dp(20));
        mapBg.setStroke(
                dp(1),
                Color.argb(
                        100,
                        141,
                        156,
                        168));

        mapBox.setBackground(
                mapBg);

        TextView mapLabel =
                text(
                        "NAVIGATION",
                        10,
                        Color.LTGRAY);

        TextView mapPlaceholder =
                text(
                        "Map Preview",
                        28,
                        Color.WHITE);

        mapPlaceholder.setGravity(
                Gravity.CENTER);

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
                        0,
                        -1,
                        1));

        dashboard.addView(
                centerStage,
                new LinearLayout.LayoutParams(
                        0,
                        -1,
                        1));

        buildTransparentBottomBar();

        launcherLayer.addView(
                bottomBar,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(100)));
    }

    private void buildTransparentBottomBar() {

        bottomBar =
                new LinearLayout(this);

        bottomBar.setOrientation(
                LinearLayout.HORIZONTAL);
        bottomBar.setGravity(
                Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(
                dp(12),
                dp(8),
                dp(12),
                dp(8));

        GradientDrawable bottomBg =
                new GradientDrawable();

        bottomBg.setColor(
                Color.argb(
                        100,
                        16,
                        22,
                        31));
        bottomBg.setCornerRadius(
                dp(16));

        bottomBar.setBackground(
                bottomBg);

        LinearLayout trafficBox =
                new LinearLayout(this);

        trafficBox.setOrientation(
                LinearLayout.VERTICAL);
        trafficBox.setGravity(
                Gravity.CENTER);

        FrameLayout trafficCircle =
                new FrameLayout(this);

        TextView trafficIcon =
                text(
                        "⚠",
                        18,
                        Color.WHITE);

        trafficIcon.setGravity(
                Gravity.CENTER);

        GradientDrawable circleBg =
                new GradientDrawable();

        circleBg.setColor(
                Color.rgb(
                        255,
                        168,
                        0));
        circleBg.setCornerRadius(
                dp(28));

        trafficIcon.setBackground(
                circleBg);
        trafficIcon.setPadding(
                dp(6),
                dp(6),
                dp(6),
                dp(6));

        trafficCircle.addView(
                trafficIcon,
                new FrameLayout.LayoutParams(
                        dp(56),
                        dp(56),
                        Gravity.CENTER));

        trafficBox.addView(
                trafficCircle,
                new LinearLayout.LayoutParams(
                        dp(56),
                        dp(56)));

        TextView trafficInfo =
                text(
                        "Traffic Info",
                        8,
                        Color.LTGRAY);
        trafficInfo.setGravity(
                Gravity.CENTER);

        trafficBox.addView(
                trafficInfo,
                new LinearLayout.LayoutParams(
                        dp(56),
                        -2));

        bottomBar.addView(
                trafficBox,
                new LinearLayout.LayoutParams(
                        dp(70),
                        -1));

        LinearLayout mediaBox =
                new LinearLayout(this);

        mediaBox.setOrientation(
                LinearLayout.VERTICAL);
        mediaBox.setGravity(
                Gravity.CENTER);

        LinearLayout controlsRow =
                new LinearLayout(this);

        controlsRow.setOrientation(
                LinearLayout.HORIZONTAL);
        controlsRow.setGravity(
                Gravity.CENTER);

        String[] mediaButtons = {
                "⏮",
                "⏸",
                "⏭"
        };

        for (String btn : mediaButtons) {
            TextView control =
                    text(
                            btn,
                            16,
                            Color.WHITE);

            control.setGravity(
                    Gravity.CENTER);
            control.setPadding(
                    dp(6),
                    dp(6),
                    dp(6),
                    dp(6));

            controlsRow.addView(
                    control,
                    new LinearLayout.LayoutParams(
                            dp(40),
                            dp(40)));
        }

        mediaBox.addView(
                controlsRow,
                new LinearLayout.LayoutParams(
                        -2,
                        dp(50)));

        TextView trackLabel =
                text(
                        "Now playing",
                        8,
                        Color.LTGRAY);
        trackLabel.setGravity(
                Gravity.CENTER);
        trackLabel.setMaxLines(1);
        trackLabel.setEllipsize(
                android.text.TextUtils.TruncateAt.END);

        mediaBox.addView(
                trackLabel,
                new LinearLayout.LayoutParams(
                        dp(150),
                        -2));

        bottomBar.addView(
                mediaBox,
                new LinearLayout.LayoutParams(
                        0,
                        -1,
                        1));
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
        bottomBar = null;

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

    private TextView sidebarButton(
            String icon) {

        TextView btn =
                text(
                        icon,
                        20,
                        Color.WHITE);

        btn.setGravity(
                Gravity.CENTER);

        GradientDrawable bg =
                new GradientDrawable();

        bg.setColor(
                Color.argb(
                        150,
                        30,
                        40,
                        52));
        bg.setCornerRadius(
                dp(14));

        btn.setBackground(bg);

        return btn;
    }

    private View spacer(int height) {
        View v = new View(this);
        v.setBackgroundColor(Color.TRANSPARENT);
        return v;
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
