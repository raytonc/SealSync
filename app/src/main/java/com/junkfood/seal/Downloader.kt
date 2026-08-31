package com.junkfood.seal

import android.net.Uri
import android.util.Log
import androidx.annotation.CheckResult
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App.Companion.applicationScope
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.App.Companion.startService
import com.junkfood.seal.App.Companion.stopService
import com.junkfood.seal.database.objects.PlaylistEntry
import com.junkfood.seal.util.AUDIO_DIRECTORY_URI
import com.junkfood.seal.util.AUDIO_EXTENSIONS
import com.junkfood.seal.util.AudioFileData
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.YOUTUBE_API_KEY
import com.junkfood.seal.util.YouTubeApiService
import com.junkfood.seal.util.clearCachedDataForAudio
import com.junkfood.seal.util.scanAudioFilesWithDocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Singleton state holder that runs playlist syncs. Owned by the UI and by
 * [DownloadService], which keeps the process alive while a sync is in flight.
 */
object Downloader {

    private const val TAG = "Downloader"

    /**
     * How many videos download at once.
     *
     * A sync used to run strictly one at a time, which left the device idle for most of
     * the run: each item spends its first seconds in extractor round trips and its last
     * in an ffmpeg transcode, and neither saturates a phone's network or its cores alone.
     * Overlapping them hides one item's latency behind another's transfer.
     *
     * Kept deliberately small rather than unbounded. Every concurrent item is a separate
     * Python process that imports yt-dlp (tens of MB resident) and then forks ffmpeg, so
     * the ceiling here is memory and the background-process limits Android enforces on a
     * foreground service, not bandwidth -- and yt-dlp already fans out
     * [DownloadUtil.CONCURRENT_FRAGMENTS] connections per item on top of this. Three is
     * enough to keep the pipe busy through the serial phases without risking the
     * low-memory killer mid-sync.
     */
    private const val MAX_CONCURRENT_DOWNLOADS = 3

    /**
     * How many times one item is attempted before the run gives up on it.
     *
     * A download is a single yt-dlp process against a network that a phone is unusually
     * good at losing: a cell handover, a wifi roam, or a throttled extractor response all
     * surface as one item throwing while the rest of the run is fine. That item used to be
     * lost for the whole sync -- the only recovery was running the entire thing again,
     * re-listing every playlist and re-scanning the folder, to retry the one track.
     *
     * Three attempts total, not more: a genuinely unavailable video (deleted, private,
     * region-blocked) fails identically every time, and retrying it is pure cost. The
     * classifier in [isTransient] is what keeps those out of the retry path in the first
     * place.
     */
    private const val MAX_ATTEMPTS = 3

    /**
     * Base backoff between attempts, doubled each time (2s, then 4s).
     *
     * Short on purpose. This is a foreground service the user is often watching, and the
     * failures worth retrying are the ones that clear in seconds -- yt-dlp already does
     * its own [DownloadUtil.LISTING_RETRIES]-style retrying inside a single attempt for
     * anything shorter than that.
     */
    private const val RETRY_BACKOFF_MS = 2_000L

    /**
     * Failure messages that mean "try again", as substrings of the reason yt-dlp reports.
     *
     * Matched against the message rather than an exception type because everything below
     * arrives as the same wrapper throwable out of the yt-dlp process -- the distinction
     * only exists in its stderr. Kept lowercase; the match lowercases the message first.
     *
     * Deliberately a list of *retryable* patterns rather than a list of fatal ones. An
     * unrecognised failure is treated as permanent, so a new kind of hard error costs one
     * attempt, not three.
     *
     * Kept narrow for the same reason. Two earlier entries had to go: a bare `fragment`
     * matched "fragment not found" and "giving up after N fragment retries" -- the latter
     * being yt-dlp reporting that it has *already* exhausted its own retries -- and
     * `unable to download` is the prefix of "unable to download video data" for a private
     * or deleted video. Both turned a permanently dead item into three process launches
     * and six seconds of backoff on every sync, forever.
     */
    private val TRANSIENT_FAILURE_PATTERNS = listOf(
        "timed out",
        "timeout",
        "connection reset",
        "connection refused",
        "connection aborted",
        "connection error",
        "temporary failure",
        "temporarily unavailable",
        "network is unreachable",
        "read operation",
        "incomplete read",
        "content too short",
        "remote end closed",
        "http error 429",
        "http error 500",
        "http error 502",
        "http error 503",
        "http error 504",
        "unable to connect",
        "urlopen error",
        "handshake",
        "broken pipe",
    )

    /**
     * Matches the `[videoId]` yt-dlp appends via the output template, which always sits
     * immediately before the extension. Anchored to the end rather than matching the
     * first bracket group: titles routinely carry their own tags (`[Official Video]`,
     * `[Remix_2024]`), and picking one of those up yields an id no playlist can claim,
     * so the file would be deleted and re-downloaded on every sync.
     */
    private val VIDEO_ID_PATTERN = Regex("\\[([a-zA-Z0-9_-]{6,50})]\\.[^.]+$")

    /**
     * Everything [normalizeName] strips out.
     *
     * Compiled once. It used to be built inline in that function, which a sync calls for
     * every remote video *and* every local file -- so a library of a few thousand tracks
     * paid for a few thousand `Pattern.compile` calls per run to apply the same pattern
     * each time.
     */
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

    /** Makes each download launch's process id unique. See [downloadVideo]. */
    private val downloadCounter = java.util.concurrent.atomic.AtomicLong()

    /**
     * Which step of the sync is running, for the card on the home screen.
     *
     * A sync is not only downloads. It lists every playlist, scans the folder, deletes what
     * no longer belongs, and only then downloads -- and the first three take real time on a
     * large library. The card used to have no way to say so: with nothing queued yet its
     * item count was zero, so every one of those steps rendered as the same "Preparing"
     * placeholder, and a run that spent a minute deleting files looked stalled.
     */
    enum class Phase {
        /** Listing every playlist's videos. One line, however many playlists there are. */
        Fetching,

