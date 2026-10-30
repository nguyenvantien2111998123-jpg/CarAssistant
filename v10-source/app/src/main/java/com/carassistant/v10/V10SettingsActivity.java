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

import com.carassistant.v10.RootShellSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Phone-side setup screen with tap-to-expand sections. */
public final class V10SettingsActivity extends Activity {
    public static final String EXTRA_REQUEST_MICROPHONE = "request_microphone_permission";
    private boolean returnAfterMicPermission;
    private LinearLayout list;
    private LinearLayout favoriteChoices;
    private LinearLayout backgroundChoices;
    private TextView favoriteStatus;
    private TextView selectedBackground;
    private TextView permissionStatus;
    private TextView orientationStatus;
    private LinearLayout defaultVoiceAppChoices;
    private TextView defaultVoiceAppStatus;
    private TextView permissionGuideStatus;
    private TextView permissionGuideAction;
    private boolean permissionRequestInFlight;
    private String selectedDefaultVoiceCategory = "navigation";
    private final RootShellSession orientationShell = new RootShellSession();
    private final ArrayList<ResolveInfo> availableApps = new ArrayList<>();

    private interface SectionBuilder {
        void build(LinearLayout body);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        returnAfterMicPermission = getIntent().getBooleanExtra(EXTRA_REQUEST_MICROPHONE, false);
        loadAvailableApps();
        buildUi();
        if (!returnAfterMicPermission) getWindow().getDecorView().post(this::startFirstLaunchPermissionFlow);
        if (returnAfterMicPermission) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                finish();
            } else {
                getWindow().getDecorView().post(() ->
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4102));
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshPermissionGuide();
        rebuildFavoriteAppChoices();
        rebuildBackgroundAppChoices();
        refreshOrientationStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(10, 14, 20));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(18), dp(20), dp(18), dp(24));
        scroll.addView(list);
        setContentView(scroll);

        TextView title = text("CarAsistant", 27, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        list.addView(title, params(-1, dp(42)));
        TextView eyebrow = text("TRUNG TÂM ĐIỀU KHIỂN & CÀI ĐẶT", 11, Color.rgb(91, 190, 207));
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        list.addView(eyebrow, params(-1, dp(24)));

        TextView intro = text(
                "Quản lý quyền, ứng dụng mặc định và cấu hình màn hình chiếu tại một nơi.",
                14, Color.rgb(183, 194, 207));
        intro.setPadding(0, 0, 0, dp(12));
        list.addView(intro, params(-1, -2));

        addPermissionGuideCard();

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

        addSection("HƯỚNG MÀN HÌNH CHIẾU", "Tùy chọn ép ứng dụng trên màn hình phụ sang ngang", body -> {
            TextView hint = text(
                    "Khi bật, CarAssistant cố định hướng ngang riêng cho màn hình chiếu 2. Không thay đổi hướng màn hình điện thoại. Cần LSPosed hoạt động trong system_server; có thể cần mở lại ứng dụng đang chiếu để áp dụng.",
                    13, Color.LTGRAY);
            hint.setPadding(dp(4), dp(2), dp(4), dp(8));
            body.addView(hint, params(-1, -2));

            orientationStatus = text("Đang kiểm tra trạng thái…", 14, Color.WHITE);
            orientationStatus.setPadding(dp(8), dp(8), dp(8), dp(8));
            body.addView(orientationStatus, params(-1, -2));

            addButton(body, "BẬT ÉP ỨNG DỤNG CHIẾU NGANG", () -> setForceLandscape(true));
            addButton(body, "TẮT ÉP ỨNG DỤNG CHIẾU NGANG", () -> setForceLandscape(false));
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

        addSection("ỨNG DỤNG MẶC ĐỊNH CHO TRỢ LÝ", "Chọn app ưu tiên cho dẫn đường, nhạc, video và TV", body -> {
            TextView hint = text(
                    "Lựa chọn được lưu riêng theo từng nhóm. Đây là ứng dụng ưu tiên khi câu lệnh không nêu tên ứng dụng.",
                    13, Color.LTGRAY);
            hint.setPadding(dp(4), dp(2), dp(4), dp(8));
            body.addView(hint, params(-1, -2));

            addButton(body, "DẪN ĐƯỜNG", () -> selectDefaultVoiceCategory("navigation"));
            addButton(body, "NGHE NHẠC", () -> selectDefaultVoiceCategory("music"));
            addButton(body, "XEM VIDEO", () -> selectDefaultVoiceCategory("video"));
            addButton(body, "XEM TV", () -> selectDefaultVoiceCategory("tv"));

            defaultVoiceAppStatus = text("", 14, Color.WHITE);
            defaultVoiceAppStatus.setPadding(dp(4), dp(8), dp(4), dp(8));
            body.addView(defaultVoiceAppStatus, params(-1, -2));

            defaultVoiceAppChoices = new LinearLayout(this);
            defaultVoiceAppChoices.setOrientation(LinearLayout.VERTICAL);
            body.addView(defaultVoiceAppChoices, params(-1, -2));

            addButton(body, "BỎ CHỌN ỨNG DỤNG MẶC ĐỊNH", () -> {
                V10SessionStore.setDefaultVoiceApp(this, selectedDefaultVoiceCategory, null);
                rebuildDefaultVoiceAppChoices();
            });
            rebuildDefaultVoiceAppChoices();
        });

        addSection("QUYỀN & CHẨN ĐOÁN", "Theo dõi quyền hệ thống và trạng thái tích hợp", body -> {
            permissionStatus = text("", 14, Color.WHITE);
            permissionStatus.setPadding(dp(12), dp(10), dp(12), dp(10));
            permissionStatus.setBackgroundColor(Color.rgb(22, 27, 33));
            body.addView(permissionStatus, params(-1, -2));

            addButton(body, "CẤP QUYỀN VỊ TRÍ", () -> requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, 4101));
            addButton(body, "CẤP QUYỀN MICRO", () ->
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4102));
            addButton(body, "MỞ QUYỀN TRỢ NĂNG MEDIA AUTO PLAY", this::openAccessibilitySettings);
            addButton(body, "MỞ QUYỀN TRUY CẬP THÔNG BÁO / MEDIA", this::openNotificationAccessSettings);

            TextView mediaHint = text(
                    "Để trợ lý tự chọn kết quả và bấm Phát trong YouTube/Zing MP3, mở Trợ năng và bật dịch vụ “CarAsistant Media Auto Play”. Android yêu cầu người dùng tự bật quyền này; ứng dụng không thể tự cấp.",
                    13, Color.LTGRAY);
            mediaHint.setPadding(dp(4), dp(10), dp(4), dp(8));
            body.addView(mediaHint, params(-1, -2));

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
        section.setBackgroundColor(Color.rgb(17, 25, 34));
        LinearLayout.LayoutParams sectionParams = params(-1, -2);
        sectionParams.setMargins(0, dp(5), 0, dp(5));
        list.addView(section, sectionParams);

        TextView header = text("", 15, Color.WHITE);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setPadding(dp(14), dp(12), dp(12), dp(12));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.rgb(27, 39, 51));
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

    private void selectDefaultVoiceCategory(String category) {
        selectedDefaultVoiceCategory = category;
        rebuildDefaultVoiceAppChoices();
    }

    private String defaultVoiceCategoryLabel(String category) {
        if ("navigation".equals(category)) return "DẪN ĐƯỜNG";
        if ("music".equals(category)) return "NGHE NHẠC";
        if ("video".equals(category)) return "XEM VIDEO";
        return "XEM TV";
    }

    private void rebuildDefaultVoiceAppChoices() {
        if (defaultVoiceAppChoices == null || defaultVoiceAppStatus == null) return;
        defaultVoiceAppChoices.removeAllViews();
        ComponentName selected = V10SessionStore.getDefaultVoiceApp(this, selectedDefaultVoiceCategory);
        defaultVoiceAppStatus.setText("Nhóm: " + defaultVoiceCategoryLabel(selectedDefaultVoiceCategory)
                + "\nỨng dụng đang chọn: "
                + (selected == null ? "Chưa chọn" : appLabel(selected.getPackageName())));

        for (ResolveInfo info : availableApps) {
            if (info.activityInfo == null) continue;
            ComponentName component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            boolean checked = selected != null
                    && selected.getPackageName().equals(component.getPackageName());
            String label = String.valueOf(info.loadLabel(getPackageManager()));
            addButton(defaultVoiceAppChoices, label + (checked ? "  ✓ ĐANG CHỌN" : ""), () -> {
                V10SessionStore.setDefaultVoiceApp(this, selectedDefaultVoiceCategory, component);
                rebuildDefaultVoiceAppChoices();
            });
        }
    }

    private String appLabel(String packageName) {
        for (ResolveInfo info : availableApps) {
            if (info.activityInfo != null
                    && packageName.equals(info.activityInfo.packageName)) {
                return String.valueOf(info.loadLabel(getPackageManager()));
            }
        }
        return packageName;
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 4102 && requestCode != 4101) return;
        permissionRequestInFlight = false;
        refreshStatus();
        refreshPermissionGuide();
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (returnAfterMicPermission && granted) {
            finish();
        } else if (returnAfterMicPermission) {
            returnAfterMicPermission = false;
        }
    }

    private void refreshOrientationStatus() {
        if (orientationStatus == null) return;
        orientationStatus.setText("Đang kiểm tra trạng thái…");
        RootShellSession.EXEC.execute(() -> {
            String value = null;
            String error = null;
            try {
                value = orientationShell.run(6,
                        "settings get global carassistant_force_landscape");
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final String result = value;
            final String failure = error;
            runOnUiThread(() -> {
                if (orientationStatus == null) return;
                if (failure != null) {
                    orientationStatus.setText(
                            "Không đọc được trạng thái. Hãy kiểm tra quyền root/su và LSPosed.");
                } else if ("1".equals(result == null ? "" : result.trim())) {
                    orientationStatus.setText("Trạng thái: ĐÃ BẬT ép ứng dụng chiếu ngang.");
                } else {
                    orientationStatus.setText("Trạng thái: ĐANG TẮT ép ứng dụng chiếu ngang.");
                }
            });
        });
    }

    private void setForceLandscape(boolean enabled) {
        if (orientationStatus != null) {
            orientationStatus.setText("Đang lưu cài đặt…");
        }
        RootShellSession.EXEC.execute(() -> {
            String error = null;
            try {
                orientationShell.run(8, enabled
                        ? "settings put global carassistant_force_landscape 1"
                        : "settings delete global carassistant_force_landscape");
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final String failure = error;
            runOnUiThread(() -> {
                if (orientationStatus == null) return;
                if (failure != null) {
                    orientationStatus.setText(
                            "Lưu thất bại. Hãy kiểm tra quyền root/su: " + failure);
                } else {
                    orientationStatus.setText(enabled
                            ? "Đã bật ép ngang. Hãy mở lại ứng dụng đang chiếu để áp dụng."
                            : "Đã tắt ép ngang. Hãy mở lại ứng dụng đang chiếu để áp dụng.");
                }
                refreshOrientationStatus();
            });
        });
    }

    @Override
    protected void onDestroy() {
        orientationShell.destroy();
        super.onDestroy();
    }


    private void addPermissionGuideCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackgroundColor(Color.rgb(22, 43, 54));
        LinearLayout.LayoutParams cp = params(-1, -2);
        cp.setMargins(0, dp(8), 0, dp(14));
        list.addView(card, cp);

        TextView heading = text("THIẾT LẬP BAN ĐẦU", 13, Color.rgb(105, 215, 224));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(heading, params(-1, dp(24)));
        permissionGuideStatus = text("", 14, Color.WHITE);
        permissionGuideStatus.setPadding(0, dp(5), 0, dp(10));
        card.addView(permissionGuideStatus, params(-1, -2));
        permissionGuideAction = text("", 14, Color.WHITE);
        permissionGuideAction.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        permissionGuideAction.setGravity(Gravity.CENTER);
        permissionGuideAction.setBackgroundColor(Color.rgb(25, 125, 145));
        LinearLayout.LayoutParams ap = params(-1, dp(48));
        ap.setMargins(0, dp(3), 0, 0);
        card.addView(permissionGuideAction, ap);
        permissionGuideAction.setOnClickListener(v -> runNextPermissionStep());
        refreshPermissionGuide();
    }

    private void startFirstLaunchPermissionFlow() {
        if (isFinishing() || permissionRequestInFlight) return;
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        if (prefs.getBoolean("permission_setup_started", false)) return;
        prefs.edit().putBoolean("permission_setup_started", true).apply();
        if (!hasRuntimePermissions()) requestRuntimePermissions();
        else if (!MediaSessionBridge.hasAccess(this)) openNotificationAccessSettings();
        else if (!hasMediaAccessibilityAccess()) openAccessibilitySettings();
    }

    private boolean hasRuntimePermissions() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRuntimePermissions() {
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!missing.isEmpty()) {
            permissionRequestInFlight = true;
            requestPermissions(missing.toArray(new String[0]), 4101);
        }
    }

    private boolean hasMediaAccessibilityAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String expected = new ComponentName(this, MediaPlaybackAccessibilityService.class).flattenToString();
        for (String item : enabled.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(item);
            if (component != null && expected.equals(component.flattenToString())) return true;
        }
        return false;
    }

    private void openNotificationAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (RuntimeException error) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void runNextPermissionStep() {
        if (!hasRuntimePermissions()) requestRuntimePermissions();
        else if (!MediaSessionBridge.hasAccess(this)) openNotificationAccessSettings();
        else if (!hasMediaAccessibilityAccess()) openAccessibilitySettings();
        else new android.app.AlertDialog.Builder(this)
                .setTitle("Quyền cơ bản đã đủ")
                .setMessage("Các quyền Android cần thiết đã được bật. Root và LSPosed cần được cấp/bật riêng trong ứng dụng quản lý tương ứng.")
                .setPositiveButton("ĐÃ HIỂU", null).show();
    }

    private void refreshPermissionGuide() {
        if (permissionGuideStatus == null || permissionGuideAction == null) return;
        boolean runtime = hasRuntimePermissions();
        boolean notification = MediaSessionBridge.hasAccess(this);
        boolean accessibility = hasMediaAccessibilityAccess();
        permissionGuideStatus.setText(
                "Vị trí & micro: " + (runtime ? "ĐÃ CẤP" : "CẦN CẤP")
                + "\nTruy cập thông báo / Media Session: " + (notification ? "ĐÃ CẤP" : "CẦN BẬT")
                + "\nTrợ năng Media Auto Play: " + (accessibility ? "ĐÃ BẬT" : "TÙY CHỌN – CẦN BẬT")
                + "\nRoot / LSPosed: bật thủ công trong ứng dụng quản lý root và LSPosed.");
        permissionGuideAction.setText(!runtime ? "CẤP QUYỀN VỊ TRÍ & MICRO"
                : !notification ? "MỞ CÀI ĐẶT TRUY CẬP MEDIA"
                : !accessibility ? "MỞ CÀI ĐẶT TRỢ NĂNG"
                : "KIỂM TRA HƯỚNG DẪN ROOT / LSPOSED");
    }

    private void refreshStatus() {
        if (permissionStatus == null) return;
        permissionStatus.setText(
                "Vị trí: " + granted(Manifest.permission.ACCESS_FINE_LOCATION)
                + "   •   Micro: " + granted(Manifest.permission.RECORD_AUDIO)
                + "\nThông báo/media: " + (MediaSessionBridge.hasAccess(this) ? "ĐÃ CẤP" : "CÒN THIẾU")
                + "   •   Trợ năng: " + (hasMediaAccessibilityAccess() ? "ĐÃ CẤP" : "CÒN THIẾU")
                + "\nRoot/LSPosed: kiểm tra riêng trong ứng dụng quản lý tương ứng.");
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
