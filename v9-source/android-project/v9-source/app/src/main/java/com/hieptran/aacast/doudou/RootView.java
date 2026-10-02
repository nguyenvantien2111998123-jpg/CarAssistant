package com.carassistant.v9;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * View gốc của màn hình chia: quản lý 3 pane, dialog, prefs và vòng đời root.
 */
public final class RootView extends LinearLayout {

    private static final String TAG = "AACastDoudou";

    /** Chỉ một RootView sống tại một thời điểm. */
    private static WeakReference<RootView> current = new WeakReference<>(null);

    public static RootView getCurrent() {
        return current.get();
    }

    public void reloadSettings() {
        handler.post(() -> {
            if (destroyed) {
                return;
            }
            layoutMode = clamp(prefs.getInt("layout_mode_v3", 0), 0, 5);
            hiddenPane = clamp(prefs.getInt("hidden_pane_v3", 0), 0, 2);
            loadRatios();
            String savedOrder = prefs.getString("tile_order", "0,1,2");
            // Cập nhật lại TileOrder
            tileOrder.reset(savedOrder);
            for (int i = 0; i < panes.length; i++) {
                ComponentName saved = ComponentName.unflattenFromString(prefs.getString("app_" + i, ""));
                if (saved != null && !getContext().getPackageName().equals(saved.getPackageName())) {
                    panes[i].setApp(new AppEntry(prefs.getString("label_" + i, saved.getPackageName()),
                            saved.getPackageName(), saved));
                }
            }
            applyVisibility();
            container.requestLayout();
            container.resizeAll();
        });
    }

    final Handler handler = new Handler(Looper.getMainLooper());
    final RootShellSession shell = new RootShellSession();
    final SharedPreferences prefs;
    final PaneView[] panes = new PaneView[3];
    final TileOrder tileOrder;
    final PaneContainer container;

    int layoutMode;
    int hiddenPane;
    float ratioFirst;
    float ratioSecond;

    /** Nút "Return to Android Auto" (chỉ có ở CarActivity). */
    Runnable exitToAuto;

    volatile boolean destroyed;
    volatile boolean rootAvailable;
    private boolean rootCheckRunning;

    private FrameLayout host;
    private FrameLayout overlayRoot;
    private ScrollView overlayScroll;
    private LinearLayout overlayContent;

