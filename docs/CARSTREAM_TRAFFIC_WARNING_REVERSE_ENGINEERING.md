# CarStream 1.24.57 — traffic-warning reverse engineering and CarAsistant port

## 1. Artifact examined

- Package: `com.carstream.pack`
- Version: `1.24.57`
- Version code: `697`
- APK SHA-256: `2c8eb76d6eefbd9edd7eccbee52c5ca506519a18e535a99f4f6910f3b4252a00`
- Main DEX: one compact, R8-obfuscated `classes.dex`; the readable application class names are mostly shortened to `Lx;`-style descriptors.
- The Vietnamese localization bundle is `assets/ngonngu/vi.json`.

This report describes the APK that was actually supplied, not an assumed CarStream Control APK.

## 2. Important architectural boundary: Full APK vs Control

The examined APK contains the phone-side Full app and its warning HUD/rendering code. Its manifest declares the launcher activity but does not declare an Accessibility Service, ContentProvider, AppWidget provider, location service, foreground source service, or broadcast receiver for traffic feeds. The DEX type table has no `java.net`, OkHttp/WebSocket, `AppWidgetManager`, `ContentResolver`, or `AccessibilityService` integration class.

The APK's own Vietnamese resources explicitly say that the CarStream Control installation is not packaged in this app. The same resources tell the user to connect a VietMap widget and configure Waze-mod WiFi WebSocket. This is strong evidence that the Full APK is the HUD/configuration/rendering half, while at least some source acquisition and transport are supplied by a separate component or by the source app itself.

Consequently, copying this APK's HUD classes and source IDs does **not** copy the missing Control-side data acquisition code. No private VietMap, Gofa, Waze, or Waze HLP payload schema has been inferred or fabricated in this port.

## 3. Recovered source selector and persistence

The relevant obfuscated class is `Ld2`:

- `Ld2.v()` reads the selected source from the `cs` SharedPreferences file using the key `ze80494b60`.
- Its default selector value is `widget`.
- The selector recognizes the `carstream` option separately.
- The source identifiers visible in the DEX and localization resources are:

| CarStream ID | Displayed source | CarAsistant enum |
|---|---|---|
| `wyn` | Wyn | `WYN` |
| `widget` | VietMap widget | `VIETMAP_WIDGET` |
| `z23e037d98` | Gofa | `GOFA` |
| `zda617cedc` | Waze | `WAZE` |
| `z9e4343e4b` | Waze HLP | `WAZE_HLP` |

- `Ld2.k(String)` checks the Gofa, Waze, and WYN IDs.
- `Li1.p(Context)` opens the `cs` SharedPreferences file.
- `Li1.l(...)` reads a string preference; `Li1.s(...)` writes a string preference; `Li1.q(...)` and `Li1.r(...)` write boolean/integer values.
- `Ld2.t()` builds speed-HUD controls including `showSpeed` and `speedStyle`; the style default is `pill`.
- Other recovered settings include `speedSignsOnly`, `speedX`, `speedY`, `speedAlpha`, `speedScale`, `limitAsTitle`, and `gaugeOnlyArt`.

CarStream uses a single selected source. CarAsistant's port intentionally changes this to independent source toggles so several sources can be enabled at once. The `carstream` source is deliberately excluded from the CarAsistant source enum.

## 4. Warning model and renderer

The key warning/HUD classes recovered from DEX structure are:

- `q0`: compact warning model with a string identifier (`a`) and integer/resource value (`b`).
- `Lj2`: base HUD renderer and warning-state/transition owner.
  - `Lj2.g(Canvas, float, float, float, q0)` switches on the warning identifier, resolves a drawable resource, caches it in a `SparseArray`, sets bounds, and draws it on the supplied Canvas.
  - `Lj2.D(Canvas, q0, RectF)` draws a warning in a bounded region.
  - `Lj2.onDraw(Canvas)` advances its animation objects and delegates the style-specific draw pass.
  - `Lj2.z(ArrayList)` updates the current/next warning entries and transition timestamps.
- `Lj3`: HUD factory.
  - `Lj3.c(Context, String)` selects the requested renderer style.
  - Recovered style IDs: `card`, `dial`, `neon`, `rail`, `spokes`, `liquid`, and `ruler`.
  - Concrete style classes include `Lk2`, `Ll2`, `Lo2`, `Ln2`, `Lr2`, `Lq2`, and `Lp2`.
- `r0`: loads localized labels from `assets/ngonngu/<language>.json`.

CarAsistant does not need to copy CarStream's whole dashboard or its phone-side Canvas HUD to satisfy the agreed Android Auto artwork layout. It ports the stable warning identifiers and source model, then composes the incoming warning bitmap into the existing CarAsistant MediaSession artwork.

## 5. Warning identifier catalogue recovered from Lj2.g and vi.json

The following identifiers are present in the CarStream warning-to-drawable switch:

