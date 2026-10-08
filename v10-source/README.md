# AACast Doudou

[English](README.md) | [Tiếng Việt](README.vi.md)

Split the **Android Auto** screen into 2–3 panes, each running a real Android app
(Google Maps, VietMap, YouTube…), built on **VirtualDisplay + root shell + Xposed injection**.

| | |
|---|---|
| Package | `com.carassistant.v10` |
| Version | 1.0.0 (versionCode 3) |
| minSdk / targetSdk / compileSdk | 29 / 36 / 36 |
| Android permissions | **none** — all privileged operations go through `su` |
| Native libs | none |
| Release APK size | ~390 KB |

> **Warning**: this project interferes with the Android Auto projection flow and requires root.
> Read [Requirements](#1-requirements) and [License & Disclaimer](#8-license--disclaimer)
> carefully before installing.

---

## Demo

<img src="docs/demo.jpg" alt="AACast Doudou running on Android Auto via Desktop Head Unit — two panes side by side" width="420">

*Split screen on Android Auto (Desktop Head Unit): two panes side by side — OMNI navigation + Google Maps.*

---

## 1. Features

- **6 layout modes**: 2 columns, 2 rows, 3 columns, 3 rows, 1 large left + 2 right, 1 large top + 2 bottom.
- **Per-pane app selection** from installed launchable apps, with accent-insensitive search.
- **Drag the ⋮ handle** to swap apps between panes; **drag the divider** to resize panes.
- **Cockpit HUD** on the phone: live preview of the car screen, layout switching, app assignment,
  root check and quick Android Auto restart.
- **Auto-hides the Android Auto dock** while split screen is visible and restores it on exit (dock lease).
- **Expands the activity region** on the Coolwalk layout (hides the Dashboard while in use).
- No third-party dependencies; pure Java logic covered by unit tests (`LayoutMath`, `TileOrder`).

---

## 2. Requirements

| Item | Requirement |
|---|---|
| Android | 10+ (API 29+) |
| Root | Magisk / KernelSU / APatch — `su` must be granted **permanently** to the app |
| Xposed | LSPosed, scope: **`android` (System Framework)** + **`com.google.android.projection.gearhead` (Android Auto)** |
| Android Auto | `com.google.android.projection.gearhead` installed on the phone (connect to a car head unit or use the Desktop Head Unit for testing) |

---

## 3. Build

### 3.1 Environment

- JDK 17
- Android SDK with Build-Tools + Platform 36 (`compileSdk 36`)
- Gradle wrapper is bundled — just use `./gradlew`.

### 3.2 Build commands

```bash
./gradlew :app:assembleDebug      # debug build — fast, for development
./gradlew :app:assembleRelease    # release build (signed if a keystore is present) — for daily use
./gradlew :app:testDebugUnitTest  # unit tests for layout math / tile order
```

> **DO NOT enable `minifyEnabled true` for release.** The bundled SDK libraries
> (`app/libs/aasdk-legacy.jar` + `aasdk-legacy.dex`) are pre-processed bytecode;
> running R8 on top of them can merge classes and trigger a
> `java.lang.InstantiationError` as soon as Android Auto hosts `CarActivity`.

### 3.3 Release signing

Release reads `keystore.properties` in the project root (this file is **not** committed):

```properties
storeFile=your-keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without `keystore.properties`, Gradle still builds but the APK is **not signed**.
The `distRelease` task copies the APK to `dist/` along with a SHA-256 file.

### 3.4 Bundled libraries

`app/libs/` contains the artifacts needed to build (committed so a fresh clone builds right away):

| File | Purpose |
|---|---|
| `aasdk-legacy.jar` | Compile-time stubs for the legacy Android Auto SDK (`CarActivity`, `CarActivityService`…) — no longer published on Maven |
| `aasdk-legacy.dex` | Matching SDK bytecode, merged straight into the APK |
| `xposed-api-82.jar` | Xposed API (`compileOnly`) — provided at runtime by LSPosed |

To re-download the Xposed API: `tools/fetch-xposed-api.sh`.

### 3.5 Verifying the APK

```bash
tools/verify-apk.sh app/build/outputs/apk/release/app-release.apk
```

The script checks: `assets/xposed_init`, required classes **defined** in the dex
(via `dexdump`), the Xposed API **not** being packaged, the manifest
(package/label/service/provider), the signature and hashes.

---

## 4. Install & activate

### 4.1 Install the APK

> **Recommended**: install via [**KingInstaller**](https://github.com/fcaronte/KingInstaller/releases)
> so Android Auto sees the app as installed by the Play Store.
> Step-by-step guide: [**INSTALL-KINGINSTALLER.vi.md**](INSTALL-KINGINSTALLER.vi.md).

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### 4.2 Enable the LSPosed module

**LSPosed** → Modules → **AACast Doudou** → enable the module and select:

- `android` — System Framework
- `com.google.android.projection.gearhead` — Android Auto

Then reboot the phone.

### 4.3 Grant root

Open **AACast Doudou** on the phone and grant **permanent root**.

### 4.4 Usage

1. Choose a split layout and assign an app to each pane.
2. Connect the phone to Android Auto.
3. Open **AACast Doudou** on the car screen.
4. Tap **Start split screen**.
5. On the car screen:
   - Tap **⋮** to change the app.
   - Drag **⋮** to another pane to move it.
   - Drag the middle bar to resize.

> If you prefer not to reboot, `adb shell am force-stop com.google.android.projection.gearhead`
> reinstalls the hooks.

---

## 5. Architecture

```
gearhead (Android Auto) ──host──> AACastCarActivity (app process)
                                     │
                     ┌───────────────┴────────────────┐
                     │  RootView (3 × PaneView)        │
                     │  each PaneView = TextureView    │
                     └──────────┬─────────────────────┘
                                │ Surface
                     VirtualDisplay "AACast Doudou 1..3"
                                ▲
                                │ su: am start --display <id>
                     child app (Maps / VietMap / YouTube…)
```

- **Car App**: `AACastCarService` (extends `CarActivityService`) registers with Android Auto
  via `CATEGORY_PROJECTION`.
- **VirtualDisplay**: each pane creates a virtual display (`flags = 10` = PRESENTATION|OWN_CONTENT_ONLY,
  density 160) mirrored into a `TextureView` in the layout.
- **Root shell**: a persistent `su` session (`RootShellSession`) runs `am start --display`,
  `input touchscreen -d <id> tap|swipe`, `input -d <id> keyevent 4`.
- **Xposed** (`aabridge/AACastBridge` is the entry point, declared in `assets/xposed_init`):

  | Process | Hook | What it does |
  |---|---|---|
  | `android` | `VirtualDisplayDevice#getDisplayDeviceInfoLocked` | adds `FLAG_OWN_DISPLAY_GROUP` + `FLAG_ALWAYS_UNLOCKED` |
  | `android` | `PhoneWindowManager#shouldDispatchInputWhenNonInteractive` | allows input while the phone screen is off |
  | `android` | `InputManagerService#injectInputEventToTarget` | re-injects by `displayId` |
  | gearhead | `getInstallerPackageName`, `InstallSourceInfo.*` | spoofs `com.android.vending` |
  | gearhead | AA's package allow-list class | returns `true` for this app's package |
  | gearhead | `Application#attach`, `WindowManagerGlobal#addView` | hides the dock + covers the `GhFacetBar` display + expands the activity region |

- **Dock lease**: `DockStateProvider` (`content://com.carassistant.v10.launcher.dock/state`)
  — the app writes `visible_until = now + 3000` every second; gearhead reads it and hides/restores the dock.
- All hooks are **fail-open**: when a class/method is missing they are simply skipped and never break the host.

### Source layout

```
app/src/main/java/com/carassistant/v10/
├── AACastCarActivity.java     # CarActivity — UI on the car screen
├── AACastCarService.java      # registers the Car App with Android Auto
├── AACastDisplay.java         # identifies this app's virtual displays (shared by app + hooks)
├── RootView.java              # root view: panes, overlays, prefs, lifecycle
├── PaneView.java              # one pane: TextureView + VirtualDisplay + child app
├── PaneContainer.java         # lays out panes/dividers, drag & drop swapping
├── DividerView.java           # draggable divider handle
├── LayoutMath.java            # geometry for the 6 layout modes (unit tested)
├── LayoutRects.java           # resulting pane/divider rects
├── TileOrder.java             # pane ordering (unit tested)
├── RootShellSession.java      # persistent `su` session
├── DockStateProvider.java     # dock lease IPC with gearhead
├── LeaseTicker.java           # writes the lease every second
├── MainActivity.java          # Cockpit HUD on the phone
├── AppCatalog.java / AppEntry.java
├── aabridge/AACastBridge.java # Xposed entry point
└── hook/                      # system_server + gearhead hooks
```

---

## 6. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Pane shows "Root access is required…" | `su` did not return UID 0 | grant permanent root in your root manager |
| Pane shows "Cannot open X" | `am start` failed (renamed activity, restricted app) | check the `Launch failed:` log — `am` stderr is included |
| Pane shows "X is not installed" | Activity not `exported`/`enabled` or missing permission | try another app; check that app's `ActivityInfo` |
| Black/white pane without errors | child app refuses to render on a virtual display (DRM, screenshot protection) | limitation of the child app — try another one |
| Touch does not work | `[AACast Input]` hook not installed | check hook logs; make sure LSPosed scope `android` is enabled |
| AA dock still visible | dock resource name changed between AA versions | look for `No supported bottom dock found` → update `DOCK_NAMES` in `GearheadDockPatcher` |
| AA does not list the app | installer spoof does not match the AA version | check `[AACast Dock]` logs; try installing the app from the Play Store |
| Dock does not come back after exiting | lease did not expire | check `Launcher lease ended`; inspect `AACastCarActivity` lifecycle |
| App crashes on launch | R8 stripped entries / missing SDK | run `tools/verify-apk.sh` |
| App stops working after an Android Auto update | AA's obfuscated class names changed | hooks are fail-open so the app still launches; update the class constants under `hook/` |

Useful logs:

```bash
adb logcat -v time -s AACastDoudou AACastDock "AACast Display" "AACast Input"
adb shell dumpsys display | grep -A3 "AACast Doudou"
```

---

## 7. Contributing

Contributions are welcome:

1. Fork the repo and create a branch for your feature/fix.
2. Keep the existing style: **plain Java, no third-party dependencies**, concise comments,
   keep logic out of Views where possible.
3. Add/update unit tests for `LayoutMath` and `TileOrder` when changing that logic.
4. Run before opening a PR:

   ```bash
   ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
   ```

5. Open a Pull Request describing the change and, if you tested on a device, the device
   and Android Auto version.

When reporting bugs, include: full `logcat`, `dumpsys display`, Android Auto version and ROM/Android version.

---

## Community

Questions, ideas and general discussion are welcome in the Facebook group:

[**Join the Facebook group →**](https://www.facebook.com/groups/2327824764621369)

---

## 8. License & Disclaimer

- The source code is intended for **learning, research and personal use**.
- Interfering with the Android Auto projection flow and hiding gearhead UI may violate
  Google's Terms of Service. You are responsible for how you use it.
- The libraries in `app/libs` are old Android Auto SDK classes (property of Google) bundled
  only so the project can be built; they are not a product of this project.
- This project has not chosen an open-source license yet. Please contact the author before
  reusing or redistributing it.

---

## ☕ Support

If AACast Doudou saves you time, you can buy the author a coffee via PayPal.
Thank you for your support!

[![Buy me a coffee](https://img.shields.io/badge/PayPal-Buy%20me%20a%20coffee-00457C?logo=paypal&logoColor=white)](https://paypal.me/daihieptn)
