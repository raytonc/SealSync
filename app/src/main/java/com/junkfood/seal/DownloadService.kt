package com.junkfood.seal

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.NotificationUtil.SERVICE_NOTIFICATION_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

private const val TAG = "DownloadService"

/**
 * Foreground service that keeps a sync alive while the app is backgrounded.
 *
 * Two entry points, both of which must reach [startForeground] promptly:
 * - Bound, from [App.startService], when a sync is kicked off from the UI.
 * - Started with [ACTION_SYNC_PLAYLISTS], from the launcher shortcut, with no UI at all.
 *
 * In both cases a placeholder notification is posted immediately so the platform's
 * five-second `startForegroundService` deadline is met before any real work begins;
 * [NotificationUtil] then replaces it with the real progress notification.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_SYNC_PLAYLISTS = "com.junkfood.seal.ACTION_SYNC_PLAYLISTS"

        /** How long to wait for a sync to actually start before concluding it was rejected. */
        private const val SYNC_START_TIMEOUT_MS = 10_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True once a shortcut-triggered sync is in flight, so unbinding must not kill us. */
    private var hasStartedWork = false

    override fun onCreate() {
        super.onCreate()
        // Every path into this service is started with startForegroundService(), so go
        // foreground straight away rather than racing the platform's deadline.
        startForeground(SERVICE_NOTIFICATION_ID, NotificationUtil.makePlaceholderServiceNotification())
    }

    override fun onBind(intent: Intent): IBinder = DownloadServiceBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        if (intent?.action == ACTION_SYNC_PLAYLISTS) {
            hasStartedWork = true
            serviceScope.launch {
                runCatching {
                    Downloader.syncPlaylists(DatabaseUtil.getPlaylistsFlow().first())
                }.onFailure { Log.e(TAG, "Sync failed from service command", it) }

                // syncPlaylists returns as soon as the sync coroutine is launched, so wait
                // for the run to actually finish before giving up the foreground slot.
                // Without this the shortcut path left the service alive forever:
                // hasStartedWork blocked onUnbind from stopping it and nothing else would.
                //
                // Waiting for Idle alone would race the launch and match the Idle that is
                // still in place here, so wait for the run to begin first. syncPlaylists
                // also rejects the request outright (no playlists, no API key, already
                // running), which never leaves Idle -- hence the timeout.
                withTimeoutOrNull(SYNC_START_TIMEOUT_MS) {
                    Downloader.downloaderState.first { it !is Downloader.State.Idle }
                }
                Downloader.downloaderState.first { it is Downloader.State.Idle }
                Log.d(TAG, "sync finished, stopping service")
                hasStartedWork = false
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: hasStartedWork=$hasStartedWork")
        // A shortcut-triggered sync outlives the binding, so only stop when nothing is running.
        if (!hasStartedWork) stopSelf()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    inner class DownloadServiceBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }
}
