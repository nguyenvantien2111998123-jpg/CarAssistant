
package com.carassistant.v10;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
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

    private TextView floatingTouchKey;
    private LinearLayout floatingMenu;

    private boolean floatingDragging;
    private float floatingDownRawX;
    private float floatingDownRawY;
    private int floatingStartX;
    private int floatingStartY;
    private long floatingDownTime;

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

        dashboard =
                new LinearLayout(this);

        dashboard.setOrientation(
                LinearLayout.VERTICAL);

        launcherLayer.addView(
                dashboard,
                new LinearLayout.LayoutParams(
                        -1,
                        -1));

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

        LinearLayout content =
                new LinearLayout(this);

        content.setOrientation(
                LinearLayout.VERTICAL);

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
                buttonText("APPS");

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

        createFloatingTouchKey();

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

    /*
     * If the user selects another application while
     * an application is already projected, release
     * the old display first.
     *
     * V10ProjectionSession itself also handles target
     * changes safely.
     */
    if (activeTarget != null
            && !activeTarget.equals(component)) {

        session.release();
    }

    activeTarget =
            component;

    /*
     * Keep the existing V10 session persistence.
     */
    V10SessionStore
            .setTarget(
                    this,
                    component);

    /*
     * Hide the complete launcher UI.
     */
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

    /*
     * Start the existing proven V10 projection
     * engine.
     */
    session.setTarget(
            component);
}

    private void returnToLauncher() {

        if (session != null
                && session.isActive()) {
            session.hideTask();
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

    /*
     * V10.4 V4
     *
     * APPS is a real Android Auto-side application
     * selector.
     *
     * It works from:
     *
     * 1. Dashboard
     * 2. Active projection
     *
     * Therefore the launcher layer is explicitly
     * restored before displaying Applications.
     */

    if (launcherLayer == null
            || appPanel == null
            || dashboard == null) {

        return;
    }

    /*
     * Hide the projected application while the
     * application selector is visible.
     *
     * The projection session itself is intentionally
     * kept alive. If another application is selected,
     * V10ProjectionSession.setTarget() will detect the
     * changed target and recreate the display.
     */
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

    /*
     * The Applications screen belongs to the
     * V10 dashboard.
     *
     * Projection is hidden until an application
     * is selected again.
     */
    projectionLayer.setVisibility(
            View.GONE);
}

    private void createFloatingTouchKey() {
    if (floatingTouchKey != null) return;
    floatingTouchKey = new TextView(this);
    floatingTouchKey.setText("●");
    floatingTouchKey.setTextColor(Color.WHITE);
    floatingTouchKey.setTextSize(22);
    floatingTouchKey.setGravity(Gravity.CENTER);
    GradientDrawable bg = new GradientDrawable();
    bg.setShape(GradientDrawable.OVAL);
    bg.setColor(Color.rgb(25, 35, 45));
    bg.setStroke(dp(2), Color.rgb(80, 170, 230));
    floatingTouchKey.setBackground(bg);
    floatingTouchKey.setElevation(dp(12));
    SharedPreferences prefs = getSharedPreferences("v10_floating_key", MODE_PRIVATE);
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.TOP | Gravity.END);
    int savedX = prefs.getInt("x", dp(18));
    int savedY = prefs.getInt("y", dp(120));
    params.setMargins(0, savedY, savedX, 0);
    projectionLayer.addView(floatingTouchKey, params);
    floatingTouchKey.setOnTouchListener(
                (view, event) -> {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            floatingDragging = false;
                            floatingDownRawX = event.getRawX();
                            floatingDownRawY = event.getRawY();
                            floatingDownTime = SystemClock.uptimeMillis();
                            ViewGroup.LayoutParams lp = view.getLayoutParams();
                            if (lp instanceof FrameLayout.LayoutParams) {
                                FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) lp;
                                floatingStartX = fp.rightMargin;
                                floatingStartY = fp.topMargin;
                            }
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - floatingDownRawX;
                            float dy = event.getRawY() - floatingDownRawY;
                            if (!floatingDragging
                                    && Math.hypot(dx, dy)
                                    > ViewConfiguration.get(this).getScaledTouchSlop()) {
                                floatingDragging = true;
                            }
                            if (floatingDragging) {
                                int width = projectionLayer.getWidth();
                                int height = projectionLayer.getHeight();
                                int keySize = dp(58);
                                int right = Math.round(floatingStartX - dx);
                                int top = Math.round(floatingStartY + dy);
                                right = Math.max(0, Math.min(Math.max(0, width - keySize), right));
                                top = Math.max(0, Math.min(Math.max(0, height - keySize), top));
                                FrameLayout.LayoutParams fp =
                                        (FrameLayout.LayoutParams) view.getLayoutParams();
                                fp.rightMargin = right;
                                fp.topMargin = top;
                                view.setLayoutParams(fp);
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                            if (floatingDragging) {
                                FrameLayout.LayoutParams fp =
                                        (FrameLayout.LayoutParams) view.getLayoutParams();
                                prefs.edit()
                                        .putInt("x", fp.rightMargin)
                                        .putInt("y", fp.topMargin)
                                        .apply();
                                floatingDragging = false;
                                return true;
                            }
                            long held = SystemClock.uptimeMillis() - floatingDownTime;
                            if (held >= ViewConfiguration.getLongPressTimeout()) {
                                handleControlHome();
                                hideFloatingMenu();
                            } else {
                                showFloatingMenu();
                            }
                            view.performClick();
                            return true;

                        case MotionEvent.ACTION_CANCEL:
                            floatingDragging = false;
                            return true;

                        default:
                            return true;
                    }
                });;
    floatingTouchKey.bringToFront();
}

private void showFloatingMenu() {
        if (floatingMenu != null) {
            hideFloatingMenu();
            return;
        }

        floatingMenu = new LinearLayout(this);
        floatingMenu.setOrientation(LinearLayout.VERTICAL);
        floatingMenu.setPadding(dp(8), dp(8), dp(8), dp(8));

        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setColor(Color.rgb(15, 20, 26));
        menuBg.setCornerRadius(dp(16));
        menuBg.setStroke(dp(1), Color.rgb(65, 85, 105));
        floatingMenu.setBackground(menuBg);
        floatingMenu.setElevation(dp(14));

        addFloatingMenuButton("BACK", () -> {
            handleControlBack();
            hideFloatingMenu();
        });

        addFloatingMenuButton("HOME", () -> {
            handleControlHome();
            hideFloatingMenu();
        });

        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(dp(170), -2,
                        Gravity.TOP | Gravity.END);
        params.setMargins(0, dp(70), dp(18), 0);
        projectionLayer.addView(floatingMenu, params);
        floatingMenu.bringToFront();
        floatingTouchKey.bringToFront();
    }

private void addFloatingMenuButton(String label, Runnable action) {
    TextView button = new TextView(this);
    button.setText(label);
    button.setTextColor(Color.WHITE);
    button.setTextSize(14);
    button.setGravity(Gravity.CENTER_VERTICAL);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(Color.rgb(28, 38, 48));
    bg.setCornerRadius(dp(10));
    button.setBackground(bg);
    button.setPadding(dp(16), 0, dp(16), 0);
    button.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
    params.setMargins(0, dp(3), 0, dp(3));
    floatingMenu.addView(button, params);
}

private void hideFloatingMenu() {
    if (floatingMenu == null) return;
    projectionLayer.removeView(floatingMenu);
    floatingMenu = null;
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

        /* Display Core V1: Activity stop is not task close. */
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