    public RootView(Context context) {
        super(context);

        RootView previous = current.get();
        if (previous != null) {
            previous.destroy();
            for (PaneView pane : previous.panes) {
                pane.status.setText(R.string.launcher_other_screen);
                pane.retry.setVisibility(GONE);
                pane.overlay.setVisibility(VISIBLE);
            }
        }
        current = new WeakReference<>(this);

        prefs = context.getSharedPreferences("aacast_doudou", Context.MODE_PRIVATE);
        layoutMode = clamp(prefs.getInt("layout_mode_v3", 0), 0, 5);
        hiddenPane = clamp(prefs.getInt("hidden_pane_v3", 0), 0, 2);
        loadRatios();
        tileOrder = new TileOrder(prefs.getString("tile_order", "0,1,2"));

        container = new PaneContainer(this, context);

        setOrientation(VERTICAL);
        setKeepScreenOn(true);
        setBackgroundColor(0xFF080E18);
        setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets cut = insets.getInsets(WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
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
                } else {
                    left = 0;
                    top = 0;
                    right = 0;
                    bottom = 0;
                }
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });

        host = new FrameLayout(context);
        host.addView(container, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(host, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < panes.length; i++) {
            panes[i] = new PaneView(this, i);
            container.addView(panes[i]);
        }
        for (DividerView divider : container.dividers) {
            container.addView(divider);
        }

        applyVisibility();
        if (prefs.getBoolean("setup_complete", false)) {
            startRootCheck();
        } else {
            showSetup();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------- pane state

    /** Pane có đang hiển thị? */
    boolean isPaneVisible(int paneIndex) {
        if (layoutMode == 0 || layoutMode == 1) {
            return paneIndex != hiddenPane;
        }
        return true;
    }

    /** Index hình học của pane (bỏ qua pane bị ẩn). */
    int visibleIndex(int paneIndex) {
        int position = tileOrder.positionOf(paneIndex);
        if (layoutMode == 0 || layoutMode == 1) {
            int hiddenPosition = tileOrder.positionOf(hiddenPane);
            return position > hiddenPosition ? position - 1 : position;
        }
        return position;
    }

    /** Ẩn/hiện pane + bật/tắt display tương ứng. */
    void applyVisibility() {
        for (PaneView pane : panes) {
            if (isPaneVisible(pane.index)) {
                pane.setVisibility(VISIBLE);
                pane.launch();
            } else {
                pane.release();
                pane.setVisibility(GONE);
            }
        }
        container.requestLayout();
    }

    // ------------------------------------------------------------------- root

    /** Kiểm tra root rồi mở toàn bộ pane. */
    void startRootCheck() {
        if (destroyed || rootCheckRunning) {
            return;
        }
        rootCheckRunning = true;
        RootShellSession.EXEC.execute(() -> {
            try {
                if (!"0".equals(shell.run(45, "/system/bin/id -u").trim())) {
                    throw new IllegalStateException("su did not grant UID 0");
                }
                handler.post(() -> {
                    if (destroyed) {
                        return;
                    }
                    rootCheckRunning = false;
                    rootAvailable = true;
                    for (PaneView pane : panes) {
                        pane.launch();
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Root initialization failed", e);
                handler.post(() -> {
                    if (destroyed) {
                        return;
                    }
                    rootCheckRunning = false;
                    rootAvailable = false;
                    for (PaneView pane : panes) {
                        pane.showError();
                        pane.status.setText(R.string.root_required);
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- ratios

    /** Nạp tỉ lệ chia từ prefs. */
    void loadRatios() {
        float d1 = LayoutMath.isLinearThreeSplit(layoutMode) ? 0.33333334f : 0.5f;
        float d2 = LayoutMath.isLinearThreeSplit(layoutMode) ? 0.6666667f : 0.5f;
        float[] ratios = LayoutMath.ratios(layoutMode,
                prefs.getFloat("ratio_first_v3_" + layoutMode, d1),
                prefs.getFloat("ratio_second_v3_" + layoutMode, d2));
        ratioFirst = ratios[0];
        ratioSecond = ratios[1];
    }

    /** Lưu tỉ lệ chia vào prefs. */
    void saveRatios() {
        prefs.edit()
                .putFloat("ratio_first_v3_" + layoutMode, ratioFirst)
                .putFloat("ratio_second_v3_" + layoutMode, ratioSecond)
                .apply();
    }

    // ---------------------------------------------------------------- dialogs

    /** Tạo overlay nền + trả về LinearLayout nội dung. */
    private LinearLayout newOverlay(String title) {
        if (overlayRoot != null) {
            host.removeView(overlayRoot);
        }
        overlayRoot = new FrameLayout(getContext());
        overlayScroll = new ScrollView(getContext());
        overlayRoot.setBackgroundColor(0xFF080E18);
        overlayRoot.setClickable(true);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(12));
        content.addView(text(21, title), new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56)));

        overlayContent = content;
        overlayScroll.addView(content);
        overlayRoot.addView(overlayScroll, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        host.addView(overlayRoot, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return content;
    }

    /** Đóng overlay; trả true nếu có overlay. */
    boolean dismissOverlay() {
        if (overlayRoot == null) {
            return false;
        }
        host.removeView(overlayRoot);
        overlayRoot = null;
        overlayScroll = null;
        overlayContent = null;
        return true;
    }

    /** Màn setup lần đầu. */
    void showSetup() {
        if (destroyed) {
            return;
        }
        LinearLayout box = newOverlay(getContext().getString(R.string.setup_title));
        TextView body = text(16, getContext().getString(R.string.setup_body));
        body.setPadding(dp(6), dp(12), dp(6), dp(24));
        box.addView(body, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        box.addView(button(getContext().getString(R.string.start), () -> {
            prefs.edit().putBoolean("setup_complete", true).apply();
            dismissOverlay();
            if (!rootAvailable) {
                startRootCheck();
            }
        }), new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52)));
    }

    /** Menu của một pane. */
    void showPaneMenu(PaneView pane) {
        if (destroyed) {
            return;
        }
        if (!prefs.getBoolean("setup_complete", false)) {
            showSetup();
            return;
        }
        LinearLayout box = newOverlay(pane.app.label);
        addMenuButton(box, R.string.close, this::dismissOverlay);
        addMenuButton(box, getContext().getString(R.string.launcher_back, pane.app.label), () -> {
            dismissOverlay();
            pane.sendInput(true, 0, 0, 0, 0, 0L, true);
        });
        addMenuButton(box, getContext().getString(R.string.change_app, visibleIndex(pane.index) + 1),
                () -> showAppChooser(pane));
        for (PaneView other : panes) {
            if (other != pane && isPaneVisible(other.index)) {
                addMenuButton(box, getContext().getString(R.string.pane_swap, other.app.label), () -> {
                    if (tileOrder.swap(pane.index, other.index)) {
                        prefs.edit().putString("tile_order", tileOrder.serialize()).apply();
                        container.requestLayout();
                    }
                    dismissOverlay();
                });
            }
        }
        addMenuButton(box, R.string.layout_settings, this::showSettings);
    }

    /** Danh sách app để chọn cho một pane. */
    void showAppChooser(PaneView pane) {
        if (destroyed || !prefs.getBoolean("setup_complete", false)) {
            return;
        }
        final String title = getContext().getString(R.string.choose_for_pane,
                visibleIndex(pane.index) + 1);
        LinearLayout box = newOverlay(title);
        box.removeViewAt(0);

        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(VERTICAL);
        header.setPadding(dp(16), 0, dp(16), dp(8));
        header.setBackgroundColor(0xFF080E18);

        LinearLayout titleRow = new LinearLayout(getContext());
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text(18, title), new LinearLayout.LayoutParams(0, dp(56), 1f));
        titleRow.addView(button(getContext().getString(R.string.close), this::dismissOverlay),
                new LinearLayout.LayoutParams(dp(88), dp(48)));
        header.addView(titleRow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56)));

        final EditText search = searchField(getContext().getString(R.string.search_apps_hint));
        header.addView(search, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));

        overlayRoot.addView(header, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP));
        FrameLayout.LayoutParams scrollParams =
                new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        scrollParams.topMargin = dp(112);
        overlayScroll.setLayoutParams(scrollParams);

        final TextView message = text(14, "");
        box.addView(message, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));

        final LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(VERTICAL);
        box.addView(list, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));

        new Thread(() -> {
            List<AppEntry> apps = AppCatalog.load(getContext());
            handler.post(() -> {
                if (destroyed || overlayContent != box) {
                    return;
                }
                renderAppList(list, apps, "", pane, message);
                search.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        if (destroyed || overlayContent != box) {
                            return;
                        }
                        renderAppList(list, apps, s.toString(), pane, message);
                    }
                });
            });
        }, "aacast-apps").start();
    }

    /** Vẽ lại danh sách app theo từ khoá tìm kiếm. */
    private void renderAppList(LinearLayout list, List<AppEntry> apps, String query,
                               PaneView pane, TextView message) {
        list.removeAllViews();
        if (apps.isEmpty()) {
            list.addView(text(16, getContext().getString(R.string.no_apps)),
                    new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                            LayoutParams.WRAP_CONTENT));
            return;
        }
        String needle = normalize(query);
        int shown = 0;
        for (AppEntry entry : apps) {
            if (!needle.isEmpty() && !matches(entry, needle)) {
                continue;
            }
            shown++;
            Button button = button(entry.label, () -> selectApp(pane, entry, message));
            button.setContentDescription(entry.label + ", " + entry.pkg);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52));
            params.topMargin = dp(6);
            list.addView(button, params);
        }
        if (shown == 0) {
            list.addView(text(16, getContext().getString(R.string.search_no_results)),
                    new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                            LayoutParams.WRAP_CONTENT));
        }
    }

    private static boolean matches(AppEntry entry, String needle) {
        return normalize(entry.label).contains(needle) || normalize(entry.pkg).contains(needle);
    }

    /** Bỏ dấu + thường hoá để tìm không phân biệt dấu/hoa thường. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }

    private EditText searchField(String hint) {
        EditText field = new EditText(getContext());
        field.setHint(hint);
        field.setHintTextColor(0x8894A3B8);
        field.setTextColor(0xFFF0F8FF);
        field.setTextSize(15f);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF0F1B2D);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), 0xFF1E385B);
        field.setBackground(background);
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) {
                return false;
            }
            InputMethodManager manager =
                    (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
            return true;
        });
        return field;
    }

    /** Chọn app cho pane (xử lý trùng app). */
    private void selectApp(PaneView pane, AppEntry entry, TextView message) {
        for (PaneView other : panes) {
            if (other == pane || !entry.pkg.equals(other.app.pkg)) {
                continue;
            }
            if (isPaneVisible(other.index)) {
                message.setText(R.string.duplicate_app);
                if (overlayScroll != null) {
                    overlayScroll.smoothScrollTo(0, 0);
                }
                return;
            }
            tileOrder.swap(pane.index, other.index);
            hiddenPane = pane.index;
            prefs.edit()
                    .putInt("hidden_pane_v3", hiddenPane)
                    .putString("tile_order", tileOrder.serialize())
                    .apply();
            applyVisibility();
            dismissOverlay();
            return;
        }
        prefs.edit()
                .putString("app_" + pane.index, entry.component.flattenToString())
                .putString("label_" + pane.index, entry.label)
                .apply();
        dismissOverlay();
        pane.setApp(entry);
    }

    /** Dialog "Bố cục và ứng dụng". */
    void showSettings() {
        if (destroyed) {
            return;
        }
        if (!prefs.getBoolean("setup_complete", false)) {
            showSetup();
            return;
        }
        container.commitDrag(false);

        LinearLayout box = newOverlay(getContext().getString(R.string.layout_settings));
        box.removeViewAt(0);

        // Header: tiêu đề + Done
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), 0, dp(16), 0);
        header.setBackgroundColor(0xFF080E18);
        header.addView(text(18, getContext().getString(R.string.layout_settings)),
                new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        header.addView(button(getContext().getString(R.string.layout_done), this::dismissOverlay),
                new LinearLayout.LayoutParams(dp(88), dp(48)));
        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP);
        overlayRoot.addView(header, headerParams);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        scrollParams.topMargin = dp(56);
        overlayScroll.setLayoutParams(scrollParams);

        boolean twoPane = (layoutMode == 0 || layoutMode == 1);

        // 2 apps / 3 apps
        LinearLayout counts = row(2, 140);
        counts.addView(choice(getContext().getString(R.string.layout_count_two), twoPane,
                () -> applyLayoutMode(twoPane ? layoutMode : 0)));
        counts.addView(choice(getContext().getString(R.string.layout_count_three), !twoPane,
                () -> applyLayoutMode(twoPane ? 2 : layoutMode)));
        box.addView(counts, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        addSectionTitle(box, R.string.layout_pick_style);

        // 6 kiểu bố cục (có preview)
        LinearLayout styles = row(4, 150);
        for (int mode = 0; mode < 6; mode++) {
            if ((mode == 0 || mode == 1) != twoPane) {
                continue;
            }
            final int targetMode = mode;
            LinearLayout tile = choice(getContext().getString(layoutName(mode)), mode == layoutMode,
                    () -> applyLayoutMode(targetMode));
            tile.addView(new LayoutPreview(getContext(), mode), 0,
                    new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(54)));
            styles.addView(tile);
        }
        box.addView(styles, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Cặp app cho bố cục 2 pane: mỗi tile = (pane sẽ bị ẩn, 2 pane còn lại theo thứ tự hiển thị)
        if (twoPane) {
            addSectionTitle(box, R.string.layout_pair_title);
            LinearLayout pairs = row(3, 190);
            for (int offset = 0; offset < panes.length; offset++) {
                final int candidate = (hiddenPane + offset) % panes.length;
                List<PaneView> shown = new ArrayList<>();
                for (PaneView pane : panes) {
                    if (pane.index != candidate) {
                        shown.add(pane);
                    }
                }
                if (shown.size() != 2) {
                    continue;
                }
                shown.sort((a, b) -> Integer.compare(
                        visibleIndexFor(a.index, candidate), visibleIndexFor(b.index, candidate)));
                boolean active = candidate == hiddenPane;
                LinearLayout tile = choice(getContext().getString(R.string.layout_pair,
                        shown.get(0).app.label, shown.get(1).app.label), active, () -> {
                    hiddenPane = candidate;
                    prefs.edit().putInt("hidden_pane_v3", hiddenPane).apply();
                    applyVisibility();
                    showSettings();
                });
                tile.removeAllViews();
                TextView state = text(12, getContext().getString(
                        active ? R.string.layout_pair_active : R.string.layout_pair_use));
                state.setTextColor(active ? 0xFF00E5FF : 0xFF94A3B8);
                tile.addView(state, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(24)));
                tile.addView(appRow(shown.get(0)), new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36)));
                tile.addView(appRow(shown.get(1)), new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36)));
                pairs.addView(tile);
            }
            box.addView(pairs, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }

        // Đổi app từng pane
        addSectionTitle(box, R.string.layout_change_apps);
        LinearLayout change = row(3, 190);
        int visibleCount = twoPane ? 2 : 3;
        for (int slot = 0; slot < visibleCount; slot++) {
            for (PaneView pane : panes) {
                if (isPaneVisible(pane.index) && visibleIndex(pane.index) == slot) {
                    change.addView(choice(getContext().getString(R.string.choose_for_pane, slot + 1)
                            + "\n" + pane.app.label, false, () -> showAppChooser(pane)));
                }
            }
        }
        box.addView(change, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView hint = text(13, getContext().getString(R.string.layout_quick_hint));
        hint.setPadding(dp(6), dp(12), dp(6), dp(12));
        box.addView(hint, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout actions = row(3, 180);
        actions.addView(choice(getContext().getString(R.string.layout_reset), false, () -> {
            ratioFirst = LayoutMath.isLinearThreeSplit(layoutMode) ? 0.33333334f : 0.5f;
            ratioSecond = LayoutMath.isLinearThreeSplit(layoutMode) ? 0.6666667f : 0.5f;
            saveRatios();
            container.requestLayout();
            container.resizeAll();
            showSettings();
        }));
        actions.addView(choice(getContext().getString(R.string.help), false, this::showHelp));
        if (exitToAuto != null) {
            actions.addView(choice(getContext().getString(R.string.layout_exit_auto), false, exitToAuto));
        }
        box.addView(actions, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /** Hướng dẫn ngắn. */
    void showHelp() {
        LinearLayout box = newOverlay(getContext().getString(R.string.help));
        TextView body = text(15, getContext().getString(R.string.setup_body));
        body.setPadding(dp(6), dp(12), dp(6), dp(24));
        box.addView(body, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        box.addView(button(getContext().getString(R.string.close), this::dismissOverlay),
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
    }

    /** Đổi bố cục. */
    void applyLayoutMode(int mode) {
        if (destroyed) {
            return;
        }
        container.commitDrag(false);
        if (mode < 0 || mode >= 6) {
            mode = 0;
        }
        layoutMode = mode;
        loadRatios();
        prefs.edit().putInt("layout_mode_v3", layoutMode).apply();
        applyVisibility();
        showSettings();
    }

    // -------------------------------------------------------------- lifecycle

    /** Dọn toàn bộ pane + phiên root. */
    void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        rootAvailable = false;
        for (PaneView pane : panes) {
            pane.release();
        }
        RootShellSession.EXEC.execute(shell::destroy);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AppCatalog.immersive(this);
        requestApplyInsets();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !destroyed) {
            AppCatalog.immersive(this);
        }
    }

    // ------------------------------------------------------------------- view

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(int sizeSp, String value) {
        TextView textView = new TextView(getContext());
        textView.setText(value);
        textView.setTextColor(0xFFF0F8FF);
        textView.setTextSize(sizeSp);
        textView.setGravity(Gravity.CENTER_VERTICAL);
        textView.setPadding(dp(6), 0, dp(6), 0);
        return textView;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13f);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF163254);
        background.setCornerRadius(dp(12));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3300E5FF), background, null));
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void addMenuButton(LinearLayout box, int stringRes, Runnable action) {
        addMenuButton(box, getContext().getString(stringRes), action);
    }

    private void addMenuButton(LinearLayout box, String label, Runnable action) {
        Button button = button(label, action);
        button.setMinimumHeight(dp(48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        box.addView(button, params);
    }

    private void addSectionTitle(LinearLayout box, int stringRes) {
        TextView title = text(16, getContext().getString(stringRes));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(4), dp(12), dp(4), dp(8));
        box.addView(title, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /** Hàng chia đều cột, dùng cho các nút chọn. */
    private LinearLayout row(int columns, int minWidthDp) {
        RowLayout row = new RowLayout(getContext(), columns, dp(8), dp(minWidthDp));
        row.setOrientation(HORIZONTAL);
        return row;
    }

    private LinearLayout choice(String label, boolean selected, Runnable action) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(VERTICAL);
        tile.setPadding(dp(10), dp(8), dp(10), dp(8));
        tile.setFocusable(true);
        tile.setOnClickListener(v -> action.run());
        styleChoice(tile, label, selected);
        TextView textView = text(13, (selected ? "✓  " : "") + label);
        textView.setGravity(Gravity.CENTER);
        textView.setMinHeight(dp(40));
        tile.addView(textView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return tile;
    }

    private void styleChoice(View view, String label, boolean selected) {
        view.setSelected(selected);
        String description = selected
                ? getContext().getString(R.string.layout_selected, label)
                : label;
        view.setContentDescription(description);
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? 0xFF152A47 : 0xFF0F1B2D);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(selected ? 2 : 1), selected ? 0xFF00E5FF : 0xFF1E385B);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3300E5FF), background, null));
    }

    private LinearLayout appRow(PaneView pane) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(getContext());
        try {
            icon.setImageDrawable(getContext().getPackageManager().getApplicationIcon(pane.app.pkg));
        } catch (Exception e) {
            icon.setImageResource(R.drawable.ic_aacast);
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = text(14, pane.app.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        return row;
    }

    /** Vị trí hiển thị của pane khi pane `hidden` bị ẩn. */
    private int visibleIndexFor(int paneIndex, int hidden) {
        int position = tileOrder.positionOf(paneIndex);
        int hiddenPosition = tileOrder.positionOf(hidden);
        return position > hiddenPosition ? position - 1 : position;
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

    /** Hàng chia cột theo số cột tối đa. */
    private static final class RowLayout extends LinearLayout {

        private final int maxColumns;
        private final int gapPx;
        private final int minWidthPx;
        private int columns = 1;
        private int cellWidth;
        private int cellHeight;

        RowLayout(Context context, int maxColumns, int gapPx, int minWidthPx) {
            super(context);
            this.maxColumns = maxColumns;
            this.gapPx = gapPx;
            this.minWidthPx = minWidthPx;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            columns = Math.max(1, Math.min(Math.min(maxColumns, getChildCount()),
                    (width + gapPx) / (minWidthPx + gapPx)));
            cellWidth = Math.max(0, (width - (columns - 1) * gapPx) / columns);
            cellHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                cellHeight = Math.max(cellHeight, child.getMeasuredHeight());
            }
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY));
            }
            int rows = (getChildCount() + columns - 1) / columns;
            setMeasuredDimension(width, View.resolveSize(Math.max(0, rows - 1) * gapPx + cellHeight * rows, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            for (int i = 0; i < getChildCount(); i++) {
                int column = i % columns;
                int rowIndex = i / columns;
                int x = (cellWidth + gapPx) * column;
                int y = (cellHeight + gapPx) * rowIndex;
                getChildAt(i).layout(x, y, x + cellWidth, y + cellHeight);
            }
        }
    }

    /** Preview nhỏ của một kiểu bố cục. */
    private static final class LayoutPreview extends View {

        private final int mode;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int density;

        LayoutPreview(Context context, int mode) {
            super(context);
            this.mode = mode;
            this.density = Math.round(context.getResources().getDisplayMetrics().density);
        }

        private int dp(int value) {
            return Math.round(value * density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = Math.min(dp(108), getWidth());
            int height = Math.min(dp(48), getHeight());
            canvas.save();
            canvas.translate((getWidth() - width) / 2f, (getHeight() - height) / 2f);
            float r1 = LayoutMath.isLinearThreeSplit(mode) ? 0.33333334f : 0.5f;
            float r2 = LayoutMath.isLinearThreeSplit(mode) ? 0.6666667f : 0.5f;
            LayoutRects rects = LayoutMath.compute(mode, width, height, dp(2), r1, r2);
            for (int i = 0; i < rects.panes.length; i++) {
                int[] rect = rects.panes[i];
                paint.setColor(0xFF00E5FF);
                canvas.drawRoundRect(rect[0], rect[1], rect[2], rect[3], dp(3), dp(3), paint);
                paint.setColor(0xFF080E18);
                paint.setTextSize(dp(11));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(String.valueOf(i + 1), (rect[0] + rect[2]) / 2f,
                        (rect[1] + rect[3]) / 2f - (paint.ascent() + paint.descent()) / 2f, paint);
            }
            canvas.restore();
        }
    }
}
