package com.carassistant.v10;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Phone-side permission and projection diagnostics screen. */
public final class V10SettingsActivity extends Activity {
    private LinearLayout list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(7, 9, 12));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(18), dp(18), dp(18), dp(18));
        scroll.addView(list);
        setContentView(scroll);

        TextView title = text("CAR ASSISTANT · SETUPS", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        list.addView(title, params(-1, dp(54)));

        TextView intro = text(
                "Cấu hình trên điện thoại. App được chọn để chiếu được chọn riêng trong mục APPS; màn hình chiếu vẫn là lớp nền dưới dashboard.",
                14, Color.LTGRAY);
        list.addView(intro, params(-1, dp(82)));

        addButton("CẤP QUYỀN VỊ TRÍ", () -> requestPermissions(
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 4101));
        addButton("CẤP QUYỀN MICRO", () ->
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4102));
        addButton("MỞ CÀI ĐẶT ỨNG DỤNG", () -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        addButton("QUAY LẠI", this::finish);

        TextView note = text(
                "Lưu ý: quyền root/su phải được cấp trong trình quản lý root; LSPosed phải bật module và scope phù hợp. Android không cho ứng dụng tự cấp các quyền đặc biệt này.",
                13, Color.LTGRAY);
        note.setPadding(0, dp(18), 0, dp(18));
        list.addView(note, params(-1, -2));
    }

    private void refreshStatus() {
        if (list == null) return;
        // Keep diagnostics visible after returning from system settings.
        TextView status = text(
                "Vị trí: " + granted(Manifest.permission.ACCESS_FINE_LOCATION)
                + "   •   Micro: " + granted(Manifest.permission.RECORD_AUDIO)
                + "\nRoot/LSPosed: cần kiểm tra riêng trong ứng dụng quản lý tương ứng.",
                14, Color.WHITE);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        status.setBackgroundColor(Color.rgb(22, 27, 33));
        if (list.getChildCount() > 1 && "permission_status".equals(list.getChildAt(1).getTag())) {
            list.removeViewAt(1);
        }
        status.setTag("permission_status");
        list.addView(status, 1, params(-1, -2));
    }

    private String granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                ? "ĐÃ CẤP" : "CHƯA CẤP";
    }

    private void addButton(String label, Runnable action) {
        TextView b = text(label, 15, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(Color.rgb(29, 35, 42));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = params(-1, dp(54));
        p.setMargins(0, dp(5), 0, dp(5));
        list.addView(b, p);
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams params(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
