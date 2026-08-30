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

    private fun buildServiceNotification(title: String): Notification =
        NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seal)
            .setContentTitle(title)
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

    fun updateServiceNotificationForPlaylist(index: Int, itemCount: Int) {
        notificationManager.notify(
            SERVICE_NOTIFICATION_ID,
            buildServiceNotification("${context.getString(R.string.service_title)} ($index/$itemCount)")
        )
    }

    /**
     * Drops the ongoing foreground notification and, if anything actually happened,
     * leaves a dismissible summary behind on a separate id.
     */
    fun finishPlaylistNotification(downloadedCount: Int, deletedCount: Int = 0) {
        // Removing a foreground notification requires stopForeground; cancel() alone won't do it.
        App.downloadService?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(SERVICE_NOTIFICATION_ID)

        if (!PreferenceUtil.getValue(NOTIFICATION)) return
        if (downloadedCount == 0 && deletedCount == 0) return

        val summary = listOfNotNull(
            downloadedCount.takeIf { it > 0 }
                ?.let { context.getString(R.string.sync_downloaded, it) },
            deletedCount.takeIf { it > 0 }
                ?.let { context.getString(R.string.sync_deleted, it) },
        ).joinToString(", ")

        notificationManager.notify(
            COMPLETION_NOTIFICATION_ID,
            NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_seal)
                .setContentTitle(context.getString(R.string.sync_complete))
                .setContentText(summary)
                .setOngoing(false)
                .setAutoCancel(true)
                .build()
        )
    }
}
