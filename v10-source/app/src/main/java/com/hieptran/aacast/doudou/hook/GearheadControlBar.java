package com.carassistant.v10.hook;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class GearheadControlBar {

    private static final String TAG =
            "[V10 ControlBar]";

    private static final String GEARHEAD =
            "com.google.android.projection.gearhead";

    private static final String ACTION =
            "com.carassistant.v10.CONTROL";

    private static final String SELF =
            "com.carassistant.v10";

    private static final Set<ViewGroup> INSTALLED =
            Collections.newSetFromMap(
                    new IdentityHashMap<ViewGroup, Boolean>());

    private static final String[] DOCK_NAMES = {
            "nav_bar_container",
            "car_navigation_bar",
            "dock_container",
            "nav_bar",
            "floating_nav_bar_view",
            "navigation_bar_container",
            "navigation_bar",
            "bottom_navigation_bar",
            "system_navigation_bar",
            "navbar",
            "car_sys_ui_navbar"
    };

    private GearheadControlBar() {
    }

    public static void install() {

        try {

            Class<?> wmg =
                    XposedHelpers.findClass(
                            "android.view.WindowManagerGlobal",
                            null);

            XposedBridge.hookAllMethods(
                    wmg,
                    "addView",
                    new XC_MethodHook() {

                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {

                            if (param.args.length == 0) {
                                return;
                            }

                            Object value =
                                    param.args[0];

                            if (!(value instanceof View)) {
                                return;
                            }

                            final View root =
                                    (View) value;

                            root.post(
                                    new Runnable() {
                                        @Override
                                        public void run() {
                                            scanRoot(root);
                                        }
                                    });
                        }
                    });

            scanExistingRoots();

            XposedBridge.log(
                    TAG
                    + " Gearhead dock hook installed");

        } catch (Throwable t) {

            XposedBridge.log(
                    TAG
                    + " install failed: "
                    + t);
        }
    }

    private static void scanExistingRoots() {

        try {

            Class<?> cls =
                    XposedHelpers.findClass(
                            "android.view.WindowManagerGlobal",
                            null);

            Object global =
                    XposedHelpers.callStaticMethod(
                            cls,
                            "getInstance");

            String[] names =
                    (String[])
                            XposedHelpers.callMethod(
                                    global,
                                    "getViewRootNames");

            if (names == null) {
                return;
            }

            for (String name : names) {

                try {

                    Object root =
                            XposedHelpers.callMethod(
                                    global,
                                    "getRootView",
                                    name);

                    if (!(root instanceof View)) {
                        continue;
                    }

                    final View view =
                            (View) root;

                    view.post(
                            new Runnable() {
                                @Override
                                public void run() {
                                    scanRoot(view);
                                }
                            });

                } catch (Throwable ignored) {
                }
            }

        } catch (Throwable t) {

            XposedBridge.log(
                    TAG
                    + " root scan failed: "
                    + t);
        }
    }

    private static void scanRoot(
            View root) {

        try {

            if (!root.isAttachedToWindow()) {
                return;
            }

            if (root.getDisplay() == null) {
                return;
            }

            if (root.getDisplay()
                    .getDisplayId() <= 0) {
                return;
            }

            if (!(root instanceof ViewGroup)) {
                return;
            }

            ViewGroup dock =
                    findDockContainer(
                            (ViewGroup) root,
                            1500);

            if (dock == null) {
                return;
            }

            if (INSTALLED.contains(dock)) {
                return;
            }

            installBar(dock);

        } catch (Throwable t) {

            XposedBridge.log(
                    TAG
                    + " scan failed: "
                    + t);
        }
    }

    private static ViewGroup findDockContainer(
            ViewGroup group,
            int budget) {

        if (budget <= 0) {
            return null;
        }

        String name =
                resourceEntryName(group);

        if (name != null
                && isDockName(name)) {

            return group;
        }

        int nextBudget =
                budget - 1;

        for (int i = 0;
                i < group.getChildCount();
                i++) {

            View child =
                    group.getChildAt(i);

            if (!(child instanceof ViewGroup)) {
                continue;
            }

            ViewGroup found =
                    findDockContainer(
                            (ViewGroup) child,
                            nextBudget);

            if (found != null) {
                return found;
            }
        }

        return null;
    }

    private static String resourceEntryName(
            View view) {

        if (view.getId() == View.NO_ID) {
            return null;
        }

        try {

            String packageName =
                    view.getResources()
                            .getResourcePackageName(
                                    view.getId());

            if (!GEARHEAD.equals(
                    packageName)) {
                return null;
            }

            return view.getResources()
                    .getResourceEntryName(
                            view.getId());

        } catch (Throwable ignored) {

            return null;
        }
    }

    private static boolean isDockName(
            String name) {

        for (String candidate :
                DOCK_NAMES) {

            if (candidate.equals(name)) {
                return true;
            }
        }

        return false;
    }

    private static void installBar(
            ViewGroup dock) {

        LinearLayout bar =
                new LinearLayout(
                        dock.getContext());

        bar.setOrientation(
                LinearLayout.HORIZONTAL);

        bar.setGravity(
                Gravity.CENTER_VERTICAL);

        bar.setPadding(
                4,
                2,
                4,
                2);

        bar.setBackgroundColor(
                0xDD202124);

        addButton(
                bar,
                "←",
                "back");

        addButton(
                bar,
                "⌂",
                "home");

        addButton(
                bar,
                "▣",
                "recents");

        addButton(
                bar,
                "Apps",
                "apps");

        dock.addView(
                bar,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        bar.bringToFront();

        INSTALLED.add(dock);

        XposedBridge.log(
                TAG
                + " installed in "
                + resourceEntryName(dock));
    }

    private static void addButton(
            LinearLayout parent,
            String label,
            String command) {

        TextView button =
                new TextView(
                        parent.getContext());

        button.setText(label);

        button.setTextColor(
                Color.WHITE);

        button.setTextSize(
                "Apps".equals(label)
                        ? 13
                        : 20);

        button.setGravity(
                Gravity.CENTER);

        button.setPadding(
                18,
                0,
                18,
                0);

        button.setClickable(true);

        button.setFocusable(true);

        button.setOnClickListener(
                new View.OnClickListener() {

                    @Override
                    public void onClick(
                            View v) {

                        sendCommand(
                                parent.getContext(),
                                command);
                    }
                });

        parent.addView(
                button,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
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
}