| Identifier | Vietnamese label |
|---|---|
| `warningAlertCameraAIId` | Camera AI |
| `warningAlertHighwayRestAreaId` | Trạm dừng nghỉ |
| `warningAlertNationalHighwayRestAreaId` | Trạm dừng nghỉ quốc lộ |
| `warningAlertNoParkNoStopId` | Cấm dừng đỗ |
| `warningAlertNoParkingId` | Cấm đỗ |
| `warningAlertNoParkingEvenId` | Cấm đỗ ngày chẵn |
| `warningAlertNoParkingOddId` | Cấm đỗ ngày lẻ |
| `warningAlertNoPassingGoingInId` | Bắt đầu cấm vượt |
| `warningAlertNoPassingGoingOutId` | Hết cấm vượt |
| `warningAlertPenaltyCameraId` | Camera phạt nguội |
| `warningAlertRailwayId` | Đường sắt |
| `warningAlertResidentialAreaGoingInId` | Vào khu dân cư |
| `warningAlertResidentialAreaGoingOutId` | Ra khỏi khu dân cư |
| `warningAlertSpeedLimitCameraId` | Camera tốc độ |
| `warningAlertSpeedLimitCheckpointId` | Trạm kiểm tra tốc độ |
| `warningAlertTollFeeETCId` | Thu phí ETC |
| `warningAlertTollFeeNonETCId` | Trạm thu phí |
| `warningAlertTrafficCameraId` | Camera giao thông |
| `warningAlertTunnelId` | Hầm đường bộ |

The CarAsistant enum also has a normalized `speed_limit` code for the persistent speed-sign channel. It is not one of the 19 temporary-alert IDs above.

## 6. Source-specific clues from the APK's Vietnamese help text

- **VietMap:** source ID `widget`; UI text says `Kết nối widget VietMap`; the status text includes `Chưa kết nối widget`. No Android AppWidget receiver/provider implementation is present in this Full APK.
- **Gofa:** source ID `z23e037d98`; source selection exists, but the acquisition implementation is not present in this APK.
- **Waze:** source ID `zda617cedc`; help text asks for the package name of the installed Waze mod. The acquisition implementation is not present in this APK.
- **Waze HLP:** source ID `z9e4343e4b`; help text explicitly describes enabling the Waze-mod HUD option `WiFi WebSocket`, host `127.0.0.1`, port `8765`, path `/hlp`, and says it does not require Accessibility or Bluetooth. The WebSocket message schema is not included in the APK.
- **WYN:** source ID `wyn`. The Full APK contains the selector and display mapping, but no WYN-specific provider, Flutter hook, or source protocol.

The resource string `Bản cài CarStream Control chưa đóng gói vào app này.` confirms the separate Control installation is outside this supplied APK. Its binary is not present in the supplied artifact set, so its private source transports cannot be copied from this file.

## 7. CarAsistant implementation in this branch

The branch ports the recoverable structure into these components:

- `TrafficWarningSource`: stable CarStream source IDs, with CarStream itself omitted.
- `TrafficWarningType`: the 19 recovered temporary-alert IDs plus the persistent `speed_limit` channel.
- `TrafficWarningSourceSettings`: independent SharedPreferences toggles for WYN, VietMap Widget, Gofa, Waze, and Waze HLP.
- `WarningStateRepository`: per-source persistent speed sign and temporary alert; disabling one source clears only that source's state.
- `TrafficWarningEngine`: normalized publication API and aggregate state. Unknown warning IDs are rejected, not guessed.
- `MediaSessionBridge`: generic source publication/clear entry points. Existing WYN entry points remain for compatibility.
- `V10SettingsActivity`: multi-source toggles and explicit notes about the source transports recovered from the APK.

The repository keeps a separate persistent speed-sign bitmap and temporary-alert bitmap for each enabled source. The newest active temporary alert wins; when that source clears, the next still-active temporary alert is selected, otherwise the latest persistent speed sign is restored.

## 8. Agreed Media card and Android Auto widget output

The existing CarAsistant media path already has the correct two output channels:

1. Media card artwork uses `MediaCardArtworkComposer.composeCard(...)`: top one-third is the active warning image; bottom two-thirds is the original media artwork. If no warning exists, the original media artwork is returned unchanged.
2. The mirrored MediaSession `METADATA_KEY_DISPLAY_ICON` uses warning-only artwork. It never substitutes the media artwork into the widget warning slot; if no warning exists, the warning icon is omitted.
3. `METADATA_KEY_ALBUM_ART` and `METADATA_KEY_ART` continue to carry the composed card artwork.
4. The existing title, artist, playback state, transport controls, five-second media checkpoint, autoplay, V10 projection and steering-wheel handler are outside this change.

## 9. What this APK cannot prove

The supplied Full APK does not contain the Control-side source adapters or the source payload schemas. Therefore this branch can port the complete *recoverable HUD/source catalogue and media-artwork architecture*, but it cannot honestly claim live data ingestion for VietMap, Gofa, Waze, or Waze HLP from this APK alone. Waze HLP's endpoint is known, but its frame format and message schema are not.

No physical POCO F1 / Honda BR-V / Android Auto test is claimed by this reverse-engineering report.
