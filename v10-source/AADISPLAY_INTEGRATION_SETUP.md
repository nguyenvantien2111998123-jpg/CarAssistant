# AADisplay integration setup (baseline #225)

This development branch starts from stable commit `5d32799f2725def9944a2c8e05b3f6b6a2893a60`. It preserves the V10 projection architecture and does not register a second Android Auto media app. `main` is not changed or merged.

## Integrated in this pass

- Resize an existing VirtualDisplay when the projection surface geometry changes; keep the display/task identity and rebind the Surface in a deterministic order.
- Observe all Android Auto `LayoutInflater.inflate` overloads for the Coolwalk facet-bar layout. Resource ID/name checks, one pending launch at a time, cooldown and bounded retries prevent duplicate auto-open loops.
- Keep auto-open restricted to Android Auto's `:projection` process. Gearhead's internal launch method remains version-sensitive and is logged/fail-open if it cannot be found.
- Expose read-only V10 target/media-session route state to Gearhead/system callers.
- Consume only media metadata/title/artwork clicks when the active V10 projection target matches the live media session. Transport controls and native Android Auto media apps retain their original click behavior.
- Gate steering-wheel Next/Previous interception on that same matching route. Preserve native media-key dispatch when V10 is not the active media owner.
- Wire the V10 Home sidebar TASKS button to a rooted recent-task picker. It reads recent component names off the UI thread and opens a selected component through V10's projection path.
- Retain existing Back/Home/Recents receiver commands, root-targeted input, orientation hooks, keyboard layering, multitouch injection, media seek/double-Next logic, media task restoration and reconnect/lifecycle handling.
- Keep one APK build workflow and attach source SHA/build identity to the artifact.

## Required phone setup

1. Install the build APK on the rooted POCO F1.
2. In LSPosed, enable CarAsistant and scope it to Android Auto (`com.google.android.projection.gearhead`) and Android framework/system_server as required by the existing module.
3. Reboot after changing LSPosed scopes.
4. Grant Notification Access to **CarAsistant Media Controller**. Without a readable live session, route arbitration deliberately leaves Android Auto's native media behavior in place.
5. Keep Root permission available to CarAsistant. The TASKS picker and projection task restoration use the existing root shell.
6. Connect to Android Auto. Do not install or enable a separate CarAsistant Media app; the intended projection entry is CarAsistant V10 only.

## One-round test checklist (POCO F1 + Honda BR-V)

### Connection / projection lifecycle
- [ ] Connect Android Auto from a cold start; check whether V10 opens automatically.
- [ ] Disconnect/reconnect twice; no duplicate V10 sessions, frozen head unit or black projection surface.
- [ ] Open an app through V10, leave to Android Auto Home, then reopen V10.
- [ ] Verify projected content resizes to the head-unit's actual available area after reconnect/orientation changes.
- [ ] Verify Home/sidebar returns only on V10 Home and does not cover the projected app.

### Media card and steering wheel
- [ ] Start YouTube / YouTube Music / Zing MP3 / Spotify from V10 projection; tap the AA media title/artwork. It should restore the projected V10 task, not launch the player on the phone.
- [ ] Verify the AA media transport buttons continue controlling playback.
- [ ] Steering wheel: single Next, double Next (voice assistant), Previous, hold Next/Previous (seek), release.
- [ ] Open a separate media app directly in Android Auto, not via V10. Its metadata tap and steering-wheel keys must remain native.
- [ ] If no matching V10 media session exists, ensure taps/keys are not intercepted.

### Tasks, input and regression
- [ ] Open TASKS from V10 Home; verify the recent list appears and a selected task opens on the projection display.
- [ ] Back, Home and Recents control paths.
- [ ] Test one-finger touch, two-finger pinch/multitouch, swipe and keyboard search. Keyboard must remain above projected surfaces.
- [ ] Check Vietnamese voice commands, media search/autoplay, and that a media command does not replace an active navigation projection.
- [ ] Test portrait phone + landscape projection and forced-landscape toggle.

## Boundaries / fail-open behavior

- Real head-unit behavior cannot be proven by GitHub Actions. Gearhead's internal class/method names and media-card view IDs vary across Android Auto releases.
- AADisplay's H3/H4 phenotype-property hook is a configurable override mechanism, not a universal default. No property key/value overrides were specified for this integration, so arbitrary Gearhead/GMS phenotype values are not spoofed. Unknown properties remain untouched rather than risking Android Auto instability.
- The recent-task picker reads task component names from `dumpsys activity recents`; the Android version/OEM may format that output differently. If Root is unavailable or parsing fails, it reports the failure without stopping projection.
- Media seek requires a player that exposes seek controls. Auto-open and media restoration still require the real POCO F1 + Honda BR-V test before calling them verified.
