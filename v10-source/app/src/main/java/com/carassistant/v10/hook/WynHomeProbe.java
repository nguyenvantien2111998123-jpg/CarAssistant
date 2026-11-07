package com.carassistant.v10.hook;

import android.app.Activity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Diagnostic-only inspection of WYN's resumed homepage renderer.
 * Does not launch WYN, hook popup windows, capture pixels, or modify UI.
 */
public final class WynHomeProbe {
    private static final String TAG = "[AACast WYN Home]";
    private static final String PACKAGE = "com.wynauto.wyn";
    private static final String[] FLUTTER_TYPES = {
            "io.flutter.embedding.android.FlutterView",
            "io.flutter.embedding.android.FlutterSurfaceView",
            "io.flutter.embedding.android.FlutterTextureView",
            "io.flutter.embedding.engine.renderer.FlutterRenderer",
            "io.flutter.embedding.engine.FlutterEngine",
            "io.flutter.embedding.engine.FlutterJNI",
            "io.flutter.plugin.platform.PlatformViewsController"
    };

    private WynHomeProbe() { }

    public static void install(ClassLoader ignored) {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(Activity.class, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)) return;
                            Activity activity = (Activity) param.thisObject;
                            try {
                                if (!PACKAGE.equals(activity.getPackageName())) return;
                                Window window = activity.getWindow();
                                if (window == null || window.getDecorView() == null) return;
                                View decor = window.getDecorView();
                                XposedBridge.log(TAG + " resumed activity=" + activity.getClass().getName()
                                        + " component=" + activity.getComponentName()
                                        + " decor=" + decor.getClass().getName());
                                WeakReference<View> root = new WeakReference<>(decor);
                                WeakReference<ClassLoader> loader = new WeakReference<>(activity.getClassLoader());
                                decor.postDelayed(() -> sample(root.get(), loader.get(), "250ms"), 250L);
                                decor.postDelayed(() -> sample(root.get(), loader.get(), "1000ms"), 1000L);
                                decor.postDelayed(() -> sample(root.get(), loader.get(), "2500ms"), 2500L);
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + " Activity inspection failed: " + error);
                            }
                        }
                    });
            XposedBridge.log(TAG + " installed; Activity.onResume hooks=" + (hooks == null ? 0 : hooks.size()));
        } catch (Throwable error) {
            XposedBridge.log(TAG + " installation failed: " + error);
        }
    }

    private static void sample(View root, ClassLoader loader, String when) {
        if (root == null) return;
        try {
            StringBuilder tree = new StringBuilder(512);
            appendTree(tree, root, 0);
            XposedBridge.log(TAG + " sample=" + when + " decorTree=" + tree);
            inspectKnownFlutterTypes(loader);
            Set<String> inspected = new HashSet<>();
            inspectViewClasses(root, inspected, 0);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " renderer inspection failed: " + error);
        }
    }

    private static void appendTree(StringBuilder out, View view, int depth) {
        if (view == null || depth > 10 || out.length() > 4000) return;
        if (depth > 0) out.append('/');
        out.append(view.getClass().getName()).append('[')
                .append(view.getWidth()).append('x').append(view.getHeight()).append(']');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = Math.min(group.getChildCount(), 24);
            out.append('{');
            for (int i = 0; i < count; i++) {
                if (i > 0) out.append(',');
                appendTree(out, group.getChildAt(i), depth + 1);
                if (out.length() > 4000) break;
            }
            if (group.getChildCount() > count) out.append(",+").append(group.getChildCount() - count);
            out.append('}');
        }
    }

    private static void inspectViewClasses(View view, Set<String> inspected, int depth) {
        if (view == null || depth > 10) return;
        Class<?> type = view.getClass();
        if (inspected.add(type.getName())) {
            StringBuilder info = new StringBuilder(TAG).append(" viewType=").append(type.getName())
                    .append(" super=").append(type.getSuperclass() == null ? "none" : type.getSuperclass().getName())
                    .append(" loader=").append(String.valueOf(type.getClassLoader()))
                    .append(" hardwareAccelerated=").append(view.isHardwareAccelerated())
                    .append(" layerType=").append(view.getLayerType());
            if (view instanceof SurfaceView) info.append(" kind=SurfaceView");
            if (view instanceof TextureView) info.append(" kind=TextureView");
            if (view instanceof ViewGroup) info.append(" childCount=").append(((ViewGroup) view).getChildCount());
            XposedBridge.log(info.toString());
            inspectMembers(type);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < Math.min(group.getChildCount(), 24); i++) {
                inspectViewClasses(group.getChildAt(i), inspected, depth + 1);
            }
        }
    }

    private static void inspectMembers(Class<?> start) {
        Class<?> type = start;
        int levels = 0;
        while (type != null && type != Object.class && levels++ < 5) {
            for (Class<?> iface : type.getInterfaces()) {
                XposedBridge.log(TAG + " interface=" + type.getName() + " -> " + iface.getName());
            }
            Field[] fields = type.getDeclaredFields();
            int emitted = 0;
            for (Field field : fields) {
                if (emitted >= 24) break;
                Class<?> fieldType = field.getType();
                String name = field.getName().toLowerCase();
                String fieldTypeName = fieldType.getName().toLowerCase();
                if (name.contains("surface") || name.contains("texture") || name.contains("render")
                        || name.contains("flutter") || name.contains("image") || name.contains("bitmap")
                        || fieldTypeName.contains("surface") || fieldTypeName.contains("texture")
                        || fieldTypeName.contains("flutter") || fieldTypeName.contains("renderer")) {
                    XposedBridge.log(TAG + " field=" + type.getName() + "." + field.getName()
                            + " type=" + fieldType.getName());
                    emitted++;
                }
            }
            Method[] methods = type.getDeclaredMethods();
            emitted = 0;
            for (Method method : methods) {
                if (emitted >= 20) break;
                String name = method.getName().toLowerCase();
                if (name.contains("surface") || name.contains("texture") || name.contains("render")
                        || name.contains("flutter") || name.contains("image") || name.contains("bitmap")
                        || name.contains("draw") || name.contains("paint")) {
                    XposedBridge.log(TAG + " method=" + type.getName() + "." + method.getName());
                    emitted++;
                }
            }
            type = type.getSuperclass();
        }
    }

    private static void inspectKnownFlutterTypes(ClassLoader loader) {
        if (loader == null) return;
        for (String name : FLUTTER_TYPES) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                XposedBridge.log(TAG + " flutterType=present " + name
                        + " super=" + (type.getSuperclass() == null ? "none" : type.getSuperclass().getName()));
            } catch (Throwable ignored) {
                XposedBridge.log(TAG + " flutterType=absent " + name);
            }
        }
    }
}
