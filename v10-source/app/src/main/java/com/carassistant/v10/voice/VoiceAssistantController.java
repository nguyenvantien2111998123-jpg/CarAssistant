package com.carassistant.v10.voice;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

/**
 * Push-to-talk speech lifecycle for CarAssistant V1.
 * Keeps recognition feedback responsive and does not touch projection surfaces.
 */
public final class VoiceAssistantController {
    public interface Listener {
        void onStateChanged(boolean listening, String status,
                            String transcript, boolean showTranscript);
    }

    private static final long COMPLETE_SILENCE_MS = 3000L;
    private static final long NO_SPEECH_TIMEOUT_MS = 2000L;

    private final Context context;
    private final Listener listener;
    private final VoiceCommandRouter router;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private TextToSpeech textToSpeech;
    private ToneGenerator toneGenerator;
    private boolean ttsReady;
    private boolean listening;
    private boolean sessionActive;
    private boolean speechStarted;
    private boolean destroyed;
    private boolean commandHandled;
    private boolean dispatchStarted;
    private String pendingSpeech;
    private String lastTranscript = "";

    private final Runnable noSpeechWatchdog = () -> {
        if (destroyed || !sessionActive || !listening || speechStarted) return;
        String transcript = lastTranscript == null ? "" : lastTranscript.trim();
        if (!transcript.isEmpty()) return;

        // No speech arrived within two seconds of the start beep: cancel without dispatching a command.
        sessionActive = false;
        listening = false;
        commandHandled = true;
        cancelSilenceWatchdog();
        cancelFinalResultFallback();
        try {
            if (recognizer != null) recognizer.cancel();
        } catch (RuntimeException ignored) { }
        notifyState(false, "Không nghe thấy giọng nói. Đã hủy.", "", false);
    };

    private final Runnable silenceWatchdog = () -> {
        if (destroyed || !listening || commandHandled) return;
        String transcript = lastTranscript == null ? "" : lastTranscript.trim();
        if (transcript.isEmpty()) return;

        // Some Android recognition services delay onEndOfSpeech/onResults indefinitely.
        // End capture ourselves after 3 seconds without speech and use the latest partial text.
        sessionActive = false;
        listening = false;
        commandHandled = true;
        cancelNoSpeechWatchdog();
        try {
            if (recognizer != null) recognizer.cancel();
        } catch (RuntimeException ignored) { }
        notifyState(false, "Đang xử lý lệnh…", "", false);
        dispatchTranscriptOnce(transcript);
    };

    private void scheduleSilenceWatchdog() {
        mainHandler.removeCallbacks(silenceWatchdog);
        mainHandler.postDelayed(silenceWatchdog, COMPLETE_SILENCE_MS);
    }

    private void cancelSilenceWatchdog() {
        mainHandler.removeCallbacks(silenceWatchdog);
    }

    private void scheduleNoSpeechWatchdog() {
        mainHandler.removeCallbacks(noSpeechWatchdog);
        mainHandler.postDelayed(noSpeechWatchdog, NO_SPEECH_TIMEOUT_MS);
    }

    private void cancelNoSpeechWatchdog() {
        mainHandler.removeCallbacks(noSpeechWatchdog);
    }

