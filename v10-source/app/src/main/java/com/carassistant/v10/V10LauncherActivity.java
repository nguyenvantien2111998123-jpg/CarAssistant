package com.carassistant.v10;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class V10LauncherActivity extends Activity {
    private static final int BG = Color.rgb(9, 13, 19);
    private static final int CARD = Color.rgb(22, 29, 39);
    private static final int CARD_ALT = Color.rgb(29, 39, 52);
    private static final int ACCENT = Color.rgb(115, 190, 255);
    private static final int TEXT = Color.rgb(245, 248, 252);
    private static final int MUTED = Color.rgb(165, 178, 194);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<AppInfo> apps = new ArrayList<>();

    private LinearLayout root;
    private LinearLayout body;
    private LinearLayout dashboard;
    private LinearLayout appPanel;
    private LinearLayout diagnosticsPanel;
    private LinearLayout keyboardPanel;
    private LinearLayout projectionPanel;
    private LinearLayout dock;
    private EditText search;
    private TextView clock;
    private TextView date;
    private TextView rootValue;
    private TextView autoValue;
    private TextView diagnosticRootValue;
    private TextView diagnosticAutoValue;
    private TextView targetValue;
    private TextView keyboardValue;
    private TextView appCount;
    private String rootState = "Đang kiểm tra…";
    private boolean rootChecked;

    private final Runnable clockTicker = new Runnable() {
        @Override public void run() {
            updateClock();
            if (!isFinishing() && !isDestroyed()) handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        loadApps();
        updateClock();
        refreshDeviceStatus();
        handler.post(clockTicker);
    }

    @Override protected void onResume() {
        super.onResume();
        updateDeviceCards();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(clockTicker);
        super.onDestroy();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        root.addView(buildHeader(), new LinearLayout.LayoutParams(-1, dp(68)));

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        buildDashboard();
        buildAppsPanel();
        buildProjectionPanel();
        buildKeyboardPanel();
        buildDiagnosticsPanel();
        showPanel(dashboard);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(0, dp(8), 0, 0);
        String[][] items = {
            {"⌂", "Trang chủ"}, {"▦", "Ứng dụng"},
            {"▣", "Chiếu màn hình"}, {"⌨", "Bàn phím"}, {"⚙", "Kiểm tra"}
        };
        View.OnClickListener[] actions = {
            v -> showPanel(dashboard),
            v -> { loadApps(); showPanel(appPanel); },
            v -> showPanel(projectionPanel),
            v -> showPanel(keyboardPanel),
            v -> { refreshDeviceStatus(); showPanel(diagnosticsPanel); }
        };
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            TextView symbol = text(items[i][0], 20, ACCENT);
            symbol.setGravity(Gravity.CENTER);
            TextView label = text(items[i][1], 9, MUTED);
            label.setGravity(Gravity.CENTER);
            item.addView(symbol, new LinearLayout.LayoutParams(-1, dp(27)));
            item.addView(label, new LinearLayout.LayoutParams(-1, dp(20)));
            item.setOnClickListener(actions[index]);
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(50), 1f));
        }
        root.addView(nav);
        setContentView(root);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout branding = new LinearLayout(this);
        branding.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("CarAssistant", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        date = text("Điều khiển hệ thống", 11, MUTED);
        branding.addView(title);
        branding.addView(date);
        header.addView(branding, new LinearLayout.LayoutParams(0, -2, 1f));
        clock = text("--:--", 25, TEXT);
        clock.setGravity(Gravity.CENTER);
        header.addView(clock, new LinearLayout.LayoutParams(dp(90), dp(54)));
        return header;
    }

    private void buildDashboard() {
        dashboard = panelContainer();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, dp(10));
        scroll.addView(content);
        dashboard.addView(scroll, new LinearLayout.LayoutParams(-1, -1));

        LinearLayout hero = card();
        hero.setBackground(background(CARD_ALT, 22));
        TextView overline = text("TRUNG TÂM ĐIỀU KHIỂN", 10, ACCENT);
        overline.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hero.addView(overline);
        TextView welcome = text("Mọi thứ cho hành trình của bạn", 19, TEXT);
        welcome.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        welcome.setPadding(0, dp(6), 0, dp(4));
        hero.addView(welcome);
        TextView description = text("Quản lý ứng dụng, bàn phím, màn hình chiếu và kiểm tra hệ thống ngay trên điện thoại.", 12, MUTED);
        hero.addView(description);
        content.addView(hero, marginParams(-1, -2, 0, 0, 0, 10));

        LinearLayout statusCards = new LinearLayout(this);
        statusCards.setOrientation(LinearLayout.HORIZONTAL);
        rootValue = text(rootState, 12, TEXT);
        autoValue = text("Đang kiểm tra…", 12, TEXT);
        statusCards.addView(statusCard("ROOT / MAGISK", rootValue), new LinearLayout.LayoutParams(0, dp(90), 1f));
        statusCards.addView(statusCard("ANDROID AUTO", autoValue), new LinearLayout.LayoutParams(0, dp(90), 1f));
        content.addView(statusCards, marginParams(-1, dp(90), 0, 0, 0, 10));

        LinearLayout targetCard = card();
        targetCard.addView(text("ỨNG DỤNG ĐÍCH ĐÃ CHỌN", 10, MUTED));
        targetValue = text(getTargetLabel(), 16, TEXT);
        targetValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        targetValue.setPadding(0, dp(6), 0, dp(6));
        targetCard.addView(targetValue);
        TextView targetHint = text("Chọn ứng dụng trong mục Ứng dụng để lưu làm mục tiêu chiếu. Việc này chưa tự khởi chạy phiên chiếu.", 11, MUTED);
        targetCard.addView(targetHint);
        content.addView(targetCard, marginParams(-1, -2, 0, 0, 0, 10));

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        shortcuts.addView(actionCard("Ứng dụng", "Mở ứng dụng trên điện thoại hoặc đặt làm mục tiêu chiếu", "▦", v -> { loadApps(); showPanel(appPanel); }), new LinearLayout.LayoutParams(0, dp(120), 1f));
        shortcuts.addView(actionCard("Màn hình chiếu", "Mở Android Auto và xem trạng thái mục tiêu", "▣", v -> showPanel(projectionPanel)), new LinearLayout.LayoutParams(0, dp(120), 1f));
        content.addView(shortcuts, marginParams(-1, dp(120), 0, 0, 0, 10));

        LinearLayout shortcuts2 = new LinearLayout(this);
        shortcuts2.setOrientation(LinearLayout.HORIZONTAL);
        shortcuts2.addView(actionCard("Bàn phím", "Kiểm tra phương thức nhập đang dùng", "⌨", v -> showPanel(keyboardPanel)), new LinearLayout.LayoutParams(0, dp(112), 1f));
        shortcuts2.addView(actionCard("Chẩn đoán", "Kiểm tra root, Android Auto và nhật ký", "⚙", v -> { refreshDeviceStatus(); showPanel(diagnosticsPanel); }), new LinearLayout.LayoutParams(0, dp(112), 1f));
        content.addView(shortcuts2, marginParams(-1, dp(112), 0, 0, 0, 10));

        TextView footer = text("CarAssistant V10 · Pinch-to-Zoom test base", 10, MUTED);
        footer.setGravity(Gravity.CENTER);
        content.addView(footer, marginParams(-1, dp(28), 0, 0, 0, 0));
    }

    private void buildAppsPanel() {
        appPanel = panelContainer();
        LinearLayout heading = panelHeading("Ứng dụng", "Mở trên điện thoại hoặc chọn làm ứng dụng chiếu.");
        appPanel.addView(heading);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setHint("Tìm ứng dụng…");
        search.setTextSize(14);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(background(CARD, 14));
        appPanel.addView(search, marginParams(-1, dp(48), 0, 0, 0, 6));
        appCount = text("Đang tải ứng dụng…", 11, MUTED);
        appPanel.addView(appCount, marginParams(-1, dp(28), 0, 0, 0, 0));
        ScrollView scroll = new ScrollView(this);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        scroll.addView(grid);
        appPanel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { rebuildAppGrid(grid, s.toString()); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
    }

    private void buildProjectionPanel() {
        projectionPanel = panelContainer();
        projectionPanel.addView(panelHeading("Màn hình chiếu", "Cấu hình mục tiêu và mở Android Auto."));
        LinearLayout card = card();
        card.addView(text("Ứng dụng đích", 11, MUTED));
        TextView value = text(getTargetLabel(), 17, TEXT);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(value);
        card.addView(text("Mục tiêu được lưu dùng cho phiên CarAssistant/Android Auto. Giao diện điện thoại chưa thể xác nhận phiên Virtual Display đang chạy nếu phiên đó chưa báo trạng thái qua IPC.", 12, MUTED));
        projectionPanel.addView(card, marginParams(-1, -2, 0, 0, 0, 10));
        projectionPanel.addView(button("Mở Android Auto", v -> openAndroidAuto()), marginParams(-1, dp(52), 0, 0, 0, 8));
        projectionPanel.addView(button("Chọn ứng dụng đích", v -> { loadApps(); showPanel(appPanel); }), marginParams(-1, dp(52), 0, 0, 0, 8));
        projectionPanel.addView(button("Làm mới trạng thái", v -> { updateDeviceCards(); Toast.makeText(this, "Đã làm mới thông tin cục bộ", Toast.LENGTH_SHORT).show(); }), marginParams(-1, dp(52), 0, 0, 0, 8));
    }

    private void buildKeyboardPanel() {
        keyboardPanel = panelContainer();
        keyboardPanel.addView(panelHeading("Bàn phím", "Giữ nguyên nền bàn phím hiện có; không thay thế IME."));
        LinearLayout card = card();
        card.addView(text("Phương thức nhập mặc định", 11, MUTED));
        keyboardValue = text(getDefaultKeyboard(), 15, TEXT);
        keyboardValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        keyboardValue.setPadding(0, dp(6), 0, dp(8));
        card.addView(keyboardValue);
        card.addView(text("Trang này chỉ kiểm tra và mở cài đặt bàn phím Android. Không tự đổi bàn phím, không xóa dữ liệu và không can thiệp vào cấu hình hiện tại.", 12, MUTED));
        keyboardPanel.addView(card, marginParams(-1, -2, 0, 0, 0, 10));
        keyboardPanel.addView(button("Mở cài đặt phương thức nhập", v -> {
            try { startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)); }
            catch (Exception e) { toast("Không mở được cài đặt bàn phím"); }
        }), marginParams(-1, dp(52), 0, 0, 0, 8));
        keyboardPanel.addView(button("Làm mới trạng thái", v -> {
            if (keyboardValue != null) keyboardValue.setText(getDefaultKeyboard());
        }), marginParams(-1, dp(52), 0, 0, 0, 8));
    }

    private void buildDiagnosticsPanel() {
        diagnosticsPanel = panelContainer();
        diagnosticsPanel.addView(panelHeading("Chẩn đoán", "Kiểm tra những gì ứng dụng có thể xác minh an toàn."));
        LinearLayout card = card();
        diagnosticRootValue = text(rootState, 13, TEXT);
        diagnosticAutoValue = text("Chưa kiểm tra", 13, TEXT);
        card.addView(text("Root / Magisk", 11, MUTED));
        card.addView(diagnosticRootValue);
        card.addView(text("Android Auto", 11, MUTED));
        card.addView(diagnosticAutoValue);
        card.addView(text("Ứng dụng đích đã lưu", 11, MUTED));
        TextView target = text(getTargetLabel(), 13, TEXT);
        card.addView(target);
        card.addView(text("Trạng thái LSPosed và việc hook nhận sự kiện đa điểm chưa thể xác nhận đáng tin cậy chỉ bằng kiểm tra giao diện này; cần dùng LSPosed/logcat khi chẩn đoán pinch.", 11, MUTED));
        diagnosticsPanel.addView(card, marginParams(-1, -2, 0, 0, 0, 10));
        diagnosticsPanel.addView(button("Kiểm tra lại root và ứng dụng", v -> refreshDeviceStatus()), marginParams(-1, dp(52), 0, 0, 0, 8));
        diagnosticsPanel.addView(button("Mở cài đặt ứng dụng CarAssistant", v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }), marginParams(-1, dp(52), 0, 0, 0, 8));
    }

    private void refreshDeviceStatus() {
        rootState = "Đang kiểm tra…";
        updateDeviceCards();
        new Thread(() -> {
            String result;
            try {
                Process process = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
                boolean ended = process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS);
                if (!ended) {
                    process.destroyForcibly();
                    result = "Không phản hồi";
                } else if (process.exitValue() == 0) {
                    result = "Root hoạt động";
                } else {
                    result = "Chưa có quyền root";
                }
            } catch (Throwable t) {
                result = "Không xác minh được root";
            }
            final String finalResult = result;
            runOnUiThread(() -> {
                rootState = finalResult;
                rootChecked = true;
                updateDeviceCards();
            });
        }, "CarAssistant-root-check").start();
    }

    private void updateDeviceCards() {
        if (rootValue != null) rootValue.setText(rootState);
        if (diagnosticRootValue != null) diagnosticRootValue.setText(rootState);
        String autoStatus = isPackageInstalled("com.google.android.projection.gearhead") ? "Đã cài đặt" : "Không tìm thấy";
        if (autoValue != null) autoValue.setText(autoStatus);
        if (diagnosticAutoValue != null) diagnosticAutoValue.setText(autoStatus);
        if (targetValue != null) targetValue.setText(getTargetLabel());
        if (keyboardValue != null) keyboardValue.setText(getDefaultKeyboard());
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openAndroidAuto() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.google.android.projection.gearhead");
        if (launch == null) {
            toast("Không tìm thấy Android Auto hoặc không có màn hình khởi chạy.");
            return;
        }
        try { startActivity(launch); }
        catch (Exception e) { toast("Không mở được Android Auto: " + e.getClass().getSimpleName()); }
    }

    private String getTargetLabel() {
        ComponentName target = V10SessionStore.getTarget(this);
        if (target == null) return "Chưa chọn ứng dụng đích";
        try {
            CharSequence label = getPackageManager().getActivityInfo(target, 0).loadLabel(getPackageManager());
            return label == null ? target.getPackageName() : label.toString();
        } catch (Exception e) {
            return target.getPackageName();
        }
    }

    private String getDefaultKeyboard() {
        try {
            String value = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (value == null || value.trim().isEmpty()) return "Chưa xác định";
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component == null) return value;
            List<InputMethodInfo> methods = ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).getEnabledInputMethodList();
            for (InputMethodInfo method : methods) {
                if (component.getPackageName().equals(method.getPackageName())) {
                    CharSequence label = method.loadLabel(getPackageManager());
                    return label == null ? component.getPackageName() : label.toString();
                }
            }
            return component.getPackageName();
        } catch (Throwable t) {
            return "Không đọc được";
        }
    }

    private void loadApps() {
        apps.clear();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> result = getPackageManager().queryIntentActivities(intent, 0);
        for (ResolveInfo info : result) {
            if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName)) continue;
            ComponentName component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            CharSequence label = info.loadLabel(getPackageManager());
            apps.add(new AppInfo(component, label == null ? info.activityInfo.packageName : label.toString()));
        }
        Collections.sort(apps, Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)));
        rebuildDock();
        if (appCount != null) appCount.setText(apps.size() + " ứng dụng tìm thấy");
        rebuildGridFromPanel();
    }

    private void rebuildDock() {
        if (dock == null) return;
        dock.removeAllViews();
        int count = Math.min(4, apps.size());
        for (int i = 0; i < count; i++) {
            AppInfo app = apps.get(i);
            TextView tile = buttonText(app.name);
            tile.setTextSize(11);
            tile.setMaxLines(2);
            tile.setOnClickListener(v -> openLocalApp(app));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1f);
            params.setMargins(dp(3), 0, dp(3), 0);
            dock.addView(tile, params);
        }
    }

    private void rebuildGridFromPanel() {
        if (appPanel == null || appPanel.getChildCount() < 4) return;
        View child = appPanel.getChildAt(3);
        if (!(child instanceof ScrollView)) return;
        View inside = ((ScrollView) child).getChildAt(0);
        if (inside instanceof GridLayout) rebuildAppGrid((GridLayout) inside, search == null ? "" : search.getText().toString());
    }

    private void rebuildAppGrid(GridLayout grid, String query) {
        grid.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (AppInfo app : apps) {
            if (!q.isEmpty() && !app.name.toLowerCase(Locale.ROOT).contains(q)
                    && !app.component.getPackageName().toLowerCase(Locale.ROOT).contains(q)) continue;
            grid.addView(appTile(app), new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)) {{
                        width = 0;
                        height = dp(132);
                        setMargins(dp(4), dp(4), dp(4), dp(4));
                    }});
            shown++;
        }
        if (appCount != null) appCount.setText(shown + " / " + apps.size() + " ứng dụng");
    }

    private LinearLayout appTile(AppInfo app) {
        LinearLayout tile = card();
        tile.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        Drawable drawable = null;
        try { drawable = getPackageManager().getApplicationIcon(app.component.getPackageName()); }
        catch (Exception ignored) { }
        if (drawable != null) icon.setImageDrawable(drawable);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView name = text(app.name, 11, TEXT);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        tile.addView(name, new LinearLayout.LayoutParams(-1, dp(36)));
        TextView open = text("MỞ", 10, ACCENT);
        open.setGravity(Gravity.CENTER);
        tile.addView(open, new LinearLayout.LayoutParams(-1, dp(20)));
        tile.setOnClickListener(v -> showAppActions(app));
        return tile;
    }

    private void showAppActions(AppInfo app) {
        LinearLayout dialog = new LinearLayout(this);
        dialog.setOrientation(LinearLayout.VERTICAL);
        dialog.setPadding(dp(18), dp(12), dp(18), dp(12));
        TextView title = text(app.name, 18, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        dialog.addView(title, marginParams(-1, dp(42), 0, 0, 0, 6));
        TextView open = buttonText("Mở trên điện thoại");
        open.setOnClickListener(v -> { openLocalApp(app); });
        dialog.addView(open, marginParams(-1, dp(48), 0, 0, 0, 6));
        TextView target = buttonText("Đặt làm ứng dụng chiếu");
        target.setOnClickListener(v -> {
            V10SessionStore.setTarget(this, app.component);
            updateDeviceCards();
            Toast.makeText(this, "Đã lưu mục tiêu: " + app.name, Toast.LENGTH_SHORT).show();
            showPanel(dashboard);
        });
        dialog.addView(target, marginParams(-1, dp(48), 0, 0, 0, 6));
        TextView cancel = buttonText("Hủy");
        cancel.setOnClickListener(v -> showPanel(appPanel));
        dialog.addView(cancel, marginParams(-1, dp(44), 0, 0, 0, 0));
        showPanel(dialog);
    }

    private void openLocalApp(AppInfo app) {
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setComponent(app.component);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } catch (Exception e) {
            toast("Không mở được " + app.name);
        }
    }

    private void showPanel(LinearLayout panel) {
        body.removeAllViews();
        if (panel.getParent() instanceof ViewGroup) ((ViewGroup) panel.getParent()).removeView(panel);
        body.addView(panel, new LinearLayout.LayoutParams(-1, -1));
    }

    private LinearLayout panelContainer() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(4), 0, dp(4));
        panel.setBackgroundColor(BG);
        return panel;
    }

    private LinearLayout panelHeading(String title, String subtitle) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(0, dp(4), 0, dp(12));
        TextView t = text(title, 21, TEXT);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(t);
        heading.addView(text(subtitle, 11, MUTED));
        return heading;
    }

    private LinearLayout statusCard(String title, TextView value) {
        LinearLayout c = card();
        c.addView(text(title, 9, MUTED));
        value.setPadding(0, dp(7), 0, 0);
        c.addView(value);
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) c.getLayoutParams();
        if (p != null) { p.setMargins(dp(3), 0, dp(3), 0); c.setLayoutParams(p); }
        return c;
    }

    private LinearLayout actionCard(String title, String subtitle, String icon, View.OnClickListener listener) {
        LinearLayout c = card();
        c.setBackground(background(CARD, 18));
        TextView glyph = text(icon, 22, ACCENT);
        c.addView(glyph);
        TextView t = text(title, 14, TEXT);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(3));
        c.addView(t);
        c.addView(text(subtitle, 10, MUTED));
        c.setOnClickListener(listener);
        return c;
    }

    private TextView button(String label, View.OnClickListener listener) {
        TextView b = buttonText(label);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(13), dp(12), dp(13), dp(12));
        box.setBackground(background(CARD, 18));
        return box;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        return bg;
    }

    private TextView buttonText(String value) {
        TextView view = text(value, 13, TEXT);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(4), dp(8), dp(4));
        view.setBackground(background(CARD_ALT, 14));
        return view;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams marginParams(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateClock() {
        Date now = new Date();
        if (clock != null) clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        if (date != null) date.setText(new SimpleDateFormat("EEEE, dd MMMM", Locale.getDefault()).format(now));
    }

    private String initial(String value) {
        return value == null || value.isEmpty() ? "?" : value.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static final class AppInfo {
        final ComponentName component;
        final String name;
        AppInfo(ComponentName component, String name) {
            this.component = component;
            this.name = name;
        }
    }
}
