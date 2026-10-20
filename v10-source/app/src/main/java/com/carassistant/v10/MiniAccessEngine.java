package com.carassistant.v10;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;

import com.carassistant.v10.voice.VoiceCommandParser;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.Locale;

/**
 * CarAsistant-owned visual fallback for media selection on the projected display.
 *
 * The existing AccessibilityService gets a short first chance. If it has not
 * selected a result, this engine OCRs the TextureView that renders the virtual
 * display and injects a tap through the same display-specific root input path
 * used by projection touch. It never captures or taps the phone's main display.
 */
public final class MiniAccessEngine {
    private static final String TAG = "CarAsstMiniAccess";
    private static final String PREFS = "media_autoplay";
    private static final long ACCESSIBILITY_GRACE_MS = 1800L;
    private static final long RETRY_MS = 750L;
    private static final long MAX_PENDING_MS = 30000L;
    private static final int MAX_ATTEMPTS = 28;

    private final Context context;
    private final TextureView projectedView;
    private final V10ProjectionSession projectionSession;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    private boolean running;
    private boolean inFlight;
    private boolean ownsPendingRequest;
    private int attempts;
    private String expectedPackage = "";
    private String expectedQuery = "";

    public MiniAccessEngine(Context context, TextureView projectedView,
                            V10ProjectionSession projectionSession) {
        this.context = context.getApplicationContext();
        this.projectedView = projectedView;
        this.projectionSession = projectionSession;
    }

    /** Begin fallback after giving the system accessibility path time to work. */
    public void start() {
        stop();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        expectedPackage = prefs.getString("target_package", "");
        expectedQuery = prefs.getString("query", "");
        if (expectedPackage.isEmpty() || expectedQuery.trim().isEmpty()
                || !"search".equals(prefs.getString("phase", ""))) {
            return;
        }
        running = true;
        attempts = 0;
        handler.postDelayed(scanTask, ACCESSIBILITY_GRACE_MS);
    }

    public void stop() {
        running = false;
        inFlight = false;
        handler.removeCallbacks(scanTask);
        releaseOwnership();
    }

    public void close() {
        stop();
        recognizer.close();
    }

    private final Runnable scanTask = new Runnable() {
        @Override public void run() {
            if (!running || inFlight) return;

            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String packageName = prefs.getString("target_package", "");
            String query = prefs.getString("query", "");
            String phase = prefs.getString("phase", "");
            long startedAt = prefs.getLong("started_at", 0L);
            int expectedDisplayId = prefs.getInt("target_display_id", -1);

            // If the accessibility path already selected a result, let its Play phase finish.
            if (!expectedPackage.equals(packageName) || !expectedQuery.equals(query)
                    || !"search".equals(phase)) {
                stop();
                return;
            }
            if (startedAt <= 0L || System.currentTimeMillis() - startedAt > MAX_PENDING_MS
                    || attempts >= MAX_ATTEMPTS) {
                Log.w(TAG, "Visual selection stopped: timeout/no matching result");
                stop();
                return;
            }
            if (projectionSession == null || !projectionSession.isActive()
                    || projectionSession.getDisplayId() != expectedDisplayId
                    || projectedView == null || !projectedView.isAvailable()
                    || projectedView.getWidth() < 1 || projectedView.getHeight() < 1) {
                scheduleRetry();
                return;
            }

            // Claim only after the system AccessibilityService has had its grace period.
            prefs.edit().putBoolean("mini_engine_active", true).apply();
            ownsPendingRequest = true;
            Bitmap bitmap;
            try {
                bitmap = projectedView.getBitmap();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not capture projected TextureView", error);
                scheduleRetry();
                return;
            }
            if (bitmap == null) {
                scheduleRetry();
                return;
            }

            attempts++;
            inFlight = true;
            Task<Text> task;
            try {
                task = recognizer.process(InputImage.fromBitmap(bitmap, 0));
            } catch (RuntimeException error) {
                inFlight = false;
                bitmap.recycle();
                Log.w(TAG, "OCR could not process projected frame", error);
                scheduleRetry();
                return;
            }
            task.addOnSuccessListener(text -> {
                inFlight = false;
                if (!running) return;
                Candidate candidate = findCandidate(text, expectedQuery,
                        bitmap.getWidth(), bitmap.getHeight());
                if (candidate != null && requestStillPending(expectedDisplayId)) {
                    SharedPreferences current = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    // Keep the request alive after selecting a search result. The
                    // accessibility service needs the same package/display context to
                    // find and click Play on the detail/player screen.
                    current.edit().putString("phase", "play")
                            .putBoolean("mini_engine_active", false).apply();
                    ownsPendingRequest = false;
                    Log.i(TAG, "OCR selected projected search result; requestId="
                            + current.getLong("started_at", 0L)
                            + " package=" + expectedPackage
                            + " display=" + expectedDisplayId
                            + " x=" + candidate.x + " y=" + candidate.y
                            + "; handing off to Play phase");
                    boolean tapped = projectionSession.tap(candidate.x, candidate.y);
                    Log.i(TAG, "OCR result tap dispatched=" + tapped
                            + " package=" + expectedPackage + " display=" + expectedDisplayId);
                    running = false;
                    handler.removeCallbacks(scanTask);
                } else {
                    scheduleRetry();
                }
            }).addOnFailureListener(error -> {
                inFlight = false;
                if (running) {
                    Log.w(TAG, "Projected OCR attempt failed", error);
                    scheduleRetry();
                }
            }).addOnCompleteListener(done -> {
                if (!bitmap.isRecycled()) bitmap.recycle();
            });
        }
    };

