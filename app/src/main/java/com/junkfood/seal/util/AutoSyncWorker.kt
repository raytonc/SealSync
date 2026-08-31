package com.junkfood.seal.util

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.junkfood.seal.App
import com.junkfood.seal.Downloader
import com.junkfood.seal.database.objects.PlaylistEntry
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

private const val TAG = "AutoSyncWorker"

/**
 * The scheduled sync.
 *
 * SealSync's whole premise is that keeping the folder current should be frictionless, and
 * until now every sync was a deliberate tap -- so the folder was only as fresh as the user
 * remembered to make it. This closes that: the same [Downloader.syncPlaylists] the button
 * calls, on a cadence, under constraints that keep it off metered data and out of the way
 * of a low battery.
 *
 * Deliberately thin. It does not reimplement the run, and it does not post its own
 * notification -- [Downloader] already binds the foreground service that carries both, and
 * a second notification for the same work would be the same run announced twice.
 */
class AutoSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Re-checked here, not just at schedule time. Work already enqueued survives the
        // preference being turned off if the cancel below ever fails to land, and a run
        // the user has switched off must not go ahead on the strength of a stale schedule.
        if (!AUTO_SYNC_ENABLED.getBoolean()) {
            Log.d(TAG, "doWork: auto sync disabled, skipping")
            return Result.success()
        }

        // A manual sync already running is not a reason to fail: syncPlaylists would reject
        // this one anyway (and toast about it), and the folder is being brought current by
        // the run that is already going. Retrying would just collide with it again.
        if (Downloader.downloaderState.value !is Downloader.State.Idle) {
            Log.d(TAG, "doWork: a sync is already running, skipping this one")
            return Result.success()
        }

        val playlists = DatabaseUtil.getPlaylistsFlow().first()
        if (playlists.isEmpty()) {
            Log.d(TAG, "doWork: no playlists saved, nothing to sync")
            return Result.success()
        }

        // The two things a sync cannot run without, checked here so the failure is a log
        // line rather than a toast fired at a user who is not looking at the phone.
        if (YOUTUBE_API_KEY.getString().isBlank()) {
            Log.w(TAG, "doWork: no API key configured, skipping")
            return Result.success()
        }

        // Take the foreground slot before anything starts.
        //
        // This is not decoration. A sync spends minutes downloading and transcoding, and a
        // plain background worker is subject to the platform's execution limits and its
        // ten-minute ceiling -- while `Downloader` would, unhelped, try to bind the same
        // foreground service the UI uses, which a background-launched process is forbidden
        // from starting on Android 12+. So the worker becomes the foreground host for the
        // run, and [App.isWorkerForeground] tells Downloader to stand down.
        //
        // The flag is set BEFORE the promotion is attempted, and stays set either way.
        //
        // It is not a record of whether the promotion succeeded -- it is what stops
        // Downloader from binding the foreground service on this run, and that has to hold
        // whether or not the worker got its slot. Setting it from the promotion's result
        // (as an earlier version did) meant a refused setForeground left the flag false,
        // Downloader then called startForegroundService from a WorkManager-woken
        // background process, and the run died on the very
        // ForegroundServiceStartNotAllowedException the flag exists to prevent.
        App.isWorkerForeground.set(true)

        // Best-effort. setForeground can be refused (notifications denied, a restricted
        // app-standby bucket, quota exhausted), and that is not a reason to skip the sync:
        // the run proceeds as an ordinary background worker, subject to the platform's
        // execution window, which the timeout on the wait below is sized against.
        val wentForeground = runCatching { setForeground(makeForegroundInfo()) }
            .onFailure { Log.w(TAG, "doWork: could not go foreground, continuing anyway", it) }
            .isSuccess

        Log.d(
            TAG,
            "doWork: starting scheduled sync of ${playlists.size} playlist(s), " +
                    "foreground=$wentForeground"
        )
        try {
            runSync(playlists)
        } finally {
            // Cleared on every path, cancellation included: leaving it set would make the
            // next manual sync silently skip binding its own service.
            App.isWorkerForeground.set(false)
        }

        Log.d(TAG, "doWork: scheduled sync finished")
        return Result.success()
    }

    /** Starts the sync and suspends until it has finished. */
    private suspend fun runSync(playlists: List<PlaylistEntry>) {
        // Silent: the ongoing notification is this run's voice, not a stack of toasts
        // over whatever the user is actually doing.
        Downloader.syncPlaylists(playlists, silent = true)

        // syncPlaylists returns as soon as it has launched the run, so wait for the run
        // itself. Without this the worker completes immediately and WorkManager is free to
        // consider the process idle while downloads are still writing files.
        //
        // Two waits, the same shape DownloadService uses: first for the run to leave Idle
        // (it may never, if syncPlaylists rejected the request), then for it to come back.
        val started = withTimeoutOrNull(SYNC_START_TIMEOUT_MS) {
            Downloader.downloaderState.first { it !is Downloader.State.Idle }
        }
        if (started == null) {
            Log.w(TAG, "runSync: sync never started, nothing to wait for")
            return
        }

        // Bounded, unlike the start wait's smaller timeout, because the thing being waited
        // on is a whole sync. A wedged yt-dlp process (a stalled socket read holds its
        // thread inside downloadVideo, so finishProcessing never runs) would otherwise
        // suspend this worker forever while its ongoing notification sat on the user's
        // screen. The ceiling is generous enough that a genuinely large first sync is not
        // cut short, and the run itself is not cancelled when it expires -- it continues
        // on applicationScope; this only stops the worker from waiting on it.
        val finished = withTimeoutOrNull(SYNC_COMPLETION_TIMEOUT_MS) {
            Downloader.downloaderState.first { it is Downloader.State.Idle }
        }
        if (finished == null) {
            Log.w(TAG, "runSync: sync still running after the wait ceiling, leaving it to run")
        }
    }

    /**
     * The ongoing notification the worker runs under.
     *
     * Reuses the service's own placeholder rather than inventing a second look for the
     * same thing: a scheduled sync and a tapped one are the same run, and Downloader
     * updates this very notification id through its phases as the run proceeds.
     */
    private fun makeForegroundInfo(): ForegroundInfo {
        val notification = NotificationUtil.makePlaceholderServiceNotification()
        // The type is only passed where the platform actually knows it.
        //
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE was added in API 34; below that the constant
        // inlines to a bit the system does not recognise, and handing it an unknown type is
        // a worse outcome than handing it none -- on those releases WorkManager's own
        // untyped promotion is correct and sufficient. From 34 the type is mandatory, and
        // it matches what the manifest declares for the same service.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NotificationUtil.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ForegroundInfo(NotificationUtil.SERVICE_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** Unique name, so rescheduling replaces the schedule rather than stacking onto it. */
        private const val WORK_NAME = "auto_sync"

        /** How long to wait for a sync to actually begin before concluding it was rejected. */
        private const val SYNC_START_TIMEOUT_MS = 10_000L

        /**
         * How long to wait for a started sync to finish before the worker stops waiting.
         *
         * Two hours: comfortably longer than any real library's first full sync, and short
         * enough that a wedged run cannot hold the worker (and its notification) forever.
         */
        private const val SYNC_COMPLETION_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        /**
         * Applies the current auto-sync preferences to WorkManager.
         *
         * Idempotent and safe to call on every app start as well as on every settings
         * change -- which is exactly how it is used. [ExistingPeriodicWorkPolicy.UPDATE]
         * rewrites the existing schedule in place when the interval or the constraints
         * changed, and leaves an already-matching one alone rather than restarting its
         * period, so opening the app does not perpetually push the next run further away.
         */
        fun reschedule(context: Context) {
            val workManager = WorkManager.getInstance(context)

            if (!AUTO_SYNC_ENABLED.getBoolean()) {
                Log.d(TAG, "reschedule: disabled, cancelling any existing work")
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val hours = AUTO_SYNC_INTERVAL_HOURS.getInt().let { stored ->
                // Guards against a value written by an older build, or a hand-edited store,
                // landing under WorkManager's 15-minute floor.
                if (stored in AUTO_SYNC_INTERVALS) stored else AUTO_SYNC_DEFAULT_INTERVAL_HOURS
            }

            val constraints = Constraints.Builder()
                // The user's cellular preference is the same one a manual sync honours;
                // a background run has even less business spending metered data, but
                // deliberately obeys the setting rather than overriding it.
                .setRequiredNetworkType(
                    if (CELLULAR_DOWNLOAD.getBoolean()) NetworkType.CONNECTED
                    else NetworkType.UNMETERED
                )
                // A sync can run for minutes with the CPU busy transcoding. Running it flat
                // is how a background feature earns a reputation for eating the battery.
                .setRequiresBatteryNotLow(true)
                .apply {
                    if (AUTO_SYNC_REQUIRES_CHARGING.getBoolean()) setRequiresCharging(true)
                }
                .build()

            val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(
                hours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(constraints)
                // Deliberately no initial delay.
                //
                // This method runs on every app start, and an initial delay is measured
                // from the moment the request is enqueued -- so under UPDATE it would be
                // reapplied each launch and push the next run further out every time the
                // user opened the app. An app opened daily would then never sync in the
                // background at all, which is the one thing this feature exists to do.
                //
                // The absence of a delay is not a licence to run immediately either: the
                // constraints below still gate the first run, and WorkManager places
                // periodic work within its period at a time of the platform's choosing.
                .build()

            Log.d(TAG, "reschedule: every ${hours}h, charging=${AUTO_SYNC_REQUIRES_CHARGING.getBoolean()}")
            // UPDATE, so this one method serves both callers.
            //
            // It rewrites an enqueued schedule in place when the spec has changed and is
            // otherwise inert, which is exactly what App.onCreate() wants: the constraints
            // are derived from preferences that can change while no scheduling code runs,
            // and a start that finds them unchanged should do nothing. KEEP would discard
            // the new request wholesale and make that call permanently dead.
            //
            // Safe against the hazard that argued for KEEP -- an initial delay being
            // reapplied on every launch and pushing the next run past its interval forever
            // -- because the request deliberately carries no initial delay at all.
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        /**
         * Applies a settings change to the schedule.
         *
         * The same call as [reschedule]: UPDATE already replaces a changed spec in place,
         * so nothing extra is needed. Kept as its own name because the call sites read
         * better for it, and because cancelling first -- which an earlier version did --
         * was both unnecessary and subtly wrong: `cancelUniqueWork` completes
         * asynchronously, so the enqueue that followed it could race the cancel.
         */
        fun applySettingsChange(context: Context) = reschedule(context)
    }
}
