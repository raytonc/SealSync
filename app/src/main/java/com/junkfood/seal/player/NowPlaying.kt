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

    internal fun publish(status: Status) {
        val previous = mutableStatus.value
        mutableStatus.value = status
        val trackChanged = previous.videoId != status.videoId
        if (trackChanged || previous.isPlaying != status.isPlaying) onChanged?.invoke(trackChanged)
    }

    internal fun persist(videoId: String?, positionMs: Long) {
        PLAYER_LAST_VIDEO_ID.updateString(videoId.orEmpty())
        PLAYER_LAST_POSITION.updateLong(positionMs)
    }
}
