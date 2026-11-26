package com.junkfood.seal.player.widget

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.junkfood.seal.R
import com.junkfood.seal.player.NowPlaying
import com.junkfood.seal.player.PlayerLibrary
import com.junkfood.seal.player.PlayerRow
import com.junkfood.seal.player.buildRows
import com.junkfood.seal.player.formatDuration
import kotlinx.coroutines.runBlocking

/** Supplies the widget's list rows: collapsed pieces, and the parts of expanded ones. */
class PlayerWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    companion object {
        /** The rows the widget last drew, shared so the provider can scroll to one. */
        @Volatile
        private var rows: List<PlayerRow> = emptyList()

        /** Row of [videoId], or of the collapsed piece containing it. */
        fun rowPositionOf(videoId: String): Int = rows.indexOfFirst { row ->
            when (row) {
                is PlayerRow.Single -> row.track.videoId == videoId
                is PlayerRow.Part -> row.track.videoId == videoId
                is PlayerRow.Header -> !row.expanded && row.piece.tracks.any { it.videoId == videoId }
            }
        }
    }

    private class Factory(private val context: Context) : RemoteViewsFactory {

        private var snapshot: List<PlayerRow> = emptyList()
        private var currentId: String? = null

        override fun onCreate() = Unit

        /** Runs on a binder thread, where blocking for the first load is allowed. */
        override fun onDataSetChanged() {
            runCatching { runBlocking { PlayerLibrary.ensureLoaded() } }
            snapshot = buildRows(PlayerLibrary.state.value.pieces, PlayerLibrary.expanded.value)
            rows = snapshot
            currentId = NowPlaying.status.value.videoId
        }

        override fun onDestroy() {
            snapshot = emptyList()
        }

        override fun getCount() = snapshot.size

        override fun getViewAt(position: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_player_row)
            val row = snapshot.getOrNull(position) ?: return views
            val fillIn = Intent()

            var indent = 0
            var isCurrent = false
            when (row) {
                is PlayerRow.Single -> {
                    val track = row.track
                    views.setTextViewText(R.id.widget_row_title, track.title)
                    views.setTextViewText(R.id.widget_row_subtitle, track.artist)
                    views.setViewVisibility(R.id.widget_row_subtitle, visibleIf(track.artist.isNotBlank()))
                    views.setTextViewText(R.id.widget_row_meta, formatDuration(track.durationMs))
                    views.setViewVisibility(R.id.widget_row_chevron, View.GONE)
                    isCurrent = track.videoId == currentId
                    fillIn.putExtra(PlayerWidgetReceiver.EXTRA_VIDEO_ID, track.videoId)
                }

                is PlayerRow.Header -> {
                    val piece = row.piece
                    views.setTextViewText(R.id.widget_row_title, piece.title)
                    val parts = context.resources.getQuantityString(
                        R.plurals.player_parts, piece.tracks.size, piece.tracks.size
                    )
                    views.setTextViewText(
                        R.id.widget_row_subtitle,
                        listOf(piece.performer, parts).filter { it.isNotBlank() }.joinToString(" · ")
                    )
                    views.setViewVisibility(R.id.widget_row_subtitle, View.VISIBLE)
                    views.setTextViewText(R.id.widget_row_meta, formatDuration(piece.durationMs))
                    views.setViewVisibility(R.id.widget_row_chevron, View.VISIBLE)
                    views.setImageViewResource(
                        R.id.widget_row_chevron,
                        if (row.expanded) R.drawable.ic_widget_collapse else R.drawable.ic_widget_expand
                    )
                    // A collapsed piece is the current row while any of its parts plays.
                    isCurrent = !row.expanded && piece.tracks.any { it.videoId == currentId }
                    fillIn.putExtra(PlayerWidgetReceiver.EXTRA_PIECE_ID, piece.id)
                }

                is PlayerRow.Part -> {
                    views.setTextViewText(R.id.widget_row_title, row.label)
                    views.setViewVisibility(R.id.widget_row_subtitle, View.GONE)
                    views.setTextViewText(R.id.widget_row_meta, formatDuration(row.track.durationMs))
                    views.setViewVisibility(R.id.widget_row_chevron, View.GONE)
                    indent = 20
                    isCurrent = row.track.videoId == currentId
                    fillIn.putExtra(PlayerWidgetReceiver.EXTRA_VIDEO_ID, row.track.videoId)
                }
            }

            views.setViewPadding(R.id.widget_row, dp(8 + indent), dp(6), dp(4), dp(6))
            views.setViewVisibility(R.id.widget_row_playing, visibleIf(isCurrent))
            views.setInt(
                R.id.widget_row, "setBackgroundResource",
                if (isCurrent) R.drawable.widget_row_current else 0
            )
            views.setOnClickFillInIntent(R.id.widget_row, fillIn)
            return views
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount() = 1

        override fun getItemId(position: Int): Long =
            snapshot.getOrNull(position)?.key?.hashCode()?.toLong() ?: position.toLong()

        override fun hasStableIds() = true

        private fun visibleIf(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

        private fun dp(value: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
        ).toInt()
    }
}
