package com.carassistant.v10.hook;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * V10 AADisplay-style Gearhead facet-bar hook.
 *
 * The control bar is inserted directly into the Android Auto
 * Gearhead facet-bar ViewGroup after its layout is inflated.
 *
 * This does not create a WindowManager overlay.
 */
public final class GearheadControlBar {

    private static final String TAG =
            "[V10 AAFacetBar]";

    private static final String GEARHEAD_PACKAGE =
            "com.google.android.projection.gearhead";

    private static final String SELF_PACKAGE =
            "com.carassistant.v10";

    private static final String CONTROL_ACTION =
            "com.carassistant.v10.CONTROL";

    private static final String CONTROL_TAG =
            "CarAssistant-V10-AADisplay";

    private static final String[] FACET_LAYOUT_NAMES = {
            "gh_coolwalk_vertical_facet_bar",
            "gh_coolwalk_facet_bar",
            "gh_coolwalk_facet_bar_rhd"
    };

    private static volatile boolean installed;

    private GearheadControlBar() {
    }

    public static void install() {

        if (installed) {
            return;
        }

        installed = true;

        try {

            XposedHelpers.findAndHookMethod(
                    LayoutInflater.class,
                    "inflate",
                    int.class,
                    ViewGroup.class,
                    boolean.class,
                    new XC_MethodHook() {

                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {

                            try {

                                int resourceId =
                                        (Integer) param.args[0];

                                if (!isFacetLayout(
                                        param.thisObject,
                                        resourceId)) {
                                    return;
                                }

                                Object result =
                                        param.getResult();

                                if (!(result instanceof ViewGroup)) {
                                    return;
                                }

                                ViewGroup facetBar =
                                        (ViewGroup) result;

                                Context context =
                                        ((LayoutInflater)
                                                param.thisObject)
                                                .getContext();

                                injectControls(
                                        facetBar,
                                        context);

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        TAG
                                                + " inflate hook error: "
                                                + t);
                            }
                        }
                    });

            XposedBridge.log(
                    TAG
                            + " LayoutInflater hook installed");

        } catch (Throwable t) {

            XposedBridge.log(
                    TAG
                            + " install failed: "
                            + t);
        }
    }

    private static boolean isFacetLayout(
            Object inflaterObject,
            int resourceId) {

        try {

            LayoutInflater inflater =
                    (LayoutInflater) inflaterObject;

            Context context =
                    inflater.getContext();

            if (context == null) {
                return false;
            }

            for (String name :
                    FACET_LAYOUT_NAMES) {

                int id =
                        context.getResources()
                                .getIdentifier(
                                        name,
                                        "layout",
                                        GEARHEAD_PACKAGE);

                if (id != 0
                        && id == resourceId) {

                    XposedBridge.log(
                            TAG
                                    + " facet layout matched: "
                                    + name);

                    return true;
                }
            }

        } catch (Throwable t) {

            XposedBridge.log(
                    TAG
                            + " resource lookup failed: "
                            + t);
        }

        return false;
    }

    private static void injectControls(
            ViewGroup facetBar,
            Context context) {

        try {

            if (facetBar == null
                    || context == null) {
                return;
            }

            Object tag =
                    facetBar.getTag();

            if (CONTROL_TAG.equals(tag)) {
                return;
            }

            /*
             * Mark this exact AA ViewGroup so repeated
             * LayoutInflater calls do not inject twice.
             */
            facetBar.setTag(CONTROL_TAG);

            LinearLayout controlBar =
                    new LinearLayout(context);

            controlBar.setOrientation(
                    LinearLayout.VERTICAL);

            controlBar.setGravity(
                    Gravity.CENTER);

            controlBar.setPadding(
                    2,
                    4,
                    2,
                    4);

            controlBar.setBackgroundColor(
                    Color.TRANSPARENT);

            addButton(
                    controlBar,
                    "⌂",
                    "home",
                    26f);

            addButton(
                    controlBar,
                    "▣",
                    "recents",
                    22f);

            addButton(
                    controlBar,
                    "‹",
                    "back",
                    30f);

            addButton(
                    controlBar,
                    "Apps",
                    "apps",
                    11f);

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);

            facetBar.addView(
                    controlBar,
                    params);

            controlBar.bringToFront();

            XposedBridge.log(
                    TAG
                            + " controls injected into "
                            + facetBar.getClass()
                                    .getName());

        } catch (Throwable t) {

            XposedBridge.log(
                    TAG
                            + " injection failed: "
                            + t);
        }
    }

    private static void addButton(
            LinearLayout parent,
            String text,
            String command,
            float textSize) {

        TextView button =
                new TextView(
                        parent.getContext());

        button.setText(text);

        button.setTextColor(
                Color.WHITE);

        button.setTextSize(
                textSize);

        button.setGravity(
                Gravity.CENTER);

        button.setClickable(true);

        button.setFocusable(true);

        button.setPadding(
                8,
                4,
                8,
                4);

        button.setOnClickListener(
                new View.OnClickListener() {

                    @Override
                    public void onClick(
                            View view) {

                        sendCommand(
                                view.getContext(),
                                command);
                    }
                });

        parent.addView(
                button,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1.0f));
    }

    private static void sendCommand(
            Context context,
            String command) {

        try {

            Intent intent =
                    new Intent(
                            CONTROL_ACTION);

            intent.setPackage(
                    SELF_PACKAGE);

            intent.putExtra(
                    "command",
                    command);

            context.sendBroadcast(
                    intent);

            XposedBridge.log(
                    TAG
                            + " command="
                            + command);

        } catch (Throwable t) {

            XposedBridge.log(
                    TAG
                            + " broadcast failed: "
                            + t);
        }
    }
}
