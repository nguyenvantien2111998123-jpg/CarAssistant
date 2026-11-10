# WYN 2.1.0 reverse engineering — warning data path

## Artifact and integrity

- Package: `com.wynauto.wyn`
- Version: 2.1.0 / versionCode 93 (from the supplied XAPK manifest)
- Main APK: `com.wynauto.wyn.apk`; its ZIP CRC test passed.
- ABI split: `config.arm64_v8a.apk`; the supplied XAPK reports a CRC mismatch for at least `libmaplibre.so` and invalid local-header offsets for later entries. Several libraries, including `libapp.so`, `libwynlib.so`, and `libwyn_car_layer.so`, were extractable, but the split must be treated as potentially incomplete.
- Runtime architecture: Flutter/Dart AOT. The DEX files expose the Android shell/plugin layer, including `com.wynauto.wyn.MainActivity`, `WynDeviceKey`, `flutter_overlay_window.OverlayService`, and `TaskRemovalGuardService`; the main warning state/rendering is not implemented as ordinary Java Activity code.

## Confirmed warning/data symbols

The extracted ARM64 `libwynlib.so` dynamic symbol table exports Flutter Rust Bridge generated functions:

- `frbgen_wyn_wire__crate__api__api__probe_sl`
- `frbgen_wyn_wire__crate__api__api__probe_ss_list`
- `frbgen_wyn_cst_new_box_autoadd_speed_limit_state`
- `frbgen_wyn_cst_new_box_autoadd_speed_limit_config`
- `frbgen_wyn_cst_new_box_autoadd_speed_limit_probe_param`
- `frbgen_wyn_cst_new_box_autoadd_probe_speed_result`
- `frbgen_wyn_cst_new_box_autoadd_street_sign`
- `frbgen_wyn_cst_new_box_autoadd_street_sign_list`
- `frbgen_wyn_cst_new_box_autoadd_next_speed`
- `frbgen_wyn_cst_new_list_street_sign`
- `frbgen_wyn_cst_new_list_next_speed`
- `frbgen_wyn_cst_new_list_speed_node_link`
- `store_dart_post_cobject`
- `frb_dart_fn_deliver_output`

These names establish a real Rust-backed speed-limit probe and street-sign-list data path. They do **not** by themselves establish that the Rust API returns rendered warning bitmaps. The exported constructors and Dart AOT strings indicate typed data objects are passed across the Flutter Rust Bridge.

Dart snapshot strings include:

- `StreetSignInfo.lanes(field0:`
- `StreetSignInfo.trafficLightId(field0:`
- `StreetSignInfo.none()`
- `speedLimit`
- `streetSignList`
- `Next speed limit`
- `Speed Limit Result NULL => Reset state`
- `>>> NEW SPEED LIMIT = `
- `SPEED LIMIT WARNING`
- `Current Speed`
- `Advance Warning`

This is direct evidence that the application has distinct speed-limit and street-sign state models, and that a null speed-limit probe result resets state.

## Confirmed image/render resources

The main APK Flutter AssetManifest includes local warning assets, including:

- `assets/images/components/speed_camera.png`
- `assets/images/components/horizontal-traffic-light.png`
- `assets/images/components/road_sign_*.svg` (DP133, P103a/b/c, P123a/b, P124 variants, P125, P130, P131 variants, P136–P139, R420/R421)
- `assets/images/components/settings/speed_sign.png`

This supports the following split:
- speed-limit and street-sign data are represented as Dart/Rust state;
- warning artwork is rendered from local Flutter assets (including SVG road signs and PNG warning images);
- the dynamic speed-limit number may be composed with a local sign template/text rather than being a unique bitmap returned by Rust.

The last point is a likely rendering model, not yet a proven exact widget call path.

## Adapter/hook implications

The current CarAssistant branch has:
- `WynHomeProbe` installing before the Flutter first frame through `Application.attach`;
- `WynWarningLinkProbe` observing `FlutterLoader.getLookupKeyForAsset` and forwarding recognized asset keys to the CarAssistant provider;
- `WynWarningAssetLoader` loading the corresponding original WYN assets and rasterizing SVGs;
- `WynWarningAdapter` routing the received warning image into persistent speed-sign or temporary-alert state;
- `MediaSessionBridge` updating artwork metadata after accepted state changes.

This is an asset-resolution mirror, not a decoded live-state hook. It has two important limitations:
1. Observing an asset lookup proves that Flutter requested an asset, not that it is currently the active Home warning. Asset preloads, settings screens, and cached lookups can produce false positives.
2. The existing asset-key recognizer covers road-sign SVG, speed-camera PNG and traffic-light PNG, but does not extract the actual dynamic numeric value from the Dart `speedLimit` state. The Rust API names `probe_sl` and `probe_ss_list` expose a more promising data boundary for that state.

## Recommended next hook, based on evidence

Do not invent a Java `updateWarning()` method. The next investigation should trace the Dart/Rust bridge result path:

1. Trace calls to exported `probe_sl` and `probe_ss_list` and their input records (`SpeedLimitProbeParam`, `ProbeParam`).
2. Trace the corresponding Flutter Rust Bridge response delivered through the registered Dart post-CObject callback. Decode the Dart_CObject/FRB envelope before interpreting fields; do not assume it is a bitmap.
3. Correlate the decoded `SpeedLimitState` / `ProbeSpeedResult` / `StreetSignInfo` with the Flutter asset key selected for the Home warning.
4. Publish a normalized event containing warning kind, exact speed-limit value (when present), and asset key to `WynWarningAdapter`. Let CarAssistant load/rasterize the matching WYN asset and compose the AA artwork.
5. Keep a separate temporary-alert channel and preserve the last persistent speed sign when the temporary alert clears.

A native hook at the exported FRB boundary is a candidate for extracting state, but the callback's concrete ABI and Dart_CObject schema have not yet been decoded. The symbol names are verified; the message layout is not.

## Status

- Verified: package/version, Flutter AOT architecture, exported Rust bridge symbols, speed-limit/street-sign data model names, warning asset catalogue, asset-mirroring implementation in CarAssistant.
- Strong inference: speed-limit/street-sign state crosses the Dart/Rust bridge and Flutter renders the visual warning from local assets.
- Not yet verified: exact Dart widget/render method, callback payload schema, exact active Home warning transition callback, and successful end-to-end WYN-to-CarAssistant live data flow.
- No device test or successful live-warning claim is made by this document.