    private final Runnable finalResultFallback = () -> {
        if (destroyed || commandHandled) return;
        String transcript = lastTranscript == null ? "" : lastTranscript.trim();
        if (transcript.isEmpty()) return;

        // Bound the wait for a final recognition callback after end-of-speech.
        commandHandled = true;
        sessionActive = false;
        listening = false;
        cancelNoSpeechWatchdog();
        cancelSilenceWatchdog();
        try {
            if (recognizer != null) recognizer.cancel();
        } catch (RuntimeException ignored) { }
        }
        notifyState(false, "Đang xử lý lệnh…", "", false);
        dispatchTranscriptOnce(transcript);
    };

    private void cancelFinalResultFallback() {
        mainHandler.removeCallbacks(finalResultFallback);
    }

    private void scheduleFinalResultFallback() {
        mainHandler.removeCallbacks(finalResultFallback);
        mainHandler.postDelayed(finalResultFallback, 1200L);
    }

    private void dispatchTranscriptOnce(String transcript) {
        if (destroyed || dispatchStarted) return;
        dispatchStarted = true;
        dispatchTranscript(transcript);
    }

    private void dispatchTranscript(String transcript) {
        sessionActive = false;
        listening = false;
        cancelNoSpeechWatchdog();
        cancelSilenceWatchdog();
        if (destroyed || transcript == null || transcript.trim().isEmpty()) {
            notifyState(false, "Chưa nhận được câu nói.", "", false);
            speak("Tôi chưa nghe rõ. Bạn thử lại nhé.");
            return;
        }
        VoiceCommandRouter.Result result = router.route(transcript.trim());
        notifyState(false, result.understood
                ? "Đã nhận: " + transcript.trim()
                : "Chưa hiểu: " + transcript.trim(), "", false);
        speak(result.response);
        lastTranscript = "";
    }

    public VoiceAssistantController(Context context, Listener listener, VoiceCommandRouter router) {
        if (context == null || router == null) {
            throw new IllegalArgumentException("context and router are required");
        }
        this.context = context;
        this.listener = listener;
        this.router = router;

        textToSpeech = new TextToSpeech(context.getApplicationContext(), status ->
                mainHandler.post(() -> {
                    if (destroyed || textToSpeech == null) return;
                    if (status == TextToSpeech.SUCCESS) {
                        int languageStatus = textToSpeech.setLanguage(new Locale("vi", "VN"));
                        ttsReady = languageStatus != TextToSpeech.LANG_MISSING_DATA
                                && languageStatus != TextToSpeech.LANG_NOT_SUPPORTED;
                        if (ttsReady) {
                            textToSpeech.setSpeechRate(1.0f);
                            textToSpeech.setPitch(1.0f);
                            if (pendingSpeech != null) {
                                String pending = pendingSpeech;
                                pendingSpeech = null;
                                speak(pending);
                            }
                        }
                    }
                }));
    }

    public void startListening() {
        runOnMain(this::startListeningOnMain);
    }

    private void startListeningOnMain() {
        if (destroyed || sessionActive) return;

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            notifyState(false, "Cần cấp quyền micro.", "", false);
            speak("Bạn hãy cấp quyền micro cho CarAssistant trước.");
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            notifyState(false, "Không có dịch vụ nhận dạng giọng nói.", "", false);
            speak("Điện thoại chưa có dịch vụ nhận dạng giọng nói. Hãy kiểm tra dịch vụ Google và ngôn ngữ tiếng Việt.");
            return;
        }

        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle params) {
                        notifyState(true, "Đang nghe…", lastTranscript, true);
                    }

                    @Override public void onBeginningOfSpeech() {
                        speechStarted = true;
                        cancelNoSpeechWatchdog();
                        scheduleSilenceWatchdog();
                        notifyState(true, "Đang nghe…", lastTranscript, true);
                    }

                    @Override public void onRmsChanged(float rmsdB) {
                        // RMS may stay high from background noise; partial transcript changes
                        // are used to detect continued speech instead.
                    }

                    @Override public void onBufferReceived(byte[] buffer) { }

                    @Override public void onEndOfSpeech() {
                        if (destroyed || commandHandled) return;
                        speechStarted = true;
                        cancelNoSpeechWatchdog();
                        cancelSilenceWatchdog();
                        listening = false;
                        notifyState(false, "Đang xử lý lệnh…", "", false);
                        // Give the service a short opportunity to deliver final text; the
                        // watchdog below is the fallback if it never does.
                        scheduleFinalResultFallback();
                    }

                    @Override public void onError(int error) {
                        if (destroyed || commandHandled) return;
                        cancelSilenceWatchdog();
                        mainHandler.removeCallbacks(finalResultFallback);

                        String transcript = lastTranscript == null ? "" : lastTranscript.trim();
                        boolean noSpeechError = error == SpeechRecognizer.ERROR_NO_MATCH
                                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;

                        // Some recognition services report no-match before the requested two-second
                        // no-speech timeout. Keep the session alive until that timer cancels it.
                        if (transcript.isEmpty() && !speechStarted && noSpeechError) {
                            return;
                        }

                        cancelNoSpeechWatchdog();
                        sessionActive = false;

                        // If the service errors after showing a usable partial transcript,
                        // prefer executing that command over announcing a false retry.
                        if (!transcript.isEmpty()) {
                            listening = false;
                            commandHandled = true;
                            notifyState(false, "Đang xử lý lệnh…", "", false);
                            dispatchTranscriptOnce(transcript);
                            return;
                        }

                        listening = false;
                        commandHandled = true;
                        String status = "Không nhận được câu lệnh.";
                        if (noSpeechError) {
                            status = "Chưa nghe rõ câu lệnh.";
                            notifyState(false, status, "", false);
                            speak("Tôi chưa nghe rõ. Bạn thử lại nhé.");
                        } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            notifyState(false, "Chưa được cấp quyền micro.", "", false);
                            speak("CarAssistant chưa được cấp quyền micro.");
                        } else {
                            notifyState(false, status, "", false);
                            speak("Không thể nhận dạng giọng nói lúc này. Bạn hãy thử lại.");
                        }
                    }

                    @Override public void onResults(Bundle results) {
                        if (destroyed || commandHandled) return;
                        cancelNoSpeechWatchdog();
                        cancelSilenceWatchdog();
                        sessionActive = false;
                        listening = false;
                        commandHandled = true;

                        ArrayList<String> matches = results == null
                                ? null
                                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        String transcript = matches == null || matches.isEmpty()
                                ? lastTranscript
                                : matches.get(0).trim();

                        notifyState(false, "Đang xử lý lệnh…", "", false);
                        dispatchTranscriptOnce(transcript);
                    }

                    @Override public void onPartialResults(Bundle partialResults) {
                        if (destroyed || !listening || commandHandled) return;
                        speechStarted = true;
                        cancelNoSpeechWatchdog();
                        ArrayList<String> matches = partialResults == null
                                ? null
                                : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches == null || matches.isEmpty()) return;
                        String partial = matches.get(0);
                        if (partial == null || partial.trim().isEmpty()) return;
                        String updatedTranscript = partial.trim();
                        // Extend the timeout only when recognized text actually changes.
                        // Duplicate partial callbacks must not delay command execution.
                        if (!updatedTranscript.equals(lastTranscript)) {
                            lastTranscript = updatedTranscript;
                            scheduleSilenceWatchdog();
                        }
                        notifyState(true, "Đang nghe…", lastTranscript, true);
                    }

                    @Override public void onEvent(int eventType, Bundle params) { }
                });
            }

            if (textToSpeech != null) textToSpeech.stop();
            lastTranscript = "";
            speechStarted = false;
            commandHandled = false;
            dispatchStarted = false;
            cancelSilenceWatchdog();
            mainHandler.removeCallbacks(finalResultFallback);

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    COMPLETE_SILENCE_MS);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    COMPLETE_SILENCE_MS);

            sessionActive = true;
            speechStarted = false;
            listening = true;
            notifyState(true, "Đang nghe…", "", true);
            playBeep();
            recognizer.startListening(intent);
            scheduleNoSpeechWatchdog();
        } catch (RuntimeException exception) {
            sessionActive = false;
            listening = false;
            cancelNoSpeechWatchdog();
            cancelSilenceWatchdog();
            notifyState(false, "Không khởi động được micro.", "", false);
            speak("Không khởi động được nhận dạng giọng nói. Bạn hãy thử lại.");
        }
    }

    public void toggleListening() {
        runOnMain(() -> {
            if (sessionActive) cancelListeningOnMain();
            else startListeningOnMain();
        });
    }

    public void cancelListening() {
        runOnMain(this::cancelListeningOnMain);
    }

    private void cancelListeningOnMain() {
        boolean wasActive = sessionActive || listening;
        if (recognizer != null && wasActive) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) { }
        }
        sessionActive = false;
        listening = false;
        speechStarted = false;
        commandHandled = true;
        cancelNoSpeechWatchdog();
        cancelSilenceWatchdog();
        mainHandler.removeCallbacks(finalResultFallback);
        lastTranscript = "";
        notifyState(false, wasActive ? "Đã hủy lệnh voice." : "Đã dừng nghe.", "", false);
    }

    public void destroy() {
        runOnMain(() -> {
            if (destroyed) return;
            destroyed = true;
            sessionActive = false;
            listening = false;
            cancelNoSpeechWatchdog();
            cancelSilenceWatchdog();
            mainHandler.removeCallbacks(finalResultFallback);

            if (recognizer != null) {
                try {
                    recognizer.cancel();
                    recognizer.destroy();
                } catch (RuntimeException ignored) { }
                recognizer = null;
            }

            if (textToSpeech != null) {
                try {
                    textToSpeech.stop();
                    textToSpeech.shutdown();
                } catch (RuntimeException ignored) { }
                textToSpeech = null;
            }
            if (toneGenerator != null) {
                try {
                    toneGenerator.release();
                } catch (RuntimeException ignored) { }
                toneGenerator = null;
            }
            ttsReady = false;
        });
    }

    private void playBeep() {
        if (destroyed) return;
        try {
            if (toneGenerator == null) {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 75);
            }
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        } catch (RuntimeException ignored) { }
    }

    private void speak(String message) {
        if (message == null || message.trim().isEmpty()) return;
        runOnMain(() -> {
            if (destroyed) return;
            if (textToSpeech == null || !ttsReady) {
                pendingSpeech = message;
                return;
            }
            try {
                textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null,
                        UUID.randomUUID().toString());
            } catch (RuntimeException ignored) { }
        });
    }

    private void notifyState(boolean isListening, String status,
                             String transcript, boolean showTranscript) {
        if (listener == null || destroyed) return;
        runOnMain(() -> {
            if (!destroyed && listener != null) {
                listener.onStateChanged(isListening, status,
                        transcript == null ? "" : transcript, showTranscript);
            }
        });
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }
}
