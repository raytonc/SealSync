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

        /**
         * How long the shortcut path waits for its sync to finish before giving up the
         * foreground slot.
         *
         * Sized against the same thing [com.junkfood.seal.util.AutoSyncWorker]'s ceiling
         * is: a started run that never finishes must not pin this service foreground for
         * the life of the process, since [hasStartedWork] is what stops `onUnbind` from
         * killing it and nothing else would.
         */
        private const val SYNC_COMPLETION_TIMEOUT_MS = 9 * 60 * 1000L
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
                // The claim is the return value, and it is what decides whether there is
                // anything here to wait for. Watching the state for a transition out of
                // Idle -- which this used to do -- cannot tell "our run started" from
                // "somebody else's run was already going": the state is global, so a
                // scheduled sync that beat us to the claim satisfied that wait instantly
                // and the service then sat on a run it did not own.
                val claimed = runCatching {
                    Downloader.syncPlaylists(DatabaseUtil.getPlaylistsFlow().first())
                }.onFailure { Log.e(TAG, "Sync failed from service command", it) }
                    .getOrDefault(false)

                if (claimed) {
                    // No preceding "has it started yet" wait: the claim inside
                    // syncPlaylists is a synchronous compareAndSet off Idle, so the state
                    // has already moved by the time it returns true.
                    //
                    // Bounded, because the thing being waited on is a whole sync and this
                    // wait is the only thing keeping the service alive -- hasStartedWork
                    // blocks onUnbind from stopping it. A wedged yt-dlp process would
                    // otherwise hold the service foreground indefinitely. The run itself
                    // is not cancelled when the ceiling expires; it continues on
                    // applicationScope, and this only stops the service waiting on it.
                    val finished = withTimeoutOrNull(SYNC_COMPLETION_TIMEOUT_MS) {
                        Downloader.downloaderState.first { it is Downloader.State.Idle }
                    }
                    if (finished == null) {
                        Log.w(TAG, "sync still running after the wait ceiling, stopping anyway")
                    }
                } else {
                    // Rejected outright (nothing to sync, no API key) or lost the claim to
                    // a run already going. Either way this service has no work of its own,
                    // and the run that does own the downloader has its own foreground host.
                    Log.d(TAG, "sync not started from service command, nothing to wait for")
                }

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
