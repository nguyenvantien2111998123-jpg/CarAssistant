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

/** Push-to-talk lifecycle. A second button press cancels the active voice session. */
public final class VoiceAssistantController {
    public interface Listener {
        void onStateChanged(boolean listening, String status, String transcript, boolean showTranscript);
    }

    private static final long COMPLETE_SILENCE_MS = 3000L;
    private static final long NO_SPEECH_TIMEOUT_MS = 3000L;
    private static final long FINAL_RESULT_TIMEOUT_MS = 1200L;

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
    private boolean endBeepPlayed;
    private boolean commandHandled;
    private String pendingSpeech;
    private String lastTranscript = "";

    private final Runnable noSpeechWatchdog = () -> {
        if (destroyed || !sessionActive || !listening || speechStarted || commandHandled) return;
        if (!lastTranscript.trim().isEmpty()) return;
        cancelRecognizer();
        sessionActive = false;
        listening = false;
        commandHandled = true;
        cancelSilenceWatchdog();
        mainHandler.removeCallbacks(finalResultFallback);
        lastTranscript = "";
        notifyState(false, "Đã hủy lệnh voice sau 3 giây không nói.", "", false);
    };

    private final Runnable silenceWatchdog = () -> {
        if (destroyed || !sessionActive || !listening || commandHandled) return;
        String transcript = lastTranscript.trim();
        if (transcript.isEmpty()) return;
        // If recognition stalls after partial text, route the command once.
        sessionActive = false;
        listening = false;
        commandHandled = true;
        cancelNoSpeechWatchdog();
        mainHandler.removeCallbacks(finalResultFallback);
        cancelRecognizer();
        notifyState(false, "Đang xử lý lệnh…", "", false);
        dispatchTranscriptOnce(transcript);
    };

    private final Runnable finalResultFallback = () -> {
        if (destroyed || !sessionActive || commandHandled) return;
        String transcript = lastTranscript.trim();
        if (transcript.isEmpty()) {
            cancelListeningOnMain("Đã hủy lệnh voice do không nhận được câu nói.");
            return;
        }
        sessionActive = false;
        listening = false;
        commandHandled = true;
        cancelNoSpeechWatchdog();
        cancelSilenceWatchdog();
        cancelRecognizer();
        notifyState(false, "Đang xử lý lệnh…", "", false);
        dispatchTranscriptOnce(transcript);
    };

    private void scheduleNoSpeechWatchdog() {
        mainHandler.removeCallbacks(noSpeechWatchdog);
        mainHandler.postDelayed(noSpeechWatchdog, NO_SPEECH_TIMEOUT_MS);
    }
    private void cancelNoSpeechWatchdog() { mainHandler.removeCallbacks(noSpeechWatchdog); }
    private void scheduleSilenceWatchdog() {
        mainHandler.removeCallbacks(silenceWatchdog);
        mainHandler.postDelayed(silenceWatchdog, COMPLETE_SILENCE_MS);
    }
    private void cancelSilenceWatchdog() { mainHandler.removeCallbacks(silenceWatchdog); }
    private void scheduleFinalResultFallback() {
        mainHandler.removeCallbacks(finalResultFallback);
        mainHandler.postDelayed(finalResultFallback, FINAL_RESULT_TIMEOUT_MS);
    }

