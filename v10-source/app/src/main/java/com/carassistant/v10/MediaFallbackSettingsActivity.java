package com.carassistant.v10;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Phone-side controls for the isolated fallback MediaSession service. */
public final class MediaFallbackSettingsActivity extends Activity {
    private static final int BG = Color.rgb(8, 13, 22);
    private static final int SURFACE = Color.rgb(19, 28, 42);
    private static final int TEXT = Color.rgb(242, 247, 255);
    private static final int MUTED = Color.rgb(155, 172, 192);
    private static final int CYAN = Color.rgb(48, 205, 220);
    private static final int GREEN = Color.rgb(55, 210, 151);
    private static final int ORANGE = Color.rgb(255, 174, 77);

    private Switch enabledSwitch;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(root);
        setContentView(scroll);

        TextView eyebrow = text("CARASSISTANT  •  MEDIA", 11, CYAN, true);
        root.addView(eyebrow, lp(-1, -2));

        TextView title = text("Media Dự Phòng", 27, TEXT, true);
        title.setPadding(0, dp(8), 0, dp(5));
        root.addView(title, lp(-1, -2));

        TextView intro = text(
                "Duy trì một MediaSession riêng của CarAsistant khi không có ứng dụng media bên ngoài đang cung cấp phiên hợp lệ.",
                14, MUTED, false);
        intro.setLineSpacing(dp(3), 1f);
        root.addView(intro, bottom(18));

        LinearLayout card = card();
        root.addView(card, bottom(14));
        enabledSwitch = new Switch(this);
        enabledSwitch.setText("Bật Media Dự Phòng");
        enabledSwitch.setTextColor(TEXT);
        enabledSwitch.setTextSize(16);
        enabledSwitch.setPadding(0, dp(4), 0, dp(8));
        card.addView(enabledSwitch, lp(-1, -2));
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            MediaFallbackSessionService.setEnabled(this, checked);
            refreshStatus();
            if (checked && !MediaFallbackSessionService.hasAccess(this)) {
                openNotificationAccess();
            }
        });

        status = text("", 13, MUTED, false);
        status.setLineSpacing(dp(3), 1f);
        card.addView(status, lp(-1, -2));

        TextView accessButton = button("MỞ QUYỀN TRUY CẬP MEDIA DỰ PHÒNG");
        root.addView(accessButton, bottom(12));
        accessButton.setOnClickListener(v -> openNotificationAccess());

        LinearLayout info = card();
        root.addView(info, bottom(14));
        addText(info, "CÁCH HOẠT ĐỘNG", 11, CYAN, true);
        addText(info, "• Theo dõi phiên media từ ứng dụng khác như YouTube, YouTube Music, Zing MP3 và Chrome.", 13, MUTED, false);
        addText(info, "• Phiên đang phát hoặc đang tạm dừng nhưng vẫn có metadata/quyền điều khiển được xem là hợp lệ.", 13, MUTED, false);
        addText(info, "• Khi không còn phiên hợp lệ, Media Dự Phòng kích hoạt MediaSession riêng, chuyển PLAYING trong khoảng 350 ms rồi PAUSED.", 13, MUTED, false);
        addText(info, "• Không phát âm thanh, không giành Audio Focus và không điều khiển nội dung của ứng dụng khác.", 13, MUTED, false);

        LinearLayout warning = card();
        root.addView(warning, bottom(18));
        addText(warning, "LƯU Ý", 11, ORANGE, true);
        addText(warning,
                "Android Auto tự quyết định MediaSession nào được ưu tiên. Việc duy trì phiên dự phòng không bảo đảm Android Auto luôn chọn phiên này; cần kiểm tra trên điện thoại và xe thực tế.",
                13, MUTED, false);

        TextView back = button("QUAY LẠI");
        root.addView(back, lp(-1, dp(48)));
        back.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (enabledSwitch != null) {
            enabledSwitch.setOnCheckedChangeListener(null);
            enabledSwitch.setChecked(MediaFallbackSessionService.isEnabled(this));
            enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
                MediaFallbackSessionService.setEnabled(this, checked);
                refreshStatus();
                if (checked && !MediaFallbackSessionService.hasAccess(this)) {
                    openNotificationAccess();
                }
            });
        }
        if (status == null) return;
        boolean enabled = MediaFallbackSessionService.isEnabled(this);
        boolean access = MediaFallbackSessionService.hasAccess(this);
        if (!enabled) {
            status.setText("Đang tắt. Các MediaSession khác của CarAsistant không bị ảnh hưởng.");
            status.setTextColor(MUTED);
        } else if (!access) {
            status.setText("Đã yêu cầu bật, nhưng chưa cấp Notification Access cho dịch vụ Media Dự Phòng.");
            status.setTextColor(ORANGE);
        } else if (MediaFallbackSessionService.isRunning()) {
            status.setText("Đã bật • Dịch vụ đang theo dõi MediaSession.");
            status.setTextColor(GREEN);
        } else {
            status.setText("Đã bật quyền • Đang chờ Android kết nối dịch vụ.");
            status.setTextColor(CYAN);
        }
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (RuntimeException error) {
            status.setText("Không mở được cài đặt Notification Access: " + error.getMessage());
            status.setTextColor(ORANGE);
        }
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15), dp(14), dp(15), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.rgb(35, 51, 70));
        box.setBackground(bg);
        return box;
    }

    private TextView button(String value) {
        TextView view = text(value, 12, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(25, 110, 135));
        bg.setCornerRadius(dp(14));
        view.setBackground(bg);
        return view;
    }

    private void addText(LinearLayout parent, String value, float size, int color, boolean bold) {
        TextView view = text(value, size, color, bold);
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(0, dp(3), 0, dp(5));
        parent.addView(view, lp(-1, -2));
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams bottom(int margin) {
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.setMargins(0, 0, 0, dp(margin));
        return p;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
