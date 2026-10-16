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

    private final Context context;
    private final Listener listener;
    private final VoiceCommandRouter router;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private TextToSpeech textToSpeech;
    private ToneGenerator toneGenerator;
    private boolean ttsReady;
    private boolean listening;
    private boolean destroyed;
    private boolean endBeepPlayed;
    private String pendingSpeech;
    private String lastTranscript = "";

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
        if (destroyed || listening) return;

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
                        notifyState(true, "Đang nghe…", lastTranscript, true);
                    }

                    @Override public void onRmsChanged(float rmsdB) { }

                    @Override public void onBufferReceived(byte[] buffer) { }

                    @Override public void onEndOfSpeech() {
                        // Silence timeout is requested as 3 seconds below. The second beep
                        // marks the end of capture; command routing waits only for final text.
                        listening = false;
                        playBeep();
                        endBeepPlayed = true;
                        notifyState(false, "Đang xử lý lệnh…", lastTranscript, false);
                    }

                    @Override public void onError(int error) {
                        if (destroyed) return;
                        if (!endBeepPlayed) playBeep();
                        listening = false;
                        String status = "Không nhận được câu lệnh.";
                        if (error == SpeechRecognizer.ERROR_NO_MATCH
                                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
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
                        if (destroyed) return;
                        listening = false;
                        if (!endBeepPlayed) playBeep();
                        endBeepPlayed = true;

                        ArrayList<String> matches = results == null
                                ? null
                                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        String transcript = matches == null || matches.isEmpty()
                                ? ""
                                : matches.get(0).trim();

                        // Hide the transcript and dispatch the command immediately when the
                        // recognizer provides its final result; don't wait for TTS playback.
                        notifyState(false, "Đang xử lý lệnh…", "", false);
                        if (transcript.isEmpty()) {
                            notifyState(false, "Chưa nhận được câu nói.", "", false);
                            speak("Tôi chưa nghe rõ. Bạn thử lại nhé.");
                            return;
                        }

                        VoiceCommandRouter.Result result = router.route(transcript);
                        notifyState(false, result.understood
                                ? "Đã nhận: " + transcript
                                : "Chưa hiểu: " + transcript, "", false);
                        speak(result.response);
                        lastTranscript = "";
                    }

                    @Override public void onPartialResults(Bundle partialResults) {
                        if (destroyed || !listening) return;
                        ArrayList<String> matches = partialResults == null
                                ? null
                                : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches == null || matches.isEmpty()) return;
                        String partial = matches.get(0);
                        if (partial == null || partial.trim().isEmpty()) return;
                        lastTranscript = partial.trim();
                        notifyState(true, "Đang nghe…", lastTranscript, true);
                    }

                    @Override public void onEvent(int eventType, Bundle params) { }
                });
            }

            if (textToSpeech != null) textToSpeech.stop();
            lastTranscript = "";
            endBeepPlayed = false;

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

            listening = true;
            notifyState(true, "Đang nghe…", "", true);
            playBeep();
            recognizer.startListening(intent);
        } catch (RuntimeException exception) {
            listening = false;
            if (!endBeepPlayed) playBeep();
            notifyState(false, "Không khởi động được micro.", "", false);
            speak("Không khởi động được nhận dạng giọng nói. Bạn hãy thử lại.");
        }
    }

    public void cancelListening() {
        runOnMain(() -> {
            if (recognizer != null && listening) {
                try {
                    recognizer.cancel();
                } catch (RuntimeException ignored) { }
            }
            listening = false;
            lastTranscript = "";
            if (!endBeepPlayed) playBeep();
            endBeepPlayed = true;
            notifyState(false, "Đã dừng nghe.", "", false);
        });
    }

    public void destroy() {
        runOnMain(() -> {
            if (destroyed) return;
            destroyed = true;
            listening = false;

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
