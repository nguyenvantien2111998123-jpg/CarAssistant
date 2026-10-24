# AADisplay integration setup (baseline #225)

This branch is based on commit 5d32799f2725def9944a2c8e05b3f6b6a2893a60. It preserves the existing V10 projection/session architecture and does not register a second Android Auto media app.

## Integrated in this pass

- Resize an existing VirtualDisplay when its TextureView geometry changes, preserving the display/task identity.
- Make surface reattach and display teardown more deterministic; teardown detaches the producer before release.
- Expose read-only routing state from DockStateProvider: current V10 target and live media package.
- Route Android Auto media metadata taps only when the V10 projected target matches the live media session. Otherwise the native Android Auto click path remains untouched.
- Gate steering-wheel Next/Previous interception on the active V10 media route so independent Android Auto media apps retain their native controls.
- Add the development branch to the existing single APK workflow so a push runs unit tests and one debug APK build.

## Device setup checklist

1. Install the generated debug APK on the rooted test phone.
2. Enable the CarAsistant LSPosed module and scope it to Android Auto (com.google.android.projection.gearhead) and system framework/system_server as already required by the module setup.
3. Reboot after changing LSPosed scopes.
4. Grant Notification Access to CarAsistant Media Controller. Without it, the live media-route gate intentionally stays off and native Android Auto media clicks/keys are preserved.
5. Connect Android Auto and open V10 projection. Start a media app through V10, then test metadata tap, Next/Previous, double-Next voice, and press-and-hold seek.
6. Open a media app directly in Android Auto (not through V10) and verify its media card and steering-wheel controls still work normally.
7. Resize/reconnect Android Auto and verify the projected task survives without duplicate tasks or a black Surface.

## Important scope notes

- AutoOpen and internal Gearhead hooks remain version-sensitive; the workflow cannot prove they work on a specific head unit.
- Media-session controls depend on each player's PlaybackState/TransportControls support.
- Root-level multi-touch injection, forced display flags, installer-source hooks and media-card resource matching are retained from #225; this pass does not broaden signature/property spoofing to unspecified system properties.
- A build passing on GitHub Actions is not a substitute for a real POCO F1 + Honda BR-V Android Auto test.

## Expected routing behavior

| State | Metadata tap | Next/Previous |
|---|---|---|
| V10 projects the same app that owns the live media session | Open/restore V10 projected task | CarAsistant arbitration |
| No matching V10 media route | Android Auto native behavior | Android Auto/system native dispatch |
