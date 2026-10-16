
package com.carassistant.v10;

import android.Manifest;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.carassistant.v10.voice.VoiceAssistantController;
import com.carassistant.v10.voice.VoiceCommandRouter;
import com.carassistant.v10.voice.VoiceCommandParser;

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
    private FrameLayout appProjectionLayer;
    private TextureView appTextureView;
    private V10ProjectionSession backgroundSession;
    private ComponentName activeBackgroundTarget;
    private ComponentName selectedAppTarget;
    private TextView selectedAppLabel;
    private TextView dashboardVoiceButton;
    private TextView floatingVoiceButton;
    private TextView voiceTranscriptView;
    private VoiceAssistantController voiceAssistantController;

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

                    ComponentName target = V10SessionStore.getTarget(V10CarActivity.this);
                    if (target != null && !target.equals(activeTarget)) launchProjection(target);

                    ComponentName backgroundTarget = V10SessionStore.getBackgroundTarget(V10CarActivity.this);
                    if (backgroundTarget != null && !backgroundTarget.equals(activeBackgroundTarget)) {
                        launchBackgroundProjection(backgroundTarget);
                    } else if (backgroundTarget == null && activeBackgroundTarget != null) {
                        stopBackgroundProjection();
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

        if (voiceAssistantController == null) {
            voiceAssistantController = new VoiceAssistantController(
                    this,
                    (listening, status, transcript, showTranscript) -> {
                        String icon = listening ? "🔴" : "🎙";
                        float scale = listening ? 1.10f : 1.0f;
                        if (dashboardVoiceButton != null) {
                            dashboardVoiceButton.setText(icon);
                            dashboardVoiceButton.setContentDescription(status);
                            dashboardVoiceButton.setScaleX(scale);
                            dashboardVoiceButton.setScaleY(scale);
                            dashboardVoiceButton.setAlpha(listening ? 0.82f : 1.0f);
                        }
                        if (floatingVoiceButton != null) {
                            floatingVoiceButton.setText(icon);
                            floatingVoiceButton.setContentDescription(status);
                            floatingVoiceButton.setScaleX(scale);
                            floatingVoiceButton.setScaleY(scale);
                            floatingVoiceButton.setAlpha(listening ? 0.82f : 1.0f);
                        }
                        if (voiceTranscriptView != null) {
                            String words = transcript == null ? "" : transcript.trim();
                            voiceTranscriptView.setText(words);
                            voiceTranscriptView.setVisibility(showTranscript && !words.isEmpty()
                                    ? View.VISIBLE : View.GONE);
                            if (showTranscript && !words.isEmpty()) {
                                voiceTranscriptView.setSelected(true);
                                voiceTranscriptView.bringToFront();
                            }
                        }
                    },
                    new VoiceCommandRouter(new VoiceCommandRouter.Host() {
                        @Override
                        public String openApp(String spokenName) {
                            return openAppByVoice(spokenName);
                        }

                        @Override
                        public void showAppPicker() {
                            V10CarActivity.this.showApps();
                        }

                        @Override
                        public void goHome() {
                            V10CarActivity.this.returnToLauncher();
                        }
                    }));
        }

        loadApps();
        rebuildDock();

        handler.removeCallbacks(
                clockTicker);

        handler.removeCallbacks(
                targetPoller);

        handler.post(clockTicker);
        handler.post(targetPoller);

        ComponentName backgroundTarget = V10SessionStore.getBackgroundTarget(this);
        if (backgroundTarget != null) launchBackgroundProjection(backgroundTarget);

        ComponentName target = V10SessionStore.getTarget(this);
        if (target != null) launchProjection(target);
    }


    private void buildUi() {
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        // Independent app projection is the bottom layer.
        // The existing dashboard is added after it and remains above it.
        projectionLayer=new FrameLayout(this);
        // Layer 1 is shown only on Home and only when a default app is configured.
        projectionLayer.setVisibility(View.GONE);
        root.addView(projectionLayer, new FrameLayout.LayoutParams(-1,-1));

        // Independent second projection surface, controlled only by APP CHỌN.
        appProjectionLayer = new FrameLayout(this);
        appProjectionLayer.setVisibility(View.GONE);
        root.addView(appProjectionLayer, new FrameLayout.LayoutParams(-1, -1));

        launcherLayer=new LinearLayout(this);
        launcherLayer.setOrientation(LinearLayout.VERTICAL);
        launcherLayer.setPadding(dp(18),dp(12),dp(18),dp(8));
        launcherLayer.setBackgroundColor(Color.TRANSPARENT);
        root.addView(
                launcherLayer,
                new FrameLayout.LayoutParams(-1,-1));

        buildDashboard();
        buildAppPanel();

        // Transcript overlay sits above the existing UI but does not intercept touch.
        voiceTranscriptView = new TextView(this);
        voiceTranscriptView.setTextColor(Color.WHITE);
        voiceTranscriptView.setTextSize(19);
        voiceTranscriptView.setGravity(Gravity.CENTER);
        voiceTranscriptView.setSingleLine(true);
        voiceTranscriptView.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        voiceTranscriptView.setMarqueeRepeatLimit(-1);
        voiceTranscriptView.setSelected(true);
        voiceTranscriptView.setPadding(dp(18), dp(8), dp(18), dp(8));
        voiceTranscriptView.setClickable(false);
        voiceTranscriptView.setFocusable(false);
        GradientDrawable transcriptBg = new GradientDrawable();
        transcriptBg.setColor(Color.rgb(12, 20, 25));
        transcriptBg.setCornerRadius(dp(18));
        transcriptBg.setStroke(dp(2), Color.rgb(70, 190, 150));
        voiceTranscriptView.setBackground(transcriptBg);
        voiceTranscriptView.setElevation(dp(40));
        voiceTranscriptView.setVisibility(View.GONE);
        FrameLayout.LayoutParams transcriptParams = new FrameLayout.LayoutParams(
                dp(420), dp(54), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        transcriptParams.setMargins(dp(12), dp(18), dp(12), 0);
        root.addView(voiceTranscriptView, transcriptParams);

        setContentView(root);
    }



    private void buildDashboard() {
        dashboard=new LinearLayout(this);
        dashboard.setOrientation(LinearLayout.HORIZONTAL);
        dashboard.setGravity(Gravity.CENTER_VERTICAL);
        dashboard.setBackgroundColor(Color.TRANSPARENT);
        launcherLayer.addView(dashboard,new LinearLayout.LayoutParams(-1,-1));

        LinearLayout sidebar=new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        sidebar.setPadding(dp(3),dp(3),dp(3),dp(3));
        dashboard.addView(sidebar,new LinearLayout.LayoutParams(dp(64),-1));

        TextView choose=sidebarButton("APP CHỌN");
        choose.setOnClickListener(v->showApps());
        sidebar.addView(choose,sidebarButtonParams());

        dock=new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        sidebar.addView(dock,new LinearLayout.LayoutParams(-1,0,1f));

        TextView voice=sidebarButton("🎙");
        voice.setTextSize(18);
        voice.setContentDescription("Kích hoạt trợ lý giọng nói");
        voice.setOnClickListener(v -> activateVoiceAssistant());
        dashboardVoiceButton = voice;
        sidebar.addView(voice,sidebarFixedButtonParams());

        TextView tasks=sidebarButton("TASKS");
        tasks.setOnClickListener(v->{});
        sidebar.addView(tasks,sidebarFixedButtonParams());

        FrameLayout main=new FrameLayout(this);
        dashboard.addView(main,new LinearLayout.LayoutParams(0,-1,1f));

        LinearLayout bottom=new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(8),dp(8),dp(8),dp(8));
        main.addView(bottom,new FrameLayout.LayoutParams(-1,dp(88),Gravity.BOTTOM));

        TextView wyn=circleButton("WYN");
        wyn.setOnClickListener(v->{});
        bottom.addView(wyn,new LinearLayout.LayoutParams(dp(78),dp(78)));

        LinearLayout media=card();
        media.setPadding(dp(14),dp(7),dp(14),dp(7));
        media.addView(text("MEDIA",12,Color.LTGRAY),new LinearLayout.LayoutParams(-1,dp(25)));
        media.addView(text("▶  Đang phát...",17,Color.WHITE),new LinearLayout.LayoutParams(-1,dp(35)));
        media.setOnClickListener(v->{});
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(78),1f);
        mp.setMargins(dp(12),0,0,0);
        bottom.addView(media,mp);
    }


    private void buildAppPanel() {

        appPanel =
                new LinearLayout(this);

        appPanel.setOrientation(
                LinearLayout.VERTICAL);

        // Opaque selector surface prevents the projected app texture from visually
        // swallowing the app list while APP CHỌN is open.
        appPanel.setBackgroundColor(Color.rgb(7, 9, 12));
        appPanel.setElevation(dp(24));

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

        TextView title = text("APPLICATIONS", 18, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1));

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

        // appPanel children are: header, selected-app label, then the
        // ScrollView. Do not assume the list is child index 1: that is the
        // label, so the grid otherwise stays empty after loadApps().
        if (appPanel != null) {
            for (int i = 0; i < appPanel.getChildCount(); i++) {
                View child = appPanel.getChildAt(i);
                if (!(child instanceof ScrollView)) continue;

                View inside = ((ScrollView) child).getChildAt(0);
                if (inside instanceof GridLayout) {
                    rebuildAppGrid((GridLayout) inside);
                    break;
                }
            }
        }
    }


    private void rebuildDock() {
        if (dock == null) return;
        dock.removeAllViews();
        ArrayList<AppInfo> favorites = getQuickLaunchApps();
        for (AppInfo app : favorites) {
            dock.addView(quickLaunchTile(app), sidebarQuickParams());
        }
    }

    private ArrayList<AppInfo> getQuickLaunchApps() {
        ArrayList<AppInfo> out = new ArrayList<>();
        ArrayList<ComponentName> configured = V10SessionStore.getQuickLaunchApps(this);
        for (ComponentName component : configured) {
            if (component == null) continue;
            for (AppInfo app : apps) {
                if (component.getPackageName().equals(app.component.getPackageName())) {
                    out.add(app);
                    break;
                }
            }
            if (out.size() == 3) break;
        }
        return out;
    }

    private LinearLayout quickLaunchTile(AppInfo app) {
        LinearLayout t=new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g=new GradientDrawable();
        g.setColor(Color.rgb(22,27,33));
        g.setCornerRadius(dp(14));
        t.setBackground(g);
        ImageView i=new ImageView(this);
        Drawable d=getRealAppIcon(app.component.getPackageName());
        if(d!=null)i.setImageDrawable(d);
        i.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        t.addView(i,new LinearLayout.LayoutParams(dp(30),dp(30)));
        TextView n=text(app.name,7,Color.WHITE);
        n.setGravity(Gravity.CENTER);
        n.setMaxLines(1);
        t.addView(n,new LinearLayout.LayoutParams(-1,dp(16)));
        t.setOnClickListener(v->launchProjection(app.component));
        return t;
    }

    private Drawable getRealAppIcon(String packageName) {
        try {
            PackageManager pm=getPackageManager();
            ApplicationInfo i=pm.getApplicationInfo(packageName,0);
            return i.loadIcon(pm);
        } catch(Exception e) {
            return null;
        }
    }



    private LinearLayout appTile(AppInfo app) {
        LinearLayout t=new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g=new GradientDrawable();
        g.setColor(Color.rgb(22,27,33));
        g.setCornerRadius(dp(16));
        t.setBackground(g);
        ImageView i=new ImageView(this);
        Drawable d=getRealAppIcon(app.component.getPackageName());
        if(d!=null)i.setImageDrawable(d);
        i.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        t.addView(i,new LinearLayout.LayoutParams(-1,dp(64)));
        TextView n=text(app.name,12,Color.WHITE);
        n.setGravity(Gravity.CENTER);
        n.setMaxLines(2);
        t.addView(n,new LinearLayout.LayoutParams(-1,dp(38)));
        // Selecting an app starts projection immediately; the picker and Home UI
        // are hidden by launchProjection() while display 2 takes over.
        t.setOnClickListener(v -> launchProjection(app.component));
        return t;
    }


    private void attachProjection() {

        // Layer 1: the fixed app chosen in phone SETUPS.
        textureView = new TextureView(this);
        projectionLayer.addView(textureView, new FrameLayout.LayoutParams(-1, -1));
        backgroundSession = new V10ProjectionSession(this, 0);
        backgroundSession.attachTextureView(textureView);
        textureView.setOnTouchListener(new V10InputController(backgroundSession).createListener());

        // Layer 2: app chosen on the car-side APP CHỌN screen.
        appTextureView = new TextureView(this);
        appProjectionLayer.addView(appTextureView, new FrameLayout.LayoutParams(-1, -1));
        session = new V10ProjectionSession(this, 1);
        session.attachTextureView(appTextureView);
        appTextureView.setOnTouchListener(new V10InputController(session).createListener());

        createFloatingTouchKey();
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

    activeTarget = component;
    V10SessionStore.setTarget(this, component);

    if (floatingVoiceButton != null) {
        floatingVoiceButton.setVisibility(View.VISIBLE);
        floatingVoiceButton.bringToFront();
    }

    // Display 2 owns the entire surface while an app is projected:
    // hide display 1 and the Android Auto Home chrome so neither can
    // cover the selected app.
    projectionLayer.setVisibility(View.GONE);
    appProjectionLayer.setVisibility(View.VISIBLE);
    launcherLayer.setVisibility(View.GONE);

    /*
     * Start the existing proven V10 projection
     * engine.
     */
    session.setTarget(component);
}

