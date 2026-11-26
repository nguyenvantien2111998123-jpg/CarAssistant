package com.carassistant.v10.hook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.CRC32;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Captures WYN popup pixels from its own live SurfaceView, independent of its screen position. */
public final class WynPopupWindowTrace {
    private static final String TAG = "AACastWYNPopupTrace";
    private static final String WARNING_ACTION = "com.carassistant.v10.action.WYN_WARNING_FRAME";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<View, CaptureLoop> LOOPS =
            Collections.synchronizedMap(new WeakHashMap<View, CaptureLoop>());
    private static volatile boolean installed;

    private WynPopupWindowTrace() { }

    public static synchronized void install(ClassLoader loader) {
        if (installed || loader == null) return;
        installed = true;
        hookActivityLifecycle();
        hookWindowManagerGlobal();
        Log.i(TAG, "live popup capture installed; screen-coordinate crop disabled");
    }

    private static void hookActivityLifecycle() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { logActivity("RESUMED", p.thisObject); }
            });
            XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { logActivity("PAUSING", p.thisObject); }
            });
            XposedBridge.hookAllMethods(Activity.class, "onStop", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { logActivity("STOPPED", p.thisObject); }
            });
        } catch (Throwable error) {
            Log.w(TAG, "Activity lifecycle hooks unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static void logActivity(String state, Object candidate) {
        if (!(candidate instanceof Activity)) return;
        Activity activity = (Activity) candidate;
        try {
            if ("com.wynauto.wyn".equals(activity.getPackageName())) {
                Log.i(TAG, "ACTIVITY " + state + " class=" + activity.getClass().getName()
                        + " taskId=" + activity.getTaskId() + " finishing=" + activity.isFinishing());
            }
        } catch (Throwable ignored) { }
    }

    private static void hookWindowManagerGlobal() {
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal", false, null);
            hookWindowMethod(type, "addView", "ADD");
            hookWindowMethod(type, "updateViewLayout", "UPDATE");
            hookWindowMethod(type, "removeView", "REMOVE");
            hookWindowMethod(type, "removeViewImmediate", "REMOVE_NOW");
        } catch (Throwable error) {
            Log.w(TAG, "WindowManagerGlobal hooks unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static void hookWindowMethod(Class<?> type, String methodName, String action) {
        try {
            XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View view = findView(p.args);
                    if (view == null) return;
                    if ("REMOVE".equals(action) || "REMOVE_NOW".equals(action)) {
                        stopCapture(view);
                        Log.i(TAG, action + " view=" + view.getClass().getName());
                        return;
                    }
                    WindowManager.LayoutParams attrs = findLayoutParams(p.args);
                    logWindow(action, view, attrs);
                    if (isWynWarningPopup(view, attrs)) startCapture(view);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "hook " + methodName + " unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static View findView(Object[] args) {
        if (args != null) for (Object arg : args) if (arg instanceof View) return (View) arg;
        return null;
    }
    private static WindowManager.LayoutParams findLayoutParams(Object[] args) {
        if (args != null) for (Object arg : args) if (arg instanceof WindowManager.LayoutParams)
            return (WindowManager.LayoutParams) arg;
        return null;
    }
    /**
     * WYN's confirmed warning popup is a separate TYPE_APPLICATION_OVERLAY window
     * (2038), class jm.n, roughly 230x507. Never capture the Activity window or
     * another Flutter SurfaceView: that was the source of startup-screen crops.
     */
    private static boolean isWynWarningPopup(View view, WindowManager.LayoutParams attrs) {
        if (view == null || attrs == null
                || attrs.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) return false;
        try {
            if (!"com.wynauto.wyn".equals(view.getContext().getPackageName())) return false;
            String className = view.getClass().getName();
            if ("jm.n".equals(className) || containsPopupClass(view)) return true;
            int width = view.getWidth() > 0 ? view.getWidth() : attrs.width;
            int height = view.getHeight() > 0 ? view.getHeight() : attrs.height;
            return width >= 80 && height >= 120 && height >= width * 1.35f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsPopupClass(View view) {
        if ("jm.n".equals(view.getClass().getName())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child != null && containsPopupClass(child)) return true;
            }
        }
        return false;
    }

    private static void startCapture(View root) {
        synchronized (LOOPS) {
            if (LOOPS.containsKey(root)) return;
            CaptureLoop loop = new CaptureLoop(root);
            LOOPS.put(root, loop);
            MAIN.post(loop);
        }
    }
    private static void stopCapture(View root) {
        synchronized (LOOPS) {
            CaptureLoop loop = LOOPS.remove(root);
            if (loop != null) loop.stop();
        }
    }

    private static final class CaptureLoop implements Runnable {
        final WeakReference<View> root;
        long lastHash = Long.MIN_VALUE;
        long lastPublishedAt;
        long lastRequest;
        boolean captureInFlight;
        boolean stopped;
        CaptureLoop(View view) { root = new WeakReference<>(view); }
        void stop() { stopped = true; MAIN.removeCallbacks(this); }
        @Override public void run() {
            if (stopped) return;
            View view = root.get();
            if (view == null || !view.isAttachedToWindow()) {
                if (view != null) stopCapture(view); else stop();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (view.getVisibility() == View.VISIBLE && view.isShown()
                    && view.getWidth() >= 80 && view.getHeight() >= 120
                    && !captureInFlight && now - lastRequest >= 600L) {
                lastRequest = now;
                captureInFlight = true;
                try {
                    capture(view, this);
                } catch (Throwable error) {
                    captureInFlight = false;
                    Log.w(TAG, "Popup capture request failed", error);
                }
            }
            MAIN.postDelayed(this, 600L);
        }
    }

    private static void capture(View root, CaptureLoop loop) {
        SurfaceView surface = findSurfaceView(root);
        if (surface != null && Build.VERSION.SDK_INT >= 24 && surface.getWidth() > 0
                && surface.getHeight() > 0 && surface.getHolder().getSurface() != null
                && surface.getHolder().getSurface().isValid()) {
            Bitmap bitmap = Bitmap.createBitmap(surface.getWidth(), surface.getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(surface, bitmap, result -> {
                if (result == PixelCopy.SUCCESS) publish(root.getContext(), bitmap, loop,
                        "SurfaceView:" + surface.getClass().getName());
                else {
                    bitmap.recycle();
                    loop.captureInFlight = false;
                    Log.d(TAG, "Popup PixelCopy result=" + result);
                }
            }, MAIN);
            return;
        }
        TextureView texture = findTextureView(root);
        if (texture != null && texture.isAvailable()) {
            Bitmap bitmap = texture.getBitmap();
            if (bitmap != null) { publish(root.getContext(), bitmap, loop, "TextureView"); return; }
        }
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            root.draw(new Canvas(bitmap));
            publish(root.getContext(), bitmap, loop, "View.draw:" + root.getClass().getName());
        } catch (Throwable error) {
            loop.captureInFlight = false;
            bitmap.recycle();
            Log.w(TAG, "View draw fallback failed", error);
        }
    }

    private static SurfaceView findSurfaceView(View view) {
        if (view instanceof SurfaceView) return (SurfaceView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i=0;i<group.getChildCount();i++) {
                SurfaceView found=findSurfaceView(group.getChildAt(i));
                if(found!=null)return found;
            }
        }
        return null;
    }
    private static TextureView findTextureView(View view) {
        if (view instanceof TextureView) return (TextureView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                TextureView found=findTextureView(group.getChildAt(i));
                if(found!=null)return found;
            }
        }
        return null;
    }

    private static void publish(Context context, Bitmap bitmap, CaptureLoop loop, String source) {
        try {
            loop.captureInFlight = false;
            if (bitmap.getWidth() < 80 || bitmap.getHeight() < 120
                    || bitmap.getHeight() < bitmap.getWidth() * 1.2f) {
                Log.d(TAG, "Ignoring non-popup WYN frame " + bitmap.getWidth()
                        + "x" + bitmap.getHeight());
                return;
            }
            if (!hasVisualContent(bitmap)) {
                Log.d(TAG, "Ignoring blank/unrendered WYN popup surface");
                return;
            }
            long hash=sampleHash(bitmap);
            if(hash==loop.lastHash)return;
            long now = SystemClock.elapsedRealtime();
            // Flutter popup surfaces may animate. Limit MediaSession artwork updates
            // to at most one per second so Gearhead keeps the media card stable.
            if (loop.lastPublishedAt > 0L && now - loop.lastPublishedAt < 1000L) return;
            loop.lastHash=hash;
            loop.lastPublishedAt=now;
            byte[] png = encodeTransparentPng(bitmap);
            if (png == null || png.length == 0) return;
            Context app=context.getApplicationContext();
            if(app==null)app=context;
            Intent intent=new Intent(WARNING_ACTION);
            intent.setComponent(new ComponentName("com.carassistant.v10","com.carassistant.v10.WynWarningReceiver"));
            intent.setPackage("com.carassistant.v10");
            intent.putExtra("png",png);
            app.sendBroadcast(intent);
            Log.i(TAG,"WARNING_FRAME_SENT source="+source+" size="+bitmap.getWidth()+"x"+bitmap.getHeight()
                    +" bytes="+png.length+" format=PNG_ALPHA");
        } catch(Throwable error) { Log.w(TAG,"Could not publish popup frame",error); }
        finally {
            loop.captureInFlight = false;
            if(!bitmap.isRecycled())bitmap.recycle();
        }
    }

    /** Preserve popup transparency; downscale only as much as needed for broadcast size. */
    private static byte[] encodeTransparentPng(Bitmap source) {
        Bitmap current = source;
        try {
            for (int attempt = 0; attempt < 8; attempt++) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                current.compress(Bitmap.CompressFormat.PNG, 100, out);
                byte[] bytes = out.toByteArray();
                if (bytes.length <= 900 * 1024
                        || (current.getWidth() <= 180 && current.getHeight() <= 180)) {
                    return bytes;
                }
                Bitmap smaller = Bitmap.createScaledBitmap(current,
                        Math.max(1, Math.round(current.getWidth() * 0.82f)),
                        Math.max(1, Math.round(current.getHeight() * 0.82f)), true);
                if (current != source && !current.isRecycled()) current.recycle();
                current = smaller;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            current.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } finally {
            if (current != source && !current.isRecycled()) current.recycle();
        }
    }

    /** Reject transparent/blank surfaces captured before WYN has rendered its warning. */
    private static boolean hasVisualContent(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int center = bitmap.getPixel(width / 2, height / 2);
        int visible = 0;
        int distinct = 0;
        int[] colors = new int[12];
        for (int row = 0; row < 12; row++) {
            int y = Math.min(height - 1, (row * height) / 12);
            for (int col = 0; col < 8; col++) {
                int x = Math.min(width - 1, (col * width) / 8);
                int color = bitmap.getPixel(x, y);
                if (((color >>> 24) & 0xff) > 24) visible++;
                boolean seen = color == center;
                for (int i = 0; i < distinct && !seen; i++) seen = colors[i] == color;
                if (!seen && distinct < colors.length) colors[distinct++] = color;
            }
        }
        return visible >= 8 && distinct >= 2;
    }

    /** Hash every popup pixel so text-only speed-limit/alert changes are never missed. */
    private static long sampleHash(Bitmap bitmap) {
        CRC32 crc = new CRC32();
        ByteBuffer pixels = ByteBuffer.allocate(bitmap.getByteCount());
        bitmap.copyPixelsToBuffer(pixels);
        crc.update(pixels.array());
        return crc.getValue();
    }

    private static void logWindow(String action, View view, WindowManager.LayoutParams attrs) {
        try {
            Rect bounds=new Rect(); boolean visible=view.getGlobalVisibleRect(bounds);
            Log.i(TAG,action+" class="+view.getClass().getName()
                    +" bounds="+(visible?bounds.flattenToString():"not-visible")
                    +"|size="+view.getWidth()+"x"+view.getHeight()
                    +"|type="+(attrs==null?"?":attrs.type)
                    +"|gravity="+(attrs==null?"?":attrs.gravity)
                    +"|xy="+(attrs==null?"?":attrs.x+","+attrs.y)
                    +" children="+describeChildren(view,0));
        } catch(Throwable error) { Log.d(TAG,action+" window trace failed: "+error.getClass().getSimpleName()); }
    }
    private static String describeChildren(View view,int depth) {
        if(!(view instanceof ViewGroup)||depth>=3)return "";
        ViewGroup group=(ViewGroup)view; StringBuilder out=new StringBuilder();
        int count=Math.min(group.getChildCount(),12);
        for(int i=0;i<count;i++) {
            View child=group.getChildAt(i); if(child==null)continue;
            if(out.length()>0)out.append(',');
            out.append(child.getClass().getName()).append('[').append(child.getWidth())
                    .append('x').append(child.getHeight()).append(']');
            if(depth<2&&child instanceof ViewGroup)out.append('{').append(describeChildren(child,depth+1)).append('}');
        }
        if(group.getChildCount()>count)out.append(",…");
        return out.toString();
    }
}