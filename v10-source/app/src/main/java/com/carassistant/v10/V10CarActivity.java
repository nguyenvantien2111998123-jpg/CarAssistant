
package com.carassistant.v10;

import android.Manifest;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.content.Intent;
import android.net.Uri;
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
import android.provider.Settings;
import android.text.TextUtils;
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
    private CarAssistantKeyboardController keyboardController;
    private FrameLayout appProjectionLayer;
    private TextureView appTextureView;
    private V10ProjectionSession backgroundSession;
    private ComponentName activeBackgroundTarget;
    private ComponentName selectedAppTarget;
    private TextView selectedAppLabel;
    private TextView dashboardVoiceButton;
    private ImageView mediaArtwork;
    private TextView mediaTitle;
    private TextView mediaArtist;
    private TextView mediaPlayPause;
    private LinearLayout mediaCard;
    private final MediaSessionBridge.Listener mediaSessionListener = this::updateMediaCard;
    private TextView floatingVoiceButton;
    private TextView voiceTranscriptView;
    private VoiceAssistantController voiceAssistantController;
    private MiniAccessEngine miniAccessEngine;

    private TextView floatingTouchKey;
    private LinearLayout floatingMenu;
    private FrameLayout taskSwitcherLayer;
    private LinearLayout taskSwitcherList;
    private final ArrayList<ComponentName> projectedTaskHistory = new ArrayList<>();

    private boolean floatingDragging;
    private float floatingDownRawX;
    private float floatingDownRawY;
    private int floatingStartX;
    private int floatingStartY;
    private long floatingDownTime;

    private TextView clock;
    private TextView date;

    private boolean resumed;
    private boolean resumeCheckpointAttempted;
    private int resumeCheckpointRetries;

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

        MediaSessionBridge.addListener(mediaSessionListener);
        updateMediaCard(MediaSessionBridge.getSnapshot());
        handler.postDelayed(this::restoreSavedMediaCheckpoint, 900L);

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
                        public String openVoiceTask(String category, String query, String explicitProvider) {
                            if ("navigation".equals(category) || "nearby".equals(category)) {
                                AppInfo selectedApp = null;
                                if (explicitProvider != null && !explicitProvider.trim().isEmpty()) {
                                    selectedApp = findAppByVoice(explicitProvider);
                                } else {
                                    ComponentName configured = V10SessionStore.getDefaultVoiceApp(
                                            V10CarActivity.this, "navigation");
                                    if (configured != null) {
                                        selectedApp = findAppByVoice(appLabelForComponent(configured));
                                        if (selectedApp == null) {
                                            for (AppInfo app : apps) {
                                                if (configured.getPackageName().equals(app.component.getPackageName())) {
                                                    selectedApp = app;
                                                    break;
                                                }
                                            }
                                        }
                                    } else {
                                        String[] candidates = new String[]{"Google Maps", "Vietmap", "Navitel", "Maps"};
                                        for (String candidate : candidates) {
                                            selectedApp = findAppByVoice(candidate);
                                            if (selectedApp != null) break;
                                        }
                                    }
                                }
                                if (selectedApp == null) return null;
                                launchProjection(selectedApp.component);
                                if (query != null && !query.trim().isEmpty() && session != null) {
                                    String mapUri = buildMapUri(selectedApp.component, query,
                                            "nearby".equals(category));
                                    if (mapUri != null) {
                                        session.launchViewIntentOnDisplay(
                                                selectedApp.component.getPackageName(), mapUri);
                                    }
                                }
                                return selectedApp.name;
                            }

                            if ("music".equals(category) || "video".equals(category)) {
                                ComponentName selected = null;
                                String selectedLabel = null;

                                // Media must be visible on the car-side secondary projection display
                                // (session/display 1), not the hidden ImageReader-only virtual display.
                                if (explicitProvider != null && !explicitProvider.trim().isEmpty()) {
                                    String normalizedProvider = VoiceCommandParser.normalize(explicitProvider);
                                    if ("youtube".equals(normalizedProvider)) {
                                        // "on YouTube" is the generic video-provider wording. Respect the
                                        // user's configured video app even when it is ReVanced/Morphe or
                                        // another selected variant; don't pick an arbitrary installed copy.
                                        ComponentName configured = V10SessionStore.getDefaultVoiceApp(
                                                V10CarActivity.this, "video");
                                        if (configured == null) {
                                            configured = V10SessionStore.getDefaultVoiceApp(
                                                    V10CarActivity.this, category);
                                        }
                                        if (configured != null) {
                                            selected = configured;
                                            selectedLabel = appLabelForComponent(configured);
                                        } else {
                                            AppInfo explicitApp = findAppByVoice("youtube");
                                            if (explicitApp == null) return null;
                                            selected = explicitApp.component;
                                            selectedLabel = explicitApp.name;
                                        }
                                    } else {
                                        // Explicit names such as YouTube Music Morphe, YouTube Morphe,
                                        // YouTube ReVanced and Zing MP3 resolve to that exact installed app.
                                        AppInfo explicitApp = findAppByVoice(explicitProvider);
                                        if (explicitApp == null) return null;
                                        selected = explicitApp.component;
                                        selectedLabel = explicitApp.name;
                                    }
                                } else {
                                    selected = V10SessionStore.getDefaultVoiceApp(
                                            V10CarActivity.this, category);
                                    if (selected != null) {
                                        selectedLabel = appLabelForComponent(selected);
                                    } else {
                                        String[] candidates = "music".equals(category)
                                                ? new String[]{"Zing MP3", "Spotify", "YouTube Music", "NhacCuaTui"}
                                                : new String[]{"YouTube"};
                                        for (String candidate : candidates) {
                                            AppInfo app = findAppByVoice(candidate);
                                            if (app != null) {
                                                selected = app.component;
                                                selectedLabel = app.name;
                                                break;
                                            }
                                        }
                                    }
                                }

                                if (selected == null || session == null) return null;
                                String mediaUri = null;
                                if (query != null && !query.trim().isEmpty()) {
                                    mediaUri = VoiceBackgroundTaskManager.buildSearchUri(
                                            selected, query.trim(), category);
                                    if (mediaUri == null) return null;
                                }

                                // Launch on the real projection surface, then send the search
                                // intent to that same display. Keep the phone UI out of this flow.
                                launchProjection(selected);
                                if (mediaUri != null) {
                                    dispatchMediaSearchOnProjection(selected, mediaUri, query.trim());
                                }
                                return selectedLabel == null ? selected.getPackageName() : selectedLabel;
                            }
                            if ("tv".equals(category)) {
                                AppInfo selectedApp = null;
                                if (explicitProvider != null && !explicitProvider.trim().isEmpty()) {
                                    selectedApp = findAppByVoice(explicitProvider);
                                } else {
                                    ComponentName configured = V10SessionStore.getDefaultVoiceApp(
                                            V10CarActivity.this, "tv");
                                    if (configured != null) {
                                        for (AppInfo app : apps) {
                                            if (configured.getPackageName().equals(app.component.getPackageName())) {
                                                selectedApp = app;
                                                break;
                                            }
                                        }
                                    } else {
                                        String[] candidates = new String[]{"VTV Go", "FPT Play", "TV"};
                                        for (String candidate : candidates) {
                                            selectedApp = findAppByVoice(candidate);
                                            if (selectedApp != null) break;
                                        }
                                    }
                                }
                                if (selectedApp == null) return null;
                                launchProjection(selectedApp.component);
                                if (query != null && !query.trim().isEmpty() && session != null) {
                                    session.launchSearchIntentOnDisplay(
                                            selectedApp.component.getPackageName(), query);
                                }
                                return selectedApp.name;
                            }

                            if (explicitProvider != null && !explicitProvider.trim().isEmpty()) {
                                return openAppByVoice(explicitProvider);
                            }

                            ComponentName configured = V10SessionStore.getDefaultVoiceApp(
                                    V10CarActivity.this, category);
                            if (configured != null) {
                                launchProjection(configured);
                                return appLabelForComponent(configured);
                            }

                            String[] candidates;
                            if ("navigation".equals(category)) {
                                candidates = new String[]{"Google Maps", "Vietmap", "Navitel", "Maps"};
                            } else {
                                candidates = new String[]{"VTV Go", "FPT Play", "TV"};
                            }
                            for (String candidate : candidates) {
                                String opened = openAppByVoice(candidate);
                                if (opened != null) return opened;
                            }
                            return null;
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

        SharedPreferences voicePrefs = getSharedPreferences("media_autoplay", MODE_PRIVATE);
        if (voicePrefs.getBoolean("voice_pending", false)) {
            voicePrefs.edit().putBoolean("voice_pending", false).apply();
            handler.postDelayed(this::handleSteeringVoice, 700L);
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
        tasks.setContentDescription("Các task đang hoạt động trên màn hình phụ");
        tasks.setOnClickListener(v->showTaskSwitcher());
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

        mediaCard = card();
        mediaCard.setOrientation(LinearLayout.HORIZONTAL);
        mediaCard.setGravity(Gravity.CENTER_VERTICAL);
        mediaCard.setPadding(dp(8),dp(5),dp(8),dp(5));

        mediaArtwork = new ImageView(this);
        mediaArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaArtwork.setContentDescription("Ảnh bìa media");
        mediaArtwork.setBackgroundColor(Color.rgb(34,42,50));
        mediaCard.addView(mediaArtwork,new LinearLayout.LayoutParams(dp(54),dp(54)));

        LinearLayout mediaInfo = new LinearLayout(this);
        mediaInfo.setOrientation(LinearLayout.VERTICAL);
        mediaInfo.setGravity(Gravity.CENTER_VERTICAL);
        mediaInfo.setPadding(dp(10),0,dp(6),0);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0,-1,1f);
        mediaCard.addView(mediaInfo,infoParams);

        mediaTitle = text("Chưa có media đang phát",14,Color.WHITE);
        mediaTitle.setSingleLine(true);
        mediaTitle.setEllipsize(TextUtils.TruncateAt.END);
        mediaInfo.addView(mediaTitle,new LinearLayout.LayoutParams(-1,dp(25)));

        mediaArtist = text("Mở YouTube, Zing MP3 hoặc ứng dụng nghe nhạc",11,Color.LTGRAY);
        mediaArtist.setSingleLine(true);
        mediaArtist.setEllipsize(TextUtils.TruncateAt.END);
        mediaInfo.addView(mediaArtist,new LinearLayout.LayoutParams(-1,dp(20)));

        TextView previous = buttonText("⏮");
        previous.setContentDescription("Bài trước");
        mediaCard.addView(previous,new LinearLayout.LayoutParams(dp(34),dp(48)));
        previous.setOnClickListener(v -> {
            if (ensureMediaAccess()) MediaSessionBridge.skipPrevious();
        });

        TextView rewind = buttonText("↶15");
        rewind.setTextSize(10);
        rewind.setContentDescription("Tua lùi 15 giây");
        mediaCard.addView(rewind,new LinearLayout.LayoutParams(dp(36),dp(48)));
        rewind.setOnClickListener(v -> {
            if (ensureMediaAccess()) MediaSessionBridge.seekBy(-15000L);
        });

        mediaPlayPause = buttonText("▶");
        mediaPlayPause.setContentDescription("Phát hoặc tạm dừng");
        mediaCard.addView(mediaPlayPause,new LinearLayout.LayoutParams(dp(42),dp(48)));
        mediaPlayPause.setOnClickListener(v -> {
            if (ensureMediaAccess()) MediaSessionBridge.togglePlayPause();
        });

        TextView forward = buttonText("15↷");
        forward.setTextSize(10);
        forward.setContentDescription("Tua tới 15 giây");
        mediaCard.addView(forward,new LinearLayout.LayoutParams(dp(36),dp(48)));
        forward.setOnClickListener(v -> {
            if (ensureMediaAccess()) MediaSessionBridge.seekBy(15000L);
        });

        TextView next = buttonText("⏭");
        next.setContentDescription("Bài tiếp");
        mediaCard.addView(next,new LinearLayout.LayoutParams(dp(34),dp(48)));
        next.setOnClickListener(v -> {
            if (ensureMediaAccess()) MediaSessionBridge.skipNext();
        });

        View.OnClickListener openPlayer = v -> openActiveMediaOnProjection();
        mediaArtwork.setOnClickListener(openPlayer);
        mediaTitle.setOnClickListener(openPlayer);
        mediaArtist.setOnClickListener(openPlayer);
        mediaCard.setOnClickListener(openPlayer);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(78),1f);
        mp.setMargins(dp(12),0,0,0);
        bottom.addView(mediaCard,mp);
    }


    /** Called from the Android Auto media card and the steering-key bridge. */
    public void handleOpenActiveMediaOnProjection() {
        handler.post(this::openActiveMediaOnProjection);
    }

    private void openActiveMediaOnProjection() {
        if (!ensureMediaAccess()) return;
        MediaSessionBridge.Snapshot state = MediaSessionBridge.getSnapshot();
        if (state == null || TextUtils.isEmpty(state.packageName)) {
            Toast.makeText(this, "Chưa có ứng dụng media đang hoạt động.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(state.packageName);
            if (launch == null || launch.getComponent() == null) {
                Toast.makeText(this, "Không tìm thấy màn hình mở của ứng dụng media.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            launchProjection(launch.getComponent());
        } catch (RuntimeException error) {
            Toast.makeText(this, "Không thể mở ứng dụng media trên màn hình phụ.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private boolean ensureMediaAccess() {
        if (MediaSessionBridge.hasAccess(this)) return true;
        Toast.makeText(this,
                "Hãy bật quyền Notification Access cho CarAsistant để đồng bộ media",
                Toast.LENGTH_LONG).show();
        MediaSessionBridge.requestAccess(this);
        return false;
    }

    private void updateMediaCard(MediaSessionBridge.Snapshot state) {
        if (mediaTitle == null || mediaArtist == null || mediaPlayPause == null) return;
        if (!MediaSessionBridge.hasAccess(this)) {
            mediaTitle.setText("Bật quyền media");
            mediaArtist.setText("Chạm để cấp quyền đồng bộ trình phát");
            mediaPlayPause.setText("⚙");
            if (mediaArtwork != null) mediaArtwork.setImageDrawable(null);
            return;
        }
        if (state == null || TextUtils.isEmpty(state.packageName)) {
            mediaTitle.setText("Chưa có media đang phát");
            mediaArtist.setText("Mở YouTube, Zing MP3 hoặc ứng dụng nghe nhạc");
            mediaPlayPause.setText("▶");
            if (mediaArtwork != null) mediaArtwork.setImageDrawable(null);
            return;
        }
        mediaTitle.setText(TextUtils.isEmpty(state.title) ? "Đang phát media" : state.title);
        mediaArtist.setText(TextUtils.isEmpty(state.artist) ? state.packageName : state.artist);
        mediaPlayPause.setText(state.playing ? "⏸" : "▶");
        if (mediaArtwork != null) {
            if (state.artwork != null) mediaArtwork.setImageBitmap(state.artwork);
            else mediaArtwork.setImageDrawable(null);
        }
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
        miniAccessEngine = new MiniAccessEngine(this, appTextureView, session);

        createFloatingTouchKey();

        keyboardController = new CarAssistantKeyboardController(
                root, backgroundSession, session);
        keyboardController.start();
    }


private void launchProjection(ComponentName component) {

    if (keyboardController != null) keyboardController.hide();

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
    rememberProjectedTask(component);
}

    private void restoreSavedMediaCheckpoint() {
        if (!resumed || resumeCheckpointAttempted || session == null
                || resumeCheckpointRetries >= 3) return;
        SharedPreferences prefs = getSharedPreferences("carassistant_media_resume", MODE_PRIVATE);
        boolean valid = prefs.getBoolean("resume_valid", false);
        boolean wasPlaying = prefs.getBoolean("resume_was_playing", false);
        long savedAt = prefs.getLong("resume_saved_at", 0L);
        if (!valid || !wasPlaying || savedAt <= 0L
                || System.currentTimeMillis() - savedAt > 7L * 24L * 60L * 60L * 1000L) return;

        String packageName = prefs.getString("resume_media_package",
                prefs.getString("last_media_package", ""));
        String title = prefs.getString("resume_title", "");
        String artist = prefs.getString("resume_artist", "");
        long position = Math.max(0L, prefs.getLong("resume_position", 0L));
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(title)) return;
        String query = title;
        if (!TextUtils.isEmpty(artist) && !artist.equalsIgnoreCase(packageName)
                && !artist.equalsIgnoreCase(title)) {
            query = title + " " + artist;
        }

        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null || launch.getComponent() == null) return;
        String category = com.carassistant.v10.voice.MediaSearchUriBuilder
                .isYouTubeVideoPackage(packageName) ? "video" : "music";
        String uri = com.carassistant.v10.voice.MediaSearchUriBuilder
                .build(packageName, query, category);
        if (TextUtils.isEmpty(uri)) {
            android.util.Log.w("CarAssistantMedia", "No safe restore search URI for " + packageName);
            return;
        }

        resumeCheckpointAttempted = true;
        resumeCheckpointRetries++;
        ComponentName target = launch.getComponent();
        launchProjection(target);
        // This is a restore request even when the saved position is exactly 0 ms.
        // Keep it on the existing display-scoped Accessibility path and do not start
        // MiniAccessEngine in parallel with the restore controller.
        dispatchMediaSearchOnProjection(target, uri, query, position, true);
        android.util.Log.i("CarAssistantMedia", "Restoring saved media on projection: "
                + packageName + " / " + query + " @ " + position + "ms");
    }

/**
 * Wait until the selected app is attached to display 2 before sending its search URI.
 * Some media apps otherwise reuse an existing phone task during the initial launch race.
 */
private void dispatchMediaSearchOnProjection(ComponentName target, String uri, String query) {
    dispatchMediaSearchOnProjection(target, uri, query, 0L, false);
}

private void dispatchMediaSearchOnProjection(
        ComponentName target, String uri, String query, long resumePositionMs) {
    dispatchMediaSearchOnProjection(target, uri, query, resumePositionMs,
            resumePositionMs > 0L);
}

private void scheduleMediaResumeRetry() {
    if (!resumed || resumeCheckpointRetries >= 3) return;
    resumeCheckpointAttempted = false;
    handler.postDelayed(this::restoreSavedMediaCheckpoint, 1200L);
}

private void dispatchMediaSearchOnProjection(
        ComponentName target, String uri, String query, long resumePositionMs,
        boolean restoringCheckpoint) {
    if (target == null || uri == null || query == null || session == null) return;
    final long deadline = SystemClock.uptimeMillis() + 5000L;
    Runnable dispatch = new Runnable() {
        @Override public void run() {
            if (session == null || !target.equals(activeTarget)) return;
            if (!session.isActive() || session.getDisplayId() <= 0) {
                if (SystemClock.uptimeMillis() < deadline) {
                    handler.postDelayed(this, 250L);
                } else if (restoringCheckpoint) {
                    android.util.Log.w("CarAssistantMedia",
                            "Projection display was not ready for media restore; retrying");
                    scheduleMediaResumeRetry();
                } else {
                    Toast.makeText(V10CarActivity.this,
                            "Không thể mở kết quả tìm kiếm trên màn hình chiếu.",
                            Toast.LENGTH_SHORT).show();
                }
                return;
            }

            SharedPreferences mediaPrefs = getSharedPreferences("media_autoplay", MODE_PRIVATE);
            // The same display-scoped automation handles voice search and power-loss resume.
            if (restoringCheckpoint) {
                MediaPlaybackAccessibilityService.prepareResume(
                        V10CarActivity.this, target.getPackageName(), query,
                        session.getDisplayId(), resumePositionMs);
            } else {
                mediaPrefs.edit()
                        .putString("target_package", target.getPackageName())
                        .putString("query", query)
                        .putString("phase", "search")
                        .putInt("target_display_id", session.getDisplayId())
                        .putLong("started_at", System.currentTimeMillis())
                        .putBoolean("mini_engine_active", false)
                        .apply();
            }

            // Delay a little after the launcher intent so the provider's own task is
            // already on the target display; ACTION_VIEW then starts an independent task there.
            boolean accepted = session.launchMediaViewIntentOnDisplay(target.getPackageName(), uri);
            if (!accepted) {
                getSharedPreferences("media_autoplay", MODE_PRIVATE).edit()
                        .remove("target_package").remove("query")
                        .remove("phase").remove("target_display_id").remove("started_at")
                        .putBoolean("mini_engine_active", false).apply();
                if (restoringCheckpoint) scheduleMediaResumeRetry();
            } else if (miniAccessEngine != null && !restoringCheckpoint) {
                miniAccessEngine.start();
            }
        }
    };
    handler.postDelayed(dispatch, 650L);
}

private boolean isMediaAutoPlayAccessibilityEnabled() {
    String enabledServices = Settings.Secure.getString(
            getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    if (TextUtils.isEmpty(enabledServices)) return false;

    ComponentName expected = new ComponentName(
            this, MediaPlaybackAccessibilityService.class);
    TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
    splitter.setString(enabledServices);
    for (String entry : splitter) {
        ComponentName enabled = ComponentName.unflattenFromString(entry);
        if (expected.equals(enabled)) return true;
    }
    return false;
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

        if (keyboardController != null) keyboardController.hide();

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

    if (keyboardController != null) keyboardController.hide();

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
                                // The floating key is a child of root, so clamp its margins
                                // against the same coordinate space used by its FrameLayout.LayoutParams.
                                int width = root.getWidth();
                                int height = root.getHeight();
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
        floatingMenu.setOrientation(LinearLayout.HORIZONTAL);
        floatingMenu.setPadding(dp(8), dp(8), dp(8), dp(8));

        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setColor(Color.rgb(15, 20, 26));
        menuBg.setCornerRadius(dp(16));
        menuBg.setStroke(dp(1), Color.rgb(65, 85, 105));
        floatingMenu.setBackground(menuBg);
        floatingMenu.setElevation(dp(14));

        addFloatingMenuButton("‹", "Back", () -> {
            handleControlBack();
            hideFloatingMenu();
        });

        addFloatingMenuButton("⌂", "Home", () -> {
            handleControlHome();
            hideFloatingMenu();
        });

        addFloatingMenuButton("▣", "Task", () -> {
            hideFloatingMenu();
            showTaskSwitcher();
        });

        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(-2, -2,
                        Gravity.TOP | Gravity.END);
        params.setMargins(0, dp(70), dp(18), 0);
        root.addView(floatingMenu, params);
        floatingMenu.bringToFront();
        floatingTouchKey.bringToFront();
    }

private void addFloatingMenuButton(String glyph, String label, Runnable action) {
    TextView button = new TextView(this);
    button.setText(glyph);
    button.setContentDescription(label);
    button.setTextColor(Color.WHITE);
    button.setTextSize(24);
    button.setGravity(Gravity.CENTER);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(Color.rgb(28, 38, 48));
    bg.setCornerRadius(dp(10));
    button.setBackground(bg);
    button.setPadding(dp(10), 0, dp(10), 0);
    button.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(52), dp(52));
    params.setMargins(dp(3), 0, dp(3), 0);
    floatingMenu.addView(button, params);
}

private void hideFloatingMenu() {
    if (floatingMenu == null) return;
    root.removeView(floatingMenu);
    floatingMenu = null;
}

private void rememberProjectedTask(ComponentName component) {
    if (component == null) return;
    projectedTaskHistory.remove(component);
    projectedTaskHistory.add(0, component);
    while (projectedTaskHistory.size() > 12) {
        projectedTaskHistory.remove(projectedTaskHistory.size() - 1);
    }
}

private void showTaskSwitcher() {
    if (root == null) return;
    if (taskSwitcherLayer != null) {
        root.removeView(taskSwitcherLayer);
        taskSwitcherLayer = null;
    }

    taskSwitcherLayer = new FrameLayout(this);
    taskSwitcherLayer.setBackgroundColor(Color.rgb(10, 14, 19));
    taskSwitcherLayer.setElevation(dp(30));

    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(18), dp(14), dp(18), dp(14));

    TextView heading = text("TASK TRÊN MÀN HÌNH PHỤ", 18, Color.WHITE);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(48)));

    TextView close = text("✕", 22, Color.WHITE);
    close.setGravity(Gravity.CENTER);
    close.setContentDescription("Đóng danh sách task");
    FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
    panel.addView(close, closeParams);
    close.setOnClickListener(v -> hideTaskSwitcher());

    ScrollView scroll = new ScrollView(this);
    taskSwitcherList = new LinearLayout(this);
    taskSwitcherList.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(taskSwitcherList, new ScrollView.LayoutParams(-1, -2));
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

    if (projectedTaskHistory.isEmpty() && activeTarget != null) {
        rememberProjectedTask(activeTarget);
    }

    if (projectedTaskHistory.isEmpty()) {
        TextView empty = text("Chưa có task nào được mở qua CarAsistant.", 14, Color.LTGRAY);
        empty.setGravity(Gravity.CENTER);
        taskSwitcherList.addView(empty, new LinearLayout.LayoutParams(-1, dp(100)));
    } else {
        for (ComponentName component : new ArrayList<>(projectedTaskHistory)) {
            addProjectedTaskRow(component);
        }
    }

    taskSwitcherLayer.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    root.addView(taskSwitcherLayer, new FrameLayout.LayoutParams(-1, -1));
    taskSwitcherLayer.bringToFront();
    if (floatingTouchKey != null) floatingTouchKey.bringToFront();
}

