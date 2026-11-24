package com.carassistant.v10;

import android.content.Context;
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
 * Captures the Android/Google Assistant hand-off around the user's
 * "Mở app CarAsistant" voice request. Root logcat is required for system logs.
 */
public final class GeminiCommandLogCapture {
    private static final String TAG = "GeminiCommandTrace";
    private static final int MAX_LINES = 3000;
    private static final long AUTO_STOP_MS = 120_000L;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>();
    private static volatile boolean capturing;
    private static volatile boolean launchSeen;
    private static volatile long startedAt;
    private static volatile File latestFile;
    private static volatile Process logcatProcess;
    private static volatile Thread captureThread;
    private static volatile Context appContext;

    private GeminiCommandLogCapture() { }

    public static boolean isCapturing() { return capturing; }

    public static File getLatestFile(Context context) {
        File file = latestFile;
        if (file != null && file.isFile()) return file;
        File dir = new File(context.getExternalFilesDir(null), "diagnostics");
        File[] files = dir.listFiles((d, name) -> name.startsWith("gemini-command-") && name.endsWith(".txt"));
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
            startedAt = System.currentTimeMillis();
            capturing = true;
            BUFFER.add("CarAsistant Gemini + WYN diagnostic");
            BUFFER.add("Started: " + new Date(startedAt));
            BUFFER.add("Trigger phrase: Mở app CarAsistant");
            BUFFER.add("Capture source: root logcat -b all; relevant Google Assistant, Android Auto, CarAsistant and WYN lines are retained.");
            BUFFER.add("Included tag: AACastGeminiTrace (Gearhead Car Activity Intent/method trace).");
            BUFFER.add("Included tag: AACastWYNPopupTrace (WYN Activity/Window/View bounds trace).");
            BUFFER.add("Capture remains active after CarAsistant opens so later WYN popup events are included; maximum duration 120 seconds.");
            BUFFER.add("Note: private Gemini conversation content may not be exposed by Android logcat.");
        }

        Thread worker = new Thread(() -> {
            try {
                Process process = new ProcessBuilder("su", "-c", "logcat -b all -v threadtime")
                        .redirectErrorStream(true).start();
                logcatProcess = process;
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (capturing && (line = reader.readLine()) != null) {
                    if (isRelevant(line)) append(line);
                }
                reader.close();
            } catch (Throwable error) {
                append("CAPTURE_ERROR: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                finish("Không đọc được logcat/root");
            }
        }, "CarAsistant-Gemini-Logcat");
        worker.setDaemon(true);
        captureThread = worker;
        worker.start();

        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(AUTO_STOP_MS); } catch (InterruptedException ignored) { return; }
            if (capturing) finish(launchSeen
                    ? "Đã mở CarAsistant; hoàn tất cửa sổ thu log Gemini + WYN 120 giây"
                    : "Hết 120 giây nhưng chưa thấy CarAsistant được mở");
        }, "CarAsistant-Gemini-Log-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return true;
    }

    /** Called by V10CarActivity only when Android Auto has actually resumed it. */
    public static void onCarActivityResumed() {
        if (!capturing || launchSeen) return;
        launchSeen = true;
        append("CARASISTANT_MARKER: V10CarActivity.onResume observed at " + new Date());
        append("CAPTURE_STATUS: tiếp tục thu log WYN popup sau khi CarAsistant mở; tự dừng theo giới hạn 120 giây.");
    }

    private static boolean isRelevant(String line) {
        String s = line.toLowerCase(Locale.ROOT);
        return s.contains("aacastgeminitrace")
                || s.contains("aacastwynpopuptrace")
                || s.contains("com.wynauto.wyn")
                || s.contains("gemini")
                || s.contains("googlequicksearchbox")
                || s.contains("googleassistant")
                || s.contains("voiceinteraction")
                || s.contains("hotword")
                || s.contains("recognitionservice")
                || s.contains("speechrecognizer")
                || s.contains("activitytaskmanager")
                || s.contains("activitymanager")
                || s.contains("gearhead")
                || s.contains("projection")
                || s.contains("carassistant")
                || s.contains("aacast")
                || s.contains("google.android.apps")
                || s.contains("permission denied")
                || s.contains("fatal exception");
    }

    private static void append(String line) {
        synchronized (LOCK) {
            if (BUFFER.size() >= MAX_LINES) BUFFER.removeFirst();
            BUFFER.addLast(line);
        }
    }

    private static void finish(String reason) {
        synchronized (LOCK) {
            if (!capturing) return;
            capturing = false;
            BUFFER.add("Stopped: " + new Date());
            BUFFER.add("Result: " + reason);
        }
        Process process = logcatProcess;
        logcatProcess = null;
        if (process != null) process.destroy();
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
        File output = new File(dir, "gemini-command-" + stamp + ".txt");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            synchronized (LOCK) {
                for (String line : BUFFER) {
                    stream.write(line.getBytes(StandardCharsets.UTF_8));
                    stream.write('\n');
                }
            }
            stream.flush();
            latestFile = output;
            Log.i(TAG, "Diagnostic log saved: " + output.getAbsolutePath());
        } catch (Exception error) {
            Log.e(TAG, "Cannot save diagnostic log", error);
        }
    }
}
