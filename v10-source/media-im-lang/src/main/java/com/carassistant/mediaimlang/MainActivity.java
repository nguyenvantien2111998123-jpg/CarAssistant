package com.carassistant.mediaimlang;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 36, 28, 28);

        TextView title = new TextView(this);
        title.setText("Media Im Lặng");
        title.setTextSize(24);
        root.addView(title);

        TextView detail = new TextView(this);
        detail.setText("Ứng dụng media nền cho Android Auto. Cấp quyền Truy cập thông báo để ứng dụng nhận biết khi YouTube, YouTube Music, Zing MP3 hoặc ứng dụng media khác đang phát. Ứng dụng tạo playlist gồm 3 file MP4, mỗi file 30 phút. Dữ liệu AAC là số 0 (không có tone/tần số nghe được) và âm lượng MediaPlayer luôn bằng 0. Hỗ trợ Next, Previous, tua tiến/lùi và tự chuyển file.");
        detail.setTextSize(16);
        root.addView(detail);

        Button access = new Button(this);
        access.setText("Bật quyền truy cập thông báo");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(access);

        Button start = new Button(this);
        start.setText("Khởi động Media Im Lặng");
        start.setOnClickListener(v -> {
            Intent service = new Intent(this, SilentMediaBrowserService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        });
        root.addView(start);
        setContentView(root);
    }
}
