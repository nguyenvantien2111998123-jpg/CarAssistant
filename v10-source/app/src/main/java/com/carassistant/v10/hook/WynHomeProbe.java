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
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Diagnostic-only inspection of WYN's resumed homepage Flutter renderer.
 * Reads renderer/engine references through existing no-argument getters.
 * Does not launch WYN, hook popup windows, capture pixels, or modify UI.
 */
public final class WynHomeProbe {
    private static final String TAG = "[AACast WYN Home]";
    private static final String PACKAGE = "com.wynauto.wyn";
    private static final String[] GETTERS = {
            "getAttachedFlutterEngine", "getCurrentImageSurface", "getAttachedRenderer"
    };
    private static final Set<String> DECODE_HOOKED_LOADERS = new HashSet<>();
    private static final String[] INTERESTING = {
            "flutter", "render", "surface", "texture", "image", "bitmap", "engine", "jni"
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
                                installDecodeImageProbe(activity.getClassLoader());
                                WynWarningLinkProbe.install(activity.getClassLoader());
                                Window window = activity.getWindow();
                                if (window == null || window.getDecorView() == null) return;
                                View decor = window.getDecorView();
                                XposedBridge.log(TAG + " resumed activity=" + activity.getClass().getName()
                                        + " component=" + activity.getComponentName()
                                        + " decor=" + decor.getClass().getName());
                                WeakReference<View> root = new WeakReference<>(decor);
                                decor.postDelayed(() -> sample(root.get(), "250ms"), 250L);
                                decor.postDelayed(() -> sample(root.get(), "1000ms"), 1000L);
                                decor.postDelayed(() -> sample(root.get(), "2500ms"), 2500L);
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

    /**
     * Observes Flutter's Java image decode boundary. It records only buffer metadata
     * and the returned Bitmap dimensions; it never reads pixels or advances the buffer.
     */
    private static void installDecodeImageProbe(ClassLoader loader) {
        if (loader == null) return;
        String loaderKey = Integer.toHexString(System.identityHashCode(loader));
        synchronized (DECODE_HOOKED_LOADERS) {
            if (!DECODE_HOOKED_LOADERS.add(loaderKey)) return;
        }
        try {
            Class<?> flutterJni = Class.forName("io.flutter.embedding.engine.FlutterJNI", false, loader);
            int hooked = 0;
            for (Method method : flutterJni.getDeclaredMethods()) {
                if (!"decodeImage".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2 || !ByteBuffer.class.isAssignableFrom(params[0])
                        || params[1] != Long.TYPE) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            ByteBuffer buffer = (ByteBuffer) param.args[0];
                            int position = buffer == null ? -1 : buffer.position();
                            int limit = buffer == null ? -1 : buffer.limit();
                            int remaining = buffer == null ? -1 : buffer.remaining();
                            XposedBridge.log(TAG + " decodeImage BEFORE jni="
                                    + Integer.toHexString(System.identityHashCode(param.thisObject))
                                    + " buffer=" + (buffer == null ? "null" : buffer.getClass().getName())
                                    + " position=" + position + " limit=" + limit
                                    + " remaining=" + remaining + " codecHandle=" + param.args[1]);
                        } catch (Throwable error) {
                            XposedBridge.log(TAG + " decodeImage beforeError="
                                    + error.getClass().getSimpleName());
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object result = param.getResult();
                            if (result instanceof android.graphics.Bitmap) {
                                android.graphics.Bitmap bitmap = (android.graphics.Bitmap) result;
                                XposedBridge.log(TAG + " decodeImage AFTER jni="
                                        + Integer.toHexString(System.identityHashCode(param.thisObject))
                                        + " bitmap=" + Integer.toHexString(System.identityHashCode(bitmap))
                                        + " size=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                                        + " config=" + bitmap.getConfig()
                                        + " recycled=" + bitmap.isRecycled());
                            } else {
                                XposedBridge.log(TAG + " decodeImage AFTER result="
                                        + (result == null ? "null" : result.getClass().getName()));
                            }
                        } catch (Throwable error) {
                            XposedBridge.log(TAG + " decodeImage afterError="
                                    + error.getClass().getSimpleName());
                        }
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + " decodeImage probe installed classLoader=" + loaderKey
                    + " matchingMethods=" + hooked);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " decodeImage probe unavailable classLoader=" + loaderKey
                    + " error=" + error.getClass().getSimpleName());
        }
    }

    private static void sample(View root, String when) {
        if (root == null) return;
        try {
            StringBuilder tree = new StringBuilder(512);
            appendTree(tree, root, 0);
            XposedBridge.log(TAG + " sample=" + when + " decorTree=" + tree);

            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            inspectViewForGetters(root, when, visited, 0);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " renderer inspection failed: " + error);
        }
    }

    private static void appendTree(StringBuilder out, View view, int depth) {
        if (view == null || depth > 10 || out.length() > 2500) return;
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
                if (out.length() > 2500) break;
            }
            if (group.getChildCount() > count) out.append(",+").append(group.getChildCount() - count);
            out.append('}');
        }
    }

    private static void inspectViewForGetters(View view, String when, Set<Object> visited, int depth) {
        if (view == null || depth > 10) return;
        String className = view.getClass().getName();
        if (view instanceof SurfaceView || view instanceof TextureView
                || className.startsWith("ul.") || className.toLowerCase().contains("flutter")) {
            XposedBridge.log(TAG + " targetView=" + className
                    + " kind=" + (view instanceof SurfaceView ? "SurfaceView"
                    : view instanceof TextureView ? "TextureView" : "embedded")
                    + " hardwareAccelerated=" + view.isHardwareAccelerated()
                    + " layerType=" + view.getLayerType());
            for (String getter : GETTERS) {
                Object value = invokeNoArgGetter(view, getter);
                if (value != null) {
                    XposedBridge.log(TAG + " getter=" + className + "." + getter
                            + " sample=" + when + " resultClass=" + value.getClass().getName()
                            + " identity=" + Integer.toHexString(System.identityHashCode(value)));
                    inspectObject(value, getter, visited, 0);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < Math.min(group.getChildCount(), 24); i++) {
                inspectViewForGetters(group.getChildAt(i), when, visited, depth + 1);
            }
        }
    }

    private static Object invokeNoArgGetter(Object target, String name) {
        try {
            Method method = findNoArgMethod(target.getClass(), name);
            if (method == null || method.getReturnType() == Void.TYPE
                    || !Modifier.isPublic(method.getModifiers())) {
                XposedBridge.log(TAG + " getterUnavailable=" + target.getClass().getName() + "." + name);
                return null;
            }
            return method.invoke(target);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " getterError=" + target.getClass().getName() + "." + name
                    + " error=" + error.getClass().getSimpleName());
            return null;
        }
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static void inspectObject(Object value, String source, Set<Object> visited, int depth) {
        if (value == null || depth > 2 || !visited.add(value)) return;
        Class<?> type = value.getClass();
        XposedBridge.log(TAG + " object source=" + source + " class=" + type.getName()
                + " super=" + (type.getSuperclass() == null ? "none" : type.getSuperclass().getName())
                + " identity=" + Integer.toHexString(System.identityHashCode(value)));

        Class<?> current = type;
        int levels = 0;
        while (current != null && current != Object.class && levels++ < 4) {
            for (Class<?> iface : current.getInterfaces()) {
                XposedBridge.log(TAG + " objectInterface=" + current.getName() + " -> " + iface.getName());
            }
            int emittedFields = 0;
            for (Field field : current.getDeclaredFields()) {
                if (emittedFields >= 18) break;
                String fieldName = field.getName().toLowerCase();
                String fieldType = field.getType().getName().toLowerCase();
                if (!isInteresting(fieldName) && !isInteresting(fieldType)) continue;
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(value);
                    String valueInfo = fieldValue == null ? "null"
                            : fieldValue.getClass().getName() + "@"
                            + Integer.toHexString(System.identityHashCode(fieldValue));
                    XposedBridge.log(TAG + " objectField=" + current.getName() + "." + field.getName()
                            + " declaredType=" + field.getType().getName() + " value=" + valueInfo);
                    emittedFields++;
                    if (fieldValue != null && !isLeaf(fieldValue.getClass())) {
                        inspectObject(fieldValue, current.getName() + "." + field.getName(), visited, depth + 1);
                    }
                } catch (Throwable error) {
                    XposedBridge.log(TAG + " objectFieldError=" + current.getName() + "." + field.getName()
                            + " error=" + error.getClass().getSimpleName());
                    emittedFields++;
                }
            }
            int emittedMethods = 0;
            for (Method method : current.getDeclaredMethods()) {
                if (emittedMethods >= 16) break;
                if (!isInteresting(method.getName().toLowerCase())) continue;
                StringBuilder signature = new StringBuilder();
                signature.append(method.getReturnType().getName()).append(' ')
                        .append(current.getName()).append('.').append(method.getName()).append('(');
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) signature.append(',');
                    signature.append(params[i].getName());
                }
                signature.append(')');
                XposedBridge.log(TAG + " objectMethod=" + signature);
                emittedMethods++;
            }
            current = current.getSuperclass();
        }
    }

    private static boolean isInteresting(String text) {
        for (String token : INTERESTING) if (text.contains(token)) return true;
        return false;
    }

    private static boolean isLeaf(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class
                || Number.class.isAssignableFrom(type) || type == Boolean.class
                || type == Character.class || type.getName().startsWith("android.");
    }
}
