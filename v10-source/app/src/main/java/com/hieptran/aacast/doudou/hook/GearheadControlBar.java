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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Enumeration;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Car Assistant V10.2-A3.
 *
 * AADisplay-style Android Auto UI hook.
 *
 * Important:
 * - No WindowManagerGlobal overlay.
 * - Hooks Gearhead's LayoutInfo constructor.
 * - Forces the vertical rail layout.
 * - Hooks the real Gearhead facet-bar inflation.
 *
 * Relevant implementation approach derived from
 * AADisplay (GPL-3.0).
 */
public final class GearheadControlBar {

    private static final String TAG =
            "[V10 A3 AAFacetBar]";

    private static final String GEARHEAD =
            "com.google.android.projection.gearhead";

    private static final String SELF =
            "com.carassistant.v10";

    private static final String ACTION =
            "com.carassistant.v10.CONTROL";

    private static final String MARKER =
            "CarAssistant-V10-A3";

    private static final String[] FACET_LAYOUTS = {
            "gh_coolwalk_vertical_facet_bar",
            "gh_coolwalk_facet_bar",
            "gh_coolwalk_facet_bar_rhd"
    };

    private static final String LEFT_RAIL =
            "sys_ui_layout_canonical_vertical_rail_lhd";

    private static final String RIGHT_RAIL =
            "sys_ui_layout_canonical_vertical_rail_rhd";

    private static boolean installed;
    private static boolean layoutInfoInstalled;

    private GearheadControlBar() {
    }

    public static void install() {

        if (installed) {
            return;
        }

        installed = true;

        try {

            Context context =
                    (Context) XposedHelpers.callStaticMethod(
                            Class.forName(
                                    "android.app.ActivityThread"),
                            "currentApplication");

            if (context == null) {

                XposedBridge.log(
                        TAG + " no application context");

                return;
            }

            installLayoutInfoHook(
                    context.getClassLoader(),
                    context);

            installFacetInflateHook();

            XposedBridge.log(
                    TAG + " A3 hooks installed");

        } catch (Throwable t) {

            XposedBridge.log(
                    TAG + " install failed: " + t);
        }
    }

