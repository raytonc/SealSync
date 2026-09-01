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
import java.util.concurrent.atomic.AtomicInteger

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
         * the life of the process, since [outstandingRuns] is what stops `onUnbind` from
         * killing it and nothing else would.
         */
        private const val SYNC_COMPLETION_TIMEOUT_MS = 9 * 60 * 1000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * How many shortcut-triggered runs this service is currently hosting.
     *
     * A count rather than a flag, and atomic rather than a plain `var`. Two taps of the
     * launcher shortcut deliver two [onStartCommand] calls to the same service instance,
     * each launching its own coroutine. With a single boolean, the coroutine that *lost*
     * the downloader claim still fell through to `hasStartedWork = false; stopSelf()` --
     * destroying the service, cancelling [serviceScope] and with it the winning
     * coroutine's wait, and dropping the foreground notification while that run kept
     * downloading on applicationScope with no foreground host and no protection from the
     * low-memory killer. Now the loser only decrements, and the service stops when the
     * last outstanding run has actually finished.
     *
     * Atomic because it is written from [serviceScope]'s IO threads and read from the
     * main thread in [onUnbind]; the plain field it replaces had no happens-before edge
     * between the two.
     */
    private val outstandingRuns = AtomicInteger(0)

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
            outstandingRuns.incrementAndGet()
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

                // Only the last run standing stops the service. A coroutine that lost the
                // claim reaches here immediately, and stopping on its way out would
                // cancel serviceScope -- and with it the waiting coroutine that actually
                // owns the run.
                if (outstandingRuns.decrementAndGet() == 0) {
                    Log.d(TAG, "sync finished, stopping service")
                    stopSelf()
                } else {
                    Log.d(TAG, "sync finished, another run still outstanding, staying up")
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: outstandingRuns=${outstandingRuns.get()}")
        // A shortcut-triggered sync outlives the binding, so only stop when nothing is running.
        if (outstandingRuns.get() == 0) stopSelf()
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
