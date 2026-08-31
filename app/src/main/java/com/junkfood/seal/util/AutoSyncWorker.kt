package com.junkfood.seal.util

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        // The platform's execution window opens here, so this is what the wait ceiling in
        // [runSync] is measured against -- everything before it spends the same budget.
        val startedAt = SystemClock.elapsedRealtime()

        // Re-checked here, not just at schedule time. Work already enqueued survives the
        // preference being turned off if the cancel below ever fails to land, and a run
        // the user has switched off must not go ahead on the strength of a stale schedule.
        if (!AUTO_SYNC_ENABLED.getBoolean()) {
            Log.d(TAG, "doWork: auto sync disabled, skipping")
            return Result.success()
        }

        // Whether something else holds the downloader is deliberately NOT checked here.
        //
        // A check at this point is a check-then-act: a manual sync can start in the window
        // between it and syncPlaylists, and this worker would then have already committed
        // to running. syncPlaylists claims the downloader atomically and tells us whether
        // it got it, so that answer -- taken below, after the foreground slot is set up --
        // is the only one that cannot be stale by the time it is used.
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
        // Best-effort. setForeground can be refused (notifications denied, a restricted
        // app-standby bucket, quota exhausted), and that is not a reason to skip the sync:
        // the run proceeds as an ordinary background worker, subject to the platform's
        // execution window, which the timeout on the wait below is sized against.
        //
        // Note the ordering against [App.isWorkerForeground], which is deliberately NOT
        // set here: see [runSync], which sets it only once the claim is won.
        //
        // Promoted before the binary wait below, not after. That wait can run for a full
        // minute on a slow first-run unpack, and doing it as a plain background worker
        // spends it under exactly the Doze and standby pressure the promotion exists to
        // escape -- the conditions most likely to have the platform stop the worker
        // mid-wait, and on the one run (first after an update) where that unpack is
        // slowest.
        val wentForeground = runCatching { setForeground(makeForegroundInfo()) }
            .onFailure { Log.w(TAG, "doWork: could not go foreground, continuing anyway", it) }
            .isSuccess

        // Wait for yt-dlp to finish unpacking before anything tries to run it.
        //
        // This worker is the one caller that routinely starts from a cold process:
        // WorkManager wakes the app and `doWork` begins within milliseconds of
        // `App.onCreate`, which kicks the binary unpack off asynchronously and does not
        // wait for it. Without this, a first run after an update reaches
        // `YoutubeDL.getInstance().execute` mid-unpack and every item fails with an
        // initialisation error -- which no [Downloader] retry pattern recognises as
        // transient, so all of them settle as permanently Failed on the first attempt.
        //
        // Three outcomes, and they need different answers:
        //
        // - It completes: the binaries are usable, carry on.
        // - It throws: the init failed and will never succeed again in this process. Not
        //   retryable -- `Result.retry()` here is what made a broken unpack loop forever,
        //   WorkManager re-running the worker on backoff, each run holding a foreground
        //   slot and posting an ongoing notification for binaries that cannot work. The
        //   next *scheduled* run gets a fresh process and a fresh chance; this one is done.
        //   Not `Result.failure()` either, which would take the schedule down with the
        //   run: FAILURE is terminal for a PeriodicWorkRequest -- WorkerWrapper resets the
        //   period on SUCCESS and RETRY only, so a failed spec is simply marked FAILED and
        //   never runs again. One bad unpack would silently end background sync until the
        //   user next opened the app. Success is the answer that skips this period and
        //   keeps the next one, exactly as retryUntil does past its cap.
        // - It outruns the ceiling: the device is pathologically slow rather than broken,
        //   which is genuinely worth another attempt from a process where the unpack has
        //   long since settled.
        val ready = withTimeoutOrNull(BINARY_INIT_TIMEOUT_MS) {
            runCatching { App.binariesReady.await() }
        }
        if (ready == null) {
            Log.w(TAG, "doWork: yt-dlp not initialised in time, retrying later")
            return retryUntil(MAX_RUN_ATTEMPTS, "binary init timed out")
        }
        ready.onFailure {
            Log.e(TAG, "doWork: yt-dlp init failed, nothing this run can do", it)
            return Result.success()
        }

        Log.d(
            TAG,
            "doWork: starting scheduled sync of ${playlists.size} playlist(s), " +
                    "foreground=$wentForeground"
        )
        val outcome = runSync(playlists, startedAt)

        Log.d(TAG, "doWork: scheduled sync finished, outcome=$outcome")
        // A run that aborted before it could compare anything -- a listing that would not
        // answer, an unreadable folder -- is a transient failure of exactly the kind
        // WorkManager's backoff exists for. Returning success there made the worker wait
        // out the whole interval, up to a week on the longest setting, over a fault that
        // would typically have cleared in minutes.
        return if (outcome == SyncOutcome.Failed) {
            retryUntil(MAX_RUN_ATTEMPTS, "sync aborted")
        } else {
            Result.success()
        }
    }

    /**
     * Asks WorkManager for another attempt, but only while there is reason to think one
     * would go differently.
     *
     * "Transient" is an assumption, and some faults never clear on their own: a revoked
     * API key, an exhausted quota, a sync folder the app has lost permission to. Each of
     * those fails identically on every attempt, and an uncapped [Result.retry] answers by
     * re-running a *foreground* worker on backoff indefinitely -- a recurring ongoing
     * notification and real battery cost for a fault only the user can fix.
     *
     * Past the cap this returns success, which is not a claim that the run worked. It
     * means the worker has stopped arguing with the backoff and is waiting for the next
     * scheduled period, which is the natural cadence for a fault that has already
     * survived several immediate attempts.
     */
    private fun retryUntil(attempts: Int, reason: String): Result =
        if (runAttemptCount + 1 < attempts) {
            Log.d(TAG, "doWork: $reason, retrying (attempt ${runAttemptCount + 1}/$attempts)")
            Result.retry()
        } else {
            Log.w(TAG, "doWork: $reason, giving up after $attempts attempts")
            Result.success()
        }

    /** How a scheduled run ended, as far as the worker needs to care. */
    private enum class SyncOutcome {
        /** The run finished, or was cancelled by the user, without aborting. */
        Completed,

        /** The run aborted before it could compare anything. Worth another attempt. */
        Failed,

        /** Something else held the downloader, or the run outlived the wait. Not ours to judge. */
        NotOurs,
    }

    /** Starts the sync, suspends until it has finished, and reports how it ended. */
    private suspend fun runSync(playlists: List<PlaylistEntry>, startedAt: Long): SyncOutcome {
        // The flag goes up first, and comes back down immediately if the claim is lost.
        //
        // It has to precede the claim: syncPlaylists' compareAndSet is what moves the
        // state off Idle, and Downloader's state collector reacts to that by calling
        // App.startService() -- so a flag set after the call has already lost the race it
        // exists to win, and the run would try to bind a foreground service from a
        // WorkManager-woken background process.
        //
        // Setting it in doWork, before the suspending setForeground, was worse in the
        // other direction. That IPC round-trip is a wide window, and a manual sync
        // starting inside it found the flag already up: its startService() returned
        // immediately, this worker then lost the claim, cleared the flag on its way out
        // and released WorkManager's foreground slot with it -- leaving the user's sync
        // running on applicationScope with no foreground host of any kind.
        App.isWorkerForeground.set(true)

        // Silent: the ongoing notification is this run's voice, not a stack of toasts
        // over whatever the user is actually doing.
        //
        // The return value is the claim, and it is what decides whether there is anything
        // to wait for. Watching the downloader state for a transition out of Idle -- which
        // an earlier version did -- cannot tell "our run started" from "somebody else's
        // run is already going": the state is global and a manual sync that beat us to the
        // claim satisfies the wait instantly. The worker then sat on a run it did not own,
        // holding App.isWorkerForeground, which suppresses the foreground service binding
        // for the run that actually won -- leaving the real sync with no foreground host.
        if (!Downloader.syncPlaylists(playlists, silent = true)) {
            // Cleared before returning, not in a finally at the end: the run that won the
            // claim is starting right now and needs to be able to bind its own service.
            App.isWorkerForeground.set(false)
            // And told to look again. The run that beat us to the claim asked the question
            // while the flag was still up -- a window of a single compareAndSet, but a real
            // one -- and was told to stand down. That answer is wrong the moment the line
            // above executes, and nothing else will re-ask: the state transition that
            // normally triggers the binding has already been and gone.
            Downloader.rebindServiceIfRunning()
            Log.d(TAG, "runSync: another run holds the downloader, leaving it to that one")
            return SyncOutcome.NotOurs
        }

        try {
            // Safe to wait for Idle directly, with no preceding "has it started yet" wait:
            // the claim inside syncPlaylists is a synchronous compareAndSet off Idle, so
            // the state is already DownloadingPlaylist by the time it returns true. There
            // is no window here for the wait to match the Idle the run has not left yet.
            //
            // Bounded, because the thing being waited on is a whole sync. A wedged yt-dlp
            // process (a stalled socket read holds its thread inside downloadVideo, so
            // finishProcessing never runs) would otherwise suspend this worker until the
            // platform killed it, with its ongoing notification sitting on the user's
            // screen the whole time. The run itself is not cancelled when the ceiling
            // expires -- it continues on applicationScope; this only stops the worker
            // from waiting on it.
            // What is left of the ceiling after everything doWork spent getting here.
            // Floored rather than allowed to go negative, which withTimeoutOrNull would
            // treat as "already expired" and return from without ever suspending -- the
            // worker would abandon a sync it had just successfully claimed.
            val remaining =
                (SYNC_COMPLETION_TIMEOUT_MS - (SystemClock.elapsedRealtime() - startedAt))
                    .coerceAtLeast(MIN_COMPLETION_WAIT_MS)
            val finished = withTimeoutOrNull(remaining) {
                Downloader.downloaderState.first { it is Downloader.State.Idle }
            }
            if (finished == null) {
                Log.w(TAG, "runSync: sync still running after the wait ceiling, leaving it to run")
                return SyncOutcome.NotOurs
            }

            // Published by finishProcessing just before the state returns to Idle, so it
            // is already in place by the time the wait above resumes. `error` is set only
            // when the run aborted before it could compare anything; a run that merely
            // failed some individual items did its job and should not be retried wholesale.
            val error = Downloader.syncResult.value?.error
            if (error != null) {
                Log.w(TAG, "runSync: sync aborted ($error)")
                return SyncOutcome.Failed
            }
            return SyncOutcome.Completed
        } finally {
            // Cleared on every path, cancellation included -- and cancellation is the
            // likely one: WorkManager stops a worker that outruns its execution window,
            // which cancels this coroutine mid-wait. Leaving it set would make the next
            // manual sync silently skip binding its own service.
            App.isWorkerForeground.set(false)
            // And the binding question re-asked, for the same reason the lost-claim path
            // above asks it: this worker was the run's foreground host, and the moment the
            // flag drops it stops being one. The paths that get here with the run still
            // going -- the wait ceiling expiring, or WorkManager cancelling this coroutine
            // mid-wait -- leave a sync alive on applicationScope whose ongoing notification
            // disappears with WorkManager's foreground slot, in a process the low-memory
            // killer is now free to take. The state collector will not re-ask on its own:
            // the transition out of Idle happened when this run started, and startService()
            // answered it by returning immediately on the flag.
            //
            // A no-op on the ordinary path, where the run reached Idle before the wait did.
            Downloader.rebindServiceIfRunning()
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

        /**
         * How long to wait for yt-dlp and friends to finish unpacking.
         *
         * A minute is far longer than the unpack takes even on slow storage; what it is
         * really sized against is the case where the init threw and [App.binariesReady]
         * will never complete at all.
         */
        private const val BINARY_INIT_TIMEOUT_MS = 60 * 1000L

        /**
         * How many back-to-back attempts a failing scheduled run gets before it waits for
         * the next period instead.
         *
         * WorkManager's backoff is exponential from 30 seconds, so three attempts spans
         * roughly two minutes -- enough to ride out the faults that actually are transient
         * (a listing that timed out, a network that dropped mid-run) without turning a
         * permanent one into an unbounded loop of foreground workers.
         */
        private const val MAX_RUN_ATTEMPTS = 3

        /**
         * How long to wait for a started sync to finish before the worker stops waiting.
         *
         * Sized against the platform, not against the work. WorkManager stops a worker
         * that outruns its execution window -- ten minutes for a plain one, and a
         * foreground one is stopped too, with `stopReason` TIMEOUT on API 31+ or under
         * Doze and standby pressure. So a ceiling above that window can never fire: the
         * platform's cancellation always arrives first, and the two-hour value this used
         * to hold was dead code dressed as a safety net.
         *
         * Nine minutes leaves the ordinary case untouched -- the wait ends when the sync
         * does, which for a typical run is well inside it -- while giving the worker a
         * chance to return under its own power, clearing [App.isWorkerForeground] and
         * releasing the foreground slot deliberately, rather than being cut down mid-wait.
         * A run still going at that point is not abandoned: it continues on
         * applicationScope, and the next scheduled run finds the downloader busy and
         * stands down.
         *
         * Counted against the worker's own start, not against the wait's -- see the
         * budget [runSync] is given. The platform's window opens when `doWork` does, so
         * anything spent before the wait (the binary unpack, which can be a full minute on
         * a cold first run) comes out of the same ten minutes. Measuring the ceiling from
         * the wait alone let the two add up to more than the window, which is exactly the
         * overrun this constant exists to stay inside.
         */
        private const val SYNC_COMPLETION_TIMEOUT_MS = 9 * 60 * 1000L

        /**
         * The smallest wait the run is given once the budget above is spent.
         *
         * Only reachable when the setup ahead of the wait ran long, and it exists because
         * the alternative is worse: a zero or negative ceiling makes `withTimeoutOrNull`
         * return without suspending at all, so the worker would walk away from a sync it
         * had just won the claim for -- reporting NotOurs for its own run and leaving
         * [App.isWorkerForeground] to be cleared while the run was still going. A short
         * wait at least gives a quick sync the chance to finish and be reported honestly.
         */
        private const val MIN_COMPLETION_WAIT_MS = 30 * 1000L

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
                // landing under WorkManager's 15-minute floor. Checked against the same
                // list the settings screen offers, so an interval the UI cannot label is
                // also an interval this will not schedule.
                if (AUTO_SYNC_INTERVALS.any { it.hours == stored }) stored
                else AUTO_SYNC_DEFAULT_INTERVAL_HOURS
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
         * Applies a settings change to the schedule, off the main thread.
         *
         * The same work as [reschedule]: UPDATE already replaces a changed spec in place,
         * so nothing extra is needed. Kept as its own name because the call sites read
         * better for it, and because cancelling first -- which an earlier version did --
         * was both unnecessary and subtly wrong: `cancelUniqueWork` completes
         * asynchronously, so the enqueue that followed it could race the cancel.
         *
         * Where [reschedule] is called once per start from a caller that already has an IO
         * context, this one is called from Compose click handlers -- every toggle of the
         * auto-sync switch, every interval pick -- which run on the main thread.
         * `getInstance` opens the WorkDatabase and the enqueue writes to disk, so calling
         * it straight from the handler janks the frame the tap animates in. The
         * `runCatching` matters just as much: WorkManager throws if it is not initialised
         * or its database errors, and an exception out of a click handler reaches the
         * default uncaught handler installed in `App.onCreate` and takes the app down on a
         * settings tap.
         *
         * Fire-and-forget by design. Nothing on screen waits for the schedule -- the row
         * already reflects the preference, which is written before this is called.
         */
        fun applySettingsChange(context: Context) {
            val appContext = context.applicationContext
            App.applicationScope.launch(Dispatchers.IO) {
                runCatching { reschedule(appContext) }
                    .onFailure { Log.e(TAG, "applySettingsChange: could not reschedule", it) }
            }
        }
    }
}
