package com.junkfood.seal.player

import android.content.Context

/**
 * What tapping a row does, shared by the player screen and the widget so they behave
 * the same.
 */
object PlayerActions {

    /**
     * A collapsed piece expands and starts from its first track. If the current track is
     * already inside it, it expands and plays on from there instead, because restarting a
     * piece the user is in the middle of would be the wrong guess. An expanded piece
     * collapses and playback carries on.
     */
    fun onPieceClicked(context: Context, piece: Piece) {
        if (piece.isSingle) {
            PlayerClient.playTrack(context, piece.tracks.first().videoId)
            return
        }
        if (PlayerLibrary.isExpanded(piece.id)) {
            PlayerLibrary.setExpanded(piece.id, false)
            return
        }
        PlayerLibrary.setExpanded(piece.id, true)
        val current = NowPlaying.status.value
        when {
            piece.tracks.none { it.videoId == current.videoId } ->
                PlayerClient.playTrack(context, piece.tracks.first().videoId)

            !current.isPlaying -> PlayerClient.play(context)
        }
    }

    fun onTrackClicked(context: Context, videoId: String) =
        PlayerClient.playTrack(context, videoId)
}