private void addProjectedTaskRow(ComponentName component) {
    if (taskSwitcherList == null || component == null) return;
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(12), dp(6), dp(6), dp(6));

    GradientDrawable rowBg = new GradientDrawable();
    rowBg.setColor(component.equals(activeTarget) ? Color.rgb(35, 65, 83) : Color.rgb(27, 34, 42));
    rowBg.setCornerRadius(dp(12));
    row.setBackground(rowBg);

    TextView appName = text(appLabelForComponent(component), 15, Color.WHITE);
    appName.setGravity(Gravity.CENTER_VERTICAL);
    appName.setMaxLines(2);
    row.addView(appName, new LinearLayout.LayoutParams(0, dp(58), 1f));
    appName.setOnClickListener(v -> {
        hideTaskSwitcher();
        launchProjection(component);
    });

    TextView close = text("×", 28, Color.WHITE);
    close.setGravity(Gravity.CENTER);
    close.setContentDescription("Đóng task " + appLabelForComponent(component));
    GradientDrawable closeBg = new GradientDrawable();
    closeBg.setShape(GradientDrawable.OVAL);
    closeBg.setColor(Color.rgb(180, 54, 54));
    close.setBackground(closeBg);
    LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(42), dp(42));
    closeParams.setMargins(dp(8), 0, 0, 0);
    row.addView(close, closeParams);
    close.setOnClickListener(v -> closeProjectedTask(component));

    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
    rowParams.setMargins(0, dp(4), 0, dp(4));
    taskSwitcherList.addView(row, rowParams);
}

