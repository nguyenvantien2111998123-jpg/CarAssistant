package com.carassistant.v10.hook;

import android.app.Application;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Reads a pending Gemini media request from CarAsistant's provider and opens
 * the V10 CarActivity through Gearhead's existing Car App launcher. No Gemini
 * internals are hooked and no steering/media-key code is changed.
 */
public final class GearheadGeminiVoiceBridge {
    private static final String TAG = "[AACastGeminiVoiceBridge]";
    private static final String LOG_TAG = "AACastGeminiVoiceBridge";
    private static final String AUTHORITY = "com.carassistant.v10.launcher.dock";
    private static final long POLL_MS = 500L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile Context context;
    private volatile long lastDispatchedId;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try {
                if (context != null && inFlight.compareAndSet(false, true)) {
                    worker.execute(GearheadGeminiVoiceBridge.this::readPendingRequest);
                }
            } catch (Throwable error) {
                log("poll failed: " + error);
            } finally {
                main.postDelayed(this, POLL_MS);
            }
        }
    };

    private GearheadGeminiVoiceBridge() { }

    public static void install() {
        GearheadGeminiVoiceBridge bridge = new GearheadGeminiVoiceBridge();
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Context attached = (Context) param.args[0];
                            if (attached != null
                                    && "com.google.android.projection.gearhead".equals(attached.getPackageName())) {
                                bridge.context = attached.getApplicationContext() != null
                                        ? attached.getApplicationContext() : attached;
                                bridge.main.post(bridge.poll);
                                log("installed in Gearhead");
                            }
                        }
                    });
        } catch (Throwable error) {
            log("install failed: " + error);
        }
    }

    private void readPendingRequest() {
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri uri = Uri.parse("content://" + AUTHORITY + "/state");
            cursor = resolver.query(uri,
                    new String[]{"visible_until", "gemini_request_id", "gemini_query"},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                long requestId = cursor.getLong(1);
                String query = cursor.getString(2);
                if (requestId > 0L && requestId != lastDispatchedId
                        && query != null && !query.trim().isEmpty()) {
                    lastDispatchedId = requestId;
                    String safeQuery = query.trim();
                    main.post(() -> dispatch(safeQuery, requestId));
                }
            }
        } catch (Throwable error) {
            Log.w(LOG_TAG, "Provider read failed", error);
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (RuntimeException ignored) { }
            }
            inFlight.set(false);
        }
    }

    private void dispatch(String query, long requestId) {
        try {
            ClassLoader loader = context.getClassLoader();
            Class<?> controller = XposedHelpers.findClassIfExists(
                    "com.google.android.projection.gearhead.service.CarSystemUiControllerService",
                    loader);
            Method launcher = findCarAppLauncher(controller);
            if (launcher == null) throw new NoSuchMethodException("Gearhead Car App launcher unavailable");
            launcher.setAccessible(true);
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.carassistant.v10", "com.carassistant.v10.AACastCarService"));
            intent.putExtra("android.intent.extra.PACKAGE_NAME", "com.carassistant.v10");
            intent.putExtra("com.carassistant.v10.OPEN_GEMINI_MEDIA_SEARCH", true);
            intent.putExtra("com.carassistant.v10.GEMINI_MEDIA_QUERY", query);
            intent.putExtra("com.carassistant.v10.GEMINI_MEDIA_REQUEST_ID", requestId);
            launcher.invoke(null, intent);
            log("forwarded Gemini media request id=" + requestId + " query=" + query);
        } catch (Throwable error) {
            log("could not open CarAsistant for Gemini request id=" + requestId + ": " + error);
        }
    }

    private static Method findCarAppLauncher(Class<?> controller) {
        for (Class<?> type = controller; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) && "a".equals(method.getName())
                        && method.getParameterTypes().length == 1
                        && Intent.class.equals(method.getParameterTypes()[0])) return method;
            }
        }
        return null;
    }

    private static void log(String message) {
        Log.i(LOG_TAG, message);
        XposedBridge.log(TAG + " " + message);
    }
}
