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
import com.junkfood.seal.util.AudioFileData
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.YOUTUBE_API_KEY
import com.junkfood.seal.util.YouTubeApiService
import com.junkfood.seal.util.scanAudioFilesWithDocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Singleton state holder that runs playlist syncs. Owned by the UI and by
 * [DownloadService], which keeps the process alive while a sync is in flight.
 */
object Downloader {

    private const val TAG = "Downloader"

    /** Audio containers yt-dlp may produce, used when scanning the destination folder. */
    private val AUDIO_EXTENSIONS =
        setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "webm", "flac", "wav")

    /**
     * Matches the `[videoId]` yt-dlp appends via the output template, which always sits
     * immediately before the extension. Anchored to the end rather than matching the
     * first bracket group: titles routinely carry their own tags (`[Official Video]`,
     * `[Remix_2024]`), and picking one of those up yields an id no playlist can claim,
     * so the file would be deleted and re-downloaded on every sync.
     */
    private val VIDEO_ID_PATTERN = Regex("\\[([a-zA-Z0-9_-]{6,50})]\\.[^.]+$")

    sealed class State {
        data class DownloadingPlaylist(
            val currentItem: Int = 0,
            val itemCount: Int = 0,
        ) : State()

        data object Idle : State()
        data object Updating : State()
    }

    sealed class ErrorState(
        open val url: String = "",
        open val report: String = "",
    ) {
        data class DownloadError(override val url: String, override val report: String) :
            ErrorState(url = url, report = report)

        data class FetchInfoError(override val url: String, override val report: String) :
            ErrorState(url = url, report = report)

        data object None : ErrorState()

        val title: String
            @Composable get() = when (this) {
                is DownloadError -> stringResource(id = R.string.download_error_msg)
                is FetchInfoError -> stringResource(id = R.string.fetch_info_error_msg)
                None -> ""
            }
    }

    /**
     * Outcome of the last completed sync, for the summary card on the home screen. Null
     * until a run finishes, and cleared once the card is dismissed.
     */
    data class SyncResult(val downloaded: Int, val deleted: Int, val cancelled: Boolean = false)

    private val mutableDownloaderState: MutableStateFlow<State> = MutableStateFlow(State.Idle)
    private val mutableTaskState = MutableStateFlow(DownloadTaskItem())
    private val mutableErrorState: MutableStateFlow<ErrorState> = MutableStateFlow(ErrorState.None)
    private val mutableSyncResult: MutableStateFlow<SyncResult?> = MutableStateFlow(null)

    val downloaderState = mutableDownloaderState.asStateFlow()
    val errorState = mutableErrorState.asStateFlow()
    val syncResult = mutableSyncResult.asStateFlow()

    /** Dismisses the sync summary card. */
    fun clearSyncResult() {
        mutableSyncResult.update { null }
    }

    /**
     * Stops an in-flight sync. The download loop checks the state before each item and
     * between the fetch and download steps, so flipping off [State.DownloadingPlaylist]
     * unwinds it at the next checkpoint and the `finally` block reports what it managed
     * to finish. The already-downloaded files are kept.
     */
    fun cancelSync() {
        if (mutableDownloaderState.value is State.DownloadingPlaylist) {
            updateState(State.Idle)
        }
    }

    /** Progress of the video currently downloading, for the sync UI. */
    val taskState = mutableTaskState.asStateFlow()

    init {
        // Keep the foreground service bound exactly while something is running.
        applicationScope.launch {
            downloaderState.collect { state ->
                if (state is State.Idle) stopService() else startService()
            }
        }
    }

    private fun isDownloaderAvailable(): Boolean {
        if (downloaderState.value !is State.Idle) {
            ToastUtil.showToast(context.getString(R.string.task_running))
            return false
        }
        return true
    }

    fun updateState(state: State) = mutableDownloaderState.update { state }

    private fun clearErrorState() {
        mutableErrorState.update { ErrorState.None }
    }

    /**
     * Syncs the audio folder against every saved playlist: downloads playlist items that
     * are missing locally, and deletes local files that are no longer in any playlist.
     */
    fun syncPlaylists(playlists: List<PlaylistEntry>) {
        if (!isDownloaderAvailable()) return
        if (playlists.isEmpty()) {
            ToastUtil.showToast(context.getString(R.string.sync_no_playlists))
            return
        }

        val apiKey = YOUTUBE_API_KEY.getString()
        if (apiKey.isBlank()) {
            ToastUtil.showToast(context.getString(R.string.sync_no_api_key))
            return
        }

        Log.d(TAG, "syncPlaylists: starting sync for ${playlists.size} playlists")
        // Drop the previous run's error and summary now that a new one is starting.
        clearErrorState()
        clearSyncResult()
        mutableTaskState.update { DownloadTaskItem() }
        mutableDownloaderState.update { State.DownloadingPlaylist() }

        applicationScope.launch(Dispatchers.IO) {
            // Counted outside the body so the finally block can report them on every exit
            // path, cancellation included.
            var downloadedCount = 0
            var deletedCount = 0

            try {
                refreshPlaylistMetadata(playlists, apiKey)

                val preferences = DownloadUtil.DownloadPreferences(
                    extractAudio = true,
                    embedThumbnail = true,
                    embedMetadata = true,
                    cropArtwork = true
                )

                // Step 1: enumerate every video across every playlist.
                val remote = fetchRemoteVideos(playlists, preferences) ?: return@launch

                if (remote.videos.isEmpty()) {
                    Log.e(TAG, "syncPlaylists: abort, no playlist videos fetched")
                    ToastUtil.showToast(context.getString(R.string.sync_no_playlist_data))
                    return@launch
                }

                // Step 2: scan the destination folder.
                val existingFiles = scanExistingAudioFiles() ?: return@launch

                // Step 3: index local files by embedded video id, and by normalized basename
                // so files downloaded before ids were in the template still match.
                val filesByVideoId = mutableMapOf<String, AudioFileData>()
                val localBasenames = mutableSetOf<String>()
                existingFiles.forEach { file ->
                    localBasenames.add(normalizeName(file.name.substringBeforeLast('.')))
                    VIDEO_ID_PATTERN.find(file.name)?.groupValues?.get(1)?.let {
                        filesByVideoId[it] = file
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
                filesToDelete.forEach { (videoId, file) ->
                    runCatching {
                        if (DocumentFile.fromSingleUri(context, file.uri)?.delete() == true) {
                            Log.d(TAG, "syncPlaylists: deleted ${file.name} ($videoId)")
                        } else {
                            Log.w(TAG, "syncPlaylists: failed to delete ${file.name} ($videoId)")
                        }
                    }.onFailure { Log.e(TAG, "syncPlaylists: failed to delete ${file.name}", it) }
                }
                deletedCount = filesToDelete.size

                // Step 6: download whatever is still missing.
                val videosToDownload = remote.videos.filterKeys { it !in presentVideoIds }
                val downloadCount = videosToDownload.size
                Log.d(
                    TAG,
                    "syncPlaylists: ${remote.videos.size} remote, ${existingFiles.size} local, " +
                            "$downloadCount to download, ${filesToDelete.size} to delete"
                )

                if (downloadCount == 0) {
                    ToastUtil.showToast(context.getString(R.string.sync_already_synced))
                    return@launch
                }

                videosToDownload.entries.forEachIndexed { index, (videoId, source) ->
                    if (downloaderState.value !is State.DownloadingPlaylist) {
                        Log.d(TAG, "syncPlaylists: cancelled")
                        return@launch
                    }

                    mutableDownloaderState.update {
                        if (it is State.DownloadingPlaylist) {
                            it.copy(currentItem = index + 1, itemCount = downloadCount)
                        } else return@launch
                    }
                    NotificationUtil.updateServiceNotificationForPlaylist(index + 1, downloadCount)

                    val (playlistUrl, playlistIndex) = source
                    Log.d(TAG, "syncPlaylists: [${index + 1}/$downloadCount] $videoId")

                    DownloadUtil.fetchVideoInfoFromUrl(
                        url = playlistUrl,
                        playlistItem = playlistIndex,
                        preferences = preferences
                    ).onSuccess { videoInfo ->
                        if (downloaderState.value !is State.DownloadingPlaylist) return@launch
                        // Download the single video rather than the playlist, so yt-dlp does not
                        // re-walk every item for each entry.
                        downloadVideo(videoInfo, preferences)
                            .onSuccess { downloadedCount++ }
                            .onFailure { th ->
                                reportItemError(th, videoInfo.originalUrl, isFetchingInfo = false)
                            }
                    }.onFailure { th ->
                        reportItemError(th, playlistUrl, isFetchingInfo = true)
                    }
                }

                Log.d(TAG, "syncPlaylists: complete")
                // The summary card on the home screen reports the counts now; a toast on top
                // of it would say the same thing twice.
            } finally {
                // Every exit path lands here, so a cancelled or failed run still clears the
                // notification and returns to Idle. Without this the state stays
                // DownloadingPlaylist forever, the foreground service is never stopped, and
                // isDownloaderAvailable() rejects every later sync until the process dies.
                finishProcessing(
                    downloaded = downloadedCount,
                    deleted = deletedCount,
                    // Whoever cancelled flipped the state off DownloadingPlaylist first.
                    cancelled = downloaderState.value !is State.DownloadingPlaylist,
                )
            }
        }
    }

    /** A remote video: which playlist it came from, and its 1-based index within it. */
    private data class VideoSource(val playlistUrl: String, val playlistIndex: Int)

    private data class RemoteVideos(
        val videos: Map<String, VideoSource>,
        /** videoId -> normalized title, for matching files downloaded without an id suffix. */
        val normalizedTitles: Map<String, String>,
    )

    /** Refreshes stored playlist metadata; failures for one playlist don't stop the rest. */
    private suspend fun refreshPlaylistMetadata(playlists: List<PlaylistEntry>, apiKey: String) {
        Log.d(TAG, "refreshPlaylistMetadata: refreshing ${playlists.size} playlists")
        playlists.forEach { playlist ->
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
    }

    /**
     * Enumerates every video in every playlist. Returns null if any playlist failed to
     * fetch: a partial listing would make step 5 delete files that are still wanted.
     */
    private fun fetchRemoteVideos(
        playlists: List<PlaylistEntry>,
        preferences: DownloadUtil.DownloadPreferences,
    ): RemoteVideos? {
        val videos = mutableMapOf<String, VideoSource>()
        val normalizedTitles = mutableMapOf<String, String>()
        var failures = 0

        playlists.forEach { entry ->
            DownloadUtil.getPlaylistOrVideoInfo(
                playlistURL = entry.url,
                downloadPreferences = preferences
            ).onSuccess { info ->
                when (info) {
                    is PlaylistResult -> info.entries.orEmpty()
                        .forEachIndexed { index, playlistItem ->
                            val videoId = playlistItem.id ?: return@forEachIndexed
                            videos[videoId] = VideoSource(entry.url, index + 1)
                            playlistItem.title?.let { normalizedTitles[videoId] = normalizeName(it) }
                        }

                    is VideoInfo -> {
                        videos[info.id] = VideoSource(entry.url, 0)
                        info.title.takeIf { it.isNotEmpty() }
                            ?.let { normalizedTitles[info.id] = normalizeName(it) }
                    }
                }
            }.onFailure {
                failures++
                Log.e(TAG, "fetchRemoteVideos: failed for '${entry.title}': ${it.message}")
            }
        }

        if (failures > 0) {
            ToastUtil.showToast(
                context.getString(R.string.sync_fetch_failed, failures)
            )
            return null
        }
        return RemoteVideos(videos, normalizedTitles)
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
                ToastUtil.showToast(
                    context.getString(R.string.sync_scan_failed, it.message.orEmpty())
                )
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
        s.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")

    @CheckResult
    private fun downloadVideo(
        videoInfo: VideoInfo,
        preferences: DownloadUtil.DownloadPreferences,
    ): Result<List<String>> {
        mutableTaskState.update { DownloadTaskItem(title = videoInfo.title) }
        val taskId = videoInfo.id + preferences.hashCode()
        Log.d(TAG, "downloadVideo: ${videoInfo.id} ${videoInfo.title}")

        return DownloadUtil.downloadVideo(
            videoInfo = videoInfo,
            downloadPreferences = preferences,
            taskId = taskId
        ) { progress, _, line ->
            // Per-item progress goes to the shared service notification counter, not to
            // a notification of its own, so a sync doesn't spam one per video.
            mutableTaskState.update { it.copy(progress = progress, progressText = line) }
        }
    }

    /**
     * Ends the run: clears the ongoing notification (leaving a summary when anything
     * happened) and returns to Idle, which unbinds the foreground service.
     */
    private fun finishProcessing(downloaded: Int = 0, deleted: Int = 0, cancelled: Boolean = false) {
        // No early return when already Idle: a cancelled sync reaches here with the state
        // flipped to Idle by whoever cancelled it, and the ongoing notification still
        // posted. Both steps below are idempotent, so running them twice is harmless.
        NotificationUtil.finishPlaylistNotification(downloaded, deleted)
        mutableTaskState.update { it.copy(progress = 100f, progressText = "") }
        // Publish the outcome for the summary card. This is the in-app replacement for the
        // completion toast, which was the only sign a sync had ever finished.
        mutableSyncResult.update { SyncResult(downloaded, deleted, cancelled) }
        updateState(State.Idle)
        // Deliberately not clearing the error state: any item that failed during the run
        // is the one thing worth showing once it ends. Clearing here wiped the banner
        // before it could render. The next sync clears it on the way in instead.
    }

    /**
     * Records a failure for one playlist item. The sync keeps going: one unavailable
     * video should not abandon the rest of the run.
     */
    private fun reportItemError(th: Throwable, url: String?, isFetchingInfo: Boolean) {
        if (th is YoutubeDL.CanceledException) return
        th.printStackTrace()
        ToastUtil.showToast(
            context.getString(
                if (isFetchingInfo) R.string.fetch_info_error_msg else R.string.download_error_msg
            )
        )

        val report = th.message.toString()
        mutableErrorState.update {
            if (isFetchingInfo) ErrorState.FetchInfoError(url.toString(), report)
            else ErrorState.DownloadError(url.toString(), report)
        }
    }
}
