package com.carassistant.v10.hook;

import android.app.Application;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.database.Cursor;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * AADisplay AaUiHook-compatible reconnect trigger.
 *
 * AA's projection process inflates its facet bar when the connected car UI is
 * created. If CarAsistant has a durable checkpoint that was playing, request
 * the registered CarActivity through Gearhead's own CarSystemUiControllerService.
 */
public final class GearheadAutoResumeHook {
    private static final String TAG = "[AACast AutoResume]";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final long CHECKPOINT_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final String[] FACET_LAYOUTS = {
            "gh_coolwalk_vertical_facet_bar",
            "gh_coolwalk_facet_bar",
            "gh_coolwalk_facet_bar_rhd"
    };

    private static final AtomicBoolean CHECK_IN_FLIGHT = new AtomicBoolean(false);
    private static volatile Context appContext;
    private static final AtomicBoolean RECEIVER_REGISTERED = new AtomicBoolean(false);

    private GearheadAutoResumeHook() { }

    public static void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Context candidate = (Context) param.args[0];
                            if (candidate != null && GEARHEAD.equals(candidate.getPackageName())) {
                                appContext = candidate.getApplicationContext() != null
                                        ? candidate.getApplicationContext() : candidate;
                                registerOpenReceiver(appContext);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Context context = appContext;
                            if (context == null || !(param.args[0] instanceof Integer)) return;
                            int resourceId = (Integer) param.args[0];
                            if (!isFacetBarLayout(context, resourceId)) return;
                            if (!CHECK_IN_FLIGHT.compareAndSet(false, true)) return;
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(() -> {
                                        boolean shouldResume = hasDurablePlayingCheckpoint(context);
                                        CHECK_IN_FLIGHT.set(false);
                                        if (shouldResume) {
                                            GearheadCarAppLauncher.open();
                                        }
                                    }, 1000L);
                        }
                    });
            XposedBridge.log(TAG + " installed in Gearhead projection process");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " install failed: " + error);
        }
    }

    private static void registerOpenReceiver(Context context) {
        if (!RECEIVER_REGISTERED.compareAndSet(false, true)) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context receiverContext, Intent intent) {
                    if (intent != null
                            && "com.carassistant.v10.OPEN_CAR_APP".equals(intent.getAction())) {
                        XposedBridge.log(TAG + " received CarAsistant media voice/open request");
                        GearheadCarAppLauncher.open();
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.carassistant.v10.OPEN_CAR_APP");
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (Throwable error) {
            RECEIVER_REGISTERED.set(false);
            XposedBridge.log(TAG + " could not register CarAsistant open receiver: " + error);
        }
    }

    private static boolean isFacetBarLayout(Context context, int resourceId) {
        try {
            String name = context.getResources().getResourceEntryName(resourceId);
            for (String candidate : FACET_LAYOUTS) {
                if (candidate.equals(name)) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static boolean hasDurablePlayingCheckpoint(Context context) {
        Cursor cursor = null;
        try {
            Uri uri = Uri.parse("content://com.carassistant.v10.launcher.dock/media_resume");
            cursor = context.getContentResolver().query(uri,
                    new String[]{"resume_valid", "resume_was_playing", "resume_saved_at"},
                    null, null, null);
            if (cursor == null || !cursor.moveToFirst()) return false;
            boolean valid = cursor.getInt(0) == 1;
            boolean wasPlaying = cursor.getInt(1) == 1;
            long savedAt = cursor.getLong(2);
            long age = System.currentTimeMillis() - savedAt;
            return valid && wasPlaying && savedAt > 0L
                    && age >= 0L && age < CHECKPOINT_MAX_AGE_MS;
        } catch (Throwable error) {
            XposedBridge.log(TAG + " checkpoint query failed: " + error);
            return false;
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (RuntimeException ignored) { }
            }
        }
    }
}
