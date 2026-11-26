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
                    logWindow(action, view, findLayoutParams(p.args));
                    if ("ADD".equals(action)) startCapture(view);
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
        long lastRequest;
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
            if (view.getVisibility() == View.VISIBLE && view.getWidth() >= 80
                    && view.getHeight() >= 80 && now - lastRequest >= 450L) {
                lastRequest = now;
                capture(view, this);
            }
            MAIN.postDelayed(this, 450L);
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
                else { bitmap.recycle(); Log.d(TAG, "PixelCopy result=" + result); }
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
            long hash=sampleHash(bitmap);
            if(hash==loop.lastHash)return;
            loop.lastHash=hash;
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
        finally { if(!bitmap.isRecycled())bitmap.recycle(); }
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

    private static long sampleHash(Bitmap bitmap) {
        CRC32 crc=new CRC32(); int w=bitmap.getWidth(),h=bitmap.getHeight();
        for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
            int color=bitmap.getPixel(Math.min(w-1,x*w/8),Math.min(h-1,y*h/8));
            crc.update(color>>>24&255); crc.update(color>>>16&255);
            crc.update(color>>>8&255); crc.update(color&255);
        }
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