    private static void installFacetInflateHook() {

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

                                int resource =
                                        (Integer) param.args[0];

                                LayoutInflater inflater =
                                        (LayoutInflater)
                                                param.thisObject;

                                Context context =
                                        inflater.getContext();

                                if (!isFacetResource(
                                        context,
                                        resource)) {
                                    return;
                                }

                                Object result =
                                        param.getResult();

                                if (!(result instanceof ViewGroup)) {

                                    XposedBridge.log(
                                            TAG
                                                    + " facet result not ViewGroup");

                                    return;
                                }

                                inject(
                                        (ViewGroup) result,
                                        context);

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        TAG
                                                + " facet hook error: "
                                                + t);
                            }
                        }
                    });

            XposedBridge.log(
                    TAG + " LayoutInflater hook ready");

        } catch (Throwable t) {

            XposedBridge.log(
                    TAG + " LayoutInflater hook failed: "
                            + t);
        }
    }

    private static boolean isFacetResource(
            Context context,
            int resource) {

        if (context == null || resource == 0) {
            return false;
        }

        for (String name : FACET_LAYOUTS) {

            int id =
                    context.getResources()
                            .getIdentifier(
                                    name,
                                    "layout",
                                    GEARHEAD);

            if (id != 0 && id == resource) {

                XposedBridge.log(
                        TAG
                                + " facet matched "
                                + name
                                + " id="
                                + resource);

                return true;
            }
        }

        return false;
    }

    private static void inject(
            ViewGroup facet,
            Context context) {

        if (facet == null || context == null) {
            return;
        }

        if (MARKER.equals(facet.getTag())) {
            return;
        }

        try {

            facet.setTag(MARKER);

            LinearLayout controls =
                    new LinearLayout(context);

            controls.setOrientation(
                    LinearLayout.VERTICAL);

            controls.setGravity(
                    Gravity.CENTER);

            controls.setPadding(
                    0,
                    2,
                    0,
                    2);

            addButton(
                    controls,
                    "⌂",
                    "home",
                    22f);

            addButton(
                    controls,
                    "▣",
                    "recents",
                    20f);

            addButton(
                    controls,
                    "‹",
                    "back",
                    28f);

            addButton(
                    controls,
                    "Apps",
                    "apps",
                    10f);

            facet.addView(
                    controls,
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));

            XposedBridge.log(
                    TAG
                            + " controls injected childCount="
                            + facet.getChildCount());

        } catch (Throwable t) {

            XposedBridge.log(
                    TAG + " injection failed: " + t);
        }
    }

    private static void addButton(
            LinearLayout parent,
            String label,
            String command,
            float size) {

        TextView button =
                new TextView(
                        parent.getContext());

        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(size);
        button.setGravity(Gravity.CENTER);

        button.setClickable(true);
        button.setFocusable(true);

        button.setPadding(
                6,
                2,
                6,
                2);

        button.setOnClickListener(
                view -> sendCommand(
                        view.getContext(),
                        command));

        parent.addView(
                button,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));
    }

    private static void sendCommand(
            Context context,
            String command) {

        try {

            Intent intent =
                    new Intent(ACTION);

            intent.setPackage(
                    SELF);

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

    private static void installLayoutInfoHook(
            ClassLoader loader,
            Context context) {

        if (layoutInfoInstalled) {
            return;
        }

        try {

            Class<?> layoutInfo =
                    findLayoutInfoClass(loader);

            if (layoutInfo == null) {

                XposedBridge.log(
                        TAG
                                + " LayoutInfo class not found");

                return;
            }

            Constructor<?> constructor =
                    findLayoutInfoConstructor(
                            layoutInfo);

            if (constructor == null) {

                XposedBridge.log(
                        TAG
                                + " LayoutInfo constructor not found");

                return;
            }

            final int left =
                    context.getResources()
                            .getIdentifier(
                                    LEFT_RAIL,
                                    "layout",
                                    GEARHEAD);

            final int right =
                    context.getResources()
                            .getIdentifier(
                                    RIGHT_RAIL,
                                    "layout",
                                    GEARHEAD);

            XposedBridge.hookMethod(
                    constructor,
                    new XC_MethodHook() {

                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {

                            try {

                                if (param.args.length != 8) {
                                    return;
                                }

                                if (!(param.args[0]
                                        instanceof Integer)
                                        || !(param.args[1]
                                        instanceof Integer)
                                        || !(param.args[2]
                                        instanceof Integer)
                                        || !(param.args[3]
                                        instanceof Integer)
                                        || !(param.args[4]
                                        instanceof Boolean)
                                        || !(param.args[5]
                                        instanceof Boolean)
                                        || !(param.args[7]
                                        instanceof Boolean)) {
                                    return;
                                }

                                boolean rhd =
                                        (Boolean)
                                                param.args[4];

                                if (rhd && right != 0) {

                                    param.args[0] =
                                            right;

                                    param.args[3] =
                                            4;

                                } else if (!rhd
                                        && left != 0) {

                                    param.args[0] =
                                            left;

                                    param.args[3] =
                                            3;
                                }

                                param.args[5] =
                                        true;

                                XposedBridge.log(
                                        TAG
                                                + " LayoutInfo patched rhd="
                                                + rhd);

                            } catch (Throwable t) {

                                XposedBridge.log(
                                        TAG
                                                + " LayoutInfo patch error: "
                                                + t);
                            }
                        }
                    });

            layoutInfoInstalled = true;

            XposedBridge.log(
                    TAG
                            + " LayoutInfo hook installed: "
                            + layoutInfo.getName());

        } catch (Throwable t) {

            XposedBridge.log(
                    TAG
                            + " LayoutInfo scan failed: "
                            + t);
        }
    }

    private static Constructor<?> findLayoutInfoConstructor(
            Class<?> type) {

        for (Constructor<?> constructor :
                type.getDeclaredConstructors()) {

            Class<?>[] p =
                    constructor.getParameterTypes();

            if (p.length == 8
                    && p[0] == int.class
                    && p[1] == int.class
                    && p[2] == int.class
                    && p[3] == int.class
                    && p[4] == boolean.class
                    && p[5] == boolean.class
                    && p[7] == boolean.class) {

                return constructor;
            }
        }

        return null;
    }

    private static Class<?> findLayoutInfoClass(
            ClassLoader loader) {

        try {

            Field pathList =
                    BaseDexClassLoader.class
                            .getDeclaredField(
                                    "pathList");

            pathList.setAccessible(true);

            Object dexPathList =
                    pathList.get(loader);

            Field dexElements =
                    dexPathList.getClass()
                            .getDeclaredField(
                                    "dexElements");

            dexElements.setAccessible(true);

            Object[] elements =
                    (Object[]) dexElements.get(
                            dexPathList);

            for (Object element :
                    elements) {

                Field dexFileField =
                        element.getClass()
                                .getDeclaredField(
                                        "dexFile");

                dexFileField.setAccessible(true);

                DexFile dex =
                        (DexFile)
                                dexFileField.get(
                                        element);

                if (dex == null) {
                    continue;
                }

                Enumeration<String> names =
                        dex.entries();

                while (names.hasMoreElements()) {

                    String name =
                            names.nextElement();

                    if (!name.startsWith(
                            GEARHEAD)) {
                        continue;
                    }

                    try {

                        Class<?> type =
                                Class.forName(
                                        name,
                                        false,
                                        loader);

                        for (Constructor<?> constructor :
                                type.getDeclaredConstructors()) {

                            if (!looksLikeLayoutInfo(
                                    constructor)) {
                                continue;
                            }

                            if (hasLayoutInfoStringShape(
                                    constructor)) {

                                return type;
                            }
                        }

                    } catch (Throwable ignored) {
                    }
                }
            }

        } catch (Throwable t) {

            XposedBridge.log(
                    TAG
                            + " dex scan error: "
                            + t);
        }

        return null;
    }

    private static boolean looksLikeLayoutInfo(
            Constructor<?> constructor) {

        Class<?>[] p =
                constructor.getParameterTypes();

        return p.length == 8
                && p[0] == int.class
                && p[1] == int.class
                && p[2] == int.class
                && p[3] == int.class
                && p[4] == boolean.class
                && p[5] == boolean.class
                && p[7] == boolean.class;
    }

    private static boolean hasLayoutInfoStringShape(
            Constructor<?> constructor) {

        try {

            constructor.setAccessible(true);

            Object value =
                    constructor.newInstance(
                            1,
                            2,
                            3,
                            4,
                            false,
                            true,
                            null,
                            false);

            String text =
                    String.valueOf(value);

            return text.startsWith(
                    "LayoutInfo{layoutResourceId=");

        } catch (Throwable ignored) {

            return false;
        }
    }
}