private void launchBackgroundProjection(ComponentName component) {
    if (component == null || backgroundSession == null) return;
    activeBackgroundTarget = component;
    V10SessionStore.setBackgroundTarget(this, component);
    // Layer 1 is a Home background, never shown over APP CHỌN or other screens.
    projectionLayer.setVisibility(isHomeScreen() ? View.VISIBLE : View.GONE);
    backgroundSession.setTarget(component);
}

private void stopAppPickerProjection() {
    if (session != null) {
        session.hideTask();
        session.setTarget(null);
    }
    V10SessionStore.clearTarget(this);
    activeTarget = null;
    appProjectionLayer.setVisibility(View.GONE);
}

private void stopBackgroundProjection() {
    if (backgroundSession != null) backgroundSession.setTarget(null);
    V10SessionStore.setBackgroundTarget(this, null);
    activeBackgroundTarget = null;
    projectionLayer.setVisibility(View.GONE);
}

private boolean isHomeScreen() {
    return launcherLayer != null
            && launcherLayer.getVisibility() == View.VISIBLE
            && dashboard != null
            && dashboard.getVisibility() == View.VISIBLE
            && appPanel != null
            && appPanel.getVisibility() != View.VISIBLE;
}

    private void returnToLauncher() {

        // Leaving display 2 returns to Home and restores display 1.
        // Detach display 2 first so it cannot remain visible under Home chrome.
        if (session != null) {
            session.hideTask();
            session.setTarget(null);
        }
        V10SessionStore.clearTarget(this);
        activeTarget = null;
        appProjectionLayer.setVisibility(View.GONE);

        launcherLayer.setVisibility(View.VISIBLE);
        dashboard.setVisibility(View.VISIBLE);
        appPanel.setVisibility(View.GONE);
        if (floatingVoiceButton != null) floatingVoiceButton.setVisibility(View.GONE);

        projectionLayer.setVisibility(
                V10SessionStore.getBackgroundTarget(this) != null
                        ? View.VISIBLE : View.GONE);
    }


