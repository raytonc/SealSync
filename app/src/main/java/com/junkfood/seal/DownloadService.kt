package com.junkfood.seal

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.NotificationUtil.SERVICE_NOTIFICATION_ID

private const val TAG = "DownloadService"

/**
 * This `Service` does nothing
 */
class DownloadService : Service() {


    override fun onBind(intent: Intent): IBinder {
        // Don't create notification here - it will be created when actual work starts
        // in initializeServiceNotificationForPlaylist()
        return DownloadServiceBinder()
    }

    fun startForegroundWithNotification(notification: android.app.Notification) {
        startForeground(SERVICE_NOTIFICATION_ID, notification)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: ")
        // stopForeground is now called from finishPlaylistNotification to ensure proper timing
        stopSelf()
        return super.onUnbind(intent)
    }

    inner class DownloadServiceBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }
}