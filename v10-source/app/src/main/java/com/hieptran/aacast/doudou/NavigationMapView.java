package com.carassistant.v10;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.LocationComponentOptions;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.UiSettings;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;

public final class NavigationMapView extends FrameLayout {

    private static final int LOCATION_REQUEST = 4101;
    private static final int MICROPHONE_REQUEST = 4102;

    private static final double AUTO_FOLLOW_ZOOM = 16.0;
    private static final double DESTINATION_ZOOM = 15.0;

    /*
     * V3.2:
     * Sau 5 giây không có tiếng nói mới dừng
     * và tự động search.
     */
    private static final long VOICE_SILENCE_TIMEOUT_MS = 5000L;

    private final MapView mapView;
    private MapLibreMap map;

    private EditText searchInput;
    private TextView searchButton;
    private TextView voiceButton;
    private LinearLayout searchResults;

    private SpeechRecognizer speechRecognizer;

    private final Handler voiceHandler =
            new Handler(Looper.getMainLooper());

    private Runnable voiceTimeoutRunnable;

    private boolean voiceListening = false;

    private boolean voiceSearchStarted = false;

    private String latestVoiceText = "";

    private ToneGenerator toneGenerator;

    private Marker destinationMarker;

    private volatile boolean destroyed;

    public NavigationMapView(Context context) {
        super(context);

        setBackgroundColor(Color.BLACK);

        MapLibre.getInstance(
                context.getApplicationContext());

        mapView = new MapView(context);

        mapView.onCreate(
                new Bundle());

        addView(
                mapView,
                new LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT));

        mapView.getMapAsync(
                this::onMapReady);

