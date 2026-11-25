package com.carassistant.v10;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * One-shot diagnostic recorder for Gemini -> Gearhead -> Android Auto Car Activity,
 * Android task/display lifecycle and WYN popup-window traces.
 *
 * Root logcat is required for system_server and other-process records.
 */
public final class GeminiCommandLogCapture {
    private static final String TAG = "GeminiCommandTrace";
    private static final int MAX_LINES = 30000;
    private static final long AUTO_STOP_MS = 300_000L;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>();
    private static volatile boolean capturing;
    private static volatile boolean launchSeen;
    private static volatile long startedAt;
    private static volatile long linesSeen;
    private static volatile long linesRetained;
    private static volatile long linesEvicted;
    private static volatile File latestFile;
    private static volatile Process logcatProcess;
    private static volatile Context appContext;

    private GeminiCommandLogCapture() { }

    public static boolean isCapturing() { return capturing; }

    public static long getCaptureDurationSeconds() {
        return capturing ? Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L) : 0L;
    }

    public static File getLatestFile(Context context) {
        File file = latestFile;
        if (file != null && file.isFile()) return file;
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File dir = new File(base, "diagnostics");
        File[] files = dir.listFiles((d, name) -> (name.startsWith("gemini-command-") || name.startsWith("carassistant-unified-")) && name.endsWith(".txt"));
        if (files == null || files.length == 0) return null;
        File newest = files[0];
        for (File candidate : files) if (candidate.lastModified() > newest.lastModified()) newest = candidate;
        latestFile = newest;
        return newest;
    }

    public static boolean start(Context context) {
        synchronized (LOCK) {
            if (capturing) return false;
            appContext = context.getApplicationContext();
            BUFFER.clear();
            launchSeen = false;
            linesSeen = 0L;
            linesRetained = 0L;
            linesEvicted = 0L;
            startedAt = System.currentTimeMillis();
            capturing = true;
            addLocked("CarAsistant unified diagnostic log");
            addLocked("Started: " + new Date(startedAt));
            addLocked("Device: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "; Android=" + Build.VERSION.RELEASE + "; SDK=" + Build.VERSION.SDK_INT);
            addLocked("Trigger sequence: Android Auto connect -> Gemini: Mở app CarAsistant -> wait for Car Activity -> WYN Home/background/popup changes.");
            addLocked("Sources: root logcat -b all; Gemini/Gearhead/Android Auto/ActivityTaskManager/WindowManager/DisplayManager/CarAsistant/WYN.");
            addLocked("Trace tag: AACastGeminiTrace; WYN tag: AACastWYNPopupTrace.");
            addLocked("Buffer: newest " + MAX_LINES + " relevant lines; maximum capture duration " + (AUTO_STOP_MS / 1000L) + " seconds.");
            addLocked("Privacy: Intent extra values and private voice/conversation content are not deliberately dumped; metadata and extra keys only.");
            addLocked("Capture boundary: records begin when this button is pressed; old logcat history is not intentionally cleared.");
        }

        Thread worker = new Thread(() -> {
            try {
                Process process = new ProcessBuilder("su", "-c", "logcat -b all -v threadtime -T 1")
                        .redirectErrorStream(true).start();
                logcatProcess = process;
                if (!capturing) {
                    process.destroy();
                    return;
                }
                append("CAPTURE_STATUS: root logcat process started; command=logcat -b all -v threadtime -T 1.");
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (capturing && (line = reader.readLine()) != null) {
                    if (isRelevant(line)) {
                        linesSeen++;
                        append(line);
                    }
                }
                reader.close();
                if (capturing) {
                    append("CAPTURE_ERROR: logcat stream ended unexpectedly; exitCode=" + safeExitCode(process));
                    finish("Logcat stream ended unexpectedly");
                }
            } catch (Throwable error) {
                append("CAPTURE_ERROR: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                finish("Không đọc được logcat/root; kiểm tra quyền su");
            }
        }, "CarAsistant-Unified-Logcat");
        worker.setDaemon(true);
        worker.start();

        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(AUTO_STOP_MS); } catch (InterruptedException ignored) { return; }
            if (capturing) finish("Tự dừng sau " + (AUTO_STOP_MS / 1000L) + " giây");
        }, "CarAsistant-Unified-Log-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return true;
    }

    /** Called only when Android Auto's CarActivity has actually resumed. */
    public static void onCarActivityResumed() {
        if (!capturing || launchSeen) return;
        launchSeen = true;
        append("CARASISTANT_MARKER: V10CarActivity.onResume observed at " + new Date());
        append("CAPTURE_STATUS: CarAsistant Car Activity reached foreground; keep recording subsequent projection/WYN events.");
    }

    public static void stopByUser() {
        finish("Người dùng chủ động dừng và xuất log");
    }

    private static int safeExitCode(Process process) {
        try { return process.exitValue(); } catch (Throwable ignored) { return -1; }
    }

    private static boolean isRelevant(String line) {
        String s = line.toLowerCase(Locale.ROOT);
        return s.contains("aacastgeminitrace")
                || s.contains("aacastwynpopuptrace")
                || s.contains("geminicommandtrace")
                || s.contains("com.wynauto.wyn")
                || s.contains("com.carassistant.v10")
                || s.contains("carassistant")
                || s.contains("aacast")
                || s.contains("gearhead")
                || s.contains("androidauto")
                || s.contains("android auto")
                || s.contains("projection")
                || s.contains("caractivity")
                || s.contains("car activity")
                || s.contains("carsystemuicontroller")
                || s.contains("carservice")
                || s.contains("car service")
                || s.contains("activitytaskmanager")
                || s.contains("activitymanager")
                || s.contains("windowmanager")
                || s.contains("displaymanager")
                || s.contains("taskpersister")
                || s.contains("system_server")
                || s.contains("googlequicksearchbox")
                || s.contains("googleassistant")
                || s.contains("assistant")
                || s.contains("google.android.apps")
                || s.contains("google.android.projection")
                || s.contains("voiceinteraction")
                || s.contains("speechrecognizer")
                || s.contains("recognitionservice")
                || s.contains("hotword")
                || s.contains("fatal exception")
                || s.contains("permission denial")
                || s.contains("permission denied")
                || s.contains("am_create_activity")
                || s.contains("am_resume_activity")
                || s.contains("am_pause_activity")
                || s.contains("am_stop_activity")
                || s.contains("am_destroy_activity")
                || s.contains("am_proc_start")
                || s.contains("am_proc_died")
                || s.contains("wm_create_activity")
                || s.contains("wm_resume_activity")
                || s.contains("wm_pause_activity")
                || s.contains("wm_finish_activity")
                || s.contains("wm_task_created")
                || s.contains("wm_task_removed")
                || s.contains("wm_set_resumed_activity")
                || s.contains("wm_on_top_resumed_gained")
                || s.contains("am_")
                || s.contains("wm_");
    }

    private static void addLocked(String line) {
        if (BUFFER.size() >= MAX_LINES) {
            BUFFER.removeFirst();
            linesEvicted++;
        }
        BUFFER.addLast(line);
        linesRetained++;
    }

    private static void append(String line) {
        synchronized (LOCK) {
            if (!capturing) return;
            addLocked(line);
        }
    }

    private static void finish(String reason) {
        synchronized (LOCK) {
            if (!capturing) return;
            long endedAt = System.currentTimeMillis();
            capturing = false;
            addLocked("Stopped: " + new Date(endedAt));
            addLocked("Duration seconds: " + Math.max(0L, (endedAt - startedAt) / 1000L));
            addLocked("Car Activity observed: " + launchSeen);
            addLocked("Relevant log lines seen: " + linesSeen);
            addLocked("Lines retained (including metadata): " + linesRetained);
            addLocked("Oldest lines evicted because buffer was full: " + linesEvicted);
            addLocked("Buffer capacity: " + MAX_LINES);
            addLocked("Result: " + reason);
        }
        Process process = logcatProcess;
        logcatProcess = null;
        if (process != null) {
            try { process.destroy(); } catch (Throwable ignored) { }
        }
        save();
    }

    private static void save() {
        Context context = appContext;
        if (context == null) return;
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File dir = new File(base, "diagnostics");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create diagnostics directory: " + dir);
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File output = new File(dir, "carassistant-unified-" + stamp + ".txt");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            synchronized (LOCK) {
                for (String line : BUFFER) {
                    stream.write(line.getBytes(StandardCharsets.UTF_8));
                    stream.write('\n');
                }
            }
            stream.flush();
            latestFile = output;
            Log.i(TAG, "Unified diagnostic saved: " + output.getAbsolutePath()
                    + "; bytes=" + output.length());
        } catch (Exception error) {
            Log.e(TAG, "Cannot save unified diagnostic log", error);
        }
    }
}
