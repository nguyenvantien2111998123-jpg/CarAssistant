package com.carassistant.v10;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Collects a small, filtered snapshot of system logcat after the user reproduces
 * an Android Auto issue. Root is required to read logs from Gearhead/LSPosed.
 * Only selected diagnostic lines are written to the shareable file.
 */
public final class DiagnosticLogCollector {
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();
    private static final int MAX_MATCHED_LINES = 8000;
    private static final String[] TOKENS = {
            "LAYOUTDIAG", "LayoutDiag", "AACastDock", "AACast Dock",
            "GearheadDockPatcher", "DiagnosticLayoutInflaterHook", "MEDIACLICKDIAG",
            "MediaPlayerFragment", "TappableRegion", "MetadataView.performClick"
    };

    private DiagnosticLogCollector() { }

    public static void collectAndShare(Activity activity) {
        Toast.makeText(activity, "Đang thu log chẩn đoán… hãy chấp nhận yêu cầu root nếu xuất hiện.",
                Toast.LENGTH_LONG).show();
        EXECUTOR.execute(() -> {
            File output = null;
            String error = null;
            try {
                output = collect(activity);
            } catch (Throwable failure) {
                error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            }
            final File logFile = output;
            final String failureMessage = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (failureMessage != null) {
                    Toast.makeText(activity,
                            "Không thu được log. Kiểm tra quyền root/su. " + failureMessage,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                share(activity, logFile);
            });
        });
    }

    private static File collect(Activity activity) throws Exception {
        File directory = new File(activity.getFilesDir(), "diagnostics");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Không tạo được thư mục log");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        File output = new File(directory, "CarAsistant-AndroidAuto-" + stamp + ".txt");

        Process process = new ProcessBuilder("su", "-c", "logcat -d -v threadtime")
                .redirectErrorStream(true)
                .start();
        int matched = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                     process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     new FileOutputStream(output), StandardCharsets.UTF_8))) {
            writer.write("CarAsistant Android Auto diagnostic log\n");
            writer.write("Collected: " + new Date() + "\n");
            writer.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "; Android " + Build.VERSION.RELEASE
                    + " (SDK " + Build.VERSION.SDK_INT + ")\n");
            writer.write("Filter: LAYOUTDIAG, LayoutDiag, AACastDock, AACast Dock, GearheadDockPatcher, DiagnosticLayoutInflaterHook\n\n");

            String line;
            while ((line = reader.readLine()) != null) {
                if (!matches(line)) continue;
                writer.write(line);
                writer.newLine();
                matched++;
                if (matched >= MAX_MATCHED_LINES) {
                    writer.write("\n[Đã dừng ở giới hạn " + MAX_MATCHED_LINES + " dòng khớp.]\n");
                    break;
                }
            }
            writer.write("\nMatched lines: " + matched + "\n");
        } finally {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("logcat quá thời gian");
            }
        }

        if (output.length() == 0) throw new IllegalStateException("Tệp log rỗng");
        return output;
    }

    private static boolean matches(String line) {
        for (String token : TOKENS) {
            if (line.contains(token)) return true;
        }
        // Include relevant LSPosed/Xposed startup failures without dumping unrelated logs.
        String lower = line.toLowerCase(Locale.ROOT);
        return (lower.contains("lsposed") || lower.contains("xposed"))
                && (lower.contains("gearhead") || lower.contains("carassistant")
                || lower.contains("com.carassistant.v10"));
    }

    private static void share(Activity activity, File file) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "CarAsistant Android Auto diagnostic log");
            send.putExtra(Intent.EXTRA_TEXT,
                    "Log chẩn đoán CarAsistant. Gửi tệp đính kèm này trong cuộc trò chuyện hỗ trợ.");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(send, "Chia sẻ log chẩn đoán"));
        } catch (Throwable failure) {
            Toast.makeText(activity, "Đã lưu log nhưng không mở được chia sẻ: "
                    + failure.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
