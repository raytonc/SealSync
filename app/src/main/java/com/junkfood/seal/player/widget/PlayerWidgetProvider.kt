package com.junkfood.seal.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.junkfood.seal.MainActivity
import com.junkfood.seal.R
import com.junkfood.seal.player.NowPlaying
import com.junkfood.seal.player.PlayerLibrary

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
        @Suppress("DEPRECATION")
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }

    companion object {

        private var lastScrolledTo: String? = null

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
            if (listChanged) {
                @Suppress("DEPRECATION")
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            }
            scrollToCurrent(context, manager, ids)
        }

        /** Follows playback: when the track changes, brings its row into view once. */
        private fun scrollToCurrent(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val current = NowPlaying.status.value.videoId ?: return
            if (current == lastScrolledTo) return
            val position = PlayerWidgetService.rowPositionOf(current)
            if (position < 0) return
            lastScrolledTo = current
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setScrollPosition(R.id.widget_list, position)
            ids.forEach { manager.partiallyUpdateAppWidget(it, views) }
        }

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