        /** Reading the destination folder to see what is already there. */
        Scanning,

        /** Removing local files that no longer belong to any playlist. */
        Deleting,

        /** Downloading what is missing. The only phase with a per-item queue behind it. */
        Downloading,
    }

    sealed class State {
        /**
         * [currentItem] counts items that have *finished* (successfully or not) plus those
         * in flight, so the "n of m" it drives still advances monotonically now that
         * several items download at once. [itemCount] is the total the run set out to do.
         *
         * [phase] is which step is running and [deleted] how many files have been removed
         * so far, both so the card can describe the run before any download starts.
         */
        data class DownloadingPlaylist(
            val currentItem: Int = 0,
            val itemCount: Int = 0,
            val phase: Phase = Phase.Fetching,
            val deleted: Int = 0,
        ) : State()

        data object Idle : State()
        data object Updating : State()
    }

    /**
     * The most recent item failure, for the banner on the home screen.
     *
     * Only downloads report here now. A failed *listing* aborts the whole run instead of
     * failing one item, and says so through the summary card, so the second variant this
     * used to carry was unreachable.
     */
    sealed class ErrorState(
        open val url: String = "",
        open val report: String = "",
    ) {
        data class DownloadError(override val url: String, override val report: String) :
            ErrorState(url = url, report = report)

        data object None : ErrorState()

        val title: String
            @Composable get() = when (this) {
                is DownloadError -> stringResource(id = R.string.download_error_msg)
                None -> ""
            }
    }

    /**
     * Outcome of the last completed sync, for the summary card on the home screen. Null
     * until a run finishes, and cleared once the card is dismissed.
     */
    data class SyncResult(
        val downloaded: Int,
        val deleted: Int,
        val cancelled: Boolean = false,
        /** Downloads that were attempted and threw. */
        val failed: Int = 0,
        /**
         * Set when the run stopped before it could compare anything -- a playlist that
         * would not list, an unreadable folder. Nothing was downloaded and nothing was
         * deleted, but that is emphatically not the same as being up to date, and the
         * card said exactly the wrong thing when it could not tell the two apart.
         */
        val error: String? = null,
    )

    private val mutableDownloaderState: MutableStateFlow<State> = MutableStateFlow(State.Idle)

    /**
     * Why the last listing or folder scan gave up, set by the step that failed and read by
     * the sync body to phrase its abort message.
     *
     * Out-params rather than richer return types because both helpers already use null as
     * "no result", and only one sync runs at a time -- [isDownloaderAvailable] is what
     * guarantees that, so there is no second run to interleave writes with.
     */
    private var failedPlaylists = 0
    private var scanFailure: String? = null

    /**
     * Every track in the current run, keyed by video id, in the order the run enumerated
     * them.
     *
     * Seeded whole and up front rather than grown as items start: several download at
     * once, so "what is happening" is a set, and the queue screen wants the items still
     * waiting as much as the ones in flight. Entries move through their statuses in place
     * and are never removed mid-run, so a finished or failed track stays on screen as the
     * record of what the run did -- the next sync is what clears it.
     *
     * Updates arrive from every download's progress callback on its own thread; a
     * [MutableStateFlow] of an immutable map updated through [MutableStateFlow.update]
     * keeps those read-modify-writes atomic without a lock around the callback.
     */
    private val mutableQueue = MutableStateFlow<Map<String, TrackDownload>>(emptyMap())
    private val mutableErrorState: MutableStateFlow<ErrorState> = MutableStateFlow(ErrorState.None)
    private val mutableSyncResult: MutableStateFlow<SyncResult?> = MutableStateFlow(null)

    val downloaderState = mutableDownloaderState.asStateFlow()
    val errorState = mutableErrorState.asStateFlow()
    val syncResult = mutableSyncResult.asStateFlow()

    /** The run's tracks in enumeration order, for the queue screen. */
    val queue: StateFlow<List<TrackDownload>> = mutableQueue
        .map { it.values.toList() }
        .stateIn(applicationScope, SharingStarted.Eagerly, emptyList())

    /** Tallies over [queue], for the queue header and the home card. */
    val queueSummary: StateFlow<QueueSummary> = mutableQueue
        .map { it.values.toSummary() }
        .stateIn(applicationScope, SharingStarted.Eagerly, QueueSummary())

