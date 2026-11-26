# Media Im Lặng

Standalone Android media app module: `:media-im-lang`.

## Intended behavior
- Publishes a native Android `MediaBrowserService` / `MediaSession` so Android Auto can discover it as a media source.
- CarAsistant sends an explicit package-scoped broadcast when its Android Auto connection monitor detects connect/disconnect.
- When no other active session is in `STATE_PLAYING`, the app creates a short MP4 in its private app files directory. The MP4 contains AAC samples filled with digital zeroes, and MediaPlayer output is additionally muted at volume 0.
- Plays the clip for about 3 seconds, then pauses the session. A real media session takes priority immediately.
- The app is isolated from CarAsistant's steering-wheel and existing media restore implementation.

## One-time setup on the phone
1. Install Media Im Lặng APK.
2. Open it once and enable **Notification Access** for Media Im Lặng. Android requires this user-granted access to inspect other apps' MediaSessions.
3. Confirm Android Auto lists Media Im Lặng as a media app. Android Auto may need a reconnect after first installation.

## Important platform constraints
- Notification Access cannot be silently granted by an ordinary app; user action is mandatory.
- The connect broadcast is sent by the CarAsistant branch only. Android may still reject a background foreground-service launch on some ROMs; validate on the POCO F1 / Android 12 in the car.
- A paused MediaSession is not guaranteed to remain pinned in every Android Auto UI version. The service keeps its browser/session active, but actual card persistence is host-controlled.
- The MP4 is generated on first use by the device AAC encoder rather than stored as a prebuilt binary asset. If the device has no AAC encoder, generation fails and the service logs the error.