        buildNavigationControls();
        buildDestinationSearch();
    }

    private void buildDestinationSearch() {

        LinearLayout searchBox =
                new LinearLayout(getContext());

        searchBox.setOrientation(
                LinearLayout.HORIZONTAL);

        searchBox.setGravity(
                Gravity.CENTER_VERTICAL);

        searchBox.setPadding(
                dp(8),
                0,
                dp(6),
                0);

        /*
         * V3.2:
         * Ô tìm kiếm trong suốt hơn.
         */
        GradientDrawable searchBg =
                new GradientDrawable();

        searchBg.setColor(
                Color.argb(
                        82,
                        18,
                        24,
                        30));

        searchBg.setCornerRadius(
                dp(16));

        searchBg.setStroke(
                dp(1),
                Color.argb(
                        105,
                        80,
                        170,
                        230));

        searchBox.setBackground(searchBg);
        searchBox.setElevation(dp(6));

        searchInput =
                new EditText(getContext());

        searchInput.setSingleLine(true);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(
                Color.argb(
                        205,
                        220,
                        225,
                        230));

        searchInput.setHint(
                "Tìm địa điểm...");

        searchInput.setTextSize(16);

        searchInput.setPadding(
                dp(10),
                0,
                dp(8),
                0);

        searchInput.setImeOptions(
                EditorInfo.IME_ACTION_SEARCH);

        searchBox.addView(
                searchInput,
                new LinearLayout.LayoutParams(
                        0,
                        -1,
                        1f));

        /*
         * V3.2 — VOICE SEARCH
         */
        voiceButton =
                new TextView(getContext());

        voiceButton.setText("🎙");
        voiceButton.setTextColor(Color.WHITE);
        voiceButton.setTextSize(22);

        voiceButton.setTypeface(
                null,
                Typeface.BOLD);

        voiceButton.setGravity(
                Gravity.CENTER);

        voiceButton.setContentDescription(
                "Tìm kiếm bằng giọng nói");

        voiceButton.setClickable(true);

        GradientDrawable voiceBg =
                new GradientDrawable();

        voiceBg.setColor(
                Color.argb(
                        105,
                        29,
                        40,
                        51));

        voiceBg.setCornerRadius(
                dp(12));

        voiceButton.setBackground(voiceBg);
        voiceButton.setAlpha(0.82f);

        searchBox.addView(
                voiceButton,
                new LinearLayout.LayoutParams(
                        dp(52),
                        dp(48)));

        /*
         * Tìm kiếm
         */
        searchButton =
                new TextView(getContext());

        searchButton.setText("⌕");
        searchButton.setTextColor(Color.WHITE);
        searchButton.setTextSize(25);

        searchButton.setTypeface(
                null,
                Typeface.BOLD);

        searchButton.setGravity(
                Gravity.CENTER);

        searchButton.setContentDescription(
                "Tìm địa điểm");

        searchButton.setClickable(true);

        GradientDrawable buttonBg =
                new GradientDrawable();

        buttonBg.setColor(
                Color.argb(
                        105,
                        29,
                        40,
                        51));

        buttonBg.setCornerRadius(
                dp(12));

        searchButton.setBackground(buttonBg);
        searchButton.setAlpha(0.82f);

        searchBox.addView(
                searchButton,
                new LinearLayout.LayoutParams(
                        dp(52),
                        dp(48)));

        FrameLayout.LayoutParams searchParams =
                new FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        dp(58),
                        Gravity.TOP | Gravity.START);

        /*
         * Vùng an toàn:
         * - left 112dp: tránh Home UI bên trái
         * - right 94dp: tránh + / − / ◎
         * - top 18dp
         */
        searchParams.setMargins(
                dp(112),
                dp(18),
                dp(94),
                0);

        addView(
                searchBox,
                searchParams);

        /*
         * =====================================================
         * KẾT QUẢ TÌM KIẾM
         * =====================================================
         */

        searchResults =
                new LinearLayout(getContext());

        searchResults.setOrientation(
                LinearLayout.VERTICAL);

        searchResults.setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(4));

        /*
         * V3.2:
         * Danh sách kết quả trong suốt hơn.
         */
        GradientDrawable resultsBg =
                new GradientDrawable();

        resultsBg.setColor(
                Color.argb(
                        78,
                        12,
                        17,
                        22));

        resultsBg.setCornerRadius(
                dp(14));

        resultsBg.setStroke(
                dp(1),
                Color.argb(
                        85,
                        65,
                        85,
                        105));

        searchResults.setBackground(
                resultsBg);

        searchResults.setElevation(
                dp(10));

        searchResults.setVisibility(
                View.GONE);

        FrameLayout.LayoutParams resultsParams =
                new FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.START);

        resultsParams.setMargins(
                dp(112),
                dp(82),
                dp(94),
                0);

        addView(
                searchResults,
                resultsParams);

        voiceButton.setOnClickListener(
                v -> startVoiceSearch());

        searchButton.setOnClickListener(
                v -> performSearch());

        searchInput.setOnEditorActionListener(
                (v, actionId, event) -> {

                    if (actionId ==
                            EditorInfo.IME_ACTION_SEARCH) {

                        performSearch();

                        return true;
                    }

                    return false;
                });
    }

    /*
     * =========================================================
     * V3.2 VOICE SEARCH
     * =========================================================
     */

    private void startVoiceSearch() {

        if (voiceListening) {
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(
                getContext())) {

            showSearchMessage(
                    "Thiết bị không hỗ trợ nhận dạng giọng nói");

            return;
        }

        if (getContext().checkSelfPermission(
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            if (getContext() instanceof Activity) {

                ((Activity) getContext()).requestPermissions(
                        new String[]{
                                Manifest.permission.RECORD_AUDIO
                        },
                        MICROPHONE_REQUEST);

                showSearchMessage(
                        "Cho phép microphone rồi bấm 🎙 lại");

            } else {

                showSearchMessage(
                        "Không thể xin quyền microphone");
            }

            return;
        }

        hideKeyboard();

        /*
         * V3.2:
         * Âm báo bắt đầu nghe NGAY khi bấm mic.
         */
        playVoiceStartTone();

        voiceListening = true;
        voiceSearchStarted = true;
        latestVoiceText = "";

        if (voiceButton != null) {
            voiceButton.setText("…");
        }

        showSearchMessage(
                "Đang nghe…");

        cancelVoiceTimeout();

        startSpeechRecognizer();
    }

    private void startSpeechRecognizer() {

        if (!voiceListening || destroyed) {
            return;
        }

        try {

            if (speechRecognizer == null) {

                speechRecognizer =
                        SpeechRecognizer.createSpeechRecognizer(
                                getContext());

                speechRecognizer.setRecognitionListener(
                        createVoiceListener());
            }

            Intent intent =
                    new Intent(
                            RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

            intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

            /*
             * Tiếng Việt.
             */
            intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "vi-VN");

            intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    "vi-VN");

            intent.putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    5);

            /*
             * V3.2:
             * Cho phép partial result.
             */
            intent.putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true);

            /*
             * Gợi ý cho speech engine giữ phiên nghe
             * lâu hơn trước khi coi là hoàn tất.
             */
            intent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    1000L);

            intent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    VOICE_SILENCE_TIMEOUT_MS);

            intent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    VOICE_SILENCE_TIMEOUT_MS);

            speechRecognizer.startListening(
                    intent);

            /*
             * Timeout 5 giây tính từ lúc bắt đầu nghe.
             * Mỗi partial sẽ reset lại timer.
             */
            resetVoiceSilenceTimer();

        } catch (Exception e) {

            finishVoiceSearch(
                    "Không thể khởi động nhận dạng giọng nói",
                    false);
        }
    }

    private RecognitionListener createVoiceListener() {

        return new RecognitionListener() {

            @Override
            public void onReadyForSpeech(
                    Bundle params) {

                if (!voiceListening) {
                    return;
                }

                showSearchMessage(
                        "Đang nghe…");

                resetVoiceSilenceTimer();
            }

            @Override
            public void onBeginningOfSpeech() {

                if (!voiceListening) {
                    return;
                }

                showSearchMessage(
                        "Đang nghe…");

                resetVoiceSilenceTimer();
            }

            @Override
            public void onRmsChanged(
                    float rmsdB) {
            }

            @Override
            public void onBufferReceived(
                    byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {

                if (!voiceListening) {
                    return;
                }

                /*
                 * Không search ngay tại đây.
                 *
                 * V3.2 yêu cầu:
                 * phải chờ đủ 5 giây không có tiếng nói.
                 */
                showSearchMessage(
                        latestVoiceText.isEmpty()
                                ? "Đang nghe…"
                                : latestVoiceText);

                resetVoiceSilenceTimer();
            }

            @Override
            public void onError(
                    int error) {

                if (!voiceListening) {
                    return;
                }

                /*
                 * Một số speech engine tự kết thúc phiên
                 * khá sớm. Nếu chưa hết 5 giây thì thử
                 * mở lại phiên nghe để người dùng có thể
                 * nói tiếp.
                 */
                if (error ==
                        SpeechRecognizer.ERROR_NO_MATCH
                        || error ==
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        || error ==
                        SpeechRecognizer.ERROR_CLIENT) {

                    resetVoiceSilenceTimer();

                    voiceHandler.postDelayed(
                            () -> {

                                if (!voiceListening
                                        || destroyed) {
                                    return;
                                }

                                startSpeechRecognizer();

                            },
                            180L);

                    return;
                }

                if (error ==
                        SpeechRecognizer.ERROR_NETWORK
                        || error ==
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {

                    finishVoiceSearch(
                            "Lỗi kết nối nhận dạng giọng nói",
                            false);

                    return;
                }

                if (error ==
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {

                    finishVoiceSearch(
                            "Chưa được cấp quyền microphone",
                            false);

                    return;
                }

                finishVoiceSearch(
                        "Không thể nhận dạng giọng nói",
                        false);
            }

            @Override
            public void onResults(
                    Bundle results) {

                if (!voiceListening) {
                    return;
                }

                ArrayList<String> matches =
                        results.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION);

                if (matches != null
                        && !matches.isEmpty()) {

                    String spoken =
                            matches.get(0);

                    if (spoken != null
                            && !spoken.trim().isEmpty()) {

                        latestVoiceText =
                                spoken.trim();

                        updateVoiceText(
                                latestVoiceText);
                    }
                }

                /*
                 * Không search ngay.
                 * Chờ đủ 5 giây im lặng.
                 */
                resetVoiceSilenceTimer();

                /*
                 * SpeechRecognizer thường tự kết thúc
                 * một phiên sau khi nhận được câu.
                 *
                 * Nếu người dùng vẫn tiếp tục nói trong
                 * khoảng 5 giây, mở phiên mới.
                 */
                if (voiceListening) {

                    voiceHandler.postDelayed(
                            () -> {

                                if (!voiceListening
                                        || destroyed) {
                                    return;
                                }

                                startSpeechRecognizer();

                            },
                            180L);
                }
            }

            @Override
            public void onPartialResults(
                    Bundle partialResults) {

                if (!voiceListening) {
                    return;
                }

                ArrayList<String> matches =
                        partialResults.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION);

                if (matches == null
                        || matches.isEmpty()) {
                    return;
                }

                String partial =
                        matches.get(0);

                if (partial == null) {
                    return;
                }

                partial =
                        partial.trim();

                if (partial.isEmpty()) {
                    return;
                }

                /*
                 * V3.2:
                 * Ví dụ:
                 *
                 * THCS
                 * THCS Tháng
                 * THCS Tháng 10
                 *
                 * cập nhật trực tiếp vào ô tìm kiếm.
                 */
                latestVoiceText =
                        partial;

                updateVoiceText(
                        partial);

                /*
                 * Mỗi lần nhận được tiếng nói
                 * thì reset 5 giây.
                 */
                resetVoiceSilenceTimer();
            }

            @Override
            public void onEvent(
                    int eventType,
                    Bundle params) {
            }
        };
    }

    private void updateVoiceText(
            String text) {

        if (destroyed) {
            return;
        }

        post(() -> {

            if (destroyed) {
                return;
            }

            if (searchInput == null) {
                return;
            }

            searchInput.setText(text);

            searchInput.setSelection(
                    searchInput.length());

            showSearchMessage(
                    "Đang nghe…");
        });
    }

    private void resetVoiceSilenceTimer() {

        cancelVoiceTimeout();

        if (!voiceListening
                || destroyed) {
            return;
        }

        voiceTimeoutRunnable =
                () -> {

                    if (!voiceListening
                            || destroyed) {
                        return;
                    }

                    finishVoiceSearch(
                            null,
                            true);
                };

        voiceHandler.postDelayed(
                voiceTimeoutRunnable,
                VOICE_SILENCE_TIMEOUT_MS);
    }

    private void cancelVoiceTimeout() {

        if (voiceTimeoutRunnable != null) {

            voiceHandler.removeCallbacks(
                    voiceTimeoutRunnable);

            voiceTimeoutRunnable = null;
        }
    }

    private void finishVoiceSearch(
            String message,
            boolean autoSearch) {

        if (!voiceListening) {
            return;
        }

        voiceListening = false;
        voiceSearchStarted = false;

        cancelVoiceTimeout();

        stopSpeechRecognizerOnly();

        if (voiceButton != null) {
            voiceButton.setText("🎙");
        }

        String finalText =
                latestVoiceText == null
                        ? ""
                        : latestVoiceText.trim();

        if (autoSearch) {

            if (finalText.isEmpty()) {

                showSearchMessage(
                        "Không nhận dạng được địa điểm");

                return;
            }

            if (searchInput != null) {

                searchInput.setText(
                        finalText);

                searchInput.setSelection(
                        searchInput.length());
            }

            /*
             * Tự động search đúng 1 lần.
             */
            performSearch();

            return;
        }

        if (message != null
                && !message.isEmpty()) {

            showSearchMessage(
                    message);
        }
    }

    private void stopSpeechRecognizerOnly() {

        if (speechRecognizer == null) {
            return;
        }

        try {
            speechRecognizer.stopListening();
        } catch (Exception ignored) {
        }

        try {
            speechRecognizer.cancel();
        } catch (Exception ignored) {
        }
    }

    private void stopVoiceRecognizer() {

        voiceListening = false;
        voiceSearchStarted = false;

        cancelVoiceTimeout();

        if (speechRecognizer != null) {

            try {
                speechRecognizer.stopListening();
            } catch (Exception ignored) {
            }

            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {
            }

            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) {
            }

            speechRecognizer = null;
        }

        if (toneGenerator != null) {

            try {
                toneGenerator.release();
            } catch (Exception ignored) {
            }

            toneGenerator = null;
        }

        if (voiceButton != null) {
            voiceButton.setText("🎙");
        }
    }

    private void playVoiceStartTone() {

        try {

            if (toneGenerator != null) {

                toneGenerator.release();

                toneGenerator = null;
            }

            toneGenerator =
                    new ToneGenerator(
                            AudioManager.STREAM_NOTIFICATION,
                            80);

            toneGenerator.startTone(
                    ToneGenerator.TONE_PROP_BEEP,
                    120);

        } catch (Exception ignored) {
        }
    }

    /*
     * =========================================================
     * SEARCH
     * =========================================================
     */

    private void performSearch() {

        if (searchInput == null) {
            return;
        }

        final String query =
                searchInput.getText()
                        .toString()
                        .trim();

        if (query.isEmpty()) {

            showSearchMessage(
                    "Nhập địa điểm cần tìm");

            return;
        }

        hideKeyboard();

        if (searchButton != null) {
            searchButton.setText("…");
        }

        showSearchMessage(
                "Đang tìm...");

        new Thread(
                () -> searchNominatim(query))
                .start();
    }

    private void searchNominatim(
            String query) {

        HttpURLConnection connection = null;

        try {

            String encoded =
                    URLEncoder.encode(
                            query,
                            "UTF-8");

            URL url =
                    new URL(
                            "https://nominatim.openstreetmap.org/search"
                                    + "?format=jsonv2"
                                    + "&limit=5"
                                    + "&accept-language=vi"
                                    + "&q="
                                    + encoded);

            connection =
                    (HttpURLConnection)
                            url.openConnection();

            connection.setRequestMethod(
                    "GET");

            connection.setConnectTimeout(
                    8000);

            connection.setReadTimeout(
                    10000);

            connection.setUseCaches(false);

            connection.setRequestProperty(
                    "User-Agent",
                    "CarAssistant-NavigationV3/1.0");

            connection.setRequestProperty(
                    "Accept",
                    "application/json");

            connection.setRequestProperty(
                    "Accept-Language",
                    "vi-VN,vi;q=0.9");

            int code =
                    connection.getResponseCode();

            if (code !=
                    HttpURLConnection.HTTP_OK) {

                postSearchMessage(
                        "Không tìm được địa điểm");

                return;
            }

            InputStream input =
                    connection.getInputStream();

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    input,
                                    "UTF-8"));

            StringBuilder body =
                    new StringBuilder();

            String line;

            while ((line =
                    reader.readLine()) != null) {

                body.append(line);
            }

            reader.close();

            JSONArray array =
                    new JSONArray(
                            body.toString());

            ArrayList<SearchResult> results =
                    new ArrayList<>();

            for (int i = 0;
                 i < array.length() && i < 5;
                 i++) {

                JSONObject item =
                        array.getJSONObject(i);

                String latText =
                        item.optString(
                                "lat",
                                "");

                String lonText =
                        item.optString(
                                "lon",
                                "");

                String name =
                        item.optString(
                                "display_name",
                                "Địa điểm");

                if (latText.isEmpty()
                        || lonText.isEmpty()) {

                    continue;
                }

                try {

                    double lat =
                            Double.parseDouble(
                                    latText);

                    double lon =
                            Double.parseDouble(
                                    lonText);

                    results.add(
                            new SearchResult(
                                    name,
                                    lat,
                                    lon));

                } catch (
                        NumberFormatException ignored) {
                }
            }

            postSearchResults(
                    results);

        } catch (UnknownHostException e) {

            postSearchMessage(
                    "Không có kết nối Internet");

        } catch (SocketTimeoutException e) {

            postSearchMessage(
                    "Máy chủ tìm kiếm phản hồi quá chậm");

        } catch (Exception e) {

            postSearchMessage(
                    "Lỗi kết nối tìm kiếm");

        } finally {

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void postSearchResults(
            final ArrayList<SearchResult> results) {

        post(() -> {

            if (destroyed) {
                return;
            }

            if (searchButton != null) {
                searchButton.setText("⌕");
            }

            searchResults.removeAllViews();

            if (results.isEmpty()) {

                showSearchMessage(
                        "Không có kết quả phù hợp");

                return;
            }

            for (SearchResult result : results) {

                TextView row =
                        new TextView(getContext());

                row.setText(
                        result.name);

                row.setTextColor(
                        Color.WHITE);

                row.setTextSize(14);

                row.setGravity(
                        Gravity.CENTER_VERTICAL);

                row.setMaxLines(2);

                row.setEllipsize(
                        android.text.TextUtils.TruncateAt.END);

                row.setPadding(
                        dp(14),
                        dp(7),
                        dp(10),
                        dp(7));

                /*
                 * V3.2:
                 * Row kết quả cũng trong suốt hơn.
                 */
                GradientDrawable rowBg =
                        new GradientDrawable();

                rowBg.setColor(
                        Color.argb(
                                88,
                                28,
                                38,
                                48));

                rowBg.setCornerRadius(
                        dp(10));

                row.setBackground(
                        rowBg);

                row.setOnClickListener(
                        v -> selectDestination(
                                result));

                LinearLayout.LayoutParams rowParams =
                        new LinearLayout.LayoutParams(
                                -1,
                                dp(54));

                rowParams.setMargins(
                        dp(2),
                        dp(2),
                        dp(2),
                        dp(2));

                searchResults.addView(
                        row,
                        rowParams);
            }

            searchResults.setVisibility(
                    View.VISIBLE);

            searchResults.bringToFront();

            searchButton.bringToFront();

            searchInput.bringToFront();

            voiceButton.bringToFront();
        });
    }

    private void showSearchMessage(
            String message) {

        if (searchResults == null) {
            return;
        }

        searchResults.removeAllViews();

        TextView row =
                new TextView(getContext());

        row.setText(message);

        row.setTextColor(
                Color.WHITE);

        row.setTextSize(14);

        row.setGravity(
                Gravity.CENTER);

        row.setPadding(
                dp(12),
                dp(8),
                dp(12),
                dp(8));

        searchResults.addView(
                row,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(52)));

        searchResults.setVisibility(
                View.VISIBLE);

        searchResults.bringToFront();
    }

    private void selectDestination(
            SearchResult result) {

        if (map == null) {
            return;
        }

        LatLng target =
                new LatLng(
                        result.latitude,
                        result.longitude);

        if (destinationMarker != null) {

            try {

                map.removeMarker(
                        destinationMarker);

            } catch (Exception ignored) {
            }
        }

        destinationMarker =
                map.addMarker(
                        new MarkerOptions()
                                .position(target)
                                .title("Điểm đến")
                                .snippet(result.name));

        map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                        target,
                        DESTINATION_ZOOM),
                700);

        if (searchInput != null) {

            searchInput.setText(
                    result.name);
        }

        if (searchResults != null) {

            searchResults.setVisibility(
                    View.GONE);
        }

        hideKeyboard();
    }

    private void hideKeyboard() {

        if (searchInput == null) {
            return;
        }

        InputMethodManager imm =
                (InputMethodManager)
                        getContext().getSystemService(
                                Context.INPUT_METHOD_SERVICE);

        if (imm != null) {

            imm.hideSoftInputFromWindow(
                    searchInput.getWindowToken(),
                    0);
        }
    }

    private void postSearchMessage(
            final String message) {

        post(() -> {

            if (destroyed) {
                return;
            }

            if (searchButton != null) {
                searchButton.setText("⌕");
            }

            showSearchMessage(
                    message);
        });
    }

    /*
     * =========================================================
     * NAVIGATION CONTROLS
     * + / − / ◎ GIỮ NGUYÊN
     * =========================================================
     */

    private void buildNavigationControls() {

        LinearLayout controls =
                new LinearLayout(getContext());

        controls.setOrientation(
                LinearLayout.VERTICAL);

        controls.setGravity(
                Gravity.CENTER);

        controls.setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(4));

        FrameLayout.LayoutParams containerParams =
                new FrameLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.END);

        containerParams.setMargins(
                0,
                dp(18),
                dp(18),
                0);

        addControlButton(
                controls,
                "+",
                "Zoom in",
                v -> zoomIn());

        addControlButton(
                controls,
                "−",
                "Zoom out",
                v -> zoomOut());

        addControlButton(
                controls,
                "◎",
                "Recenter and resume auto follow",
                v -> recenter());

        addView(
                controls,
                containerParams);

        controls.bringToFront();
    }

    private void addControlButton(
            LinearLayout parent,
            String label,
            String description,
            View.OnClickListener listener) {

        TextView button =
                new TextView(getContext());

        button.setText(label);

        button.setTextColor(
                Color.WHITE);

        button.setTextSize(24);

        button.setGravity(
                Gravity.CENTER);

        button.setTypeface(
                null,
                Typeface.BOLD);

        button.setContentDescription(
                description);

        button.setClickable(true);

        button.setFocusable(true);

        button.setElevation(
                dp(8));

        /*
         * V3.1 hiện tại:
         * giữ nguyên độ trong suốt của + / − / ◎.
         */
        button.setAlpha(0.52f);

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(
                Color.argb(
                        115,
                        25,
                        35,
                        45));

        background.setCornerRadius(
                dp(14));

        background.setStroke(
                dp(1),
                Color.argb(
                        105,
                        80,
                        170,
                        230));

        button.setBackground(
                background);

        button.setOnClickListener(
                listener);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        dp(58),
                        dp(58));

        params.setMargins(
                0,
                dp(3),
                0,
                dp(3));

        parent.addView(
                button,
                params);
    }

    /*
     * =========================================================
     * MAP
     * =========================================================
     */

    private void onMapReady(
            MapLibreMap readyMap) {

        map = readyMap;

        UiSettings ui =
                map.getUiSettings();

        ui.setCompassEnabled(false);

        ui.setZoomGesturesEnabled(true);

        ui.setScrollGesturesEnabled(true);

        ui.setRotateGesturesEnabled(true);

        ui.setTiltGesturesEnabled(false);

        map.setStyle(
                "https://tiles.openfreemap.org/styles/liberty",
                style -> enableLocation());
    }

    private void enableLocation() {

        if (map == null) {
            return;
        }

        Context context =
                getContext();

        if (context == null) {
            return;
        }

        if (context.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            if (context instanceof Activity) {

                ((Activity) context).requestPermissions(
                        new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        },
                        LOCATION_REQUEST);
            }

            return;
        }

        LocationComponentOptions options =
                LocationComponentOptions
                        .builder(context)
                        .pulseEnabled(true)
                        .trackingGesturesManagement(true)
                        .build();

        LocationComponentActivationOptions activation =
                LocationComponentActivationOptions
                        .builder(
                                context,
                                map.getStyle())
                        .locationComponentOptions(
                                options)
                        .build();

        map.getLocationComponent()
                .activateLocationComponent(
                        activation);

        map.getLocationComponent()
                .setLocationComponentEnabled(true);

        startAutoFollow();
    }

    /*
     * =========================================================
     * AUTO-FOLLOW V2 — GIỮ NGUYÊN
     * =========================================================
     */

    private void startAutoFollow() {

        if (map == null) {
            return;
        }

        try {

            map.getLocationComponent()
                    .setCameraMode(
                            CameraMode.TRACKING,
                            800L,
                            AUTO_FOLLOW_ZOOM,
                            null,
                            0.0,
                            null);

        } catch (Exception ignored) {

            recenter();
        }
    }

    public void zoomIn() {

        if (map == null) {
            return;
        }

        double zoom =
                map.getCameraPosition().zoom;

        map.animateCamera(
                CameraUpdateFactory.zoomTo(
                        Math.min(
                                20.0,
                                zoom + 1.0)),
                250);
    }

    public void zoomOut() {

        if (map == null) {
            return;
        }

        double zoom =
                map.getCameraPosition().zoom;

        map.animateCamera(
                CameraUpdateFactory.zoomTo(
                        Math.max(
                                1.0,
                                zoom - 1.0)),
                250);
    }

    public void recenter() {

        if (map == null) {
            return;
        }

        try {

            Location location =
                    map.getLocationComponent()
                            .getLastKnownLocation();

            if (location == null) {

                startAutoFollow();

                return;
            }

            map.getLocationComponent()
                    .setCameraMode(
                            CameraMode.TRACKING,
                            500L,
                            AUTO_FOLLOW_ZOOM,
                            null,
                            0.0,
                            null);

        } catch (Exception ignored) {
        }
    }

    /*
     * =========================================================
     * LIFECYCLE
     * =========================================================
     */

    public void onHostResume() {

        mapView.onResume();
    }

    public void onHostPause() {

        mapView.onPause();
    }

    public void onHostDestroy() {

        destroyed = true;

        stopVoiceRecognizer();

        mapView.onDestroy();
    }

    private int dp(float value) {

        return Math.round(
                value
                        * getResources()
                                .getDisplayMetrics()
                                .density);
    }

    private static final class SearchResult {

        final String name;
        final double latitude;
        final double longitude;

        SearchResult(
                String name,
                double latitude,
                double longitude) {

            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
