package com.carassistant.v10;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public final class CarAssistantKeyboard {

    public interface Action {
        void text(String value);
        void key(int keyCode);
    }

    private final FrameLayout host;
    private final Action action;

    private LinearLayout panel;
    private boolean visible;

    public CarAssistantKeyboard(
            FrameLayout host,
            Action action) {

        this.host = host;
        this.action = action;
    }

    public boolean isVisible() {
        return visible;
    }

    public void show() {

        if (panel == null) {

            panel = buildKeyboard();

            FrameLayout.LayoutParams p =
                    new FrameLayout.LayoutParams(
                            -1,
                            dp(270),
                            Gravity.BOTTOM);

            p.setMargins(
                    dp(8),
                    0,
                    dp(8),
                    dp(8));

            host.addView(panel, p);
        }

        panel.setVisibility(View.VISIBLE);
        // Keep the existing keyboard view hierarchy; strengthen only its Z-order.
        panel.setElevation(dp(1000));
        panel.setTranslationZ(dp(1000));
        panel.bringToFront();
        host.invalidate();

        visible = true;
    }

    public void hide() {

        if (panel != null) {
            panel.setVisibility(View.GONE);
        }

        visible = false;
    }

    private LinearLayout buildKeyboard() {

        LinearLayout root =
                new LinearLayout(host.getContext());

        root.setOrientation(
                LinearLayout.VERTICAL);

        root.setPadding(
                dp(8),
                dp(8),
                dp(8),
                dp(8));

        GradientDrawable bg =
                new GradientDrawable();

        bg.setColor(
                Color.rgb(15, 20, 26));

        bg.setCornerRadius(dp(18));

        bg.setStroke(
                dp(1),
                Color.rgb(65, 85, 105));

        root.setBackground(bg);
        root.setElevation(dp(18));

        addRow(root, "1234567890");
        addRow(root, "QWERTYUIOP");
        addRow(root, "ASDFGHJKL");
        addRow(root, "ZXCVBNM");

        LinearLayout bottom =
                new LinearLayout(host.getContext());

        bottom.setGravity(Gravity.CENTER);

        addKey(
                bottom,
                "⌫",
                1.35f,
                () -> action.key(
                        KeyEvent.KEYCODE_DEL));

        addKey(
                bottom,
                "SPACE",
                3.8f,
                () -> action.key(
                        KeyEvent.KEYCODE_SPACE));

        addKey(
                bottom,
                "ENTER",
                1.55f,
                () -> action.key(
                        KeyEvent.KEYCODE_ENTER));

        root.addView(
                bottom,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1f));

        return root;
    }

    private void addRow(
            LinearLayout root,
            String values) {

        LinearLayout row =
                new LinearLayout(
                        host.getContext());

        row.setGravity(Gravity.CENTER);

        for (int i = 0;
             i < values.length();
             i++) {

            final String value =
                    String.valueOf(
                            values.charAt(i))
                            .toLowerCase(
                                    Locale.ROOT);

            addKey(
                    row,
                    String.valueOf(
                            values.charAt(i)),
                    1f,
                    () -> action.text(value));
        }

        root.addView(
                row,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1f));
    }

    private void addKey(
            LinearLayout row,
            String label,
            float weight,
            Runnable click) {

        TextView key =
                new TextView(
                        host.getContext());

        key.setText(label);

        key.setTextColor(Color.WHITE);

        key.setTextSize(
                label.length() > 1
                        ? 13
                        : 18);

        key.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD);

        key.setGravity(Gravity.CENTER);

        GradientDrawable bg =
                new GradientDrawable();

        bg.setColor(
                Color.rgb(32, 41, 50));

        bg.setCornerRadius(dp(11));

        key.setBackground(bg);

        key.setOnClickListener(
                v -> click.run());

        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(
                        0,
                        -1,
                        weight);

        p.setMargins(
                dp(3),
                dp(3),
                dp(3),
                dp(3));

        row.addView(key, p);
    }

    private int dp(int value) {

        return Math.round(
                value *
                host.getResources()
                    .getDisplayMetrics()
                    .density);
    }
}
