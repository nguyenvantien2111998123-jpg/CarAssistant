package com.carassistant.v10;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Giao diện buồng lái xe thông minh (Automotive Cockpit HUD) cho Android Auto.
 * Tông màu Xanh dương Neon / Deep Electric Blue, giao diện màn hình xe ảo trực quan 100% mới.
 */
public final class MainActivity extends Activity {

    // Bảng màu Xanh Dương Công Nghệ (Cyber Blue & Deep Navy Palette)
    static final int COLOR_BG = 0xFF080E18;               // Nền tối Navy vũ trụ
    static final int COLOR_SURFACE = 0xFF0F1B2D;          // Khung viền buồng lái
    static final int COLOR_SURFACE_VARIANT = 0xFF14243B;  // Mặt kính màn hình xe / Ô con
    static final int COLOR_SURFACE_SELECTED = 0xFF163254; // Ô đang kích hoạt
    static final int COLOR_BORDER = 0xFF1E385B;           // Viền kim loại tối
    static final int COLOR_CYAN_ACCENT = 0xFF00E5FF;      // Xanh Neon Cyan (Chủ đạo)
    static final int COLOR_BLUE_ACCENT = 0xFF38BDF8;      // Xanh dương sáng
    static final int COLOR_BLUE_DEEP = 0xFF2563EB;        // Xanh Cobalt đậm
    static final int COLOR_TEXT_PRIMARY = 0xFFF0F8FF;     // Trắng băng
    static final int COLOR_TEXT_SECONDARY = 0xFF94A3B8;   // Xám xanh nhạt
    static final int COLOR_TEXT_MUTED = 0xFF5C728E;       // Xám chữ mờ
    static final int COLOR_SUCCESS = 0xFF10B981;          // Xanh ngọc thành công
    static final int COLOR_WARNING = 0xFFF59E0B;          // Vàng hổ phách

    private static final String COMMUNITY_URL = "https://www.facebook.com/groups/2327824764621369";
    private static final String GITHUB_URL = "https://github.com/xuanthanhtn96/AACast-Doudou";
    private static final String PREF_COMMUNITY_DONE = "community_prompt_done";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    int layoutMode;
    int hiddenPane;
    TileOrder tileOrder;
    final AppEntry[] panelApps = new AppEntry[3];

    private Boolean rootGranted = null;

    private FrameLayout rootContainer;
    private ScrollView mainScrollView;
    private LinearLayout contentContainer;

    // View mô phỏng màn hình xe tương tác trực tiếp
    private CarScreenStageView carStageView;
    private LinearLayout modeStripContainer;
    private LinearLayout slotStripContainer;
    private LinearLayout pairStripContainer;
    private TextView rootBadgeText;
    private View rootBadgeView;

