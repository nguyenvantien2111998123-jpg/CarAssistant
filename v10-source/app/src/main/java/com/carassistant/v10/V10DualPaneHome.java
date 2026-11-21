package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Two independent Home projection panes with a draggable 3:7 divider. */
public final class V10DualPaneHome extends FrameLayout {
    public static final class PaneApp {
        public final ComponentName component;
        public final String label;
        public final Drawable icon;

        public PaneApp(ComponentName component, String label, Drawable icon) {
            this.component = component;
            this.label = label == null ? component.getPackageName() : label;
            this.icon = icon;
        }
    }

    public interface Listener {
        void onAppSelected(boolean leftPane, ComponentName component);
        void onExpandRequested(boolean leftPane, ComponentName component);
        void onRatioChanged(float ratio);
    }

    private final Listener listener;
    private final Pane left;
    private final Pane right;
    private final LinearLayout row;
    private final View divider;
    private float ratio = 0.30f;
    private boolean draggingDivider;
    private float downRawX;

    public V10DualPaneHome(Context context, Listener listener, float initialRatio) {
        super(context);
        this.listener = listener;
        setClipChildren(true);
        setClipToPadding(true);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new FrameLayout.LayoutParams(-1, -1));

        left = new Pane(context, true);
        right = new Pane(context, false);
        divider = new View(context);
        divider.setBackgroundColor(Color.argb(210, 100, 150, 175));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(7), -1);
        row.addView(left, new LinearLayout.LayoutParams(0, -1, 3f));
        row.addView(divider, dividerParams);
        row.addView(right, new LinearLayout.LayoutParams(0, -1, 7f));
        setRatio(initialRatio, false);

        divider.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    draggingDivider = true;
                    downRawX = event.getRawX();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (draggingDivider) updateRatioFromRawX(event.getRawX());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (draggingDivider) {
                        draggingDivider = false;
                        updateRatioFromRawX(event.getRawX());
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    public TextureView getLeftTextureView() { return left.texture; }
    public TextureView getRightTextureView() { return right.texture; }

    public void setLeftApps(List<PaneApp> apps) { left.setApps(apps); }
    public void setRightApps(List<PaneApp> apps) { right.setApps(apps); }

    public void closePickers() {
        left.closePicker();
        right.closePicker();
    }

    public void setLeftTargetLabel(String label) { left.setTargetLabel(label); }
    public void setRightTargetLabel(String label) { right.setTargetLabel(label); }

    public void setRatio(float value) { setRatio(value, true); }

    private void setRatio(float value, boolean persist) {
        ratio = Math.max(0.18f, Math.min(0.65f, value));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) left.getLayoutParams();
        LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) right.getLayoutParams();
        if (lp != null && rp != null) {
            lp.weight = ratio;
            rp.weight = 1f - ratio;
            left.setLayoutParams(lp);
            right.setLayoutParams(rp);
        }
        if (persist && listener != null) listener.onRatioChanged(ratio);
    }

    private void updateRatioFromRawX(float rawX) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        int width = getWidth();
        if (width <= dp(100)) return;
        float next = (rawX - location[0]) / (float) width;
        setRatio(next, true);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class Pane extends FrameLayout {
        final boolean isLeft;
        final TextureView texture;
        final TextView topHandle;
        final FrameLayout picker;
        final LinearLayout appList;
        final TextView currentLabel;
        final ArrayList<PaneApp> apps = new ArrayList<>();
        ComponentName target;

        Pane(Context context, boolean isLeft) {
            super(context);
            this.isLeft = isLeft;
            setBackgroundColor(Color.rgb(5, 9, 13));
            setClipChildren(true);
            texture = new TextureView(context);
            texture.setOpaque(true);
            addView(texture, new FrameLayout.LayoutParams(-1, -1));

            currentLabel = new TextView(context);
            currentLabel.setText(isLeft ? "MÀN HÌNH PHỤ 1" : "MÀN HÌNH PHỤ 1.1");
            currentLabel.setTextColor(Color.argb(180, 220, 230, 240));
            currentLabel.setTextSize(11);
            currentLabel.setGravity(Gravity.CENTER);
            currentLabel.setClickable(false);
            addView(currentLabel, new FrameLayout.LayoutParams(-1, -1));

            topHandle = new TextView(context);
            topHandle.setText("⌄");
            topHandle.setTextSize(20);
            topHandle.setTextColor(Color.WHITE);
            topHandle.setGravity(Gravity.CENTER);
            topHandle.setBackground(pill(Color.argb(105, 15, 22, 30)));
            topHandle.setElevation(dp(12));
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(54), dp(28),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            hp.topMargin = dp(3);
            addView(topHandle, hp);
            topHandle.setOnClickListener(v -> togglePicker());

            picker = new FrameLayout(context);
            picker.setBackgroundColor(Color.argb(238, 8, 13, 19));
            picker.setVisibility(View.GONE);
            picker.setElevation(dp(18));
            addView(picker, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(5), dp(4), dp(5), dp(4));
            picker.addView(panel, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout header = new LinearLayout(context);
            header.setGravity(Gravity.CENTER_VERTICAL);
            panel.addView(header, new LinearLayout.LayoutParams(-1, dp(38)));

            TextView expand = actionButton("⛶");
            expand.setContentDescription("Phóng to ứng dụng lên màn hình phụ 2");
            header.addView(expand, new LinearLayout.LayoutParams(dp(38), -1));
            expand.setOnClickListener(v -> {
                if (target != null && listener != null) listener.onExpandRequested(isLeft, target);
            });

            TextView title = new TextView(context);
            title.setText(isLeft ? "MÀN HÌNH 1" : "MÀN HÌNH 1.1");
            title.setTextColor(Color.WHITE);
            title.setTextSize(11);
            title.setGravity(Gravity.CENTER);
            header.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));

            TextView close = actionButton("×");
            close.setContentDescription("Đóng danh sách ứng dụng");
            header.addView(close, new LinearLayout.LayoutParams(dp(34), -1));
            close.setOnClickListener(v -> closePicker());

            ScrollView scroll = new ScrollView(context);
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
            appList = new LinearLayout(context);
            appList.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(appList, new ScrollView.LayoutParams(-1, -2));
        }

        void setApps(List<PaneApp> values) {
            apps.clear();
            if (values != null) apps.addAll(values);
            rebuildApps();
        }

        void setTargetLabel(String label) {
            if (label != null && !label.isEmpty()) currentLabel.setText(label);
            currentLabel.setVisibility(target == null ? View.VISIBLE : View.GONE);
        }

        void togglePicker() {
            if (picker.getVisibility() == View.VISIBLE) closePicker();
            else {
                rebuildApps();
                picker.setVisibility(View.VISIBLE);
                topHandle.setVisibility(View.GONE);
            }
        }

        void closePicker() {
            picker.setVisibility(View.GONE);
            topHandle.setVisibility(View.VISIBLE);
        }

        void rebuildApps() {
            appList.removeAllViews();
            if (apps.isEmpty()) {
                TextView empty = new TextView(getContext());
                empty.setText(isLeft
                        ? "Chưa chọn ứng dụng trong Cài đặt điện thoại"
                        : "Không tìm thấy ứng dụng");
                empty.setTextColor(Color.LTGRAY);
                empty.setTextSize(11);
                empty.setPadding(dp(5), dp(12), dp(5), dp(12));
                appList.addView(empty);
                return;
            }
            for (PaneApp app : apps) {
                LinearLayout item = new LinearLayout(getContext());
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(dp(4), dp(3), dp(4), dp(3));
                item.setBackground(pill(Color.argb(80, 55, 75, 92)));
                LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, dp(46));
                itemParams.setMargins(0, dp(2), 0, dp(2));
                appList.addView(item, itemParams);

                if (app.icon != null) {
                    android.widget.ImageView icon = new android.widget.ImageView(getContext());
                    icon.setImageDrawable(app.icon);
                    icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    item.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
                }
                TextView name = new TextView(getContext());
                name.setText(app.label);
                name.setTextColor(Color.WHITE);
                name.setTextSize(11);
                name.setMaxLines(2);
                name.setGravity(Gravity.CENTER_VERTICAL);
                name.setPadding(dp(5), 0, dp(2), 0);
                item.addView(name, new LinearLayout.LayoutParams(0, -1, 1f));
                item.setOnClickListener(v -> {
                    target = app.component;
                    currentLabel.setVisibility(View.GONE);
                    closePicker();
                    if (listener != null) listener.onAppSelected(isLeft, app.component);
                });
            }
        }

        TextView actionButton(String text) {
            TextView button = new TextView(getContext());
            button.setText(text);
            button.setTextColor(Color.WHITE);
            button.setTextSize(19);
            button.setGravity(Gravity.CENTER);
            button.setBackground(pill(Color.argb(130, 35, 52, 68)));
            return button;
        }

        GradientDrawable pill(int color) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(dp(9));
            bg.setStroke(dp(1), Color.argb(80, 180, 205, 225));
            return bg;
        }
    }
}
