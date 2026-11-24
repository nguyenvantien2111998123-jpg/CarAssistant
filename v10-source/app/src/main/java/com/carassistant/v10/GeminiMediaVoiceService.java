package com.carassistant.v10;

import android.content.Context;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.provider.MediaStore;
import android.service.media.MediaBrowserService;
import android.text.TextUtils;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * Exposes CarAsistant's existing mirrored MediaSession to Android Auto's
 * media-browser/voice-action pipeline. It deliberately reuses the one existing
 * session; it does not create a second session or alter steering/media controls.
 */
public final class GeminiMediaVoiceService extends MediaBrowserService {
    private static final String TAG = "AACastGeminiMediaVoice";
    private static final String ROOT_ID = "carassistant_root";
    private static final String PREFS = "gemini_media_voice";
    static final String PREF_REQUEST_ID = "pending_request_id";
    static final String PREF_QUERY = "pending_query";
    static final String PREF_PROVIDER = "pending_provider";

    private static volatile GeminiMediaVoiceService instance;
    private MediaSession attachedSession;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        MediaSession session = MediaSessionBridge.getPublishedMirrorSession();
        if (session != null) attachSession(session);
        Log.i(TAG, "MediaBrowserService created; waiting for CarAsistant mirror session if needed");
    }

    static void onMirrorSessionReady(MediaSession session) {
        GeminiMediaVoiceService service = instance;
        if (service != null && session != null) service.attachSession(session);
    }

    private synchronized void attachSession(MediaSession session) {
        if (session == null || attachedSession == session) return;
        try {
            setSessionToken(session.getSessionToken());
            attachedSession = session;
            Log.i(TAG, "Android Auto media browser now shares the existing CarAsistant MediaSession");
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not publish existing MediaSession to Android Auto", error);
        }
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        if (attachedSession == null) {
            MediaSession session = MediaSessionBridge.getPublishedMirrorSession();
            if (session != null) attachSession(session);
        }
        if (attachedSession == null) {
            Log.w(TAG, "Rejecting media browser root until the existing mirror session is ready");
            return null;
        }
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override public void onLoadChildren(String parentId, Result<List<MediaItem>> result) {
        // CarAsistant mirrors external players rather than owning a browsable music catalogue.
        // Voice search is handled by the shared MediaSession callback below.
        result.sendResult(Collections.emptyList());
    }

    static void enqueueVoiceSearch(Context context, String query, Bundle extras) {
        if (context == null) return;
        String resolved = query == null ? "" : query.trim();
        String focus = "";
        String artist = "";
        String title = "";
        if (extras != null) {
            try {
                focus = extras.getString(MediaStore.EXTRA_MEDIA_FOCUS, "");
                artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST, "");
                title = extras.getString(MediaStore.EXTRA_MEDIA_TITLE, "");
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not read voice-search extras", error);
            }
        }
        if (TextUtils.isEmpty(resolved)) {
            if (!TextUtils.isEmpty(artist)) resolved = artist.trim();
            else if (!TextUtils.isEmpty(title)) resolved = title.trim();
        }
        if (TextUtils.isEmpty(resolved)) {
            Log.w(TAG, "Ignoring empty Android Auto voice-search query");
            return;
        }
        long requestId = System.currentTimeMillis();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(PREF_REQUEST_ID, requestId)
                .putString(PREF_QUERY, resolved)
                .putString(PREF_PROVIDER, "YouTube")
                .putString("search_focus", focus == null ? "" : focus)
                .putString("search_artist", artist == null ? "" : artist)
                .putString("search_title", title == null ? "" : title)
                .commit();
        Log.i(TAG, "VOICE_SEARCH_ACCEPTED id=" + requestId
                + " query=" + resolved + " focus=" + focus
                + " artist=" + artist + " title=" + title);
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        attachedSession = null;
        super.onDestroy();
    }
}
