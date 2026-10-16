package com.carassistant.v10;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Phone-side setup screen with tap-to-expand sections. */
public final class V10SettingsActivity extends Activity {
    private LinearLayout list;
    private LinearLayout favoriteChoices;
    private LinearLayout backgroundChoices;
    private TextView favoriteStatus;
    private TextView selectedBackground;
    private TextView permissionStatus;
    private final ArrayList<ResolveInfo> availableApps = new ArrayList<>();

    private interface SectionBuilder {
        void build(LinearLayout body);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadAvailableApps();
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        rebuildFavoriteAppChoices();
        rebuildBackgroundAppChoices();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7, 9, 12));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(14), dp(16), dp(18));
        scroll.addView(list);
        setContentView(scroll);

        TextView title = text("CAR ASSISTANT · SETUPS", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        list.addView(title, params(-1, dp(52)));

        TextView intro = text(
                "Cài đặt nhanh cho giao diện CarAssistant trên điện thoại. Chạm từng mục để mở chi tiết; chạm lần nữa để thu gọn.",
                14, Color.LTGRAY);
        intro.setPadding(0, 0, 0, dp(12));
        list.addView(intro, params(-1, -2));

        addSection("NỀN HOME", "Chọn ứng dụng chạy ở lớp nền", body -> {
            selectedBackground = text("", 14, Color.LTGRAY);
            selectedBackground.setPadding(dp(4), dp(4), dp(4), dp(8));
            body.addView(selectedBackground, params(-1, -2));
            backgroundChoices = new LinearLayout(this);
            backgroundChoices.setOrientation(LinearLayout.VERTICAL);
            body.addView(backgroundChoices, params(-1, -2));
            addButton(body, "TẮT APP NỀN HOME", () -> {
                V10SessionStore.setBackgroundTarget(this, null);
                refreshBackgroundLabel();
                rebuildBackgroundAppChoices();
            });
            rebuildBackgroundAppChoices();
        });

        addSection("ỨNG DỤNG HAY DÙNG", "Chọn tối đa 3 ứng dụng cho thanh dọc bên trái", body -> {
            TextView hint = text(
                    "Chạm ứng dụng để chọn hoặc bỏ chọn. Danh sách lựa chọn được lưu lại.",
                    13, Color.LTGRAY);
            hint.setPadding(dp(4), dp(2), dp(4), dp(8));
            body.addView(hint, params(-1, -2));
            favoriteStatus = text("", 14, Color.WHITE);
            favoriteStatus.setPadding(dp(4), dp(4), dp(4), dp(8));
            body.addView(favoriteStatus, params(-1, -2));
            favoriteChoices = new LinearLayout(this);
            favoriteChoices.setOrientation(LinearLayout.VERTICAL);
            body.addView(favoriteChoices, params(-1, -2));
            rebuildFavoriteAppChoices();
        });

        addSection("QUYỀN VÀ CHẨN ĐOÁN", "Vị trí, micro, cài đặt ứng dụng, Root/LSPosed", body -> {
            permissionStatus = text("", 14, Color.WHITE);
            permissionStatus.setPadding(dp(12), dp(10), dp(12), dp(10));
            permissionStatus.setBackgroundColor(Color.rgb(22, 27, 33));
            body.addView(permissionStatus, params(-1, -2));

            addButton(body, "CẤP QUYỀN VỊ TRÍ", () -> requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, 4101));
            addButton(body, "CẤP QUYỀN MICRO", () ->
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4102));
            addButton(body, "MỞ CÀI ĐẶT ỨNG DỤNG", () -> {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            });

            TextView note = text(
                    "Root/su phải được cấp trong trình quản lý root. LSPosed cần bật module và chọn scope phù hợp. Android không cho ứng dụng tự cấp các quyền đặc biệt này.",
                    13, Color.LTGRAY);
            note.setPadding(dp(4), dp(12), dp(4), dp(8));
            body.addView(note, params(-1, -2));
        });

        addButton(list, "QUAY LẠI", this::finish);
        refreshStatus();
    }

    /** Every setup section starts collapsed and toggles when its header is tapped. */
    private void addSection(String heading, String subtitle, SectionBuilder builder) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(Color.rgb(16, 20, 25));
        LinearLayout.LayoutParams sectionParams = params(-1, -2);
        sectionParams.setMargins(0, dp(5), 0, dp(5));
        list.addView(section, sectionParams);

        TextView header = text("", 15, Color.WHITE);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setPadding(dp(14), dp(12), dp(12), dp(12));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.rgb(29, 35, 42));
        section.addView(header, params(-1, -2));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(10), dp(8), dp(10), dp(10));
        builder.build(body);
        body.setVisibility(View.GONE);
        section.addView(body, params(-1, -2));

        header.setTag(Boolean.FALSE);
        header.setOnClickListener(v -> {
            boolean expanded = Boolean.TRUE.equals(header.getTag());
            header.setTag(!expanded);
            body.setVisibility(expanded ? View.GONE : View.VISIBLE);
            updateSectionHeader(header, heading, subtitle, !expanded);
        });
        updateSectionHeader(header, heading, subtitle, false);
    }

    private void updateSectionHeader(TextView header, String heading, String subtitle, boolean expanded) {
        header.setText((expanded ? "▾  " : "▸  ") + heading + "\n"
                + subtitle + (expanded ? "" : "\nChạm để xem chi tiết"));
    }

    private void loadAvailableApps() {
        List<ResolveInfo> results = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        availableApps.clear();
        for (ResolveInfo info : results) {
            if (info.activityInfo != null
                    && !getPackageName().equals(info.activityInfo.packageName)) {
                availableApps.add(info);
            }
        }
        Collections.sort(availableApps, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(getPackageManager()))
                        .compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager())));
            }
        });
    }

    private void rebuildBackgroundAppChoices() {
        if (backgroundChoices == null) return;
        backgroundChoices.removeAllViews();
        ComponentName current = V10SessionStore.getBackgroundTarget(this);
        refreshBackgroundLabel();
        for (ResolveInfo info : availableApps) {
            ComponentName component = new ComponentName(
                    info.activityInfo.packageName, info.activityInfo.name);
            String label = String.valueOf(info.loadLabel(getPackageManager()));
            addButton(backgroundChoices,
                    label + (component.equals(current) ? "  ✓ ĐANG CHỌN" : ""),
                    () -> {
                        V10SessionStore.setBackgroundTarget(this, component);
                        refreshBackgroundLabel();
                        rebuildBackgroundAppChoices();
                    });
        }
    }

    private void refreshBackgroundLabel() {
        if (selectedBackground == null) return;
        ComponentName current = V10SessionStore.getBackgroundTarget(this);
        selectedBackground.setText(current == null
                ? "Chưa chọn ứng dụng chiếu nền"
                : "Đang chọn: " + current.getPackageName());
    }

    private void rebuildFavoriteAppChoices() {
        if (favoriteChoices == null || favoriteStatus == null) return;
        favoriteChoices.removeAllViews();
        ArrayList<ComponentName> selected = V10SessionStore.getQuickLaunchApps(this);
        favoriteStatus.setText("Đã chọn " + selected.size() + " / 3 ứng dụng");
        for (ResolveInfo info : availableApps) {
            ComponentName component = new ComponentName(
                    info.activityInfo.packageName, info.activityInfo.name);
            String label = String.valueOf(info.loadLabel(getPackageManager()));
            boolean checked = isFavoriteSelected(selected, component);
            addFavoriteChoiceButton(label + (checked ? "   ✓ ĐÃ CHỌN" : ""), component, checked);
        }
    }

    private boolean isFavoriteSelected(List<ComponentName> selected, ComponentName candidate) {
        for (ComponentName item : selected) {
            if (item != null && item.getPackageName().equals(candidate.getPackageName())) return true;
        }
        return false;
    }

    private void addFavoriteChoiceButton(String label, ComponentName component, boolean checked) {
        TextView button = text(label, 14, Color.WHITE);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(12), 0, dp(10), 0);
        button.setBackgroundColor(checked ? Color.rgb(35, 75, 85) : Color.rgb(29, 35, 42));
        LinearLayout.LayoutParams p = params(-1, dp(46));
        p.setMargins(0, dp(3), 0, dp(3));
        favoriteChoices.addView(button, p);
        button.setOnClickListener(v -> {
            ArrayList<ComponentName> selected = V10SessionStore.getQuickLaunchApps(this);
            int found = -1;
            for (int i = 0; i < selected.size(); i++) {
                if (selected.get(i).getPackageName().equals(component.getPackageName())) {
                    found = i;
                    break;
                }
            }
            if (found >= 0) {
                selected.remove(found);
            } else {
                if (selected.size() >= 3) {
                    favoriteStatus.setText("Đã đủ 3 app. Bỏ chọn một app trước khi chọn app khác.");
                    return;
                }
                selected.add(component);
            }
            V10SessionStore.setQuickLaunchApps(this, selected);
            rebuildFavoriteAppChoices();
        });
    }

    private void refreshStatus() {
        if (permissionStatus == null) return;
        permissionStatus.setText(
                "Vị trí: " + granted(Manifest.permission.ACCESS_FINE_LOCATION)
                + "   •   Micro: " + granted(Manifest.permission.RECORD_AUDIO)
                + "\nRoot/LSPosed: cần kiểm tra riêng trong ứng dụng quản lý tương ứng.");
    }

    private String granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                ? "ĐÃ CẤP" : "CHƯA CẤP";
    }

    private void addButton(LinearLayout parent, String label, Runnable action) {
        TextView button = text(label, 14, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), dp(6), dp(8), dp(6));
        button.setBackgroundColor(Color.rgb(29, 35, 42));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = params(-1, -2);
        p.height = dp(48);
        p.setMargins(0, dp(3), 0, dp(3));
        parent.addView(button, p);
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(true);
        return t;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
