package com.carassistant.v10;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.widget.FrameLayout;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.LocationComponentOptions;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.UiSettings;

public final class NavigationMapView extends FrameLayout {

    private static final int LOCATION_REQUEST = 4101;

    private final MapView mapView;
    private MapLibreMap map;

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
                        .build();

        LocationComponentActivationOptions activation =
                LocationComponentActivationOptions
                        .builder(
                                context,
                                map.getStyle())
                        .locationComponentOptions(options)
                        .build();

        map.getLocationComponent()
                .activateLocationComponent(
                        activation);

        map.getLocationComponent()
                .setLocationComponentEnabled(true);
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
                return;
            }

            map.animateCamera(
                    CameraUpdateFactory
                            .newLatLngZoom(
                                    new LatLng(
                                            location.getLatitude(),
                                            location.getLongitude()),
                                    16.0),
                    500);

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
        mapView.onDestroy();
    }
}
