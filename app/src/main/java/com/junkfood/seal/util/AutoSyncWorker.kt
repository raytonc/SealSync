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
        // Best-effort: setForeground can still be refused (notifications denied, or an
        // unusual restriction), and that is not a reason to skip the sync -- it only means
        // the run proceeds without the elevated slot, exactly as it would have before.
        val wentForeground = runCatching { setForeground(makeForegroundInfo()) }
            .onFailure { Log.w(TAG, "doWork: could not go foreground, continuing anyway", it) }
            .isSuccess

        Log.d(TAG, "doWork: starting scheduled sync of ${playlists.size} playlist(s)")
        App.isWorkerForeground.set(wentForeground)
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
        Downloader.downloaderState.first { it is Downloader.State.Idle }
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
            // KEEP, not UPDATE. UPDATE would rewrite the request on every app start; even
            // with an identical spec that is a needless write, and the moment the spec does
            // differ it also restarts the period. So the schedule is torn down and rebuilt
            // explicitly when the settings that shape it change, and left strictly alone
            // otherwise -- which is what makes calling this from App.onCreate() safe.
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /**
         * Rebuilds the schedule from scratch, for when the user changes what it should be.
         *
         * [reschedule] deliberately keeps an existing schedule untouched, which is right on
         * app start and wrong here: a new interval or a new constraint has to replace the
         * enqueued work, not defer to it. Cancelling first is what makes the following
         * KEEP-policy enqueue behave as a replacement.
         */
        fun applySettingsChange(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            reschedule(context)
        }
    }
}
