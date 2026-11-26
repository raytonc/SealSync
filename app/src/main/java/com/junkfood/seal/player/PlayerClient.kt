package com.junkfood.seal.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken

/**
 * Sends one command to [PlaybackService].
 *
 * Each call connects a controller, sends, waits for the service to finish the command,
 * and disconnects. The screen and the widget only need to issue commands, because they
 * read state from [NowPlaying] and [PlayerLibrary] in-process, so there is no
 * long-lived controller whose binding would keep the service alive while nothing plays.
 */
object PlayerClient {

    private const val TAG = "PlayerClient"

    fun playTrack(context: Context, videoId: String) = send(
        context,
        PlaybackService.COMMAND_PLAY_TRACK,
        Bundle().apply { putString(PlaybackService.EXTRA_VIDEO_ID, videoId) },
    )

    fun play(context: Context) = send(context, PlaybackService.COMMAND_PLAY)

    fun toggle(context: Context) = send(context, PlaybackService.COMMAND_TOGGLE)

    fun next(context: Context) = send(context, PlaybackService.COMMAND_NEXT)

    fun previous(context: Context) = send(context, PlaybackService.COMMAND_PREVIOUS)

    /** Must be called on the main thread; MediaController is bound to the calling looper. */
    private fun send(context: Context, action: String, args: Bundle = Bundle.EMPTY) {
        val app = context.applicationContext
        val mainExecutor = ContextCompat.getMainExecutor(app)
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val controller = runCatching { future.get() }.getOrElse {
                Log.e(TAG, "send: could not connect for $action", it)
                return@addListener
            }
            val result = controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
            result.addListener({ controller.release() }, mainExecutor)
        }, mainExecutor)
    }
}