    public VoiceAssistantController(Context context, Listener listener, VoiceCommandRouter router) {
        if (context == null || router == null) throw new IllegalArgumentException("context and router are required");
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

    public void startListening() { runOnMain(this::startListeningOnMain); }

    /** First press starts listening; second press cancels without dispatching the partial command. */
    public void toggleListening() {
        runOnMain(() -> {
            if (sessionActive) cancelListeningOnMain("Đã hủy lệnh voice.");
            else startListeningOnMain();
        });
    }

    private void startListeningOnMain() {
        if (destroyed || sessionActive) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
                        if (!destroyed && sessionActive && !commandHandled)
                            notifyState(true, "Đang nghe…", lastTranscript, true);
                    }
                    @Override public void onBeginningOfSpeech() {
                        if (destroyed || !sessionActive || commandHandled) return;
                        speechStarted = true;
                        cancelNoSpeechWatchdog();
                        scheduleSilenceWatchdog();
                        notifyState(true, "Đang nghe…", lastTranscript, true);
                    }
                    @Override public void onRmsChanged(float rmsdB) { }
                    @Override public void onBufferReceived(byte[] buffer) { }
                    @Override public void onEndOfSpeech() {
                        if (destroyed || !sessionActive || commandHandled) return;
                        speechStarted = true;
                        listening = false;
                        cancelNoSpeechWatchdog();
                        cancelSilenceWatchdog();
                        notifyState(false, "Đang xử lý lệnh…", "", false);
                        scheduleFinalResultFallback();
                    }
                    @Override public void onError(int error) {
                        if (destroyed || !sessionActive || commandHandled) return;
                        boolean noSpeechError = error == SpeechRecognizer.ERROR_NO_MATCH
                                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                        String transcript = lastTranscript.trim();
                        // Services can signal no-match early. Keep the session until the 3-second timer.
                        if (transcript.isEmpty() && !speechStarted && noSpeechError) return;
                        cancelNoSpeechWatchdog();
                        cancelSilenceWatchdog();
                        mainHandler.removeCallbacks(finalResultFallback);
                        if (!transcript.isEmpty()) {
                            sessionActive = false;
                            listening = false;
                            commandHandled = true;
                            cancelRecognizer();
                            notifyState(false, "Đang xử lý lệnh…", "", false);
                            dispatchTranscriptOnce(transcript);
                            return;
                        }
                        sessionActive = false;
                        listening = false;
                        commandHandled = true;
                        cancelRecognizer();
                        String status = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                                ? "Chưa được cấp quyền micro." : "Không thể nhận dạng giọng nói lúc này.";
                        notifyState(false, status, "", false);
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                            speak("CarAssistant chưa được cấp quyền micro.");
                        else if (!noSpeechError)
                            speak("Không thể nhận dạng giọng nói lúc này. Bạn hãy thử lại.");
                    }
                    @Override public void onResults(Bundle results) {
                        if (destroyed || !sessionActive || commandHandled) return;
                        ArrayList<String> matches = results == null ? null
                                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        String transcript = matches == null || matches.isEmpty()
                                ? lastTranscript : matches.get(0).trim();
                        cancelNoSpeechWatchdog();
                        cancelSilenceWatchdog();
                        mainHandler.removeCallbacks(finalResultFallback);
                        sessionActive = false;
                        listening = false;
                        commandHandled = true;
                        notifyState(false, "Đang xử lý lệnh…", "", false);
                        dispatchTranscriptOnce(transcript);
                    }
                    @Override public void onPartialResults(Bundle partialResults) {
                        if (destroyed || !sessionActive || !listening || commandHandled) return;
                        ArrayList<String> matches = partialResults == null ? null
                                : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches == null || matches.isEmpty()) return;
                        String partial = matches.get(0);
                        if (partial == null || partial.trim().isEmpty()) return;
                        String updated = partial.trim();
                        speechStarted = true;
                        cancelNoSpeechWatchdog();
                        if (!updated.equals(lastTranscript)) {
                            lastTranscript = updated;
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
            endBeepPlayed = false;
            commandHandled = false;
            sessionActive = true;
            listening = true;
            cancelNoSpeechWatchdog();
            cancelSilenceWatchdog();
            mainHandler.removeCallbacks(finalResultFallback);

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS);

            notifyState(true, "Đang nghe…", "", true);
            playBeep();
            recognizer.startListening(intent);
            scheduleNoSpeechWatchdog();
        } catch (RuntimeException exception) {
            sessionActive = false;
            listening = false;
            commandHandled = true;
            cancelNoSpeechWatchdog();
            cancelSilenceWatchdog();
            mainHandler.removeCallbacks(finalResultFallback);
            notifyState(false, "Không khởi động được micro.", "", false);
            speak("Không khởi động được nhận dạng giọng nói. Bạn hãy thử lại.");
        }
    }

    public void cancelListening() { runOnMain(() -> cancelListeningOnMain("Đã dừng nghe.")); }

    private void cancelListeningOnMain(String status) {
        boolean wasActive = sessionActive || listening;
        cancelNoSpeechWatchdog();
        cancelSilenceWatchdog();
        mainHandler.removeCallbacks(finalResultFallback);
        if (wasActive) cancelRecognizer();
        sessionActive = false;
        listening = false;
        speechStarted = false;
        commandHandled = true;
        lastTranscript = "";
        notifyState(false, status, "", false);
    }

    private void cancelRecognizer() {
        if (recognizer == null) return;
        try { recognizer.cancel(); } catch (RuntimeException ignored) { }
    }

    private void dispatchTranscriptOnce(String transcript) {
        if (destroyed) return;
        String clean = transcript == null ? "" : transcript.trim();
        if (clean.isEmpty()) {
            lastTranscript = "";
            notifyState(false, "Chưa nhận được câu nói.", "", false);
            return;
        }
        sessionActive = false;
        listening = false;
        commandHandled = true;
        cancelNoSpeechWatchdog();
        cancelSilenceWatchdog();
        mainHandler.removeCallbacks(finalResultFallback);
        VoiceCommandRouter.Result result = router.route(clean);
        notifyState(false, result.understood ? "Đã nhận: " + clean : "Chưa hiểu: " + clean, "", false);
        speak(result.response);
        lastTranscript = "";
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
                try { recognizer.cancel(); recognizer.destroy(); } catch (RuntimeException ignored) { }
                recognizer = null;
            }
            if (textToSpeech != null) {
                try { textToSpeech.stop(); textToSpeech.shutdown(); } catch (RuntimeException ignored) { }
                textToSpeech = null;
            }
            if (toneGenerator != null) {
                try { toneGenerator.release(); } catch (RuntimeException ignored) { }
                toneGenerator = null;
            }
            ttsReady = false;
        });
    }

    private void playBeep() {
        if (destroyed) return;
        try {
            if (toneGenerator == null) toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 75);
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        } catch (RuntimeException ignored) { }
    }
    private void speak(String message) {
        if (message == null || message.trim().isEmpty()) return;
        runOnMain(() -> {
            if (destroyed) return;
            if (textToSpeech == null || !ttsReady) { pendingSpeech = message; return; }
            try { textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString()); }
            catch (RuntimeException ignored) { }
        });
    }
    private void notifyState(boolean isListening, String status, String transcript, boolean showTranscript) {
        if (listener == null || destroyed) return;
        runOnMain(() -> {
            if (!destroyed && listener != null)
                listener.onStateChanged(isListening, status, transcript == null ? "" : transcript, showTranscript);
        });
    }
    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }
}