    /**
     * Titles of the items downloading right now, for the home screen's sync card.
     *
     * Derived here rather than on the card, which used to collect the whole [queue] just to
     * pick these few out of it. That made the home screen recompose against a list of every
     * track in the run on every progress tick of every download -- hundreds of entries, a
     * few times a second, to render at most [MAX_CONCURRENT_DOWNLOADS] lines. [distinctUntilChanged]
     * then holds the emission back entirely while only the progress numbers move, which is
     * most of the time: the set of active titles only changes when an item starts or finishes.
     */
    val activeTitles: StateFlow<List<String>> = mutableQueue
        .map { tracks ->
            tracks.values.mapNotNull { track ->
                track.title.takeIf { track.status is TrackDownload.Status.Downloading }
            }
        }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, emptyList())

    private fun Collection<TrackDownload>.toSummary(): QueueSummary {
        if (isEmpty()) return QueueSummary()
        var done = 0
        var failed = 0
        var skipped = 0
        var downloading = 0
        var queued = 0
        var retrying = 0
        // Finished items count as a whole unit and running ones as their own fraction, so
        // the overall bar advances continuously instead of stepping once per track.
        var completedUnits = 0.0
        forEach { track ->
            when (val status = track.status) {
                is TrackDownload.Status.Queued -> queued++
                is TrackDownload.Status.Downloading -> {
                    downloading++
                    completedUnits += (status.progress / 100f).coerceIn(0f, 1f)
                }

                // Contributes nothing to the bar: the attempt that just failed wrote no
                // usable bytes, and the next one starts from zero.
                is TrackDownload.Status.Retrying -> retrying++

                is TrackDownload.Status.Done -> {
                    done++
                    completedUnits += 1
                }

                is TrackDownload.Status.Failed -> {
                    failed++
                    completedUnits += 1
                }

                // Settled, not pending: a cancelled run should reach the end of the bar
                // rather than sitting short of it forever.
                is TrackDownload.Status.Skipped -> {
                    skipped++
                    completedUnits += 1
                }
            }
        }
        return QueueSummary(
            total = size,
            done = done,
            failed = failed,
            skipped = skipped,
            downloading = downloading,
            queued = queued,
            retrying = retrying,
            progress = (completedUnits / size).toFloat(),
        )
    }

    /** Dismisses the sync summary card. */
    fun clearSyncResult() {
        mutableSyncResult.update { null }
    }

    /**
     * Stops an in-flight sync. Every queued item checks the state once it takes its
     * download slot, so flipping off [State.DownloadingPlaylist] drains the queue without
     * starting anything new, and the `finally` block reports what the run managed to
     * finish. The already-downloaded files are kept.
     *
     * The handful of items already downloading run to completion -- their yt-dlp
     * processes are not killed -- so the sync ends once the last of them lands rather
     * than instantly. This is the same behaviour as before downloads ran in parallel,
     * only now it can be up to [MAX_CONCURRENT_DOWNLOADS] items instead of one.
     */
    fun cancelSync() {
        if (mutableDownloaderState.value is State.DownloadingPlaylist) {
            updateState(State.Idle)
        }
    }

    init {
        // Keep the foreground service bound exactly while something is running.
        applicationScope.launch {
            downloaderState.collect { state ->
                if (state is State.Idle) stopService() else startService()
            }
        }
    }

    private fun isDownloaderAvailable(silent: Boolean = false): Boolean {
        if (downloaderState.value !is State.Idle) {
            if (!silent) ToastUtil.showToast(context.getString(R.string.task_running))
            return false
        }
        return true
    }

    fun updateState(state: State) = mutableDownloaderState.update { state }

    /**
     * Moves the run to its next step, if it is still running, and says so on the ongoing
     * notification as well -- it is the only view of the sync once the app is backgrounded.
     */
    private fun updatePhase(phase: Phase) {
        var moved = false
        mutableDownloaderState.update {
            if (it is State.DownloadingPlaylist) {
                moved = true
                it.copy(phase = phase)
            } else it
        }
        if (moved) NotificationUtil.updateServiceNotificationForPhase(phase)
    }

    private fun clearErrorState() {
        mutableErrorState.update { ErrorState.None }
    }

    /**
     * Syncs the audio folder against every saved playlist: downloads playlist items that
     * are missing locally, and deletes local files that are no longer in any playlist.
     *
     * [silent] suppresses the run's progress and completion toasts. A scheduled sync sets
     * it: those toasts exist to acknowledge a tap the user just made, and firing them at
     * someone who is in another app entirely -- three times, over several minutes, for
     * work they did not ask for right now -- is how a background feature makes itself
     * unwelcome. The ongoing notification is the right channel for a run nobody started
     * by hand, and it carries the same information.
     */
    fun syncPlaylists(playlists: List<PlaylistEntry>, silent: Boolean = false) {
        // These three rejections are toast-worthy for a tap and never for a scheduled run.
        // The worker checks all three itself before calling, but those checks are a
        // check-then-act: a playlist can be deleted, or a manual sync can start, in the
        // window between them and here. Without honouring silent, that race surfaces as
        // "A sync is already running" popping up over whatever app the user is in.
        if (!isDownloaderAvailable(silent)) return
        if (playlists.isEmpty()) {
            if (!silent) ToastUtil.showToast(context.getString(R.string.sync_no_playlists))
            return
        }

        val apiKey = YOUTUBE_API_KEY.getString()
        if (apiKey.isBlank()) {
            if (!silent) ToastUtil.showToast(context.getString(R.string.sync_no_api_key))
            return
        }

        Log.d(TAG, "syncPlaylists: starting sync for ${playlists.size} playlists")
        // Drop the previous run's error and summary now that a new one is starting.
        clearErrorState()
        clearSyncResult()
        // The previous run's rows are the record of that run and stay visible on the
        // queue screen until here -- a new run is what clears them.
        mutableQueue.update { emptyMap() }
        failedPlaylists = 0
        scanFailure = null
        mutableDownloaderState.update { State.DownloadingPlaylist() }

        applicationScope.launch(Dispatchers.IO) {
            // Counted outside the body so the finally block can report them on every exit
            // path, cancellation included. Downloads finish on several coroutines at once,
            // so the successes are tallied atomically rather than with `++`, which would
            // drop increments that interleave.
            val downloadedCount = AtomicInteger()
            val failedCount = AtomicInteger()
            var deletedCount = 0
            // Set by whichever early return stopped the run, and read in the finally block.
            // Without it every abort reached the summary card as a plain (0, 0), which the
            // card could only render as "already up to date" -- the most misleading thing
            // it could possibly say about a sync that never got as far as comparing.
            var abortReason: String? = null

            try {
                val preferences = DownloadUtil.DownloadPreferences(
                    embedMetadata = true,
                    cropArtwork = true
                )

                // Step 1: enumerate every video across every playlist. The metadata refresh
                // only feeds the library UI and nothing below depends on it, so it runs
                // alongside the listing instead of delaying it by a full API round trip per
                // playlist. It is still awaited before the sync ends so a cancelled run
                // does not leave a write racing against the next one.
                updatePhase(Phase.Fetching)
                // Once per run, here rather than inside the per-playlist listing call --
                // that fired one identical toast per saved playlist. A sync can be started
                // from the launcher shortcut with no UI at all, so this is the only
                // acknowledgement that the tap did anything.
                if (!silent) {
                    ToastUtil.showToast(context.getString(R.string.fetching_playlist_info))
                }
                val metadataRefresh = launch {
                    runCatching { refreshPlaylistMetadata(playlists, apiKey) }
                        .onFailure { Log.e(TAG, "syncPlaylists: metadata refresh failed", it) }
                }
                val remote = fetchRemoteVideos(playlists)
                metadataRefresh.join()
                if (remote == null) {
                    // Named counts where we have them: "2 playlists could not be read" is
                    // actionable in a way that a bare failure notice is not.
                    abortReason = failedPlaylists.takeIf { it > 0 }
                        ?.let { context.getString(R.string.sync_fetch_failed, it) }
                        ?: context.getString(R.string.sync_abort_fetch)
                    return@launch
                }

                if (remote.videos.isEmpty()) {
                    Log.e(TAG, "syncPlaylists: abort, no playlist videos fetched")
                    abortReason = context.getString(R.string.sync_no_playlist_data)
                    return@launch
                }

                // Step 2: scan the destination folder.
                updatePhase(Phase.Scanning)
                val existingFiles = scanExistingAudioFiles() ?: run {
                    abortReason = scanFailure?.takeIf { it.isNotBlank() }
                        ?.let { context.getString(R.string.sync_scan_failed, it) }
                        ?: context.getString(R.string.sync_abort_scan)
                    return@launch
                }

                // Step 3: index local files by embedded video id, and by normalized basename
                // so files downloaded before ids were in the template still match.
                //
                // Several files can carry the same id. A re-download that picks a different
                // container leaves the old extension behind ("Song [abc123].opus" beside
                // "Song [abc123].m4a"), and an interrupted run can leave a partial next to
                // the finished file. Keyed by id to a single file, the last one indexed won
                // and the rest became invisible to the delete step below -- so a track
                // dropped from a playlist left its duplicates in the folder permanently,
                // with no way to reach them short of deleting by hand.
                val filesByVideoId = mutableMapOf<String, MutableList<AudioFileData>>()
                val localBasenames = mutableSetOf<String>()
                existingFiles.forEach { file ->
                    localBasenames.add(normalizeName(file.name.substringBeforeLast('.')))
                    VIDEO_ID_PATTERN.find(file.name)?.groupValues?.get(1)?.let {
                        filesByVideoId.getOrPut(it) { mutableListOf() }.add(file)
                    }
                }

                // Step 4: a remote video is present if its id matches a file, or its title does.
                val presentVideoIds = remote.videos.keys.filterTo(mutableSetOf()) { videoId ->
                    filesByVideoId.containsKey(videoId) ||
                            remote.normalizedTitles[videoId]?.let(localBasenames::contains) == true
                }

                // Step 5: delete identifiable local files that no longer belong to any playlist.
                // Only files carrying an id are eligible, so untracked files are never touched.
                val filesToDelete = filesByVideoId.filterKeys { it !in remote.videos }
                if (filesToDelete.isNotEmpty()) updatePhase(Phase.Deleting)
                // Counts files actually removed, not files attempted. A delete can fail --
                // the provider revokes the grant, the file is gone already, the card is
                // read-only -- and reporting the attempt told the user their folder had
                // been tidied when it had not, which is the one thing the summary card
                // must not get wrong.
                var deletedFiles = 0
                filesToDelete.forEach { (videoId, files) ->
                    files.forEach { file ->
                        runCatching {
                            if (DocumentFile.fromSingleUri(context, file.uri)?.delete() == true) {
                                deletedFiles++
                                // The artwork cache and the download's sidecars sit in app
                                // storage rather than in the synced folder, so removing the
                                // audio leaves both. Over many syncs that is unbounded
                                // growth in the cache dir for tracks that no longer exist.
                                clearCachedDataForAudio(context, file.uri, file.name)
                                Log.d(TAG, "syncPlaylists: deleted ${file.name} ($videoId)")
                            } else {
                                Log.w(
                                    TAG,
                                    "syncPlaylists: failed to delete ${file.name} ($videoId)"
                                )
                            }
                        }.onFailure {
                            Log.e(TAG, "syncPlaylists: failed to delete ${file.name}", it)
                        }
                    }
                }
                deletedCount = deletedFiles
                // Published as it lands, so a run whose only work is deletion has something
                // truthful to show instead of sitting on the "preparing" placeholder.
                mutableDownloaderState.update {
                    if (it is State.DownloadingPlaylist) it.copy(deleted = deletedCount) else it
                }
                if (deletedCount > 0) {
                    NotificationUtil.updateServiceNotificationForPhase(
                        Phase.Deleting, deletedCount
                    )
                }

                // Step 6: download whatever is still missing.
                val videosToDownload = remote.videos.filterKeys { it !in presentVideoIds }
                val downloadCount = videosToDownload.size
                Log.d(
                    TAG,
                    "syncPlaylists: ${remote.videos.size} remote, ${existingFiles.size} local, " +
                            "$downloadCount to download, $deletedCount deleted"
                )

                if (downloadCount == 0) {
                    // Nothing to download, but not necessarily nothing done -- this run may
                    // have just deleted files. The completion toast in finishProcessing
                    // reports whichever it was, which the old unconditional "already synced"
                    // toast here could not: it fired even for runs that had removed files.
                    return@launch
                }

                updatePhase(Phase.Downloading)
                // The listing, scan and delete steps can run for minutes on a large library,
                // so mark the point where transfers actually begin -- and say how many, which
                // is the first moment the run knows. Shortcut-triggered syncs have no other
                // sign of this, and "fetching" followed by a long silence reads as a stall.
                if (!silent) {
                    ToastUtil.showToast(
                        context.resources.getQuantityString(
                            R.plurals.sync_starting_downloads, downloadCount, downloadCount
                        )
                    )
                }

                // Seed the whole run before any of it starts, so the queue screen can show
                // what is waiting rather than only the handful in flight. Insertion order
                // is preserved, and it is also the order items are picked up in.
                mutableQueue.update {
                    videosToDownload.entries.associate { (videoId, title) ->
                        videoId to TrackDownload(videoId = videoId, title = title)
                    }
                }

                val outcome = runDownloads(videosToDownload, preferences)
                downloadedCount.set(outcome.downloaded)
                failedCount.set(outcome.failed)

                Log.d(TAG, "syncPlaylists: complete")
                // The summary card on the home screen reports the counts now; a toast on top
                // of it would say the same thing twice.
            } catch (ce: CancellationException) {
                // Cancellation is not a failure, and the finally block already reports it
                // as its own outcome. Rethrown so the coroutine still unwinds normally.
                throw ce
            } catch (th: Throwable) {
                // Anything the steps above did not anticipate. Caught only to label the run
                // as failed -- without this it reached the finally block indistinguishable
                // from a clean no-op run, and the card announced "already up to date" for a
                // sync that had just thrown. Rethrown so the failure is not swallowed.
                Log.e(TAG, "syncPlaylists: failed", th)
                abortReason = th.toReason()
                throw th
            } finally {
                // Every exit path lands here, so a cancelled or failed run still clears the
                // notification and returns to Idle. Without this the state stays
                // DownloadingPlaylist forever, the foreground service is never stopped, and
                // isDownloaderAvailable() rejects every later sync until the process dies.
                finishProcessing(
                    downloaded = downloadedCount.get(),
                    deleted = deletedCount,
                    failed = failedCount.get(),
                    // Whoever cancelled flipped the state off DownloadingPlaylist first.
                    cancelled = downloaderState.value !is State.DownloadingPlaylist,
                    error = abortReason,
                    silent = silent,
                )
            }
        }
    }

    /**
     * Re-attempts just the tracks the last run failed on, without touching anything else.
     *
     * A full sync to recover a handful of failures is disproportionate: it re-lists every
     * playlist, re-scans the folder and re-runs the delete pass, all to arrive back at the
     * same short list of items. It is also *riskier* than the retry itself -- a listing
     * that fails in the meantime aborts the whole thing, and the failures stay unfixed.
     *
     * So this skips straight to step 6. It downloads by video id, which the failed rows
     * already carry, and deliberately performs no deletion: this run has no picture of
     * what the playlists currently hold, and the one rule the delete step must never break
     * is acting on a partial picture.
     *
     * Rows that succeed are dropped by [retainFailedTracks] at the end, so the queue empties
     * as failures are cleared and keeps whatever is still broken.
     */
    fun retryFailedDownloads() {
        if (!isDownloaderAvailable()) return

        // Snapshot before anything can mutate the map: these are the rows this run owns.
        val failed = mutableQueue.value.values
            .filter { it.status is TrackDownload.Status.Failed }
        if (failed.isEmpty()) {
            ToastUtil.showToast(context.getString(R.string.retry_nothing_to_retry))
            return
        }

        Log.d(TAG, "retryFailedDownloads: retrying ${failed.size} item(s)")
        clearErrorState()
        clearSyncResult()
        // Back to Queued so the rows read as pending work rather than as the previous
        // run's wreckage, and so the summary's progress bar starts from zero.
        mutableQueue.update { tracks ->
            tracks.mapValues { (_, track) ->
                if (track.status is TrackDownload.Status.Failed) {
                    track.copy(status = TrackDownload.Status.Queued)
                } else track
            }
        }
        // Straight to the downloading phase: there is nothing to fetch, scan or delete.
        mutableDownloaderState.update {
            State.DownloadingPlaylist(itemCount = failed.size, phase = Phase.Downloading)
        }

        applicationScope.launch(Dispatchers.IO) {
            var downloaded = 0
            var failedItems = 0
            var abortReason: String? = null

            try {
                val preferences = DownloadUtil.DownloadPreferences(
                    embedMetadata = true,
                    cropArtwork = true
                )
                // Same runner as a full sync's download step, so the two cannot drift.
                val outcome = runDownloads(
                    items = failed.associate { it.videoId to it.title },
                    preferences = preferences,
                )
                downloaded = outcome.downloaded
                failedItems = outcome.failed

                Log.d(TAG, "retryFailedDownloads: complete")
            } catch (ce: CancellationException) {
                throw ce
            } catch (th: Throwable) {
                Log.e(TAG, "retryFailedDownloads: failed", th)
                abortReason = th.toReason()
                throw th
            } finally {
                finishProcessing(
                    downloaded = downloaded,
                    deleted = 0,
                    failed = failedItems,
                    cancelled = downloaderState.value !is State.DownloadingPlaylist,
                    error = abortReason,
                )
            }
        }
    }

    /**
     * Every video across every playlist, as videoId -> title.
     *
     * This used to carry the owning playlist URL and the video's 1-based index within it,
     * which existed only to address the video as `--playlist-items N` of that playlist.
     * Downloads now go straight to the video by id, so the title -- shown while it
     * downloads -- is all that is left to keep.
     */
    private data class RemoteVideos(
        val videos: Map<String, String>,
        /** videoId -> normalized title, for matching files downloaded without an id suffix. */
        val normalizedTitles: Map<String, String>,
    )

    /**
     * Refreshes stored playlist metadata; failures for one playlist don't stop the rest.
     *
     * One independent YouTube API round trip per playlist, so they go out together rather
     * than serially.
     */
    private suspend fun refreshPlaylistMetadata(
        playlists: List<PlaylistEntry>,
        apiKey: String,
    ): Unit = coroutineScope {
        Log.d(TAG, "refreshPlaylistMetadata: refreshing ${playlists.size} playlists")
        playlists.map { playlist ->
            async {
                runCatching {
                    val playlistId = playlist.playlistId
                        ?: YouTubeApiService.extractPlaylistId(playlist.url)
                        ?: return@runCatching
                    val info = YouTubeApiService.getPlaylistInfo(playlistId, apiKey)
                        ?: return@runCatching
                    DatabaseUtil.updatePlaylist(
                        playlist.copy(
                            title = info.title,
                            thumbnailUrl = info.thumbnailUrl,
                            videoCount = info.videoCount,
                            channelTitle = info.channelTitle,
                            description = info.description,
                            lastSynced = System.currentTimeMillis(),
                            playlistId = playlistId
                        )
                    )
                }.onFailure {
                    Log.e(TAG, "refreshPlaylistMetadata: failed for ${playlist.title}", it)
                }
            }
        }.awaitAll()
    }

    /**
     * Enumerates every video in every playlist. Returns null if any playlist failed to
     * fetch: a partial listing would make step 5 delete files that are still wanted.
     *
     * Each listing is a yt-dlp launch that spends most of its wall time blocked on the
     * network, so they run concurrently rather than one after another -- a sync of several
     * playlists used to pay the full round trip once per playlist before it could start.
     * The results are merged on this coroutine afterwards, so the maps stay single-threaded.
     */
    private suspend fun fetchRemoteVideos(playlists: List<PlaylistEntry>): RemoteVideos? =
        coroutineScope {
            val results = playlists
                .map { entry -> entry to async { DownloadUtil.getPlaylistOrVideoInfo(entry.url) } }
                .map { (entry, deferred) -> entry to deferred.await() }

            val videos = mutableMapOf<String, String>()
            val normalizedTitles = mutableMapOf<String, String>()
            var failures = 0

            results.forEach { (entry, result) ->
                result.onSuccess { info ->
                    // Counted per playlist, because an empty listing is the dangerous case
                    // below and only this scope knows which playlist it came from.
                    var listed = 0
                    when (info) {
                        is PlaylistResult -> info.entries.orEmpty().forEach entries@{ item ->
                            val videoId = item.id ?: return@entries
                            val title = item.title.orEmpty()
                            videos[videoId] = title
                            listed++
                            if (title.isNotEmpty()) normalizedTitles[videoId] = normalizeName(title)
                        }

                        is VideoInfo -> {
                            videos[info.id] = info.title
                            listed++
                            info.title.takeIf { it.isNotEmpty() }
                                ?.let { normalizedTitles[info.id] = normalizeName(it) }
                        }
                    }

                    // A listing that succeeds but yields nothing is treated as a failure,
                    // not as "this playlist is now empty". yt-dlp exits 0 with no entries
                    // for a playlist that has gone private, is region-blocked, or whose
                    // page it could not parse -- and the delete step cannot tell that
                    // apart from a genuine emptying, so it would remove every file the
                    // playlist owns. Deleting a whole playlist's worth of audio because a
                    // listing came back thin is exactly the failure the abort-on-error
                    // rule above exists to prevent; this is the same rule for the case
                    // that reports success.
                    //
                    // Genuinely emptying a playlist is rare, and recoverable: the user can
                    // remove it here and the files go with it. A wrong bulk delete is not.
                    if (listed == 0) {
                        failures++
                        Log.e(
                            TAG,
                            "fetchRemoteVideos: '${entry.title}' listed 0 videos, treating " +
                                    "as a failure rather than deleting its files"
                        )
                    }
                }.onFailure {
                    failures++
                    Log.e(TAG, "fetchRemoteVideos: failed for '${entry.title}': ${it.message}")
                }
            }

            if (failures > 0) {
                // No toast: the caller turns this into the summary card's message, which
                // stays put until dismissed rather than fading before it can be read.
                Log.e(TAG, "fetchRemoteVideos: $failures playlist(s) failed, aborting sync")
                failedPlaylists = failures
                return@coroutineScope null
            }
            RemoteVideos(videos, normalizedTitles)
        }

    /** Lists audio files in the configured folder, preferring the SAF tree when set. */
    private fun scanExistingAudioFiles(): List<AudioFileData>? {
        val uriString = AUDIO_DIRECTORY_URI.getString()
        if (uriString.isNotEmpty()) {
            return runCatching {
                scanAudioFilesWithDocumentFile(context, Uri.parse(uriString))
                    .filter { !it.name.startsWith(".trashed-") }
            }.getOrElse {
                Log.e(TAG, "scanExistingAudioFiles: SAF scan failed", it)
                scanFailure = it.message.orEmpty()
                null
            }
        }

        // Legacy path for installs that predate the folder picker.
        Log.w(TAG, "scanExistingAudioFiles: no SAF URI set, falling back to the File API")
        val audioDir = File(App.audioDownloadDir).apply { mkdirs() }
        return audioDir.walkTopDown()
            .filter {
                it.isFile &&
                        it.extension.lowercase() in AUDIO_EXTENSIONS &&
                        !it.name.startsWith(".trashed-")
            }
            .map {
                AudioFileData(
                    uri = Uri.fromFile(it),
                    name = it.name,
                    size = it.length(),
                    lastModified = it.lastModified()
                )
            }
            .toList()
    }

    /** Strips case and punctuation so titles and filenames can be compared. */
    private fun normalizeName(s: String): String =
        s.lowercase(Locale.US).replace(NON_ALPHANUMERIC, "")

    /**
     * Marks one track in the run, if it is still part of it. A no-op once the next sync
     * has cleared the map, so a late callback from a process still winding down cannot
     * resurrect a row that belongs to a finished run.
     */
    private fun updateTrack(videoId: String, transform: (TrackDownload) -> TrackDownload) {
        mutableQueue.update { tracks ->
            val track = tracks[videoId] ?: return@update tracks
            tracks + (videoId to transform(track))
        }
    }

    /** A failure message worth showing on a queue row; some throwables carry none. */
    private fun Throwable.toReason(): String =
        message?.takeIf { it.isNotBlank() }?.trim()?.lines()?.last()
            ?: this::class.simpleName.orEmpty()

    /** What a download pass managed to do, for the caller's summary. */
    private data class DownloadOutcome(val downloaded: Int, val failed: Int)

    /**
     * Downloads [items] (videoId -> title), [MAX_CONCURRENT_DOWNLOADS] at a time, and
     * returns once every one of them has settled.
     *
     * The single download loop for the whole app: a full sync's step 6 and the failed-item
     * retry both run through here. They were separate copies that had already drifted --
     * only one logged its position, only one tallied atomically -- and every future fix to
     * the loop would have had to be made twice.
     *
     * Each item blocks a thread for its whole life (yt-dlp is an external process the
     * wrapper waits on), so they are launched on [Dispatchers.IO], whose pool is sized for
     * exactly that; the semaphore is what actually caps how many run. `coroutineScope`
     * makes this the join point, so a caller's `finally` cannot report the run as over
     * while downloads are still writing files.
     */
    private suspend fun runDownloads(
        items: Map<String, String>,
        preferences: DownloadUtil.DownloadPreferences,
    ): DownloadOutcome {
        val total = items.size
        // Downloads finish on several coroutines at once, so the tallies are atomic rather
        // than `++`, which would drop increments that interleave.
        val downloaded = AtomicInteger()
        val failed = AtomicInteger()
        val startedItems = AtomicInteger()
        val finishedItems = AtomicInteger()
        val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

        coroutineScope {
            items.forEach { (videoId, title) ->
                launch(Dispatchers.IO) {
                    // Checked before taking any slot: a cancellation during the run should
                    // stop everything still queued behind it. Returning from this child
                    // leaves the others alone -- the ones already downloading finish, and
                    // their files are kept.
                    if (downloaderState.value !is State.DownloadingPlaylist) {
                        Log.d(TAG, "runDownloads: cancelled, skipping $videoId")
                        // Marked rather than dropped, so the queue screen says the run was
                        // cut short instead of leaving rows that look like they are still
                        // waiting their turn.
                        updateTrack(videoId) { it.copy(status = TrackDownload.Status.Skipped) }
                        return@launch
                    }

                    val position = startedItems.incrementAndGet()
                    Log.d(TAG, "runDownloads: [$position/$total] $videoId")

                    if (downloadWithRetries(videoId, title, preferences, semaphore)) {
                        downloaded.incrementAndGet()
                    } else {
                        failed.incrementAndGet()
                    }

                    // Counted on the way out, not on the way in: with several running at
                    // once, reporting "n of m" as each one *starts* jumps straight to 3
                    // while nothing has actually landed. Finished items are what the
                    // number is claiming to mean.
                    val done = finishedItems.incrementAndGet()
                    mutableDownloaderState.update {
                        if (it is State.DownloadingPlaylist) {
                            it.copy(currentItem = done, itemCount = total)
                        } else it
                    }
                    NotificationUtil.updateServiceNotificationForPlaylist(
                        done, total, queueSummary.value.downloading
                    )
                }
            }
        }

        return DownloadOutcome(downloaded.get(), failed.get())
    }

    /**
     * Whether a failure is worth another attempt.
     *
     * A cancellation never is -- the run is over and retrying would fight it. Everything
     * else is matched against [TRANSIENT_FAILURE_PATTERNS], and anything unrecognised is
     * treated as permanent: a video that is private, deleted, region-blocked or
     * age-gated fails the same way on every attempt, so retrying it only spends the
     * user's battery and the run's time to arrive at the same answer.
     */
    private fun Throwable.isTransient(): Boolean {
        if (this is YoutubeDL.CanceledException) return false
        val text = (message ?: return false).lowercase(Locale.US)
        return TRANSIENT_FAILURE_PATTERNS.any { it in text }
    }

    /**
     * Downloads one item, retrying transient failures up to [MAX_ATTEMPTS] times. Returns
     * true if the item eventually landed.
     *
     * Settles the item's queue row on every path -- Done, or Failed carrying the attempt
     * count -- so the caller only has to tally the outcome. Between attempts the row goes
     * to [TrackDownload.Status.Retrying] and the coroutine sleeps out a doubling backoff,
     * which is also a cancellation point: a run cancelled while an item is waiting stops
     * there instead of burning the rest of its attempts.
     */
    private suspend fun downloadWithRetries(
        videoId: String,
        title: String,
        preferences: DownloadUtil.DownloadPreferences,
        semaphore: Semaphore,
    ): Boolean {
        var attempt = 1
        while (true) {
            // One permit per attempt, taken here and released before any backoff, rather
            // than one permit held across the whole item.
            //
            // The permit cannot span the backoff. Releasing it around a `delay` and
            // reacquiring in a `finally` looks symmetric but is not: a cancelled coroutine
            // resuming from `Semaphore.acquire()` throws without taking a permit, and the
            // enclosing `withPermit` would then release one it does not hold -- which
            // kotlinx's Semaphore answers with `error("The number of released permits
            // cannot be greater than N")` from inside a finally block, replacing the
            // cancellation and unwinding the whole run's accounting. Scoping the permit to
            // a single attempt removes the asymmetry instead of trying to balance it.
            val result = semaphore.withPermit {
                downloadVideo(videoId, title, preferences)
            }
            result.onSuccess {
                updateTrack(videoId) { it.copy(status = TrackDownload.Status.Done) }
                return true
            }
            val th = result.exceptionOrNull() ?: IllegalStateException("unknown failure")

            // Out of attempts, not worth retrying, or the run is over: settle as failed.
            // The state check matters as much as the other two -- without it a cancelled
            // run would keep re-attempting its in-flight items through their full backoff.
            val giveUp = attempt >= MAX_ATTEMPTS ||
                    !th.isTransient() ||
                    downloaderState.value !is State.DownloadingPlaylist
            if (giveUp) {
                updateTrack(videoId) {
                    it.copy(
                        status = TrackDownload.Status.Failed(th.toReason(), attempts = attempt)
                    )
                }
                reportItemError(th, videoId)
                return false
            }

            val backoff = RETRY_BACKOFF_MS shl (attempt - 1)
            Log.w(
                TAG,
                "downloadWithRetries: $videoId attempt $attempt/$MAX_ATTEMPTS failed " +
                        "(${th.toReason()}), retrying in ${backoff}ms"
            )
            updateTrack(videoId) {
                it.copy(
                    status = TrackDownload.Status.Retrying(
                        attempt = attempt, reason = th.toReason()
                    )
                )
            }
            // Waits outside the permit, so a backing-off item does not idle a download
            // slot -- three of them at once would otherwise stop the run dead.
            delay(backoff)
            attempt++
        }
    }

    /**
     * Downloads one video, publishing its progress into [mutableQueue] for as long as it
     * runs. Safe to call from several coroutines at once: the entry is keyed by [videoId],
     * so concurrent items report side by side instead of overwriting one shared slot.
     *
     * The row is left in place on the way out rather than removed -- the caller settles it
     * as Done or Failed, and it stays on the queue screen as the record of the run.
     */
    @CheckResult
    private fun downloadVideo(
        videoId: String,
        title: String,
        preferences: DownloadUtil.DownloadPreferences,
    ): Result<List<String>> {
        updateTrack(videoId) { it.copy(status = TrackDownload.Status.Downloading()) }
        // Unique per launch, not per video.
        //
        // The wrapper keys its process map on this and throws outright on a duplicate, and
        // it is only guaranteed to have evicted the previous entry once that process has
        // fully exited. A retry re-launches the same video seconds after its last attempt
        // died, so a lingering entry would fail the new attempt with an internal error --
        // which is not in [TRANSIENT_FAILURE_PATTERNS], so the item would settle as
        // permanently failed and the retry would have converted a network blip into a hard
        // failure. The counter makes the collision impossible rather than unlikely.
        val taskId = "$videoId:${preferences.hashCode()}:${downloadCounter.getAndIncrement()}"
        Log.d(TAG, "downloadVideo: $videoId $title")

        return DownloadUtil.downloadVideoById(
            videoId = videoId,
            downloadPreferences = preferences,
            taskId = taskId
        ) { progress, _, line ->
            // Per-item progress goes to the shared service notification counter, not to
            // a notification of its own, so a sync doesn't spam one per video.
            updateTrack(videoId) { track ->
                // Guarded: a callback arriving after the item was settled must not drag
                // the row back into Downloading.
                if (track.status is TrackDownload.Status.Downloading) {
                    track.copy(status = TrackDownload.Status.Downloading(progress, line))
                } else track
            }
        }
    }

    /**
     * Ends the run: clears the ongoing notification (leaving a summary when anything
     * happened) and returns to Idle, which unbinds the foreground service.
     */
    private fun finishProcessing(
        downloaded: Int = 0,
        deleted: Int = 0,
        failed: Int = 0,
        cancelled: Boolean = false,
        error: String? = null,
        silent: Boolean = false,
    ) {
        // No early return when already Idle: a cancelled sync reaches here with the state
        // flipped to Idle by whoever cancelled it, and the ongoing notification still
        // posted. Both steps below are idempotent, so running them twice is harmless.
        NotificationUtil.finishPlaylistNotification(downloaded, deleted, failed, cancelled, error)
        // Said once, whatever the outcome. The summary card covers the app, but a sync
        // started from the launcher shortcut never shows it, and the run would otherwise
        // finish in complete silence. A scheduled run is the exception: it has the
        // completion notification just below, and nobody is waiting on a toast for work
        // they did not start.
        if (!silent) {
            ToastUtil.showToast(syncOutcomeText(downloaded, deleted, failed, cancelled, error))
        }
        // A finished run leaves only what still needs attention.
        retainFailedTracks()
        // Publish the outcome for the summary card. This is the in-app replacement for the
        // completion toast, which was the only sign a sync had ever finished.
        mutableSyncResult.update { SyncResult(downloaded, deleted, cancelled, failed, error) }
        updateState(State.Idle)
        // Deliberately not clearing the error state: any item that failed during the run
        // is the one thing worth showing once it ends. Clearing here wiped the banner
        // before it could render. The next sync clears it on the way in instead.
    }

    /**
     * One sentence describing how the run ended, for the completion toast.
     *
     * Deliberately mirrors the summary card's cases: an abort, a cancellation, a run that
     * changed something, and a genuine no-op are four different outcomes, and collapsing
     * any of them into "already synced" is what made the old toast lie.
     */
    private fun syncOutcomeText(
        downloaded: Int,
        deleted: Int,
        failed: Int,
        cancelled: Boolean,
        error: String?,
    ): String {
        if (error != null) return error

        val parts = listOfNotNull(
            downloaded.takeIf { it > 0 }?.let { context.getString(R.string.sync_downloaded, it) },
            deleted.takeIf { it > 0 }?.let { context.getString(R.string.sync_deleted, it) },
            failed.takeIf { it > 0 }
                ?.let { context.getString(R.string.sync_result_failed_count, it) },
        ).joinToString(", ")

        val prefix = when {
            cancelled -> context.getString(R.string.sync_result_cancelled)
            parts.isEmpty() -> return context.getString(R.string.sync_result_up_to_date)
            else -> context.getString(R.string.sync_complete)
        }
        return if (parts.isEmpty()) prefix else "$prefix: $parts"
    }

    /**
     * Drops every row the run settled successfully, keeping only the failures.
     *
     * A finished sync has nothing to say about the tracks that worked -- the summary card
     * already gives the count, and a list of them is just the queue screen refusing to
     * empty. What does not survive a toast is *why* something failed, so those rows stay
     * until the next sync clears them.
     *
     * Cancelled and still-unstarted items go too: cancelling was deliberate, and an item
     * that never ran is not a problem to report. Anything left mid-flight is swept up here
     * as well -- the normal path settles every item, but a throw out of the sync body skips
     * the download loop entirely, and a row frozen mid-download would otherwise read as
     * live work on a screen the run has already left.
     */
    private fun retainFailedTracks() {
        mutableQueue.update { tracks ->
            tracks.filterValues { it.status is TrackDownload.Status.Failed }
        }
    }

    /**
     * Records a failure for one playlist item. The sync keeps going: one unavailable
     * video should not abandon the rest of the run.
     */
    private fun reportItemError(th: Throwable, url: String?) {
        if (th is YoutubeDL.CanceledException) return
        th.printStackTrace()
        // No toast per failure. Items download several at a time and a bad playlist can
        // fail dozens of them, which queued dozens of identical toasts that then outlived
        // the run they described. The banner below holds the latest reason, the summary
        // card carries the count, and the queue screen keeps every one of them with its
        // own message.

        mutableErrorState.update {
            ErrorState.DownloadError(url.toString(), th.message.toString())
        }
    }
}
