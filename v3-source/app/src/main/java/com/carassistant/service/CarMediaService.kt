package com.carassistant.service

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures

class CarMediaService : MediaLibraryService() {
    private var player: ExoPlayer? = null
    private var session: MediaLibraryService.MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        session = MediaLibraryService.MediaLibrarySession.Builder(
            this,
            player!!,
            LibraryCallback()
        ).setId("CarAssistantSession").build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? = session

    override fun onDestroy() {
        session?.release()
        player?.release()
        session = null
        player = null
        super.onDestroy()
    }

    private class LibraryCallback : MediaLibraryService.MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ) = Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem.Builder()
                    .setMediaId("root")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Car Assistant")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build(),
                params
            )
        )

        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ) = Futures.immediateFuture(
            LibraryResult.ofItemList(
                if (parentId == "root") ImmutableList.of(testItem()) else ImmutableList.of(),
                params
            )
        )

        private fun testItem(): MediaItem = MediaItem.Builder()
            .setMediaId("test-audio")
            .setUri(Uri.parse("android.resource://com.carassistant/raw/test_audio"))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Car Assistant Test")
                    .setArtist("Phase A - Offline Test")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
}