private void closeProjectedTask(ComponentName component) {
    if (component == null) return;
    if (session == null) {
        Toast.makeText(this, "Không thể đóng task: phiên chiếu chưa sẵn sàng.", Toast.LENGTH_SHORT).show();
        return;
    }

    // Remove the exact Android task only after verifying it belongs to the projection display.
    // Never force-stop the package because it may also have a separate task on the phone.
    session.closeTaskOnDisplay(component, closed -> {
        if (!closed) {
            Toast.makeText(this,
                    "Không thể đóng ứng dụng; CarAsistant đã giữ lại task trong danh sách.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        projectedTaskHistory.remove(component);
        if (component.equals(activeTarget)) {
            session.setTarget(null);
            V10SessionStore.clearTarget(this);
            activeTarget = null;
            if (appProjectionLayer != null) appProjectionLayer.setVisibility(View.GONE);
            returnToLauncher();
        }
        showTaskSwitcher();
    });
}

private void hideTaskSwitcher() {
    if (taskSwitcherLayer == null) return;
    root.removeView(taskSwitcherLayer);
    taskSwitcherLayer = null;
    taskSwitcherList = null;
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

    /** Called by the LSPosed steering-key bridge when Next is double-pressed. */
    public void handleSteeringVoice() {
        handler.post(this::activateVoiceAssistant);
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
        voiceAssistantController.toggleListening();
    }

    private String buildMapUri(ComponentName component, String query, boolean nearby) {
        if (component == null || query == null || query.trim().isEmpty()) return null;
        String encoded = Uri.encode(query.trim());
        if ("com.google.android.apps.maps".equals(component.getPackageName())) {
            if (nearby) return "geo:0,0?q=" + encoded;
            return "google.navigation:q=" + encoded;
        }
        // Many navigation apps accept geo: search intents; the target app may still
        // require confirmation or may not implement this generic Android URI.
        return "geo:0,0?q=" + encoded;
    }

    private String appLabelForComponent(ComponentName component) {
        if (component == null) return null;
        for (AppInfo app : apps) {
            if (component.getPackageName().equals(app.component.getPackageName())) return app.name;
        }
        return component.getPackageName();
    }

    private AppInfo findAppByVoice(String spokenName) {
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

            // Prefer the exact requested app family before generic YouTube matching.
            // This avoids selecting the first installed YouTube variant when the user says
            // "Morphe" or "ReVanced" explicitly.
            if (query.contains("morphe") && pkg.contains("morphe")) {
                score = Math.max(score, 120);
            }
            if (query.contains("revanced") && pkg.contains("revanced")) {
                score = Math.max(score, 120);
            }
            if (query.contains("youtube music")) {
                boolean musicPackage = com.carassistant.v10.voice.MediaSearchUriBuilder
                        .isYouTubeMusicPackage(pkg);
                if (musicPackage) score = Math.max(score, 115);
                else if (pkg.contains("youtube")) score = Math.max(score, 60);
            } else if (query.equals("youtube") && pkg.contains("youtube")) {
                score = Math.max(score, 90);
            }
            if ((query.equals("maps") || query.equals("google maps"))
                    && (pkg.equals("com.google.android.apps.maps") || label.contains("maps"))) {
                score = Math.max(score, 95);
            }
            if (query.contains("spotify") && pkg.contains("spotify")) score = Math.max(score, 110);
            if (query.contains("zing mp3") && (pkg.contains("zing") || label.contains("zing mp3"))) {
                score = Math.max(score, 110);
            }
            if (query.contains("vietmap") && (pkg.contains("vietmap") || label.contains("vietmap"))) {
                score = Math.max(score, 95);
            }
            if (query.contains("navitel") && (pkg.contains("navitel") || label.contains("navitel"))) {
                score = Math.max(score, 95);
            }

            if (score > bestScore) {
                bestScore = score;
                best = app;
            }
        }

        if (best == null || bestScore < 60) return null;
        return best;
    }

    private String openAppByVoice(String spokenName) {
        AppInfo best = findAppByVoice(spokenName);
        if (best == null) return null;
        launchProjection(best.component);
        return best.name;
    }

    @Override
    public void onPause() {

        MediaSessionBridge.removeListener(mediaSessionListener);

        if (keyboardController != null) keyboardController.hide();
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
        // A reconnect may reuse this CarActivity instance; allow the durable
        // checkpoint to be restored again on its next foreground transition.
        resumeCheckpointAttempted = false;
        resumeCheckpointRetries = 0;

        handler.removeCallbacks(
                clockTicker);

        handler.removeCallbacks(
                targetPoller);

        /* Display Core V1: Activity stop is not task close. */
        super.onStop();
    }

    @Override
    public void onDestroy() {

        hideTaskSwitcher();
        hideFloatingMenu();
        if (miniAccessEngine != null) {
            miniAccessEngine.close();
            miniAccessEngine = null;
        }
        if (keyboardController != null) {
            keyboardController.stop();
            keyboardController = null;
        }
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
