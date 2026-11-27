package com.junkfood.seal.player

import com.junkfood.seal.util.PLAYER_LAST_POSITION
import com.junkfood.seal.util.PLAYER_LAST_VIDEO_ID
import com.junkfood.seal.util.PreferenceUtil.getLong
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateLong
import com.junkfood.seal.util.PreferenceUtil.updateString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What is playing right now, published by [PlaybackService] for the player screen and the
 * widget. Both live in the same process as the service, so a plain StateFlow is enough
 * and neither needs a MediaController just to draw itself.
 *
 * While the service is not running, this holds the last track played, restored from
 * preferences, so the widget still shows where playback will resume.
 */
object NowPlaying {

    data class Status(
        val videoId: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
    )

    private val mutableStatus = MutableStateFlow(
        Status(
            videoId = PLAYER_LAST_VIDEO_ID.getString().ifEmpty { null },
            positionMs = PLAYER_LAST_POSITION.getLong(),
        )
    )
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    /**
     * Called when the track or the play state changes, so the widget can redraw. The
     * argument says whether the track changed, which also moves the list's highlight.
     */
    @Volatile
    var onChanged: ((trackChanged: Boolean) -> Unit)? = null

    /**
     * Called as the position moves, so the widget's progress bar and times can follow.
     * Throttled: each call redraws the widget header across a process boundary.
     */
    @Volatile
    var onProgress: (() -> Unit)? = null

    /** How often the widget's progress redraws while playing. */
    private const val PROGRESS_INTERVAL_MS = 5_000L
    private var lastProgressAt = 0L

    internal fun publish(status: Status) {
        val previous = mutableStatus.value
        mutableStatus.value = status
        val trackChanged = previous.videoId != status.videoId
        val stateChanged = previous.isPlaying != status.isPlaying
        val now = android.os.SystemClock.elapsedRealtime()
        // A jump of more than a couple of seconds is a seek, which should show at once.
        val jumped = kotlin.math.abs(status.positionMs - previous.positionMs) > 2_500
        if (trackChanged || stateChanged) {
            lastProgressAt = now
            onChanged?.invoke(trackChanged)
        } else if (jumped || now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
            lastProgressAt = now
            onProgress?.invoke()
        }
    }

    internal fun persist(videoId: String?, positionMs: Long) {
        PLAYER_LAST_VIDEO_ID.updateString(videoId.orEmpty())
        PLAYER_LAST_POSITION.updateLong(positionMs)
    }
}
