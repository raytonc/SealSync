package com.junkfood.seal.player.widget

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.junkfood.seal.R
import com.junkfood.seal.player.Artwork
import com.junkfood.seal.player.NowPlaying
import com.junkfood.seal.player.PlayerLibrary
import com.junkfood.seal.player.PlayerRow
import com.junkfood.seal.player.buildRows
import com.junkfood.seal.player.formatDuration
import kotlinx.coroutines.runBlocking

/** Supplies the widget's list rows: collapsed pieces, and the parts of expanded ones. */
class PlayerWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {

        private var snapshot: List<PlayerRow> = emptyList()

        /** The piece that was open after the last redraw, to notice a new one opening. */
        private var lastOpen: String? = PlayerLibrary.expanded.value.firstOrNull()
        private var currentId: String? = null

        override fun onCreate() = Unit

        /** Runs on a binder thread, where blocking for the first load is allowed. */
        override fun onDataSetChanged() {
            runCatching { runBlocking { PlayerLibrary.ensureLoaded() } }
            snapshot = buildRows(PlayerLibrary.state.value.pieces, PlayerLibrary.expanded.value)
            currentId = NowPlaying.status.value.videoId

            // A piece that has just been opened scrolls to the top of the list. This is
            // the only point where the new rows are known to be in place, so the scroll is
            // issued from here rather than from the tap, which would aim at the old list.
            val open = PlayerLibrary.expanded.value.firstOrNull()
            if (open != null && open != lastOpen) {
                val position = snapshot.indexOfFirst {
                    it is PlayerRow.Header && it.piece.id == open
                }
                if (position >= 0) {
                    PlayerWidgetProvider.scrollTo(context, position, snapshot.size)
                }
            }
            lastOpen = open
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
                    // Gemini's performer, which leaves out the composer the raw tag lists.
                    val performer = row.piece.performer
                    views.setTextViewText(R.id.widget_row_subtitle, performer)
                    views.setViewVisibility(R.id.widget_row_subtitle, visibleIf(performer.isNotBlank()))
                    views.setTextViewText(R.id.widget_row_meta, formatDuration(track.durationMs))
                    views.setViewVisibility(R.id.widget_row_chevron, View.GONE)
                    isCurrent = track.videoId == currentId
                    setArt(views, track.uri)
                    fillIn.putExtra(PlayerWidgetReceiver.EXTRA_VIDEO_ID, track.videoId)
                }

                is PlayerRow.Header -> {
                    val piece = row.piece
                    views.setTextViewText(R.id.widget_row_title, piece.title)
                    val parts = context.resources.getQuantityString(
                        R.plurals.player_parts, piece.tracks.size, piece.tracks.size
                    )
                    // Performers get the whole line now that they are full names; the part
                    // count moves under the duration.
                    views.setTextViewText(R.id.widget_row_subtitle, piece.performer)
                    views.setViewVisibility(R.id.widget_row_subtitle, visibleIf(piece.performer.isNotBlank()))
                    views.setTextViewText(
                        R.id.widget_row_meta, "${formatDuration(piece.durationMs)}\n$parts"
                    )
                    views.setViewVisibility(R.id.widget_row_chevron, View.VISIBLE)
                    views.setImageViewResource(
                        R.id.widget_row_chevron,
                        if (row.expanded) R.drawable.ic_widget_collapse else R.drawable.ic_widget_expand
                    )
                    // A collapsed piece is the current row while any of its parts plays.
                    isCurrent = !row.expanded && piece.tracks.any { it.videoId == currentId }
                    setArt(views, piece.tracks.first().uri)
                    fillIn.putExtra(PlayerWidgetReceiver.EXTRA_PIECE_ID, piece.id)
                }

                is PlayerRow.Part -> {
                    views.setTextViewText(R.id.widget_row_title, row.label)
                    views.setViewVisibility(R.id.widget_row_subtitle, View.GONE)
                    views.setTextViewText(R.id.widget_row_meta, formatDuration(row.track.durationMs))
                    views.setViewVisibility(R.id.widget_row_chevron, View.GONE)
                    // Lines up with the piece's title, past where its artwork sits.
                    indent = 52
                    views.setViewVisibility(R.id.widget_row_art, View.GONE)
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

        /** Rows run on a binder thread, so reading the file here is allowed. */
        private fun setArt(views: RemoteViews, uri: String) {
            val art = Artwork.load(context, uri, dp(40), dp(8).toFloat())
            if (art == null) {
                views.setViewVisibility(R.id.widget_row_art, View.INVISIBLE)
            } else {
                views.setImageViewBitmap(R.id.widget_row_art, art)
                views.setViewVisibility(R.id.widget_row_art, View.VISIBLE)
            }
        }

        private fun visibleIf(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

        private fun dp(value: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
        ).toInt()
    }
}
