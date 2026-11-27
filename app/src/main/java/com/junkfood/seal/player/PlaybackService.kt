package com.junkfood.seal.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.junkfood.seal.MainActivity
import com.junkfood.seal.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Plays the library as one continuous queue: every track in play order, so finishing a
 * piece runs straight on into the next one. Collapsing pieces is purely a matter of how
 * the list is drawn; the queue underneath is always the full track list.
 *
 * Driven through custom session commands ([PlayerClient] sends them) rather than by
 * controllers building the queue themselves, so the widget and the app share one code
 * path and neither has to ship hundreds of media items over the session.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "PlaybackService"

        const val COMMAND_PLAY_TRACK = "sealsync.PLAY_TRACK"
        const val COMMAND_PLAY = "sealsync.PLAY"
        const val COMMAND_TOGGLE = "sealsync.TOGGLE"
        const val COMMAND_NEXT = "sealsync.NEXT"
        const val COMMAND_PREVIOUS = "sealsync.PREVIOUS"
        const val COMMAND_SEEK = "sealsync.SEEK"
        const val EXTRA_VIDEO_ID = "video_id"
        /** Where to seek, as a fraction of the current track from 0 to 1. */
        const val EXTRA_FRACTION = "fraction"

        val CUSTOM_COMMANDS = listOf(
            COMMAND_PLAY_TRACK, COMMAND_PLAY, COMMAND_TOGGLE, COMMAND_NEXT, COMMAND_PREVIOUS,
            COMMAND_SEEK,
        )

        /** Restarting the track is more useful than going back once this much has played. */
        private const val RESTART_THRESHOLD_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queueMutex = Mutex()
    private var tickerJob: Job? = null

    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null

    /** The track ids the player's queue currently holds, in order. */
    private var queuedIds: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(playerListener)

        session = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_PLAYER)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_seal)
            }
        )

        // Keeps the queue in step with the library: a sync that adds or deletes files
        // changes the track list while something may be playing.
        scope.launch {
            // Keyed on the whole track list, so a regroup that only renames parts still
            // reaches the notification's titles.
            PlayerLibrary.state
                .distinctUntilChangedBy { it.loaded to it.tracks }
                .collect { if (it.loaded) syncQueue(it) }
        }
        scope.launch {
            runCatching { PlayerLibrary.ensureLoaded() }
                .onFailure { Log.e(TAG, "onCreate: library failed to load", it) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while music plays keeps it playing; while paused there is
        // nothing to keep the service alive for.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            saveProgress()
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveProgress()
        publishStatus(isPlayingOverride = false)
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // --- Queue ---

    private fun mediaItemFor(track: Track, piece: Piece?): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.videoId)
            .setUri(track.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(
                        if (piece == null || piece.isSingle) track.title
                        else piece.partLabels[piece.tracks.indexOf(track)]
                    )
                    .setArtist(track.artist)
                    .setAlbumTitle(piece?.title?.takeIf { piece.tracks.size > 1 })
                    .setSubtitle(piece?.title?.takeIf { piece.tracks.size > 1 })
                    .build()
            )
            .build()

    /**
     * Makes the player's queue match [library] without interrupting what is playing.
     *
     * When the current track is still in the library, everything around it is removed and
     * re-added, which leaves the current item and its playback position untouched. Only
     * when the current track itself is gone does the queue get replaced outright.
     */
    private suspend fun syncQueue(library: LibraryState) = queueMutex.withLock {
        val ids = library.tracks.map { it.videoId }
        val items = library.tracks.map { mediaItemFor(it, library.pieceOf(it.videoId)) }

        if (player.mediaItemCount == 0 || queuedIds.isEmpty()) {
            if (items.isEmpty()) return@withLock
            val resumeId = NowPlaying.status.value.videoId
            val index = library.indexOf(resumeId).coerceAtLeast(0)
            val position = if (library.indexOf(resumeId) >= 0) NowPlaying.status.value.positionMs else 0L
            player.setMediaItems(items, index, position)
            player.prepare()
            queuedIds = ids
            publishStatus()
            return@withLock
        }

        if (ids == queuedIds) {
            // Same tracks, but titles may have changed after grouping.
            refreshMetadata(items)
            return@withLock
        }

        val currentId = player.currentMediaItem?.mediaId
        val newIndex = ids.indexOf(currentId)
        if (currentId != null && newIndex >= 0) {
            val current = player.currentMediaItemIndex
            if (current + 1 < player.mediaItemCount) {
                player.removeMediaItems(current + 1, player.mediaItemCount)
            }
            if (current > 0) player.removeMediaItems(0, current)
            player.addMediaItems(0, items.subList(0, newIndex))
            player.addMediaItems(items.subList(newIndex + 1, items.size))
        } else if (items.isNotEmpty()) {
            val fallback = player.currentMediaItemIndex.coerceAtMost(items.size - 1)
            player.setMediaItems(items, fallback, 0L)
            player.prepare()
        } else {
            player.clearMediaItems()
        }
        queuedIds = ids
        publishStatus()
    }

    /** Same URI, so ExoPlayer swaps the item in place even when it is the one playing. */
    private fun refreshMetadata(items: List<MediaItem>) {
        items.forEachIndexed { i, item ->
            if (i >= player.mediaItemCount) return@forEachIndexed
            if (player.getMediaItemAt(i).mediaMetadata != item.mediaMetadata) {
                player.replaceMediaItem(i, item)
            }
        }
    }

    private suspend fun ensureQueue() {
        PlayerLibrary.ensureLoaded()
        if (player.mediaItemCount == 0) syncQueue(PlayerLibrary.state.value)
    }

    // --- Commands ---

    private suspend fun playTrack(videoId: String) {
        ensureQueue()
        val index = queuedIds.indexOf(videoId)
        if (index < 0) {
            Log.w(TAG, "playTrack: $videoId is not in the queue")
            return
        }
        player.seekTo(index, 0L)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    private suspend fun toggle() {
        ensureQueue()
        if (player.isPlaying) player.pause() else resume()
    }

    private suspend fun resume() {
        ensureQueue()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0L)
        player.play()
    }

    private suspend fun next() {
        ensureQueue()
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    private suspend fun previous() {
        ensureQueue()
        if (player.currentPosition > RESTART_THRESHOLD_MS || !player.hasPreviousMediaItem()) {
            player.seekTo(0L)
        } else {
            player.seekToPreviousMediaItem()
        }
    }

    /**
     * A library service rather than a plain session service only so that System UI can
     * offer to resume playback: its media-resume check connects as a browser and requires
     * a root. The root is empty; nothing browses this library.
     */
    /**
     * Seeks within the current track. Leaves the play state as it was.
     *
     * On a cold start the file is not open yet and the player has no duration, so the one
     * read from the file's tags stands in. Seeking before the player is ready is fine; it
     * starts from there once it is. The position is saved at once, because a paused
     * service stops as soon as the controller lets go.
     */
    private suspend fun seek(fraction: Float) {
        ensureQueue()
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: PlayerLibrary.state.value.track(player.currentMediaItem?.mediaId)?.durationMs
            ?: return
        if (duration <= 0) return
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        saveProgress()
        publishStatus()
    }

    private val sessionCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = SettableFuture.create<LibraryResult<MediaItem>>().apply {
            set(
                LibraryResult.ofItem(
                    MediaItem.Builder()
                        .setMediaId("root")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MEDIA_TYPE_FOLDER_MIXED)
                                .build()
                        )
                        .build(),
                    // System UI asks for a "recent" root; returning one is what makes the
                    // resume card appear.
                    params,
                )
            )
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Only this app may use the custom commands. Other controllers, such as the
            // system media controls and Bluetooth, get the standard ones.
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            if (controller.packageName == packageName) {
                CUSTOM_COMMANDS.forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            // Completed only once the command has run. A controller that connects just to
            // send one command (the widget's) waits on this before releasing, and
            // releasing earlier could let the service stop mid-way through a cold start.
            val result = SettableFuture.create<SessionResult>()
            scope.launch {
                runCatching {
                    when (customCommand.customAction) {
                        COMMAND_PLAY_TRACK -> args.getString(EXTRA_VIDEO_ID)?.let { playTrack(it) }
                        COMMAND_PLAY -> resume()
                        COMMAND_TOGGLE -> toggle()
                        COMMAND_NEXT -> next()
                        COMMAND_PREVIOUS -> previous()
                        COMMAND_SEEK -> seek(args.getFloat(EXTRA_FRACTION, 0f))
                        else -> Unit
                    }
                }.onFailure { Log.e(TAG, "command ${customCommand.customAction} failed", it) }
                result.set(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return result
        }

        /**
         * Lets the system resume playback after the service was stopped, for example from
         * the media controls or a Bluetooth play button.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Async because a Bluetooth play button can cold-start the process, and the
            // library has to load before there is anything to resume.
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                runCatching { PlayerLibrary.ensureLoaded() }
                val library = PlayerLibrary.state.value
                val resumeId = NowPlaying.status.value.videoId
                val index = library.indexOf(resumeId)
                val items = library.tracks.map { mediaItemFor(it, library.pieceOf(it.videoId)) }
                queuedIds = library.tracks.map { it.videoId }
                result.set(
                    MediaSession.MediaItemsWithStartPosition(
                        items,
                        index.coerceAtLeast(0),
                        if (index >= 0) NowPlaying.status.value.positionMs else 0L,
                    )
                )
            }
            return result
        }
    }

    // --- State ---

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                )
            ) {
                publishStatus()
            }
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED
                )
            ) {
                saveProgress()
            }
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                if (player.isPlaying) startTicker() else tickerJob?.cancel()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // One unreadable file should not stop the rest of the queue.
            Log.e(TAG, "onPlayerError: ${player.currentMediaItem?.mediaId}", error)
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                publishStatus()
                delay(1_000)
            }
        }
    }

    private fun publishStatus(isPlayingOverride: Boolean? = null) {
        val id = player.currentMediaItem?.mediaId ?: NowPlaying.status.value.videoId
        NowPlaying.publish(
            NowPlaying.Status(
                videoId = id,
                isPlaying = isPlayingOverride ?: player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            )
        )
    }

    private fun saveProgress() {
        val id = player.currentMediaItem?.mediaId ?: return
        NowPlaying.persist(id, player.currentPosition.coerceAtLeast(0L))
    }
}
