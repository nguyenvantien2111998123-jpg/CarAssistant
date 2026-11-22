package com.carassistant.v10;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Modern phone dashboard. It is deliberately isolated from the Android Auto projection runtime. */
public final class V10DashboardActivity extends Activity {
    private static final int BG = Color.rgb(8, 13, 22);
    private static final int SURFACE = Color.rgb(19, 28, 42);
    private static final int SURFACE_ALT = Color.rgb(25, 38, 55);
    private static final int TEXT = Color.rgb(242, 247, 255);
    private static final int MUTED = Color.rgb(155, 172, 192);
    private static final int CYAN = Color.rgb(48, 205, 220);
    private static final int BLUE = Color.rgb(76, 137, 255);
    private static final int PURPLE = Color.rgb(165, 112, 255);
    private static final int GREEN = Color.rgb(55, 210, 151);
    private static final int ORANGE = Color.rgb(255, 174, 77);

    private LinearLayout root;
    private LinearLayout content;
    private int selectedTab = 0;
    private final String[] tabNames = {"Tổng quan", "Cài đặt", "Hệ thống"};
    private final String[] tabIcons = {"⌂", "⚙", "✓"};

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildShell();
        renderTab(0);
    }

    @Override protected void onResume() {
        super.onResume();
        if (content != null && selectedTab == 0) renderTab(0);
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(6), dp(8), dp(6), dp(8));
        nav.setBackground(shape(Color.rgb(14, 22, 34), dp(18), Color.rgb(34, 49, 68), dp(1)));
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, dp(70));
        navParams.setMargins(dp(12), dp(4), dp(12), dp(10));
        root.addView(nav, navParams);
        for (int i = 0; i < tabNames.length; i++) {
            final int index = i;
            TextView item = new TextView(this);
            item.setGravity(Gravity.CENTER);
            item.setTextSize(10);
            item.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            item.setPadding(dp(2), dp(3), dp(2), dp(3));
            item.setOnClickListener(v -> renderTab(index));
            nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1f));
            item.setTag("tab_" + i);
        }
        updateNav();
    }

    private void renderTab(int tab) {
        selectedTab = tab;
        content.removeAllViews();
        updateNav();
        if (tab == 0) renderOverview();
        else if (tab == 1) renderSettingsHub();
        else renderSystem();
    }

    private void updateNav() {
        if (root == null || root.getChildCount() < 2) return;
        LinearLayout nav = (LinearLayout) root.getChildAt(1);
        for (int i = 0; i < nav.getChildCount(); i++) {
            TextView item = (TextView) nav.getChildAt(i);
            boolean active = i == selectedTab;
            item.setText(tabIcons[i] + "\\n" + tabNames[i]);
            item.setTextColor(active ? CYAN : MUTED);
            item.setBackground(active ? shape(Color.rgb(25, 55, 69), dp(13), 0, 0) : null);
        }
    }

    private void renderOverview() {
        addEyebrow("CARASSISTANT  •  SMART DRIVE");
        addTitle("Xin chào!", "Mọi điều khiển cho hành trình của bạn ở cùng một nơi.");
        LinearLayout hero = card(Color.rgb(17, 61, 78), dp(22));
        hero.setBackground(gradient(new int[]{Color.rgb(18, 82, 101), Color.rgb(29, 48, 91)}, dp(22)));
        TextView icon = text("✦", 30, Color.WHITE);
        hero.addView(icon, lp(-1, dp(40)));
        addText(hero, "TRẢI NGHIỆM LÁI XE THÔNG MINH", 11, Color.rgb(164, 235, 241), true);
        addText(hero, "CarAsistant", 25, Color.WHITE, true);
        addText(hero, "Quản lý ứng dụng chiếu, trợ lý giọng nói và media ngay trên điện thoại.", 13, Color.rgb(220, 237, 248), false);
        content.addView(hero, bottom(16));

        addSectionTitle("TÌNH TRẠNG QUYỀN", "Các quyền Android cần thiết");
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean notification = MediaSessionBridge.hasAccess(this);
        boolean accessibility = hasMediaAccessibilityAccess();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(row, bottom(16));
        addMetric(row, "🎙", "Micro", mic ? "Đã cấp" : "Cần cấp", mic ? GREEN : ORANGE);
        addMetric(row, "♫", "Media", notification ? "Đã cấp" : "Cần bật", notification ? GREEN : ORANGE);
        addMetric(row, "✧", "Trợ năng", accessibility ? "Đã bật" : "Tùy chọn", accessibility ? GREEN : BLUE);

        addInfoCard("THAO TÁC", "Dùng thanh điều hướng bên dưới để mở Cài đặt hoặc kiểm tra quyền hệ thống. Các cấu hình chi tiết chỉ xuất hiện tại một nơi.");
        addFooter();
    }

    private void renderSettingsHub() {
        addEyebrow("SETTINGS");
        addTitle("Cài đặt", "Quản lý các tùy chọn chiếu màn hình, trợ lý giọng nói và media tại một nơi.");
        addInfoCard("CẤU HÌNH TẬP TRUNG", "Ứng dụng nền, ứng dụng yêu thích, hướng màn hình, ứng dụng mặc định và khôi phục media được quản lý tại cùng một màn hình cài đặt.");
        addPrimaryButton("MỞ CẤU HÌNH CARASSISTANT", this::openSettings, BLUE);
    }

    private void renderProjection() {
        addEyebrow("DISPLAY EXPERIENCE");
        addTitle("Chiếu màn hình", "Thiết lập trải nghiệm ứng dụng trên màn hình xe.");
        addFeature("▣", "Ứng dụng nền Home", "Chọn ứng dụng hiển thị ở lớp nền khi chưa mở ứng dụng khác.", BLUE, this::openSettings);
        addFeature("★", "Ứng dụng hay dùng", "Quản lý tối đa 3 ứng dụng trên thanh truy cập nhanh.", ORANGE, this::openSettings);
        addFeature("↔", "Hướng màn hình", "Bật hoặc tắt ép ứng dụng chiếu sang ngang.", CYAN, this::openSettings);
        addInfoCard("LƯU Ý", "Các lựa chọn chiếu màn hình vẫn dùng cơ chế lưu cấu hình hiện tại. Giao diện mới không thay đổi luồng Android Auto hoặc phiên chiếu.");
        addPrimaryButton("MỞ CẤU HÌNH CHIẾU MÀN HÌNH", this::openSettings, BLUE);
    }

    private void renderAssistantMedia() {
        addEyebrow("VOICE & ENTERTAINMENT");
        addTitle("Trợ lý & Media", "Chọn ứng dụng ưu tiên và cách CarAsistant xử lý nội dung đang phát.");
        addFeature("🎙", "Ứng dụng mặc định", "Thiết lập app dẫn đường, nghe nhạc, video và TV.", PURPLE, this::openSettings);
        addFeature("♫", "Tự động khôi phục media", "Điều chỉnh hành vi khôi phục phiên media khi Android Auto kết nối.", CYAN, this::openSettings);
        addFeature("◉", "Điều khiển media", "Kiểm tra quyền truy cập thông báo và MediaSession.", GREEN, () -> openNotificationSettings());
        addInfoCard("TƯƠNG THÍCH", "Thẻ/widget CarAsistant Media, điều khiển phát và phím vô lăng tiếp tục sử dụng logic hiện có.");
        addPrimaryButton("MỞ CẤU HÌNH TRỢ LÝ & MEDIA", this::openSettings, PURPLE);
    }

    private void renderSystem() {
        addEyebrow("DEVICE & PERMISSIONS");
        addTitle("Hệ thống", "Kiểm tra quyền Android và các thành phần tích hợp.");
        addStatusLine("Microphone", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED, "Dùng cho trợ lý giọng nói");
        addStatusLine("Notification / Media access", MediaSessionBridge.hasAccess(this), "Cho phép đọc phiên media và metadata");
        addStatusLine("Media Auto Play Accessibility", hasMediaAccessibilityAccess(), "Hỗ trợ thao tác trong ứng dụng media");
        addFeature("🎙", "Cấp quyền micro", "Mở hộp thoại cấp quyền micro cho trợ lý.", GREEN, () -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4102));
        addFeature("♫", "Quyền truy cập Media", "Mở cài đặt Notification access của Android.", CYAN, this::openNotificationSettings);
        addFeature("✧", "Quyền Trợ năng", "Bật dịch vụ CarAsistant Media Auto Play nếu cần.", PURPLE, () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addFeature("ⓘ", "Thông tin ứng dụng", "Mở trang thông tin và quyền của CarAsistant.", BLUE, this::openAppDetails);
        addInfoCard("ROOT & LSPOSED", "Root/su và LSPosed cần được cấp, bật module và chọn scope thủ công trong ứng dụng quản lý tương ứng. CarAsistant không tự cấp được các quyền đặc biệt này.");
    }

    private void addEyebrow(String value) {
        TextView t = text(value, 10, CYAN);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLetterSpacing(0.12f);
        content.addView(t, bottom(8));
    }

    private void addTitle(String title, String subtitle) {
        TextView h = text(title, 29, TEXT);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(h, bottom(5));
        TextView s = text(subtitle, 14, MUTED);
        s.setLineSpacing(dp(3), 1f);
        content.addView(s, bottom(20));
    }

    private void addSectionTitle(String title, String subtitle) {
        TextView h = text(title, 12, TEXT);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(h, bottom(3));
        TextView s = text(subtitle, 12, MUTED);
        content.addView(s, bottom(10));
    }

    private void addFeature(String icon, String title, String description, int accent, Runnable action) {
        LinearLayout c = card(SURFACE, dp(17));
        c.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cp = bottom(9);
        content.addView(c, cp);
        TextView glyph = text(icon, 23, accent);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(shape(withAlpha(accent, 35), dp(14), 0, 0));
        c.addView(glyph, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), 0, dp(4), 0);
        c.addView(details, new LinearLayout.LayoutParams(0, -2, 1f));
        addText(details, title, 14, TEXT, true);
        TextView desc = text(description, 12, MUTED);
        desc.setLineSpacing(dp(2), 1f);
        details.addView(desc);
        TextView arrow = text("›", 28, accent);
        arrow.setGravity(Gravity.CENTER);
        c.addView(arrow, new LinearLayout.LayoutParams(dp(28), -1));
        c.setOnClickListener(v -> action.run());
        c.setClickable(true);
        c.setFocusable(true);
    }

    private void addMetric(LinearLayout row, String icon, String label, String value, int accent) {
        LinearLayout c = card(SURFACE, dp(15));
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(10), dp(11), dp(10), dp(11));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(105), 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        row.addView(c, p);
        addText(c, icon, 19, accent, true);
        addText(c, label, 11, MUTED, false);
        addText(c, value, 12, accent, true);
    }

    private void addStatusLine(String title, boolean granted, String detail) {
        LinearLayout c = card(SURFACE, dp(15));
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams p = bottom(8);
        content.addView(c, p);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        c.addView(top);
        addText(top, title, 14, TEXT, true);
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView status = text(granted ? "ĐÃ BẬT" : "CHƯA BẬT", 10, granted ? GREEN : ORANGE);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(status);
        addText(c, detail, 12, MUTED, false);
    }

    private void addInfoCard(String title, String message) {
        LinearLayout c = card(SURFACE_ALT, dp(16));
        c.setOrientation(LinearLayout.VERTICAL);
        addText(c, title, 10, CYAN, true);
        TextView body = text(message, 12, MUTED);
        body.setLineSpacing(dp(3), 1f);
        c.addView(body);
        content.addView(c, bottom(12));
    }

    private void addPrimaryButton(String label, Runnable action, int accent) {
        TextView b = text(label + "   →", 12, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(shape(accent, dp(15), 0, 0));
        b.setOnClickListener(v -> action.run());
        content.addView(b, bottom(12, dp(52)));
    }

    private void addAction(LinearLayout parent, String label, String symbol, int accent, Runnable action) {
        TextView b = text(label + "     " + symbol, 12, Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(shape(Color.rgb(20, 125, 145), dp(13), 0, 0));
        b.setOnClickListener(v -> action.run());
        parent.addView(b, bottom(0, dp(46)));
    }

    private void addFooter() {
        TextView footer = text("CARASSISTANT  •  DRIVE SMARTER", 10, Color.rgb(91, 111, 135));
        footer.setGravity(Gravity.CENTER);
        content.addView(footer, bottom(10, dp(34)));
    }

    private void openSettings() {
        startActivity(new Intent(this, V10SettingsActivity.class));
    }

    private void openNotificationSettings() {
        try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        catch (RuntimeException e) { openAppDetails(); }
    }

    private void openAppDetails() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private boolean hasMediaAccessibilityAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String expected = new android.content.ComponentName(this,
                MediaPlaybackAccessibilityService.class).flattenToString();
        for (String item : enabled.split(":")) {
            android.content.ComponentName c = android.content.ComponentName.unflattenFromString(item);
            if (c != null && expected.equals(c.flattenToString())) return true;
        }
        return false;
    }

    private void addText(LinearLayout parent, String value, float size, int color, boolean bold) {
        TextView t = text(value, size, color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(2), 0, dp(3));
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(true);
        return t;
    }

    private LinearLayout card(int color, int radius) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(15), dp(13), dp(15), dp(13));
        c.setBackground(shape(color, radius, Color.rgb(35, 51, 70), dp(1)));
        return c;
    }

    private GradientDrawable shape(int color, int radius, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, stroke);
        return d;
    }

    private GradientDrawable gradient(int[] colors, int radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        d.setCornerRadius(radius);
        return d;
    }

    private int withAlpha(int color, int alpha) { return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)); }
    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private LinearLayout.LayoutParams bottom(int margin) { return bottom(margin, -2); }
    private LinearLayout.LayoutParams bottom(int margin, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, height);
        p.setMargins(0, 0, 0, dp(margin));
        return p;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
