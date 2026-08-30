package com.junkfood.seal.util

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.Downloader
import com.junkfood.seal.R

/**
 * The sync's notifications: one ongoing foreground notification that counts progress,
 * and one dismissible summary once it finishes.
 */
@SuppressLint("StaticFieldLeak")
object NotificationUtil {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private const val SERVICE_CHANNEL_ID = "download_service"
    private const val NOTIFICATION_GROUP_ID = "seal.download.notification"

    const val SERVICE_NOTIFICATION_ID = 123
    private const val COMPLETION_NOTIFICATION_ID = SERVICE_NOTIFICATION_ID + 1

    @RequiresApi(Build.VERSION_CODES.O)
    fun createNotificationChannel() {
        notificationManager.createNotificationChannelGroup(
            android.app.NotificationChannelGroup(
                NOTIFICATION_GROUP_ID, context.getString(R.string.download)
            )
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.service_title)
                group = NOTIFICATION_GROUP_ID
            }
        )
    }

    /**
     * The ongoing notification. [progress] is finished-to-total; passing it draws a
     * determinate bar, and leaving it null draws none (the placeholder has nothing to
     * count yet).
     */
    private fun buildServiceNotification(
        title: String,
        subtitle: String? = null,
        progress: Pair<Int, Int>? = null,
    ): Notification =
        NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seal)
            .setContentTitle(title)
            .apply {
                subtitle?.let { setContentText(it) }
                progress?.let { (current, total) -> setProgress(total, current, false) }
            }
            .setOngoing(true)
            .setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /**
     * Posted by the service the instant it starts, purely to satisfy the platform's
     * five-second `startForegroundService` deadline. Replaced by
     * [updateServiceNotificationForPlaylist] once the sync knows how much work it has.
     */
    fun makePlaceholderServiceNotification(): Notification =
        buildServiceNotification(context.getString(R.string.service_title))

    /**
     * The pre-download phases on the ongoing notification. These take real time on a large
     * library, and the placeholder ("SealSync is downloading…") was both wrong about what
     * was happening and identical for all of them.
     */
    fun updateServiceNotificationForPhase(phase: Downloader.Phase, deleted: Int = 0) {
        val title = when (phase) {
            Downloader.Phase.Fetching -> R.string.sync_phase_fetching
            Downloader.Phase.Scanning -> R.string.sync_phase_scanning
            Downloader.Phase.Deleting -> R.string.sync_phase_deleting
            // Driven by updateServiceNotificationForPlaylist, which has counts to show.
            Downloader.Phase.Downloading -> return
        }
        notificationManager.notify(
            SERVICE_NOTIFICATION_ID,
            buildServiceNotification(
                title = context.getString(title),
                subtitle = deleted.takeIf { it > 0 }
                    ?.let { context.getString(R.string.sync_phase_deleted_detail, it) },
            )
        )
    }

    /**
     * Progress on the ongoing notification. [finished] counts items that have settled and
     * [downloading] how many are in flight right now -- several run at once, so the count
     * alone would suggest the sync had stalled between one item landing and the next.
     */
    fun updateServiceNotificationForPlaylist(finished: Int, itemCount: Int, downloading: Int = 0) {
        val text = context.getString(R.string.notification_sync_progress, finished, itemCount)
        notificationManager.notify(
            SERVICE_NOTIFICATION_ID,
            buildServiceNotification(
                title = text,
                subtitle = downloading.takeIf { it > 0 }?.let {
                    context.resources.getQuantityString(
                        R.plurals.notification_downloading_now, it, it
                    )
                },
                progress = itemCount.takeIf { it > 0 }?.let { finished to it },
            )
        )
    }

    /**
     * Drops the ongoing foreground notification and, if anything actually happened,
     * leaves a dismissible summary behind on a separate id.
     */
    fun finishPlaylistNotification(
        downloadedCount: Int,
        deletedCount: Int = 0,
        failedCount: Int = 0,
        cancelled: Boolean = false,
        error: String? = null,
    ) {
        // Removing a foreground notification requires stopForeground; cancel() alone won't do it.
        App.downloadService?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(SERVICE_NOTIFICATION_ID)

        if (!PreferenceUtil.getValue(NOTIFICATION)) return
        // A run that aborted or lost tracks is worth a notification even though it moved no
        // files -- that used to be indistinguishable from a no-op sync, so it stayed silent.
        val worthReporting =
            downloadedCount > 0 || deletedCount > 0 || failedCount > 0 || error != null
        if (!worthReporting) return

        val title = when {
            error != null -> R.string.sync_result_failed
            cancelled -> R.string.sync_result_cancelled
            else -> R.string.sync_complete
        }
        val summary = error ?: listOfNotNull(
            downloadedCount.takeIf { it > 0 }
                ?.let { context.getString(R.string.sync_downloaded, it) },
            deletedCount.takeIf { it > 0 }
                ?.let { context.getString(R.string.sync_deleted, it) },
            failedCount.takeIf { it > 0 }
                ?.let { context.getString(R.string.sync_result_failed_count, it) },
        ).joinToString(", ")

        notificationManager.notify(
            COMPLETION_NOTIFICATION_ID,
            NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_seal)
                .setContentTitle(context.getString(title))
                .setContentText(summary)
                .setOngoing(false)
                .setAutoCancel(true)
                .build()
        )
    }
}
