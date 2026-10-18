package com.carassistant.v10;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Root + on-device OCR media automation for a specific virtual display.
 * It does not require an Android AccessibilityService. OCR coordinates are
 * taken from a screenshot of the target display and taps are sent to that
 * same display with "input -d".
 */
public final class MediaOcrAutoPlayEngine {
    private static final String TAG = "CarAsstMediaOCR";
    private static final long MAX_RUN_MS = 30000L;
    private static final long RETRY_MS = 850L;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static MediaOcrAutoPlayEngine active;

    private final Context context;
    private final int displayId;
    private final String packageName;
    private final String query;
    private final File screenshot;
    private final TextRecognizer recognizer;
    private final long startedAt = android.os.SystemClock.uptimeMillis();
    private boolean stopped;
    private boolean resultTapped;
    private boolean playTapped;
    private int scans;

    private MediaOcrAutoPlayEngine(Context context, int displayId, String packageName, String query) {
        this.context = context.getApplicationContext();
        this.displayId = displayId;
        this.packageName = packageName;
        this.query = query == null ? "" : query.trim();
        File dir = this.context.getExternalFilesDir(null);
        if (dir == null) dir = this.context.getFilesDir();
        this.screenshot = new File(dir, "media_ocr_display_" + displayId + ".png");
        this.recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public static synchronized void start(Context context, int displayId, String packageName, String query) {
        stop();
        if (context == null || displayId < 0 || packageName == null || packageName.trim().isEmpty()
                || query == null || query.trim().isEmpty()) {
            Log.w(TAG, "Skip OCR automation: invalid display/package/query");
            return;
        }
        active = new MediaOcrAutoPlayEngine(context, displayId, packageName, query);
        active.schedule(1200L);
    }

    public static synchronized void stop() {
        if (active != null) {
            active.stopped = true;
            active.recognizer.close();
            active.screenshot.delete();
            active = null;
        }
    }

    private void schedule(long delay) {
        MAIN.postDelayed(this::captureAndRecognize, delay);
    }

    private void captureAndRecognize() {
        if (stopped) return;
        if (android.os.SystemClock.uptimeMillis() - startedAt > MAX_RUN_MS || scans++ > 32) {
            Log.w(TAG, "OCR automation timed out for " + packageName);
            finish();
            return;
        }
        WORKER.execute(() -> {
            try {
                RootShellSession shell = new RootShellSession();
                try {
                    String path = shellQuote(screenshot.getAbsolutePath());
                    shell.run(5, "screencap -d " + displayId + " -p " + path + " && chmod 644 " + path);
                } finally {
                    shell.destroy();
                }
                if (stopped) return;
                Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
                if (bitmap == null) {
                    schedule(RETRY_MS);
                    return;
                }
                InputImage image = InputImage.fromBitmap(bitmap, 0);
                recognizer.process(image)
                    .addOnSuccessListener(result -> {
                        try {
                            if (!stopped) handleText(result, bitmap.getWidth(), bitmap.getHeight());
                        } finally {
                            bitmap.recycle();
                            screenshot.delete();
                        }
                    })
                    .addOnFailureListener(error -> {
                        Log.w(TAG, "OCR failed on display " + displayId, error);
                        bitmap.recycle();
                        screenshot.delete();
                        schedule(RETRY_MS);
                    });
            } catch (Throwable error) {
                Log.w(TAG, "Could not capture display " + displayId, error);
                screenshot.delete();
                schedule(RETRY_MS);
            }
        });
    }

    private void handleText(Text result, int width, int height) {
        if (stopped) return;
        String all = normalize(result.getText());
        // A visible pause control is a safer positive signal than assuming that a tap worked.
        if (all.contains("pause") || all.contains("tam dung") || all.contains("tạm dừng")) {
            Log.i(TAG, "Playback state detected by OCR on display " + displayId);
            finish();
            return;
        }

        if (!resultTapped) {
            Text.TextBlock candidate = findQueryBlock(result);
            if (candidate != null && candidate.getBoundingBox() != null) {
                Rect bounds = candidate.getBoundingBox();
                tap(bounds.centerX(), bounds.centerY());
                resultTapped = true;
                Log.i(TAG, "Tapped OCR-matched result on display " + displayId + ": " + candidate.getText());
                schedule(1800L);
                return;
            }
        } else if (!playTapped) {
            Text.Line playLine = findPlayLine(result);
            if (playLine != null && playLine.getBoundingBox() != null) {
                Rect bounds = playLine.getBoundingBox();
                tap(bounds.centerX(), bounds.centerY());
                playTapped = true;
                Log.i(TAG, "Tapped OCR play control on display " + displayId);
                schedule(1500L);
                return;
            }
        }
        schedule(RETRY_MS);
    }

    private Text.TextBlock findQueryBlock(Text result) {
        String normalizedQuery = normalize(query);
        String[] tokens = normalizedQuery.split("\\s+");
        Text.TextBlock best = null;
        int bestScore = 0;
        for (Text.TextBlock block : result.getTextBlocks()) {
            String text = normalize(block.getText());
            if (text.isEmpty()) continue;
            int score = 0;
            if (text.contains(normalizedQuery)) score += 10;
            for (String token : tokens) {
                if (token.length() >= 3 && text.contains(token)) score += 2;
            }
            // Avoid tapping a random generic control for one-word queries.
            int required = tokens.length > 1 ? 2 : 10;
            if (score >= required && score > bestScore) {
                best = block;
                bestScore = score;
            }
        }
        return best;
    }

    private Text.Line findPlayLine(Text result) {
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String text = normalize(line.getText());
                if (text.equals("play") || text.equals("phat") || text.equals("phat bai")
                        || text.equals("phat nhac") || text.equals("resume")
                        || text.equals("tiep tuc")) return line;
            }
        }
        return null;
    }

    private void tap(int x, int y) {
        WORKER.execute(() -> {
            RootShellSession shell = new RootShellSession();
            try {
                shell.run(5, "/system/bin/input -d " + displayId + " tap " + Math.max(0, x) + " " + Math.max(0, y));
            } catch (Throwable error) {
                Log.w(TAG, "Root tap failed on display " + displayId, error);
            } finally {
                shell.destroy();
            }
        });
    }

    private void finish() {
        stopped = true;
        recognizer.close();
        screenshot.delete();
        synchronized (MediaOcrAutoPlayEngine.class) {
            if (active == this) active = null;
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
    }
}
