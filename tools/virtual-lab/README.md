# CarAsistant Virtual Lab

This directory documents the automated CI test environments used by
`.github/workflows/carassistant-virtual-lab.yml`.

## Phone-side emulator profile

- Android emulator: API 31 / Android 12, Google APIs, x86_64
- Screen override: 1080 x 2246
- Density override: 440 dpi
- APK: the actual `v10-source/app` debug APK
- Checks: JUnit parser tests, Gradle APK assembly, install, launcher startup,
  process liveness, fatal crash signatures, logcat, display dumpsys, screenshot.

The profile approximates the POCO F1's screen dimensions and Android 12 API 31 baseline, using 440 dpi as a starting logical-density override.
It is **not** a full POCO F1 hardware/ROM clone: the emulator CPU is x86_64, its
vendor/system image is not PixelOS for beryllium, and it does not include Magisk or
LSPosed hooks. API level and screen overrides do not recreate Snapdragon 845 / Adreno
630 hardware or the real device's privileged system services.

## Car-screen emulator profile

- Android emulator: API 31 / Android 12, Google APIs, x86_64
- Screen override: 1280 x 720, 160 dpi
- Probe: attempt to launch the real `V10CarActivity` and capture its activity stack,
  display state, logcat and screenshot.

This is a landscape car-screen probe, not the Android Auto Desktop Head Unit. If
`V10CarActivity` cannot start without Gearhead/Android Auto host services, the
job marks the result as INCONCLUSIVE and uploads the diagnostics instead of claiming
that projection has passed.

## What CI can and cannot prove

CI can prove source compilation, parser behavior, APK packaging, basic Android
installation/startup, and can preserve reproducible logs/screenshots for regressions.

CI cannot fully prove:
- Android Auto host negotiation / projection protocol.
- Root shell and LSPosed hooks inside `system_server`.
- The actual third virtual display behavior on the POCO F1 MIUI build.
- Google Maps staying visible while a real music app opens on a hidden display.
- Physical steering-wheel key events, device-specific multitouch injection, or real
  audio output/playback.

Those require either a compatible Android Auto head-unit host with the phone-side
ROM/hooks or a physical-device test. Never treat a hostless landscape emulator as
proof of real Android Auto projection.

## Run it

Open the GitHub Actions workflow **CarAsistant Virtual Lab** and choose
**Run workflow**. The workflow also runs for pushes to
`voice-assistant-v2-commands-settings` and pull requests into supported base branches.

Download the artifacts:
- `carassistant-unit-test-report`
- `carassistant-virtual-lab-diagnostics`
- `carassistant-car-display-diagnostics`
- `carassistant-debug-apk`
