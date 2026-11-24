package com.carassistant.v10;

import android.content.Context;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaSessionCompat;
import androidx.media.MediaBrowserServiceCompat;

import java.util.Collections;
import java.util.List;

/**
 * Android Auto-compatible media browser entry point. It wraps the already
 * existing platform MediaSession token; it never creates a second playback
 * session and never changes the active media/steering-wheel controller.
 */
public final class GeminiMediaVoiceService extends MediaBrowserServiceCompat {
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
        Log.i(TAG, "Compat MediaBrowserService created; existing mirror session="
                + (session != null));
    }

    static void onMirrorSessionReady(MediaSession session) {
        GeminiMediaVoiceService service = instance;
        if (service != null && session != null) service.attachSession(session);
    }

    private synchronized void attachSession(MediaSession session) {
        if (session == null || attachedSession == session) return;
        try {
            MediaSessionCompat.Token compatToken =
                    MediaSessionCompat.Token.fromToken(session.getSessionToken());
            if (compatToken == null) {
                Log.e(TAG, "Platform MediaSession token could not be wrapped");
                return;
            }
            setSessionToken(compatToken);
            attachedSession = session;
            Log.i(TAG, "Android Auto browser now shares the existing CarAsistant MediaSession");
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
            Log.w(TAG, "Rejecting media browser root: CarAsistant mirror MediaSession is not ready");
            return null;
        }
        Log.i(TAG, "onGetRoot client=" + clientPackageName + " uid=" + clientUid);
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override public void onLoadChildren(String parentId,
            Result<List<MediaBrowserCompat.MediaItem>> result) {
        // CarAsistant mirrors third-party players; it has no independent music catalogue.
        // Voice actions are delivered to the shared MediaSession callback.
        result.sendResult(Collections.emptyList());
    }

    static void enqueueVoiceSearch(Context context, String query, Bundle extras) {
        if (context == null) return;
        String resolved = query == null ? "" : query.trim();
        String focus = "";
        String artist = "";
        String title = "";
        String album = "";
        if (extras != null) {
            try {
                focus = extras.getString(MediaStore.EXTRA_MEDIA_FOCUS, "");
                artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST, "");
                title = extras.getString(MediaStore.EXTRA_MEDIA_TITLE, "");
                album = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM, "");
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not read voice-search extras", error);
            }
        }
        if (TextUtils.isEmpty(resolved)) {
            if (!TextUtils.isEmpty(artist)) resolved = artist.trim();
            else if (!TextUtils.isEmpty(title)) resolved = title.trim();
            else if (!TextUtils.isEmpty(album)) resolved = album.trim();
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
                .putString("search_album", album == null ? "" : album)
                .commit();
        Log.i(TAG, "VOICE_SEARCH_ACCEPTED id=" + requestId
                + " query=" + resolved + " focus=" + focus
                + " artist=" + artist + " title=" + title + " album=" + album);
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        attachedSession = null;
        super.onDestroy();
    }
}
