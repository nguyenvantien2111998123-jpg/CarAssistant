package com.carassistant.v10;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persists the last external media session and attempts to restore it when
 * Android Auto becomes visible to the phone-side AccessibilityService.
 *
 * The saved snapshot is refreshed by MediaSessionBridge every 60 seconds and
 * whenever the source session publishes new metadata or playback state.
 */
public final class MediaResumeCoordinator {
    private static final String TAG = "CarAsstMediaResume";
    private static final String PREFS = "carassistant_media_resume_v2";
    private static final String PACKAGE = "package";
    private static final String PROJECTED_COMPONENT = "projected_component";
    private static final String TITLE = "title";
    private static final String ARTIST = "artist";
    private static final String DURATION = "duration";
    private static final String POSITION = "position";
    private static final String PLAYING = "playing";
    private static final String SAVED_AT = "saved_at";
    private static final String LAST_RESTORE = "last_restore";
    private static final String GEARHEAD_PACKAGE = "com.google.android.projection.gearhead";
    private static final long MAX_SNAPSHOT_AGE_MS = 24L * 60L * 60L * 1000L;
    private static final long RESTORE_DEBOUNCE_MS = 120_000L;
    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final int MAX_RETRIES = 15;

    private static final AtomicBoolean restoreInProgress = new AtomicBoolean(false);

    private MediaResumeCoordinator() { }

    /** Remember which app V10 last projected, independently from the active media player. */
    public static void persistProjectedTarget(Context context, ComponentName component) {
        if (context == null || component == null) return;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PROJECTED_COMPONENT, component.flattenToString()).apply();
    }

    public static ComponentName getLastProjectedTarget(Context context) {
        if (context == null) return null;
        String value = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PROJECTED_COMPONENT, null);
        return TextUtils.isEmpty(value) ? null : ComponentName.unflattenFromString(value);
    }

    /** Store only the serializable session state; artwork remains in the live session. */
    public static void persist(Context context, MediaSessionBridge.Snapshot snapshot) {
        if (context == null || snapshot == null || TextUtils.isEmpty(snapshot.packageName)
                || snapshot.packageName.equals(context.getPackageName())) return;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(PACKAGE, snapshot.packageName)
                .putString(TITLE, snapshot.title == null ? "" : snapshot.title)
                .putString(ARTIST, snapshot.artist == null ? "" : snapshot.artist)
                .putLong(DURATION, Math.max(0L, snapshot.duration))
                .putLong(POSITION, Math.max(0L, snapshot.position))
                .putBoolean(PLAYING, snapshot.playing)
                .putLong(SAVED_AT, System.currentTimeMillis())
                .apply();
    }

    /** Called for Gearhead window events; repeated events from one connection are debounced. */
    public static void onAccessibilityEvent(Context context, AccessibilityEvent event,
                                            AccessibilityService service) {
        if (context == null || event == null || event.getPackageName() == null) return;
        if (!GEARHEAD_PACKAGE.contentEquals(event.getPackageName())) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        restoreLatest(context.getApplicationContext(), service);
    }

    /** Also catches the case where Accessibility is enabled after Android Auto is already open. */
    public static void onAccessibilityConnected(AccessibilityService service) {
        if (service == null) return;
        try {
            android.view.accessibility.AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return;
            try {
                CharSequence packageName = root.getPackageName();
                if (GEARHEAD_PACKAGE.contentEquals(packageName)) {
                    restoreLatest(service.getApplicationContext(), service);
                }
            } finally {
                root.recycle();
            }
        } catch (RuntimeException error) {
            Log.d(TAG, "Could not inspect active window during service connection", error);
        }
    }

    private static void restoreLatest(Context context, AccessibilityService service) {
        if (service == null || !restoreInProgress.compareAndSet(false, true)) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastRestore = prefs.getLong(LAST_RESTORE, 0L);
        if (now - lastRestore < RESTORE_DEBOUNCE_MS) {
            restoreInProgress.set(false);
            return;
        }

        String packageName = prefs.getString(PACKAGE, "");
        String title = prefs.getString(TITLE, "");
        long position = prefs.getLong(POSITION, 0L);
        long savedAt = prefs.getLong(SAVED_AT, 0L);
        boolean wasPlaying = prefs.getBoolean(PLAYING, false);
        if (TextUtils.isEmpty(packageName) || !wasPlaying || savedAt <= 0L
                || now - savedAt > MAX_SNAPSHOT_AGE_MS
                || packageName.equals(context.getPackageName())) {
            restoreInProgress.set(false);
            Log.i(TAG, "No recent playing media session to restore");
            return;
        }

        prefs.edit().putLong(LAST_RESTORE, now).apply();
        Log.i(TAG, "Android Auto detected; restoring package=" + packageName
                + ", title=" + title + ", savedPositionMs=" + position);

        if (MediaSessionBridge.restoreSavedPlayback(packageName, title, position)) {
            restoreInProgress.set(false);
            return;
        }

        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent == null || launchIntent.getComponent() == null
                || !packageName.equals(launchIntent.getComponent().getPackageName())) {
            restoreInProgress.set(false);
            Log.w(TAG, "No safe launcher Activity for saved media package " + packageName);
            return;
        }

        ComponentName component = launchIntent.getComponent();
        String componentName = component.flattenToShortString();
        if (!componentName.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")) {
            restoreInProgress.set(false);
            Log.w(TAG, "Refusing invalid media launch component " + componentName);
            return;
        }

        final String expectedPackage = packageName;
        final String expectedTitle = title;
        final long expectedPosition = position;
        final String target = componentName;
        RootShellSession.EXEC.execute(() -> {
            RootShellSession shell = new RootShellSession();
            try {
                int userId = android.os.Process.myUid() / 100000;
                String command = "/system/bin/am start --user " + userId
                        + " --activity-no-animation --activity-exclude-from-recents -n '"
                        + target.replace("'", "'\\''") + "' >/dev/null 2>&1; "
                        + "/system/bin/input keyevent 3";
                String output = shell.run(12, command);
                Log.i(TAG, "Background launch requested for " + expectedPackage
                        + "; phone task returned Home. " + output);
                retryRestore(context, expectedPackage, expectedTitle, expectedPosition, 0);
            } catch (Throwable error) {
                Log.w(TAG, "Could not start saved media app in background", error);
                restoreInProgress.set(false);
            } finally {
                shell.destroy();
            }
        });
    }

    private static void retryRestore(Context context, String packageName, String title,
                                     long position, int attempt) {
        if (MediaSessionBridge.restoreSavedPlayback(packageName, title, position)) {
            restoreInProgress.set(false);
            return;
        }
        if (attempt >= MAX_RETRIES) {
            Log.w(TAG, "No matching media session appeared after app relaunch: " + packageName);
            restoreInProgress.set(false);
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            retryRestore(context, packageName, title, position, attempt + 1);
        }, RETRY_INTERVAL_MS);
    }
}
