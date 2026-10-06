package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/**
 * Liệt kê ứng dụng có thể mở trong pane + tiện ích UI.
 */
public final class AppCatalog {

    private AppCatalog() {
    }

    /**
     * Danh sách app LAUNCHER hợp lệ (exported/enabled, khác chính mình), sắp theo Collator.
     */
    public static List<AppEntry> load(Context context) {
        PackageManager pm = context.getPackageManager();
        List<AppEntry> out = new ArrayList<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null) {
                continue;
            }
            if (!ai.exported || !ai.enabled || !ai.applicationInfo.enabled) {
                continue;
            }
            if (ai.permission != null && context.checkSelfPermission(ai.permission) != 0) {
                continue;
            }
            if (context.getPackageName().equals(ai.packageName)) {
                continue;
            }
            String label = ri.loadLabel(pm).toString();
            out.add(new AppEntry(label, ai.packageName, new ComponentName(ai.packageName, ai.name)));
        }
        final Collator collator = Collator.getInstance();
        out.sort((a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    /** Ẩn status/nav bar (immersive). */
    public static void immersive(View view) {
        if (Build.VERSION.SDK_INT < 30) {
            view.setSystemUiVisibility(5894);
            return;
        }
        WindowInsetsController controller = view.getWindowInsetsController();
        if (controller == null) {
            return;
        }
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsets.Type.systemBars());
    }

    /** Tên hiển thị rút gọn cho accessibility. */
    public static CharSequence safeLabel(AppEntry entry, Context context) {
        if (entry == null) {
            return context.getString(R.string.choose_app);
        }
        return TextUtils.isEmpty(entry.label) ? entry.pkg : entry.label;
    }
}
