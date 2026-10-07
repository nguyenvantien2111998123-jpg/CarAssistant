package com.carassistant.v10;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Bundle;
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
import java.util.ArrayList;

public final class NavigationMapView extends FrameLayout {

    private static final int LOCATION_REQUEST = 4101;
    private static final double AUTO_FOLLOW_ZOOM = 16.0;
    private static final double DESTINATION_ZOOM = 15.0;

    private final MapView mapView;
    private MapLibreMap map;

    private EditText searchInput;
    private TextView searchButton;
    private LinearLayout searchResults;
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

        GradientDrawable searchBg =
                new GradientDrawable();

        searchBg.setColor(
                Color.rgb(18, 24, 30));

        searchBg.setCornerRadius(
                dp(16));

        searchBg.setStroke(
                dp(2),
                Color.rgb(80, 170, 230));

        searchBox.setBackground(searchBg);
        searchBox.setElevation(dp(8));

        searchInput =
                new EditText(getContext());

        searchInput.setSingleLine(true);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(Color.LTGRAY);
        searchInput.setHint("Tìm địa điểm...");
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

        searchButton =
                new TextView(getContext());

        searchButton.setText("⌕");
        searchButton.setTextColor(Color.WHITE);
        searchButton.setTextSize(25);
        searchButton.setTypeface(
                null,
                Typeface.BOLD);
        searchButton.setGravity(Gravity.CENTER);
        searchButton.setContentDescription(
                "Tìm địa điểm");
        searchButton.setClickable(true);

        GradientDrawable buttonBg =
                new GradientDrawable();

        buttonBg.setColor(
                Color.rgb(29, 40, 51));

        buttonBg.setCornerRadius(
                dp(12));

        searchButton.setBackground(buttonBg);

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
         * - right 94dp: tránh cụm + / − / ◎ bên phải
         * - top 18dp: nằm trong vùng trống phía trên bản đồ
         */
        searchParams.setMargins(
                dp(112),
                dp(18),
                dp(94),
                0);

        addView(
                searchBox,
                searchParams);

        searchResults =
                new LinearLayout(getContext());

        searchResults.setOrientation(
                LinearLayout.VERTICAL);

        searchResults.setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(4));

        GradientDrawable resultsBg =
                new GradientDrawable();

        resultsBg.setColor(
                Color.rgb(12, 17, 22));

        resultsBg.setCornerRadius(
                dp(14));

        resultsBg.setStroke(
                dp(1),
                Color.rgb(65, 85, 105));

        searchResults.setBackground(resultsBg);
        searchResults.setElevation(dp(12));
        searchResults.setVisibility(View.GONE);

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

        searchButton.setOnClickListener(
                v -> performSearch());

        searchInput.setOnEditorActionListener(
                (v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        performSearch();
                        return true;
                    }
                    return false;
                });
    }

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

        showSearchMessage("Đang tìm...");

        new Thread(
                () -> searchNominatim(query))
                .start();
    }

    private void searchNominatim(String query) {
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

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);

            connection.setRequestProperty(
                    "User-Agent",
                    "CarAssistant-NavigationV3/1.0");

            int code =
                    connection.getResponseCode();

            if (code != HttpURLConnection.HTTP_OK) {
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

            while ((line = reader.readLine()) != null) {
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

                } catch (NumberFormatException ignored) {
                }
            }

            postSearchResults(results);

        } catch (Exception ignored) {

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

                row.setText(result.name);
                row.setTextColor(Color.WHITE);
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

                GradientDrawable rowBg =
                        new GradientDrawable();

                rowBg.setColor(
                        Color.rgb(28, 38, 48));

                rowBg.setCornerRadius(
                        dp(10));

                row.setBackground(rowBg);

                row.setOnClickListener(
                        v -> selectDestination(result));

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
        row.setTextColor(Color.WHITE);
        row.setTextSize(14);
        row.setGravity(Gravity.CENTER);
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

        /*
         * Khi chọn điểm đến:
         * - đặt camera vào điểm được chọn
         * - không gọi lại auto-follow ngay lúc này
         * - giữ camera tại điểm đến
         */
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

            showSearchMessage(message);
        });
    }

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

        GradientDrawable background =
                new GradientDrawable();

        background.setColor(
                Color.rgb(
                        25,
                        35,
                        45));

        background.setCornerRadius(
                dp(14));

        background.setStroke(
                dp(2),
                Color.rgb(
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

    public void onHostResume() {
        mapView.onResume();
    }

    public void onHostPause() {
        mapView.onPause();
    }

    public void onHostDestroy() {
        destroyed = true;
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