private void showApps() {

    if (floatingVoiceButton != null) floatingVoiceButton.setVisibility(View.GONE);

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
    // Both projection surfaces must stay behind the selector. TextureView content can
    // otherwise visually compete with the transparent launcher hierarchy on some devices.
    projectionLayer.setVisibility(View.GONE);
    appProjectionLayer.setVisibility(View.GONE);

    launcherLayer.setVisibility(View.VISIBLE);
    dashboard.setVisibility(View.GONE);
    appPanel.setVisibility(View.VISIBLE);

    // Force a deterministic foreground order whenever the selector opens.
    launcherLayer.bringToFront();
    appPanel.bringToFront();
    appPanel.setElevation(dp(24));
    appPanel.invalidate();
    launcherLayer.invalidate();
    if (floatingMenu != null) floatingMenu.bringToFront();
    if (floatingTouchKey != null) floatingTouchKey.bringToFront();

    loadApps();
}


private void hideApps() {

    if (appPanel == null
            || dashboard == null
            || launcherLayer == null) {

        return;
    }

    // Closing APP CHỌN returns to the single Home composition:
    // display 1 behind the dashboard; display 2 hidden until chosen again.
    returnToLauncher();
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
    root.addView(floatingTouchKey, params);
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

    floatingVoiceButton = new TextView(this);
    floatingVoiceButton.setText("🎙");
    floatingVoiceButton.setTextColor(Color.WHITE);
    floatingVoiceButton.setTextSize(22);
    floatingVoiceButton.setGravity(Gravity.CENTER);
    floatingVoiceButton.setContentDescription("Kích hoạt trợ lý giọng nói");
    GradientDrawable voiceBg = new GradientDrawable();
    voiceBg.setShape(GradientDrawable.OVAL);
    voiceBg.setColor(Color.rgb(25, 35, 45));
    voiceBg.setStroke(dp(2), Color.rgb(80, 190, 150));
    floatingVoiceButton.setBackground(voiceBg);
    floatingVoiceButton.setElevation(dp(12));
    FrameLayout.LayoutParams voiceParams = new FrameLayout.LayoutParams(
            dp(58), dp(58), Gravity.BOTTOM | Gravity.START);
    voiceParams.setMargins(dp(18), 0, 0, dp(18));
    root.addView(floatingVoiceButton, voiceParams);
    floatingVoiceButton.setVisibility(View.GONE);
    floatingVoiceButton.setOnClickListener(v -> activateVoiceAssistant());
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
        root.addView(floatingMenu, params);
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
    root.removeView(floatingMenu);
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

    private void activateVoiceAssistant() {
        if (voiceAssistantController == null) {
            Toast.makeText(this, "Trợ lý giọng nói chưa sẵn sàng.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Intent permissionIntent = new Intent(this, V10SettingsActivity.class);
            permissionIntent.putExtra(V10SettingsActivity.EXTRA_REQUEST_MICROPHONE, true);
            permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(permissionIntent);
            return;
        }
        voiceAssistantController.startListening();
    }

    private String openAppByVoice(String spokenName) {
        String query = VoiceCommandParser.normalize(spokenName);
        if (query.isEmpty()) return null;

        AppInfo best = null;
        int bestScore = 0;
        for (AppInfo app : apps) {
            String label = VoiceCommandParser.normalize(app.name);
            String pkg = app.component.getPackageName().toLowerCase(Locale.ROOT);
            int score = 0;

            if (label.equals(query)) score = 100;
            else if (label.length() >= 4 && query.contains(label)) score = 80;
            else if (query.length() >= 4 && label.contains(query)) score = 70;

            if (query.contains("youtube") && pkg.contains("youtube")) score = Math.max(score, 95);
            if ((query.equals("maps") || query.equals("google maps"))
                    && (pkg.equals("com.google.android.apps.maps") || label.contains("maps"))) {
                score = Math.max(score, 95);
            }
            if (query.contains("spotify") && pkg.contains("spotify")) score = Math.max(score, 95);

            if (score > bestScore) {
                bestScore = score;
                best = app;
            }
        }

        if (best == null || bestScore < 60) return null;
        launchProjection(best.component);
        return best.name;
    }

    @Override
    public void onPause() {

        if (voiceAssistantController != null) voiceAssistantController.cancelListening();
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

        if (voiceAssistantController != null) {
            voiceAssistantController.destroy();
            voiceAssistantController = null;
        }
        if (session != null) { session.destroy(); session = null; }
        if (backgroundSession != null) { backgroundSession.destroy(); backgroundSession = null; }

        V10SessionStore
                .clearCurrentActivity(
                        this);

        root = null;
        textureView = null;
        appTextureView = null;
        appProjectionLayer = null;
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


    private LinearLayout.LayoutParams sidebarButtonParams() {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));
        p.setMargins(dp(1),dp(2),dp(1),dp(2));
        return p;
    }

    private LinearLayout.LayoutParams sidebarFixedButtonParams() {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));
        p.setMargins(dp(1),dp(2),dp(1),dp(2));
        return p;
    }

    private LinearLayout.LayoutParams sidebarQuickParams() {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));
        p.setMargins(dp(1),dp(2),dp(1),dp(2));
        return p;
    }

    private TextView sidebarButton(String value) {
        TextView v=buttonText(value);
        v.setTextSize(9);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(2),dp(2),dp(2),dp(2));
        return v;
    }

    private TextView circleButton(String value) {
        TextView v=text(value,15,Color.WHITE);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g=new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.rgb(22,27,33));
        g.setStroke(dp(2),Color.rgb(70,150,210));
        v.setBackground(g);
        return v;
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