    // Overlay chọn ứng dụng
    private FrameLayout pickerOverlay;
    private FrameLayout communityOverlay;
    private EditText pickerSearch;
    private LinearLayout pickerListContainer;
    private int targetPickerPane = 0;
    private List<AppEntry> cachedAppList = null;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        prefs = getSharedPreferences("aacast_doudou", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("setup_complete", true).apply();

        loadData();
        buildCockpitUi();
        checkRootAsync();
        handler.post(this::maybeShowCommunityModal);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    (OnBackInvokedCallback) () -> {
                        if (dismissCommunity(false)) {
                            return;
                        }
                        if (!dismissPicker()) {
                            finish();
                        }
                    });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
        refreshAll();
    }

    @Override
    public void onBackPressed() {
        if (dismissCommunity(false)) {
            return;
        }
        if (dismissPicker()) {
            return;
        }
        super.onBackPressed();
    }

    private void loadData() {
        layoutMode = Math.max(0, Math.min(5, prefs.getInt("layout_mode_v3", 0)));
        hiddenPane = Math.max(0, Math.min(2, prefs.getInt("hidden_pane_v3", 0)));
        tileOrder = new TileOrder(prefs.getString("tile_order", "0,1,2"));

        for (int i = 0; i < 3; i++) {
            ComponentName comp = ComponentName.unflattenFromString(prefs.getString("app_" + i, ""));
            if (comp == null || getPackageName().equals(comp.getPackageName())) {
                panelApps[i] = new AppEntry(getString(R.string.choose_app_empty), "", null);
            } else {
                panelApps[i] = new AppEntry(prefs.getString("label_" + i, comp.getPackageName()),
                        comp.getPackageName(), comp);
            }
        }
    }

    private void syncToCar() {
        RootView carRoot = RootView.getCurrent();
        if (carRoot != null) {
            carRoot.reloadSettings();
        }
    }

    // ------------------------------------------------------------------- Giao diện Cockpit

    private void buildCockpitUi() {
        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(COLOR_BG);

        mainScrollView = new ScrollView(this);
        mainScrollView.setVerticalScrollBarEnabled(false);

        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(dp(16), dp(12), dp(16), dp(28));

        mainScrollView.addView(contentContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rootContainer.addView(mainScrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Tai thỏ / Notch và Navigation bar safe insets
        rootContainer.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = 0, top = 0, right = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets cut = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = cut.left;
                top = cut.top;
                right = cut.right;
                bottom = cut.bottom;
            } else {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    left = cutout.getSafeInsetLeft();
                    top = cutout.getSafeInsetTop();
                    right = cutout.getSafeInsetRight();
                    bottom = cutout.getSafeInsetBottom();
                }
            }
            contentContainer.setPadding(dp(16) + left, dp(12) + top, dp(16) + right, dp(28) + bottom);
            return insets;
        });

        // 1. Cockpit Top HUD Bar
        buildTopHudBar();

        // 2. HERO: Màn hình xe ảo tương tác trực tiếp (Car Screen Stage)
        buildCarScreenStage();

        // 3. Dải chọn kiểu chia ngang (Mode Pills Carousel)
        buildModePills();

        // 4. Bảng điều khiển 3 ô (Slots Console)
        buildSlotsConsole();

        // 5. Trung tâm tác vụ nhanh (Cockpit Command Hub)
        buildCommandHub();

        // 6. Trợ giúp & Kết nối xe
        buildGuideHud();

        // 7. Liên kết cộng đồng & mã nguồn
        buildFooterLinks();

        setContentView(rootContainer);
    }

    // ------------------------------------------------------------------- 1. Top HUD Bar

    private void buildTopHudBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(4), 0, dp(14));

        // Logo + Branding HUD
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_aacast);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout brandingCol = new LinearLayout(this);
        brandingCol.setOrientation(LinearLayout.VERTICAL);
        brandingCol.setPadding(dp(10), 0, 0, 0);

        TextView brand = new TextView(this);
        brand.setText("AACAST COCKPIT");
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        brand.setTextColor(COLOR_CYAN_ACCENT);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brandingCol.addView(brand);

        TextView sub = new TextView(this);
        sub.setText("Android Auto Display Center");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(COLOR_TEXT_MUTED);
        brandingCol.addView(sub);

        bar.addView(brandingCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Root status chip
        rootBadgeView = createCyberChip("ROOT: CHECK", COLOR_CYAN_ACCENT, v -> checkRootAsync());
        rootBadgeText = (TextView) ((LinearLayout) rootBadgeView).getChildAt(0);
        bar.addView(rootBadgeView);

        contentContainer.addView(bar);
    }

    // ------------------------------------------------------------------- 2. Car Screen Stage

    private void buildCarScreenStage() {
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackground(createRoundedDrawable(COLOR_SURFACE, dp(18), COLOR_BORDER, dp(2)));
        frame.setPadding(dp(12), dp(12), dp(12), dp(12));

        // Header trên viền màn hình xe
        LinearLayout bezelHeader = new LinearLayout(this);
        bezelHeader.setGravity(Gravity.CENTER_VERTICAL);
        bezelHeader.setPadding(dp(4), 0, dp(4), dp(8));

        View liveDot = new View(this);
        liveDot.setBackground(createPillDrawable(COLOR_CYAN_ACCENT, COLOR_CYAN_ACCENT));
        bezelHeader.addView(liveDot, new LinearLayout.LayoutParams(dp(7), dp(7)));

        TextView title = new TextView(this);
        title.setText(" MÀN HÌNH XE TRỰC TIẾP (CHẠM VÀO Ô ĐỂ ĐỔI APP)");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setTextColor(COLOR_TEXT_SECONDARY);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bezelHeader.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView modeBadge = new TextView(this);
        modeBadge.setText(getString(layoutName(layoutMode)));
        modeBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        modeBadge.setTextColor(COLOR_CYAN_ACCENT);
        bezelHeader.addView(modeBadge);

        frame.addView(bezelHeader);

        // Màn hình ảo tương tác
        carStageView = new CarScreenStageView(this);
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        frame.addView(carStageView, stageLp);

        contentContainer.addView(frame);
    }

    // ------------------------------------------------------------------- 3. Mode Pills Carousel

    private void buildModePills() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(14), 0, 0);

        TextView label = new TextView(this);
        label.setText("CHỌN KIỂU CHIA MÀN HÌNH XE:");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(COLOR_TEXT_SECONDARY);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        section.addView(label);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(0, dp(8), 0, 0);

        modeStripContainer = new LinearLayout(this);
        modeStripContainer.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(modeStripContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        section.addView(scroll);
        contentContainer.addView(section);
        renderModePills();
    }

    private void renderModePills() {
        if (modeStripContainer == null) {
            return;
        }
        modeStripContainer.removeAllViews();

        int[] modes = {0, 1, 2, 3, 4, 5};
        String[] shortNames = {
                "2 Cột (Trái/Phải)",
                "2 Hàng (Trên/Dưới)",
                "3 Cột Dọc",
                "3 Hàng Ngang",
                "1 Lớn Trái + 2 Phải",
                "1 Lớn Trên + 2 Dưới"
        };

        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];
            boolean selected = (mode == layoutMode);

            Button pill = new Button(this);
            pill.setText((selected ? "● " : "") + shortNames[i]);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            pill.setTextColor(selected ? COLOR_BG : COLOR_TEXT_PRIMARY);
            pill.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            pill.setAllCaps(false);
            pill.setBackground(createRoundedDrawable(
                    selected ? COLOR_CYAN_ACCENT : COLOR_SURFACE_VARIANT,
                    dp(12),
                    selected ? COLOR_CYAN_ACCENT : COLOR_BORDER,
                    dp(1)
            ));
            pill.setPadding(dp(14), dp(8), dp(14), dp(8));
            pill.setOnClickListener(v -> {
                layoutMode = mode;
                prefs.edit().putInt("layout_mode_v3", layoutMode).apply();
                syncToCar();
                refreshAll();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            if (i > 0) {
                lp.leftMargin = dp(8);
            }
            modeStripContainer.addView(pill, lp);
        }
    }

    // ------------------------------------------------------------------- 4. Slots Console

    private void buildSlotsConsole() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createRoundedDrawable(COLOR_SURFACE, dp(16), COLOR_BORDER, dp(1)));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(14);
        card.setLayoutParams(cardLp);

        // Header + Nút Hoán đổi nhanh (Swap)
        LinearLayout topRow = new LinearLayout(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("VỊ TRÍ ỨNG DỤNG TRÊN XE");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(COLOR_CYAN_ACCENT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        topRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnSwap = createCyberButton("⇄ ĐỔI VỊ TRÍ", () -> {
            tileOrder.swap(0, 1);
            tileOrder.swap(1, 2);
            prefs.edit().putString("tile_order", tileOrder.serialize()).apply();
            syncToCar();
            Toast.makeText(this, R.string.swap_panels_hint, Toast.LENGTH_SHORT).show();
            refreshAll();
        });
        topRow.addView(btnSwap, new LinearLayout.LayoutParams(dp(110), dp(36)));
        card.addView(topRow);

        // Vùng chọn cặp hiển thị cho chế độ 2 ô
        pairStripContainer = new LinearLayout(this);
        pairStripContainer.setOrientation(LinearLayout.VERTICAL);
        pairStripContainer.setPadding(0, dp(8), 0, dp(4));
        card.addView(pairStripContainer);

        // Dải 3 ô
        slotStripContainer = new LinearLayout(this);
        slotStripContainer.setOrientation(LinearLayout.VERTICAL);
        slotStripContainer.setPadding(0, dp(6), 0, 0);
        card.addView(slotStripContainer);

        contentContainer.addView(card);
        renderSlots();
    }

    private void renderSlots() {
        if (slotStripContainer == null) {
            return;
        }
        slotStripContainer.removeAllViews();
        pairStripContainer.removeAllViews();

        boolean isTwoPane = (layoutMode == 0 || layoutMode == 1);

        if (isTwoPane) {
            pairStripContainer.setVisibility(View.VISIBLE);

            TextView pairLabel = new TextView(this);
            pairLabel.setText("Chọn cặp hiển thị:");
            pairLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            pairLabel.setTextColor(COLOR_TEXT_SECONDARY);
            pairLabel.setPadding(0, 0, 0, dp(4));
            pairStripContainer.addView(pairLabel);

            LinearLayout pairRow = new LinearLayout(this);
            pairRow.setOrientation(LinearLayout.HORIZONTAL);

            int[] candidates = {2, 0, 1};
            String[] pairLabels = {
                    panelApps[0].label + " + " + panelApps[1].label,
                    panelApps[1].label + " + " + panelApps[2].label,
                    panelApps[0].label + " + " + panelApps[2].label
            };

            for (int i = 0; i < candidates.length; i++) {
                final int candidate = candidates[i];
                boolean active = (candidate == hiddenPane);

                Button btn = new Button(this);
                btn.setText(pairLabels[i]);
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                btn.setTextColor(active ? COLOR_CYAN_ACCENT : COLOR_TEXT_SECONDARY);
                btn.setSingleLine(true);
                btn.setEllipsize(TextUtils.TruncateAt.END);
                btn.setAllCaps(false);
                btn.setBackground(createRoundedDrawable(
                        active ? COLOR_SURFACE_SELECTED : COLOR_SURFACE_VARIANT,
                        dp(8),
                        active ? COLOR_CYAN_ACCENT : COLOR_BORDER,
                        dp(active ? 2 : 1)
                ));
                btn.setOnClickListener(v -> {
                    hiddenPane = candidate;
                    prefs.edit().putInt("hidden_pane_v3", hiddenPane).apply();
                    syncToCar();
                    refreshAll();
                });

                LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
                if (i > 0) {
                    pLp.leftMargin = dp(6);
                }
                pairRow.addView(btn, pLp);
            }
            pairStripContainer.addView(pairRow);
        } else {
            pairStripContainer.setVisibility(View.GONE);
        }

        // Render từng slot ô
        for (int i = 0; i < 3; i++) {
            final int paneIndex = i;
            boolean isHidden = isTwoPane && (paneIndex == hiddenPane);

            LinearLayout slot = new LinearLayout(this);
            slot.setOrientation(LinearLayout.HORIZONTAL);
            slot.setGravity(Gravity.CENTER_VERTICAL);
            slot.setPadding(dp(12), dp(10), dp(12), dp(10));
            slot.setBackground(createRoundedDrawable(
                    isHidden ? 0xFF0B1422 : COLOR_SURFACE_VARIANT,
                    dp(12),
                    isHidden ? 0xFF16253C : COLOR_BORDER,
                    dp(1)
            ));
            slot.setAlpha(isHidden ? 0.55f : 1.0f);
            slot.setClickable(true);
            slot.setFocusable(true);
            slot.setOnClickListener(v -> openAppPicker(paneIndex));

            // Icon
            ImageView iconView = new ImageView(this);
            iconView.setImageDrawable(getAppIcon(panelApps[paneIndex].pkg));
            slot.addView(iconView, new LinearLayout.LayoutParams(dp(36), dp(36)));

            // Text
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(10), 0, dp(10), 0);

            TextView slotTag = new TextView(this);
            slotTag.setText("Ô " + (paneIndex + 1) + (isHidden ? " • " + getString(R.string.panel_hidden_notice) : ""));
            slotTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            slotTag.setTextColor(isHidden ? COLOR_TEXT_MUTED : COLOR_BLUE_ACCENT);
            slotTag.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            col.addView(slotTag);

            TextView name = new TextView(this);
            name.setText(panelApps[paneIndex].label);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            name.setTextColor(COLOR_TEXT_PRIMARY);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(name);

            slot.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // Nút đổi
            Button changeBtn = createCyberButton(getString(R.string.choose_app), () -> openAppPicker(paneIndex));
            changeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            slot.addView(changeBtn, new LinearLayout.LayoutParams(dp(70), dp(34)));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            slotStripContainer.addView(slot, lp);
        }
    }

    // ------------------------------------------------------------------- 5. Command Hub

    private void buildCommandHub() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createRoundedDrawable(COLOR_SURFACE, dp(16), COLOR_BORDER, dp(1)));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(14);
        card.setLayoutParams(cardLp);

        TextView title = new TextView(this);
        title.setText("ĐIỀU KHIỂN & CÔNG CỤ NHANH");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(COLOR_CYAN_ACCENT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(10));
        card.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button btnReset = createCyberButton("↺ ĐẶT LẠI TỈ LỆ", () -> {
            float d1 = LayoutMath.isLinearThreeSplit(layoutMode) ? 0.33333334f : 0.5f;
            float d2 = LayoutMath.isLinearThreeSplit(layoutMode) ? 0.6666667f : 0.5f;
            prefs.edit()
                    .putFloat("ratio_first_v3_" + layoutMode, d1)
                    .putFloat("ratio_second_v3_" + layoutMode, d2)
                    .apply();
            syncToCar();
            Toast.makeText(this, R.string.reset_ratios_done, Toast.LENGTH_SHORT).show();
            carStageView.invalidate();
        });
        row.addView(btnReset, new LinearLayout.LayoutParams(0, dp(42), 1f));

        View s1 = new View(this);
        row.addView(s1, new LinearLayout.LayoutParams(dp(8), 1));

        Button btnRestartAA = createCyberButton("⚡ RESTART AA", this::restartGearheadAsync);
        row.addView(btnRestartAA, new LinearLayout.LayoutParams(0, dp(42), 1f));

        View s2 = new View(this);
        row.addView(s2, new LinearLayout.LayoutParams(dp(8), 1));

        Button btnOpenAA = createCyberButton("⚙ CÀI ĐẶT AA", this::openAndroidAutoSettings);
        row.addView(btnOpenAA, new LinearLayout.LayoutParams(0, dp(42), 1f));

        card.addView(row);
        contentContainer.addView(card);
    }

    // ------------------------------------------------------------------- 6. Guide HUD

    private void buildGuideHud() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createRoundedDrawable(COLOR_SURFACE, dp(16), COLOR_BORDER, dp(1)));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(14);
        card.setLayoutParams(cardLp);

        TextView title = new TextView(this);
        title.setText("HƯỚNG DẪN KẾT NỐI XE");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(COLOR_CYAN_ACCENT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(8));
        card.addView(title);

        addGuideLine(card, "1. LSPosed: Bật module Car Assistant V10, chọn scope Android Auto + System Framework.");
        addGuideLine(card, "2. Quyền Root: Đảm bảo cấp quyền vĩnh viễn (su) trong Magisk / KernelSU / APatch.");
        addGuideLine(card, "3. Mở trên xe: Cắm dây / nối không dây, chọn Car Assistant V10 trên màn hình xe.");
        addGuideLine(card, "4. Kéo chia kích thước: Trên xe có thể kéo vạch giữa để tuỳ biến ô theo ý muốn.");

        contentContainer.addView(card);
    }

    private void addGuideLine(LinearLayout parent, String text) {
        TextView line = new TextView(this);
        line.setText(text);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        line.setTextColor(COLOR_TEXT_SECONDARY);
        line.setPadding(0, dp(3), 0, dp(3));
        parent.addView(line);
    }

    // ------------------------------------------------------------------- 7. Footer links

    private void buildFooterLinks() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createRoundedDrawable(COLOR_SURFACE, dp(16), COLOR_BORDER, dp(1)));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(14);
        card.setLayoutParams(cardLp);

        TextView title = new TextView(this);
        title.setText(R.string.footer_links_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(COLOR_CYAN_ACCENT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(8));
        card.addView(title);

        Button btnGithub = createCyberButton(getString(R.string.footer_github),
                () -> openUrl(GITHUB_URL));
        card.addView(btnGithub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        Button btnFacebook = createCyberButton(getString(R.string.footer_facebook),
                () -> openUrl(COMMUNITY_URL));
        LinearLayout.LayoutParams facebookLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        facebookLp.topMargin = dp(8);
        card.addView(btnFacebook, facebookLp);

        contentContainer.addView(card);
    }

    // ------------------------------------------------------------------- App Picker Overlay

    private void openAppPicker(int paneIndex) {
        this.targetPickerPane = paneIndex;

        if (pickerOverlay != null) {
            rootContainer.removeView(pickerOverlay);
        }

        pickerOverlay = new FrameLayout(this);
        pickerOverlay.setBackgroundColor(0xF4080E18);
        pickerOverlay.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(12));

        TextView title = new TextView(this);
        title.setText(getString(R.string.choose_for_pane, paneIndex + 1));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button closeBtn = createCyberButton(getString(R.string.close), this::dismissPicker);
        header.addView(closeBtn, new LinearLayout.LayoutParams(dp(76), dp(38)));
        box.addView(header);

        // Search Bar
        pickerSearch = new EditText(this);
        pickerSearch.setHint(R.string.search_apps_hint);
        pickerSearch.setHintTextColor(COLOR_TEXT_MUTED);
        pickerSearch.setTextColor(COLOR_TEXT_PRIMARY);
        pickerSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pickerSearch.setSingleLine(true);
        pickerSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        pickerSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        pickerSearch.setBackground(createRoundedDrawable(COLOR_SURFACE, dp(12), COLOR_CYAN_ACCENT, dp(1)));
        pickerSearch.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.addView(pickerSearch);

        // List Scroll
        ScrollView listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        pickerListContainer = new LinearLayout(this);
        pickerListContainer.setOrientation(LinearLayout.VERTICAL);
        pickerListContainer.setPadding(0, dp(10), 0, dp(24));
        listScroll.addView(pickerListContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pickerOverlay.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootContainer.addView(pickerOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (cachedAppList != null) {
            renderAppList(cachedAppList, "");
        } else {
            TextView loading = new TextView(this);
            loading.setText(R.string.launcher_loading);
            loading.setTextColor(COLOR_TEXT_SECONDARY);
            loading.setPadding(0, dp(20), 0, 0);
            pickerListContainer.addView(loading);

            new Thread(() -> {
                List<AppEntry> apps = AppCatalog.load(MainActivity.this);
                cachedAppList = apps;
                handler.post(() -> {
                    if (pickerOverlay != null) {
                        renderAppList(apps, "");
                    }
                });
            }, "aacast-load-apps").start();
        }

        pickerSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (cachedAppList != null && pickerOverlay != null) {
                    renderAppList(cachedAppList, s.toString());
                }
            }
        });
    }

    private void renderAppList(List<AppEntry> apps, String query) {
        if (pickerListContainer == null) {
            return;
        }
        pickerListContainer.removeAllViews();

        String needle = normalize(query);
        int count = 0;

        for (AppEntry entry : apps) {
            if (!needle.isEmpty() && !matches(entry, needle)) {
                continue;
            }
            count++;

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.setBackground(createRoundedDrawable(COLOR_SURFACE, dp(12), COLOR_BORDER, dp(1)));
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(v -> selectAppForTarget(entry));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(getAppIcon(entry.pkg));
            item.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, 0, 0);

            TextView label = new TextView(this);
            label.setText(entry.label);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTextColor(COLOR_TEXT_PRIMARY);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(label);

            TextView pkg = new TextView(this);
            pkg.setText(entry.pkg);
            pkg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pkg.setTextColor(COLOR_TEXT_MUTED);
            pkg.setSingleLine(true);
            pkg.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(pkg);

            item.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            pickerListContainer.addView(item, lp);
        }

        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.search_no_results);
            empty.setTextColor(COLOR_TEXT_MUTED);
            empty.setPadding(dp(12), dp(20), 0, 0);
            pickerListContainer.addView(empty);
        }
    }

    private void selectAppForTarget(AppEntry entry) {
        for (int i = 0; i < 3; i++) {
            if (i != targetPickerPane && entry.pkg.equals(panelApps[i].pkg)) {
                tileOrder.swap(targetPickerPane, i);
                prefs.edit().putString("tile_order", tileOrder.serialize()).apply();
                dismissPicker();
                syncToCar();
                refreshAll();
                Toast.makeText(this, getString(R.string.launcher_moved, entry.label, panelApps[targetPickerPane].label), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        panelApps[targetPickerPane] = entry;
        prefs.edit()
                .putString("app_" + targetPickerPane, entry.component.flattenToString())
                .putString("label_" + targetPickerPane, entry.label)
                .apply();

        dismissPicker();
        syncToCar();
        refreshAll();
    }

    private boolean dismissPicker() {
        if (pickerOverlay != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && pickerSearch != null) {
                imm.hideSoftInputFromWindow(pickerSearch.getWindowToken(), 0);
            }
            rootContainer.removeView(pickerOverlay);
            pickerOverlay = null;
            pickerSearch = null;
            pickerListContainer = null;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------- Community invite

    private void maybeShowCommunityModal() {
        if (prefs.getBoolean(PREF_COMMUNITY_DONE, false)) {
            return;
        }
        showCommunityModal();
    }

    private void showCommunityModal() {
        if (communityOverlay != null) {
            return;
        }
        communityOverlay = new FrameLayout(this);
        communityOverlay.setBackgroundColor(0xF4080E18);
        communityOverlay.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createRoundedDrawable(COLOR_SURFACE, dp(18), COLOR_CYAN_ACCENT, dp(1)));
        card.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText(R.string.community_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(COLOR_CYAN_ACCENT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);

        TextView body = new TextView(this);
        body.setText(R.string.community_body);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setTextColor(COLOR_TEXT_SECONDARY);
        body.setLineSpacing(0f, 1.2f);
        body.setPadding(0, dp(10), 0, dp(16));
        card.addView(body);

        Button join = new Button(this);
        join.setText(R.string.community_join);
        join.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        join.setTextColor(COLOR_BG);
        join.setAllCaps(false);
        join.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        join.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x3300E5FF),
                createRoundedDrawable(COLOR_CYAN_ACCENT, dp(10), COLOR_CYAN_ACCENT, dp(1)),
                null));
        join.setOnClickListener(v -> openCommunityGroup());
        card.addView(join, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        Button hide = createCyberButton(getString(R.string.community_hide),
                () -> dismissCommunity(true));
        LinearLayout.LayoutParams hideLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        hideLp.topMargin = dp(8);
        card.addView(hide, hideLp);

        Button later = createCyberButton(getString(R.string.community_later),
                () -> dismissCommunity(false));
        LinearLayout.LayoutParams laterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        laterLp.topMargin = dp(8);
        card.addView(later, laterLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        cardLp.leftMargin = dp(24);
        cardLp.rightMargin = dp(24);
        communityOverlay.addView(card, cardLp);

        rootContainer.addView(communityOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openCommunityGroup() {
        dismissCommunity(openUrl(COMMUNITY_URL));
    }

    private boolean openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        } catch (Exception e) {
            Toast.makeText(this, R.string.community_open_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private boolean dismissCommunity(boolean neverShow) {
        if (communityOverlay == null) {
            return false;
        }
        rootContainer.removeView(communityOverlay);
        communityOverlay = null;
        if (neverShow) {
            prefs.edit().putBoolean(PREF_COMMUNITY_DONE, true).apply();
        }
        return true;
    }

    private void refreshAll() {
        if (carStageView != null) {
            carStageView.invalidate();
        }
        renderModePills();
        renderSlots();
    }

    // ------------------------------------------------------------------- Telemetry & Root

    private void checkRootAsync() {
        if (rootBadgeText != null) {
            rootBadgeText.setText("ROOT: CHECK...");
        }
        new Thread(() -> {
            boolean granted = false;
            try {
                Process process = new ProcessBuilder("su", "-c", "id -u").start();
                if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line = reader.readLine();
                    granted = line != null && line.trim().equals("0");
                }
            } catch (Exception ignored) {
            }
            final boolean finalGranted = granted;
            handler.post(() -> {
                rootGranted = finalGranted;
                if (rootBadgeText != null && rootBadgeView != null) {
                    if (finalGranted) {
                        rootBadgeText.setText("ROOT: OK");
                        rootBadgeText.setTextColor(COLOR_SUCCESS);
                        rootBadgeView.setBackground(createCyberChipDrawable(0x2210B981, COLOR_SUCCESS));
                    } else {
                        rootBadgeText.setText("ROOT: CẦN CẤP");
                        rootBadgeText.setTextColor(COLOR_WARNING);
                        rootBadgeView.setBackground(createCyberChipDrawable(0x22F59E0B, COLOR_WARNING));
                    }
                }
            });
        }, "root-check").start();
    }

    private void restartGearheadAsync() {
        Toast.makeText(this, "Đang gửi lệnh buộc dừng Gearhead…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Process p = new ProcessBuilder("su", "-c", "am force-stop com.google.android.projection.gearhead").start();
                p.waitFor(3, TimeUnit.SECONDS);
                handler.post(() -> Toast.makeText(this, R.string.aa_restarted, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(this, "Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }, "gearhead-stop").start();
    }

    private void openAndroidAutoSettings() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.google.android.projection.gearhead",
                    "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"));
            startActivity(intent);
        } catch (Exception ignored) {
            try {
                Intent fallback = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                fallback.setData(Uri.parse("package:com.google.android.projection.gearhead"));
                startActivity(fallback);
            } catch (Exception e) {
                Toast.makeText(this, "Chưa cài đặt Android Auto", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------------- Helpers

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private Button createCyberButton(String text, Runnable action) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(COLOR_CYAN_ACCENT);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setAllCaps(false);
        btn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        btn.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x3300E5FF),
                createRoundedDrawable(COLOR_SURFACE_VARIANT, dp(10), COLOR_BORDER, dp(1)),
                null
        ));
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    private View createCyberChip(String text, int color, View.OnClickListener clickListener) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        pill.setBackground(createCyberChipDrawable(0x2200E5FF, color));
        pill.setClickable(clickListener != null);
        pill.setFocusable(clickListener != null);
        if (clickListener != null) {
            pill.setOnClickListener(clickListener);
        }

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pill.addView(tv);

        return pill;
    }

    Drawable getAppIcon(String pkg) {
        if (!TextUtils.isEmpty(pkg)) {
            try {
                return getPackageManager().getApplicationIcon(pkg);
            } catch (Exception ignored) {
            }
        }
        return getDrawable(R.drawable.ic_aacast);
    }

    static GradientDrawable createRoundedDrawable(int bgColor, int cornerRadius, int strokeColor, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(cornerRadius);
        if (strokeWidth > 0) {
            d.setStroke(strokeWidth, strokeColor);
        }
        return d;
    }

    static GradientDrawable createCyberChipDrawable(int bgColor, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(dpStatic(6));
        d.setStroke(1, strokeColor);
        return d;
    }

    static GradientDrawable createPillDrawable(int bgColor, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(500);
        d.setStroke(1, strokeColor);
        return d;
    }

    private static int dpStatic(int val) {
        return Math.round(val * 2.5f);
    }

    private int layoutName(int mode) {
        switch (mode) {
            case 0:
                return R.string.layout_two_columns;
            case 1:
                return R.string.layout_two_rows;
            case 2:
                return R.string.layout_three_columns;
            case 3:
                return R.string.layout_three_rows;
            case 4:
                return R.string.layout_focus_left;
            default:
                return R.string.layout_focus_top;
        }
    }

    private static boolean matches(AppEntry entry, String needle) {
        return normalize(entry.label).contains(needle) || normalize(entry.pkg).contains(needle);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }

    // ------------------------------------------------------------------- Hero Car Screen Stage View

    /**
     * Màn hình xe ảo (Virtual Car Screen) hiển thị bố cục thực tế với logo app và tương tác chạm.
     */
    private final class CarScreenStageView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private LayoutRects lastRects;
        private int touchedIndex = -1;

        CarScreenStageView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (lastRects == null) {
                return super.onTouchEvent(event);
            }
            float x = event.getX();
            float y = event.getY();

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchedIndex = hitTest(x, y);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    int hit = hitTest(x, y);
                    if (hit != -1 && hit == touchedIndex) {
                        // Tìm paneIndex tương ứng
                        int targetPane = paneIndexForVisibleSlot(hit);
                        openAppPicker(targetPane);
                    }
                    touchedIndex = -1;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    touchedIndex = -1;
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(event);
        }

        private int hitTest(float x, float y) {
            if (lastRects == null) return -1;
            for (int i = 0; i < lastRects.panes.length; i++) {
                int[] r = lastRects.panes[i];
                if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) {
                    return i;
                }
            }
            return -1;
        }

        private int paneIndexForVisibleSlot(int slot) {
            boolean isTwoPane = (layoutMode == 0 || layoutMode == 1);
            if (!isTwoPane) {
                return slot;
            }
            int count = 0;
            for (int i = 0; i < 3; i++) {
                if (i != hiddenPane) {
                    if (count == slot) {
                        return i;
                    }
                    count++;
                }
            }
            return slot;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;

            float r1 = LayoutMath.isLinearThreeSplit(layoutMode) ? 0.33333334f : 0.5f;
            float r2 = LayoutMath.isLinearThreeSplit(layoutMode) ? 0.6666667f : 0.5f;
            lastRects = LayoutMath.compute(layoutMode, width, height, dp(3), r1, r2);

            for (int i = 0; i < lastRects.panes.length; i++) {
                int[] rect = lastRects.panes[i];
                boolean isTouched = (i == touchedIndex);
                int targetPane = paneIndexForVisibleSlot(i);
                AppEntry app = panelApps[targetPane];

                // Nền ô
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(isTouched ? COLOR_SURFACE_SELECTED : COLOR_SURFACE_VARIANT);
                canvas.drawRoundRect(rect[0], rect[1], rect[2], rect[3], dp(8), dp(8), paint);

                // Viền ô (Cyan Neon)
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(isTouched ? 2 : 1));
                paint.setColor(isTouched ? COLOR_CYAN_ACCENT : COLOR_BORDER);
                canvas.drawRoundRect(rect[0], rect[1], rect[2], rect[3], dp(8), dp(8), paint);

                // Vẽ nội dung ô (Icon app + Tên app)
                int paneW = rect[2] - rect[0];
                int paneH = rect[3] - rect[1];
                int centerX = (rect[0] + rect[2]) / 2;
                int centerY = (rect[1] + rect[3]) / 2;

                // Thẻ số thứ tự góc trên
                textPaint.setColor(COLOR_CYAN_ACCENT);
                textPaint.setTextSize(dp(10));
                textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                textPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText("Ô " + (targetPane + 1), rect[0] + dp(8), rect[1] + dp(14), textPaint);

                // Icon app
                int iconSize = Math.min(dp(36), Math.min(paneW / 3, paneH / 3));
                if (iconSize > dp(14)) {
                    Drawable icon = getAppIcon(app.pkg);
                    int iconLeft = centerX - iconSize / 2;
                    int iconTop = centerY - iconSize / 2 - dp(6);
                    icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
                    icon.draw(canvas);

                    // Tên app
                    textPaint.setColor(COLOR_TEXT_PRIMARY);
                    textPaint.setTextSize(dp(11));
                    textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                    textPaint.setTextAlign(Paint.Align.CENTER);
                    String label = TextUtils.isEmpty(app.label) ? "Chạm để chọn" : app.label;
                    if (label.length() > 14) {
                        label = label.substring(0, 12) + "…";
                    }
                    canvas.drawText(label, centerX, iconTop + iconSize + dp(12), textPaint);
                }
            }
        }
    }
}
