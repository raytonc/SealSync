package com.junkfood.seal.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.junkfood.seal.App
import com.junkfood.seal.MainActivity
import com.junkfood.seal.R
import com.junkfood.seal.player.Artwork
import com.junkfood.seal.player.NowPlaying
import com.junkfood.seal.player.PlayerLibrary
import com.junkfood.seal.player.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The home-screen player: what is playing, transport controls, and the library as one
 * scrollable list with each multi-part piece collapsed to a single row.
 *
 * The list itself comes from [PlayerWidgetService]. Every tap goes to
 * [PlayerWidgetReceiver], which is not exported, so no other app can drive playback
 * through it.
 */
class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context, it)) }
        refreshArt(context, manager, ids)
        @Suppress("DEPRECATION")
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }

    companion object {

        /**
         * Rebuilds every placed widget from scratch, click handlers included.
         *
         * [refresh] only patches text and icons. Force-stopping the app cancels every
         * PendingIntent it owns, which leaves the widget drawn but dead to taps until
         * something sends a full update. Called on every process start, so opening the
         * app is enough to bring it back.
         */
        fun rebind(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { manager.updateAppWidget(it, build(context, it)) }
            @Suppress("DEPRECATION")
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            refreshArt(context, manager, ids)
        }

        /** The shortest a list row can be (its minHeight), which bounds how many fit. */
        private const val MIN_ROW_HEIGHT_DP = 52

        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        /**
         * Scrolls every placed widget's list so row [position] is the top row.
         *
         * A widget can only ask its list to bring a row into view. A row coming from below
         * lands at the bottom edge; only a row coming from above lands at the top. So the
         * list first scrolls a full screen past [position], which takes that row off the
         * top whatever the rows' heights, then scrolls back to it.
         *
         * This replaces estimating how many rows fit and scrolling to the row that many
         * below. That estimate ran high (the widget's header grew, and two-line titles make
         * rows taller than the minimum), which pushed the piece above the top edge. Here the
         * last scroll aims at the row itself, so it always ends up on screen.
         */
        fun scrollTo(context: Context, position: Int, rowCount: Int) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { id ->
                // The whole widget's height overstates the list's. That only costs a little
                // extra scrolling, where understating it would leave the row on screen.
                val heightDp = manager.getAppWidgetOptions(id)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                    .takeIf { it > 0 } ?: 400
                val past = (position + heightDp / MIN_ROW_HEIGHT_DP + 1).coerceAtMost(rowCount - 1)
                // Delayed so the launcher has the new rows before the first scroll, and the
                // first scroll has settled before the second.
                if (past > position) {
                    mainHandler.postDelayed({ scroll(context, manager, id, past) }, 300)
                }
                mainHandler.postDelayed({ scroll(context, manager, id, position) }, 900)
            }
        }

        private fun scroll(context: Context, manager: AppWidgetManager, id: Int, position: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setScrollPosition(R.id.widget_list, position)
            manager.partiallyUpdateAppWidget(id, views)
        }

        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))

        /**
         * Redraws every placed widget. [listChanged] also reloads the list; leave it off
         * for a play/pause, which only changes the header.
         */
        fun refresh(context: Context, listChanged: Boolean = true) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { manager.partiallyUpdateAppWidget(it, header(context)) }
            refreshArt(context, manager, ids)
            // The list keeps its scroll position. It used to jump to the playing row, but
            // that row's position was read before the list had redrawn, so it landed
            // somewhere unrelated, and a tap is made on a row already on screen anyway.
            if (listChanged) {
                @Suppress("DEPRECATION")
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            }
        }

        /** Loads the header's artwork off the main thread, then patches it in. */
        private fun refreshArt(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val uri = PlayerLibrary.state.value.track(NowPlaying.status.value.videoId)?.uri
            App.applicationScope.launch(Dispatchers.IO) {
                val px = dp(context, 48)
                val art = uri?.let { Artwork.load(context, it, px, dp(context, 10).toFloat()) }
                val views = RemoteViews(context.packageName, R.layout.widget_player)
                if (art == null) {
                    views.setViewVisibility(R.id.widget_art, View.GONE)
                } else {
                    views.setImageViewBitmap(R.id.widget_art, art)
                    views.setViewVisibility(R.id.widget_art, View.VISIBLE)
                }
                ids.forEach { manager.partiallyUpdateAppWidget(it, views) }
            }
        }

        private fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
        ).toInt()

        private fun build(context: Context, widgetId: Int): RemoteViews {
            val views = header(context)

            val serviceIntent = Intent(context, PlayerWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                // Makes each widget's intent distinct, so the system keeps one factory each.
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            // Mutable because each row fills in which piece or track it is.
            views.setPendingIntentTemplate(
                R.id.widget_list,
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, PlayerWidgetReceiver::class.java)
                        .setAction(PlayerWidgetReceiver.ACTION_ROW),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
            )
            views.setOnClickPendingIntent(R.id.widget_previous, action(context, PlayerWidgetReceiver.ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_play_pause, action(context, PlayerWidgetReceiver.ACTION_TOGGLE))
            views.setOnClickPendingIntent(R.id.widget_next, action(context, PlayerWidgetReceiver.ACTION_NEXT))
            SEEK_ZONES.forEachIndexed { i, zone ->
                // Each zone seeks to its own centre, so the last one is just short of the end
                // rather than at it, which would skip straight to the next track.
                val fraction = (i + 0.5f) / SEEK_ZONES.size
                views.setOnClickPendingIntent(
                    zone,
                    PendingIntent.getBroadcast(
                        context, SEEK_REQUEST_BASE + i,
                        Intent(context, PlayerWidgetReceiver::class.java)
                            .setAction(PlayerWidgetReceiver.ACTION_SEEK)
                            .putExtra(PlayerWidgetReceiver.EXTRA_FRACTION, fraction),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            }
            views.setOnClickPendingIntent(
                R.id.widget_now_playing,
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_PLAYER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            return views
        }

        private val SEEK_ZONES = intArrayOf(
            R.id.widget_seek_0, R.id.widget_seek_1, R.id.widget_seek_2, R.id.widget_seek_3,
            R.id.widget_seek_4, R.id.widget_seek_5, R.id.widget_seek_6, R.id.widget_seek_7,
            R.id.widget_seek_8, R.id.widget_seek_9, R.id.widget_seek_10, R.id.widget_seek_11,
            R.id.widget_seek_12, R.id.widget_seek_13, R.id.widget_seek_14, R.id.widget_seek_15,
            R.id.widget_seek_16, R.id.widget_seek_17, R.id.widget_seek_18, R.id.widget_seek_19,
        )

        /** Request codes for the seek zones, clear of the transport buttons' hash codes. */
        private const val SEEK_REQUEST_BASE = 1_000

        /** Redraws only the progress bar and times, which change every few seconds. */
        fun refreshProgress(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val status = NowPlaying.status.value
            val track = PlayerLibrary.state.value.track(status.videoId)
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            setProgress(views, status, track?.durationMs ?: 0L)
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { manager.partiallyUpdateAppWidget(it, views) }
        }

        /**
         * The service reports a duration once the file is open; before that, and while the
         * service is not running, the duration read from the file's tags stands in.
         */
        private fun setProgress(views: RemoteViews, status: NowPlaying.Status, tagged: Long) {
            val duration = status.durationMs.takeIf { it > 0 } ?: tagged
            val position = status.positionMs.coerceIn(0L, duration.coerceAtLeast(0L))
            views.setTextViewText(R.id.widget_elapsed, formatDuration(position))
            views.setTextViewText(R.id.widget_duration, formatDuration(duration))
            views.setProgressBar(
                R.id.widget_progress, 1000,
                if (duration > 0) (position * 1000 / duration).toInt() else 0,
                false,
            )
        }

        /** The now-playing text and the play/pause icon. */
        private fun header(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            val status = NowPlaying.status.value
            val library = PlayerLibrary.state.value
            val track = library.track(status.videoId)
            val piece = library.pieceOf(status.videoId)

            if (track == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.player_nothing_playing))
                views.setTextViewText(R.id.widget_subtitle, "")
            } else if (piece == null || piece.isSingle) {
                views.setTextViewText(R.id.widget_title, track.title)
                views.setTextViewText(R.id.widget_subtitle, track.artist)
            } else {
                val position = piece.tracks.indexOfFirst { it.videoId == track.videoId }
                views.setTextViewText(R.id.widget_title, piece.partLabels[position])
                views.setTextViewText(
                    R.id.widget_subtitle,
                    listOf(piece.title, piece.performer).filter { it.isNotBlank() }.joinToString(" · ")
                )
            }

            setProgress(views, status, track?.durationMs ?: 0L)
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (status.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
            )
            views.setContentDescription(
                R.id.widget_play_pause,
                context.getString(if (status.isPlaying) R.string.player_pause else R.string.player_play)
            )
            return views
        }

        private fun action(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, action.hashCode(),
                Intent(context, PlayerWidgetReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