    private boolean requestStillPending(int expectedDisplayId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return expectedPackage.equals(prefs.getString("target_package", ""))
                && expectedQuery.equals(prefs.getString("query", ""))
                && "search".equals(prefs.getString("phase", ""))
                && expectedDisplayId == prefs.getInt("target_display_id", -1)
                && projectionSession != null && projectionSession.isActive()
                && projectionSession.getDisplayId() == expectedDisplayId;
    }

    private void scheduleRetry() {
        if (!running) return;
        handler.removeCallbacks(scanTask);
        handler.postDelayed(scanTask, RETRY_MS);
    }

    private void releaseOwnership() {
        if (!ownsPendingRequest) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("mini_engine_active", false).apply();
        ownsPendingRequest = false;
    }

    private static Candidate findCandidate(Text recognized, String query,
                                           int imageWidth, int imageHeight) {
        String normalizedQuery = VoiceCommandParser.normalize(query);
        String[] tokens = normalizedQuery.split("\\s+");
        int usefulTokens = 0;
        for (String token : tokens) if (token.length() >= 2) usefulTokens++;
        int requiredMatches = usefulTokens <= 1 ? 1 : 2;
        Candidate best = null;

        for (Text.TextBlock block : recognized.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect bounds = line.getBoundingBox();
                if (bounds == null || bounds.centerY() < imageHeight * 0.17f) continue;
                String lineText = VoiceCommandParser.normalize(line.getText());
                if (lineText.isEmpty()) continue;
                int matches = 0;
                for (String token : tokens) {
                    if (token.length() >= 2 && lineText.contains(token)) matches++;
                }
                if (matches < requiredMatches) continue;

                // Prefer more query-token matches, then the first plausible result below
                // the search toolbar. Tapping the recognized title is safer than guessing
                // the center of a whole card or clicking generic text such as "Play".
                int score = matches * 1000 - bounds.centerY();
                if (best == null || score > best.score) {
                    best = new Candidate(bounds.centerX(), bounds.centerY(), score, line.getText());
                }
            }
        }
        if (best != null) {
            Log.i(TAG, "OCR candidate text='" + best.text + "' score=" + best.score
                    + " frame=" + imageWidth + "x" + imageHeight);
        }
        return best;
    }

    private static final class Candidate {
        final float x;
        final float y;
        final int score;
        final String text;

        Candidate(float x, float y, int score, String text) {
            this.x = x;
            this.y = y;
            this.score = score;
            this.text = text;
        }
    }
}
