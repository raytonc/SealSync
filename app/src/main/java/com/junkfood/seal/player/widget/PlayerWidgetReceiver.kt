package com.junkfood.seal.player.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.junkfood.seal.App
import com.junkfood.seal.player.PlayerActions
import com.junkfood.seal.player.PlayerClient
import com.junkfood.seal.player.PlayerLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles every tap on the widget. Not exported: only the widget's own PendingIntents
 * reach it.
 */
class PlayerWidgetReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PlayerWidgetReceiver"

        const val ACTION_ROW = "com.junkfood.seal.player.widget.ROW"
        const val ACTION_TOGGLE = "com.junkfood.seal.player.widget.TOGGLE"
        const val ACTION_NEXT = "com.junkfood.seal.player.widget.NEXT"
        const val ACTION_PREVIOUS = "com.junkfood.seal.player.widget.PREVIOUS"
        const val ACTION_SEEK = "com.junkfood.seal.player.widget.SEEK"
        const val EXTRA_FRACTION = "fraction"

        const val EXTRA_PIECE_ID = "piece_id"
        const val EXTRA_VIDEO_ID = "video_id"
        /** Set by a part's thread line: collapse this piece and leave playback alone. */
        const val EXTRA_COLLAPSE_PIECE_ID = "collapse_piece_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_TOGGLE -> PlayerClient.toggle(app)
            ACTION_NEXT -> PlayerClient.next(app)
            ACTION_PREVIOUS -> PlayerClient.previous(app)
            ACTION_SEEK -> PlayerClient.seek(app, intent.getFloatExtra(EXTRA_FRACTION, 0f))
            ACTION_ROW -> {
                intent.getStringExtra(EXTRA_COLLAPSE_PIECE_ID)?.let {
                    PlayerLibrary.setExpanded(it, false)
                    return
                }
                intent.getStringExtra(EXTRA_VIDEO_ID)?.let {
                    PlayerActions.onTrackClicked(app, it)
                    return
                }
                val pieceId = intent.getStringExtra(EXTRA_PIECE_ID) ?: return
                // The process may have just been started by this tap, with nothing loaded.
                val pending = goAsync()
                App.applicationScope.launch(Dispatchers.Main) {
                    try {
                        runCatching { PlayerLibrary.ensureLoaded() }
                        val piece = PlayerLibrary.state.value.pieces.firstOrNull { it.id == pieceId }
                        if (piece == null) {
                            Log.w(TAG, "onReceive: piece $pieceId no longer exists")
                            PlayerWidgetProvider.refresh(app)
                        } else {
                            PlayerActions.onPieceClicked(app, piece)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
