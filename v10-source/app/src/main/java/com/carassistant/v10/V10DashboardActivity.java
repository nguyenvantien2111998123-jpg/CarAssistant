package com.carassistant.v10;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Compact phone dashboard. Kept separate from the Android Auto projection runtime. */
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

    private LinearLayout content;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        if (content != null) renderDashboard();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(content);
        setContentView(scroll);
        renderDashboard();
    }

    private void renderDashboard() {
        content.removeAllViews();
        addEyebrow("CARASSISTANT  •  SMART DRIVE");
        addTitle("CarAsistant", "Chọn một mục để cấu hình riêng.");

        addSectionTitle("CÀI ĐẶT", "Mỗi mục mở danh sách thiết lập tương ứng");
        addFeature("⚙", "Cấp quyền", "Micro, Media Session, Trợ năng, Root và LSPosed.",
                GREEN, () -> openSettings("CẤP QUYỀN"));
        addFeature("↔", "Ép ngang ứng dụng", "Bật hoặc tắt ép ngang cho các ứng dụng đang chiếu.",
                CYAN, () -> openSettings("ÉP NGANG ỨNG DỤNG"));
        addFeature("★", "Ứng dụng hay dùng", "Chọn tối đa 3 ứng dụng để mở toàn màn hình phụ 2.",
                ORANGE, () -> openSettings("ỨNG DỤNG HAY DÙNG"));
        addFeature("▦", "Menu app vùng tỉ lệ 3/7", "Chọn không giới hạn ứng dụng được phép chiếu ở vùng 3 (30%).",
                BLUE, () -> openSettings("APP VÙNG 3"));
        addFeature("🎙", "Trợ lý mặc định", "Chọn ứng dụng riêng cho Dẫn đường, Nghe nhạc, Video và TV.",
                PURPLE, () -> openSettings("TRỢ LÝ MẶC ĐỊNH"));

        LinearLayout projection = card(SURFACE_ALT, dp(16));
        addText(projection, "BỐ CỤC MÀN HÌNH XE", 10, CYAN, true);
        addText(projection, "Màn hình phụ 1 chia hai vùng tỉ lệ 3/7. Mỗi vùng có menu chọn ứng dụng riêng; từ menu có thể chuyển ứng dụng đang chọn sang chiếu toàn màn hình phụ 2.",
                12, MUTED, false);
        content.addView(projection, bottom(12));

        TextView footer = text("CARASSISTANT  •  DRIVE SMARTER", 10, Color.rgb(91, 111, 135));
        footer.setGravity(Gravity.CENTER);
        content.addView(footer, bottom(8, dp(34)));
    }

    private void addPermissionRow(LinearLayout parent, String label, boolean enabled, String action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        TextView name = text(label, 13, Color.WHITE);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView state = text(enabled ? "ĐÃ BẬT" : "CẦN KIỂM TRA", 10,
                enabled ? GREEN : ORANGE);
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        state.setPadding(dp(8), dp(5), dp(8), dp(5));
        state.setBackground(shape(Color.rgb(22, 38, 48), dp(10), 0, 0));
        row.addView(state);
        row.setOnClickListener(v -> openSettings("QUYỀN & CHẨN ĐOÁN"));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(action);
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
        content.addView(s, bottom(18));
    }

    private void addSectionTitle(String title, String subtitle) {
        TextView h = text(title, 12, TEXT);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(h, bottom(3));
        content.addView(text(subtitle, 12, MUTED), bottom(10));
    }

    private void addFeature(String icon, String title, String description, int accent, Runnable action) {
        LinearLayout c = card(SURFACE, dp(16));
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(c, bottom(9));
        TextView glyph = text(icon, 22, accent);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(shape(Color.rgb(Color.red(accent) / 5,
                Color.green(accent) / 5, Color.blue(accent) / 5), dp(13), 0, 0));
        c.addView(glyph, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), 0, dp(4), 0);
        c.addView(details, new LinearLayout.LayoutParams(0, -2, 1f));
        addText(details, title, 14, TEXT, true);
        TextView desc = text(description, 12, MUTED);
        desc.setLineSpacing(dp(2), 1f);
        details.addView(desc);
        TextView arrow = text("›", 27, accent);
        arrow.setGravity(Gravity.CENTER);
        c.addView(arrow, new LinearLayout.LayoutParams(dp(24), -1));
        c.setOnClickListener(v -> action.run());
        c.setClickable(true);
        c.setFocusable(true);
    }

    private void openSettings(String section) {
        Intent intent = new Intent(this, V10SettingsActivity.class);
        intent.putExtra(V10SettingsActivity.EXTRA_OPEN_SECTION, section);
        startActivity(intent);
    }

    private boolean hasMediaAccessibilityAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String expected = new android.content.ComponentName(this,
                MediaPlaybackAccessibilityService.class).flattenToString();
        for (String item : enabled.split(":")) {
            android.content.ComponentName component =
                    android.content.ComponentName.unflattenFromString(item);
            if (component != null && expected.equals(component.flattenToString())) return true;
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

    private LinearLayout.LayoutParams bottom(int margin) { return bottom(margin, -2); }
    private LinearLayout.LayoutParams bottom(int margin, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, height);
        p.setMargins(0, 0, 0, dp(margin));
        return p;
    }
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
