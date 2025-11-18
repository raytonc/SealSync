package com.junkfood.seal

import android.app.PendingIntent
import android.util.Log
import androidx.annotation.CheckResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.junkfood.seal.App.Companion.applicationScope
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.App.Companion.startService
import com.junkfood.seal.App.Companion.stopService
import com.junkfood.seal.Downloader.downloadVideoInPlaylistByIndexList
import com.junkfood.seal.Downloader.downloadVideoWithConfigurations
import com.junkfood.seal.Downloader.getInfoAndDownload
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.database.objects.PlaylistEntry
import com.junkfood.seal.util.COMMAND_DIRECTORY
import com.junkfood.seal.util.CONVERT_WAV
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.Entries
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.VideoClip
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.YOUTUBE_API_KEY
import com.junkfood.seal.util.YouTubeApiService
import com.junkfood.seal.util.toHttpsUrl
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt


/**
 * Singleton Downloader for state holder & perform downloads, used by `Activity` & `Service`
 */
object Downloader {

    private const val TAG = "Downloader"

    sealed class State {
        data class DownloadingPlaylist(
            val currentItem: Int = 0,
            val itemCount: Int = 0,
        ) : State()

        data object DownloadingVideo : State()
        data object FetchingInfo : State()
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


    data class CustomCommandTask(
        val template: CommandTemplate,
        val url: String,
        val output: String,
        val state: State,
        val currentLine: String
    ) {
        fun toKey() = makeKey(url, template.name)
        sealed class State {
            data class Error(val errorReport: String) : State()
            object Completed : State()
            object Canceled : State()
            data class Running(val progress: Float) : State()
        }

        override fun hashCode(): Int {
            return (this.url + this.template.name + this.template.template).hashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CustomCommandTask

            if (template != other.template) return false
            if (url != other.url) return false
            if (output != other.output) return false
            if (state != other.state) return false
            if (currentLine != other.currentLine) return false

            return true
        }


        fun onCopyLog(clipboardManager: ClipboardManager) {
            clipboardManager.setText(AnnotatedString(output))
        }


        fun onRestart() {
            applicationScope.launch(Dispatchers.IO) {
                DownloadUtil.executeCommandInBackground(url, template)
            }
        }


        fun onCopyError(clipboardManager: ClipboardManager) {
            clipboardManager.setText(AnnotatedString(currentLine))
            ToastUtil.makeToast(R.string.error_copied)
        }

        fun onCancel() {
            toKey().run {
                YoutubeDL.destroyProcessById(this)
                onProcessCanceled(this)
            }
        }
    }


    private var currentJob: Job? = null
    private var downloadResultTemp: Result<List<String>> = Result.failure(Exception())

    private val mutableDownloaderState: MutableStateFlow<State> = MutableStateFlow(State.Idle)
    private val mutableTaskState = MutableStateFlow(DownloadTaskItem())
    private val mutablePlaylistResult = MutableStateFlow(PlaylistResult())
    private val mutableErrorState: MutableStateFlow<ErrorState> = MutableStateFlow(ErrorState.None)
    private val mutableProcessCount = MutableStateFlow(0)
    private val mutableQuickDownloadCount = MutableStateFlow(0)

    val mutableTaskList = mutableStateMapOf<String, CustomCommandTask>()

    val taskState = mutableTaskState.asStateFlow()
    val downloaderState = mutableDownloaderState.asStateFlow()
    val playlistResult = mutablePlaylistResult.asStateFlow()
    val errorState = mutableErrorState.asStateFlow()
    val processCount = mutableProcessCount.asStateFlow()

    init {
        applicationScope.launch {
            downloaderState.combine(processCount) { state, cnt ->
                if (cnt > 0) true
                else when (state) {
                    is State.Idle -> false
                    else -> true
                }
            }.combine(mutableQuickDownloadCount) { isRunning, cnt ->
                if (!isRunning) cnt > 0 else true
            }.collect {
                if (it) startService()
                else stopService()
            }

        }
    }

    fun isDownloaderAvailable(): Boolean {
        if (downloaderState.value !is State.Idle) {
            ToastUtil.makeToastSuspend(context.getString(R.string.task_running))
            return false
        }
        return true
    }


    fun makeKey(url: String, templateName: String): String = "${templateName}_$url"

    fun onTaskStarted(template: CommandTemplate, url: String) =
        CustomCommandTask(
            template = template,
            url = url,
            output = "",
            state = CustomCommandTask.State.Running(0f),
            currentLine = ""
        ).run {
            mutableTaskList.put(this.toKey(), this)
        }


    fun updateTaskOutput(template: CommandTemplate, url: String, line: String, progress: Float) {
        val key = makeKey(url, template.name)
        val oldValue = mutableTaskList[key] ?: return
        val newValue = oldValue.run {
            copy(
                output = output + line + "\n",
                currentLine = line,
                state = CustomCommandTask.State.Running(progress)
            )
        }
        mutableTaskList[key] = newValue
    }


    fun onTaskEnded(
        template: CommandTemplate,
        url: String,
        response: String? = null
    ) {
        val key = makeKey(url, template.name)
        NotificationUtil.finishNotification(
            notificationId = key.toNotificationId(),
            title = key,
            text = context.getString(R.string.status_completed),
        )
        mutableTaskList.run {
            val oldValue = get(key) ?: return
            val newValue = oldValue.copy(state = CustomCommandTask.State.Completed).run {
                response?.let { copy(output = response) } ?: this
            }
            this[key] = newValue
        }
        FileUtil.scanDownloadDirectoryToMediaLibrary(COMMAND_DIRECTORY.getString())
    }


    fun onProcessEnded() =
        mutableProcessCount.update { it - 1 }


    fun onProcessCanceled(taskId: String) =
        mutableTaskList.run {
            get(taskId)?.let {
                this.put(
                    taskId,
                    it.copy(state = CustomCommandTask.State.Canceled)
                )
            }
        }

    fun onTaskError(errorReport: String, template: CommandTemplate, url: String) =
        mutableTaskList.run {
            val key = makeKey(url, template.name)
            NotificationUtil.makeErrorReportNotification(
                notificationId = key.toNotificationId(),
                error = errorReport
            )
            val oldValue = mutableTaskList[key] ?: return
            mutableTaskList[key] = oldValue.copy(
                state = CustomCommandTask.State.Error(
                    errorReport
                ), currentLine = errorReport, output = oldValue.output + "\n" + errorReport
            )
        }


    private fun VideoInfo.toTask(playlistIndex: Int = 0, preferencesHash: Int): DownloadTaskItem =
        DownloadTaskItem(
            webpageUrl = webpageUrl.toString(),
            title = title,
            uploader = uploader ?: channel ?: uploaderId.toString(),
            duration = duration?.roundToInt() ?: 0,
            taskId = id + preferencesHash,
            thumbnailUrl = thumbnail.toHttpsUrl(),
            fileSizeApprox = fileSize ?: fileSizeApprox ?: .0,
            playlistIndex = playlistIndex
        )

    fun updateState(state: State) = mutableDownloaderState.update { state }

    fun clearErrorState() {
        mutableErrorState.update { ErrorState.None }
    }

    private fun fetchInfoError(url: String, errorReport: String) {
        mutableErrorState.update { ErrorState.FetchInfoError(url, errorReport) }
    }

    private fun downloadError(url: String, errorReport: String) {
        mutableErrorState.update { ErrorState.DownloadError(url, errorReport) }
    }


    private fun clearProgressState(isFinished: Boolean) {
        mutableTaskState.update {
            it.copy(
                progress = if (isFinished) 100f else 0f,
                progressText = "",
            )
        }
        if (!isFinished)
            downloadResultTemp = Result.failure(Exception())
    }

    fun updatePlaylistResult(playlistResult: PlaylistResult = PlaylistResult()) =
        mutablePlaylistResult.update { playlistResult }

    fun quickDownload(
        url: String,
        downloadPreferences: DownloadUtil.DownloadPreferences = DownloadUtil.DownloadPreferences()
    ) {
        applicationScope.launch(Dispatchers.IO) {
            mutableQuickDownloadCount.update { it + 1 }
            DownloadUtil.fetchVideoInfoFromUrl(
                url = url,
                preferences = downloadPreferences
            )
                .onFailure {
                    manageDownloadError(
                        th = it,
                        url = url,
                        title = url,
                        isFetchingInfo = true
                    )
                }
                .onSuccess { videoInfo ->
                    val taskId = videoInfo.id + downloadPreferences.hashCode()
                    val notificationId = taskId.toNotificationId()
                    ToastUtil.makeToastSuspend(
                        context.getString(R.string.download_start_msg)
                            .format(videoInfo.title)
                    )
                    DownloadUtil.downloadVideo(
                        videoInfo = videoInfo,
                        downloadPreferences = downloadPreferences,
                        taskId = taskId
                    ) { progress, _, line ->
                        NotificationUtil.notifyProgress(
                            notificationId = notificationId,
                            progress = progress.toInt(),
                            text = line,
                            title = videoInfo.title,
                            taskId = taskId
                        )
                    }.onFailure {
                        NotificationUtil.cancelNotification(notificationId)
                        if (it is YoutubeDL.CanceledException) return@onFailure
                        NotificationUtil.makeErrorReportNotification(
                            title = videoInfo.title, notificationId = notificationId,
                            error = it.message.toString()
                        )
                    }.onSuccess {
                        val text =
                            context.getString(if (it.isEmpty()) R.string.status_completed else R.string.download_finish_notification)

                        FileUtil.createIntentForOpeningFile(it.firstOrNull()).run {
                            NotificationUtil.finishNotification(
                                notificationId,
                                title = videoInfo.title,
                                text = text,
                                intent = if (this != null) PendingIntent.getActivity(
                                    context,
                                    0,
                                    this,
                                    PendingIntent.FLAG_IMMUTABLE
                                ) else null
                            )
                        }
                    }
                }
            mutableQuickDownloadCount.update { it - 1 }
        }
    }

    fun getInfoAndDownload(
        url: String,
        downloadPreferences: DownloadUtil.DownloadPreferences = DownloadUtil.DownloadPreferences()
    ) {
        currentJob = applicationScope.launch(Dispatchers.IO) {
            updateState(State.FetchingInfo)
            DownloadUtil.fetchVideoInfoFromUrl(
                url = url,
                preferences = downloadPreferences
            )
                .onFailure {
                    manageDownloadError(
                        th = it,
                        url = url,
                        isFetchingInfo = true
                    )
                }
                .onSuccess { info ->
                    downloadResultTemp = downloadVideo(
                        videoInfo = info,
                        preferences = downloadPreferences
                    )
                }
        }
    }

    /**
     * Triggers a download with extra configurations made by user in the custom format selection page
     */
    fun downloadVideoWithConfigurations(
        videoInfo: VideoInfo,
        formatList: List<Format>,
        videoClips: List<VideoClip>,
        splitByChapter: Boolean,
        newTitle: String,
        selectedSubtitleCodes: List<String>,
    ) {
        currentJob = applicationScope.launch(Dispatchers.IO) {
            val fileSize = formatList.fold(.0) { acc, format ->
                acc + (format.fileSize ?: format.fileSizeApprox ?: .0)
            }

            val info = videoInfo
                .run { if (fileSize != .0) copy(fileSize = fileSize) else this }
                .run { if (newTitle.isNotEmpty()) copy(title = newTitle) else this }

            val audioOnly =
                formatList.isNotEmpty() && formatList.fold(true) { acc: Boolean, format: Format ->
                    acc && (format.vcodec == "none" && format.acodec != "none")
                }

            val mergeAudioStream = formatList.count { format ->
                format.vcodec == "none" && format.acodec != "none"
            } > 1

            val formatId = formatList.joinToString(separator = "+") { it.formatId.toString() }

            val downloadPreferences = DownloadUtil.DownloadPreferences(
                formatIdString = formatId,
                videoClips = videoClips,
                splitByChapter = splitByChapter,
                newTitle = newTitle,
                mergeAudioStream = mergeAudioStream
            ).run {
                copy(extractAudio = extractAudio || audioOnly)
            }.run {
                selectedSubtitleCodes.takeIf { it.isNotEmpty() }
                    ?.let {
                        val autoSubtitle = !info.subtitles.keys.containsAll(selectedSubtitleCodes)
                        copy(
                            downloadSubtitle = true,
                            autoSubtitle = autoSubtitle,
                            subtitleLanguage = selectedSubtitleCodes.joinToString(separator = ",") { it }
                        )
                    }
                    ?: this
            }
            downloadResultTemp = downloadVideo(
                videoInfo = info,
                preferences = downloadPreferences
            )
        }
    }

    fun downloadVideoWithInfo(info: VideoInfo) {
        currentJob = applicationScope.launch(Dispatchers.IO) {
            downloadResultTemp = downloadVideo(videoInfo = info)
        }
    }

    /**
     * This method is used for download a single video and multiple videos from playlist at the same time.
     * @see downloadVideoInPlaylistByIndexList
     * @see getInfoAndDownload
     * @see downloadVideoWithConfigurations
     */
    @CheckResult
    private suspend fun downloadVideo(
        playlistIndex: Int = 0,
        playlistUrl: String = "",
        videoInfo: VideoInfo,
        preferences: DownloadUtil.DownloadPreferences = DownloadUtil.DownloadPreferences()
    ): Result<List<String>> {

        Log.d(TAG, preferences.subtitleLanguage)
        mutableTaskState.update { videoInfo.toTask(preferencesHash = preferences.hashCode()) }

        val isDownloadingPlaylist = downloaderState.value is State.DownloadingPlaylist
        if (!isDownloadingPlaylist)
            updateState(State.DownloadingVideo)
        val taskId = videoInfo.id + preferences.hashCode()
        val notificationId = taskId.toNotificationId()
        Log.d(TAG, "downloadVideo: id=${videoInfo.id} " + videoInfo.title)
        Log.d(TAG, "notificationId: $notificationId")

        NotificationUtil.notifyProgress(
            notificationId = notificationId, title = videoInfo.title
        )
        return DownloadUtil.downloadVideo(
            videoInfo = videoInfo,
            playlistUrl = playlistUrl,
            playlistItem = playlistIndex,
            downloadPreferences = preferences,
            taskId = videoInfo.id + preferences.hashCode()
        ) { progress, _, line ->
            Log.d(TAG, line)
            mutableTaskState.update {
                it.copy(progress = progress, progressText = line)
            }
            NotificationUtil.notifyProgress(
                notificationId = notificationId,
                progress = progress.toInt(),
                text = line,
                title = videoInfo.title
            )
        }.onFailure {
            manageDownloadError(
                th = it,
                url = videoInfo.originalUrl,
                title = videoInfo.title,
                isFetchingInfo = false,
                notificationId = notificationId,
                isTaskAborted = !isDownloadingPlaylist
            )
        }.onSuccess {
            if (!isDownloadingPlaylist) finishProcessing()
            val text =
                context.getString(if (it.isEmpty()) R.string.status_completed else R.string.download_finish_notification)
            FileUtil.createIntentForOpeningFile(it.firstOrNull()).run {
                NotificationUtil.finishNotification(
                    notificationId,
                    title = videoInfo.title,
                    text = text,
                    intent = if (this != null) PendingIntent.getActivity(
                        context,
                        0,
                        this,
                        PendingIntent.FLAG_IMMUTABLE
                    ) else null
                )
            }
        }
    }

    fun downloadVideoInPlaylistByIndexList(
        url: String,
        indexList: List<Int>,
        playlistItemList: List<Entries> = emptyList(),
        preferences: DownloadUtil.DownloadPreferences = DownloadUtil.DownloadPreferences()
    ) {
        val itemCount = indexList.size

        if (!isDownloaderAvailable()) return

        mutableDownloaderState.update { State.DownloadingPlaylist() }

        currentJob = applicationScope.launch(Dispatchers.IO) {
            for (i in indexList.indices) {
                mutableDownloaderState.update {
                    if (it is State.DownloadingPlaylist)
                        it.copy(currentItem = i + 1, itemCount = indexList.size)
                    else return@launch
                }

                NotificationUtil.updateServiceNotificationForPlaylist(
                    index = i + 1, itemCount = itemCount
                )

                val playlistIndex = indexList[i]
                val playlistEntry = playlistItemList.getOrNull(i)

                Log.d(TAG, playlistEntry?.title.toString())

                val title = playlistEntry?.title

                DownloadUtil.fetchVideoInfoFromUrl(
                    url = url,
                    playlistItem = playlistIndex,
                    preferences = preferences
                ).onSuccess {
                    if (downloaderState.value !is State.DownloadingPlaylist)
                        return@launch
                    downloadResultTemp =
                        downloadVideo(
                            videoInfo = it,
                            playlistIndex = playlistIndex,
                            playlistUrl = url,
                            preferences = preferences,
                        ).onFailure { th ->
                            manageDownloadError(
                                th = th,
                                url = it.originalUrl,
                                title = it.title,
                                isFetchingInfo = false,
                                isTaskAborted = false
                            )
                        }
                }.onFailure { th ->
                    manageDownloadError(
                        th = th,
                        url = playlistEntry?.url,
                        title = title,
                        isFetchingInfo = true,
                        isTaskAborted = false
                    )
                }
            }
            finishProcessing()
        }
    }

    /**
     * Syncs audio folder with all playlists:
     * - Downloads items in playlists that aren't in folder
     * - Deletes files in folder that aren't in any playlist
     * @param playlists List of playlist entries to sync
     */
    fun syncPlaylists(playlists: List<PlaylistEntry>) {
        if (!isDownloaderAvailable()) return
        if (playlists.isEmpty()) {
            ToastUtil.makeToast("No playlists to sync")
            return
        }

        // Check if YouTube API key is configured
        val apiKey = YOUTUBE_API_KEY.getString()
        if (apiKey.isBlank()) {
            ToastUtil.makeToast("YouTube API key not configured. Please add one in Settings.")
            return
        }

        Log.d(TAG, "syncPlaylists: Starting sync for ${playlists.size} playlists")
        mutableDownloaderState.update { State.DownloadingPlaylist() }

        currentJob = applicationScope.launch(Dispatchers.IO) {
            // === Refresh playlist metadata via YouTube API ===
            if (apiKey.isNotBlank()) {
                Log.d(TAG, "syncPlaylists: Refreshing metadata for ${playlists.size} playlists")

                playlists.forEachIndexed { index, playlist ->
                    try {
                        val playlistId = playlist.playlistId
                            ?: YouTubeApiService.extractPlaylistId(playlist.url)

                        playlistId?.let { id ->
                            val info = YouTubeApiService.getPlaylistInfo(id, apiKey)
                            info?.let {
                                val updated = playlist.copy(
                                    title = it.title,
                                    thumbnailUrl = it.thumbnailUrl,
                                    videoCount = it.videoCount,
                                    channelTitle = it.channelTitle,
                                    description = it.description,
                                    lastSynced = System.currentTimeMillis(),
                                    playlistId = id
                                )
                                DatabaseUtil.updatePlaylist(updated)
                                Log.d(TAG, "syncPlaylists: Updated metadata for: ${it.title}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "syncPlaylists: Failed to refresh metadata for ${playlist.title}", e)
                        // Continue with other playlists even if one fails
                    }
                }

                ToastUtil.makeToastSuspend("Playlist metadata refreshed")
            } else {
                Log.w(TAG, "syncPlaylists: YouTube API key not configured, skipping metadata refresh")
            }

            val preferences = DownloadUtil.DownloadPreferences(
                extractAudio = true,
                embedThumbnail = true,
                embedMetadata = true,
                cropArtwork = true
            )

            // helper to normalize titles for filename comparison
            fun normalizeName(s: String): String =
                s.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")

            // Step 1: Fetch all video IDs from all playlists
            val playlistVideos =
                mutableMapOf<String, Pair<String, Int>>() // videoId -> (playlistUrl, index)
            val titleToIdTemp = mutableMapOf<String, String>() // normalizedTitle -> videoId
            var totalVideos = 0

            for (playlistEntry in playlists) {
                DownloadUtil.getPlaylistOrVideoInfo(
                    playlistURL = playlistEntry.url,
                    downloadPreferences = preferences
                ).onSuccess { info ->
                    when (info) {
                        is PlaylistResult -> {
                            val entries = info.entries ?: emptyList()
                            entries.forEachIndexed { index, entry ->
                                entry.id?.let { videoId ->
                                    playlistVideos[videoId] = Pair(playlistEntry.url, index + 1)
                                    entry.title?.let { ttl ->
                                        titleToIdTemp[normalizeName(ttl)] = videoId
                                    }
                                    totalVideos++
                                }
                            }
                        }

                        is VideoInfo -> {
                            playlistVideos[info.id] = Pair(playlistEntry.url, 0)
                            info.title.takeIf { it.isNotEmpty() }?.let { ttl ->
                                titleToIdTemp[normalizeName(ttl)] = info.id
                            }
                            totalVideos++
                        }
                    }
                }.onFailure { th ->
                    Log.e(
                        TAG,
                        "syncPlaylists: Failed to fetch playlist '${playlistEntry.title}': ${th.message}"
                    )
                }
            }

            Log.d(
                TAG,
                "syncPlaylists: Found $totalVideos videos across ${playlists.size} playlists"
            )

            // Step 2: Scan audio directory for existing files
            val audioDir = File(App.audioDownloadDir)
            if (!audioDir.exists()) {
                audioDir.mkdirs()
            }

            // Clean up old playlist metadata files to avoid MediaStore conflicts
            try {
                audioDir.listFiles()?.forEach { file ->
                    // Delete playlist-level metadata files (contains playlist ID in brackets)
                    if (file.name.matches(Regex(".*\\[PL[a-zA-Z0-9_-]+\\]\\.(info\\.json|jpg|jpeg|png|webp).*"))) {
                        file.delete()
                        Log.d(TAG, "syncPlaylists: Cleaned up old playlist metadata: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "syncPlaylists: Failed to clean up old metadata files", e)
            }

            val allowedExts = listOf("mp3", "m4a", "wav", "aac", "opus", "ogg", "webm")
            val existingFiles = audioDir.listFiles()?.filter { file ->
                file.isFile && file.extension.lowercase() in allowedExts
            } ?: emptyList()

            // Step 3: Extract video IDs from filenames and also collect normalized basenames
            val fileVideoIdMap =
                mutableMapOf<String, File>() // videoId -> File (only when an ID bracket is present)
            val fileBasenameSet = mutableSetOf<String>() // normalized basenames for title matching
            // accept a wider range of ID lengths to support other extractors, but keep it conservative
            val videoIdPattern = Regex("\\[([a-zA-Z0-9_-]{6,50})\\]")

            existingFiles.forEach { file ->
                val nameNoExt = file.name.substringBeforeLast('.')
                fileBasenameSet.add(normalizeName(nameNoExt))
                val match = videoIdPattern.find(file.name)
                match?.groupValues?.get(1)?.let { videoId ->
                    fileVideoIdMap[videoId] = file
                }
            }

            Log.d(
                TAG,
                "syncPlaylists: Found ${fileVideoIdMap.size} existing files with bracketed IDs and ${fileBasenameSet.size} files total"
            )

            // titleToIdTemp was populated during playlist parsing with normalized titles

            // Step 4: Determine which playlist items are already present (by ID or by normalized title)
            val matchedVideoIds = mutableSetOf<String>()
            playlistVideos.forEach { (videoId, _) ->
                if (fileVideoIdMap.containsKey(videoId)) {
                    matchedVideoIds.add(videoId)
                } else {
                    // try title-based matching using the titles we captured during playlist parsing
                    titleToIdTemp.entries.find { (normTitle, id) ->
                        id == videoId && fileBasenameSet.contains(
                            normTitle
                        )
                    }
                        ?.let { matchedVideoIds.add(videoId) }
                }
            }

            // Step 5: Delete files that have bracketed IDs but are not present in any playlist
            val filesToDelete = fileVideoIdMap.filterKeys { it !in playlistVideos.keys }
            filesToDelete.forEach { (videoId, file) ->
                try {
                    if (file.delete()) {
                        Log.d(TAG, "syncPlaylists: Deleted ${file.name} (ID: $videoId)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "syncPlaylists: Failed to delete ${file.name}", e)
                }
            }

            if (filesToDelete.isNotEmpty()) {
                ToastUtil.makeToastSuspend("Deleted ${filesToDelete.size} files not in playlists")
            }

            // Step 6: Download videos not in folder (considered present if matched by id or title)
            val videosToDownload = playlistVideos.filterKeys { it !in matchedVideoIds }
            val downloadCount = videosToDownload.size

            Log.d(TAG, "syncPlaylists: Need to download $downloadCount videos")

            // Diagnostic logging to help debug false 'already synced' reports
            Log.d(
                TAG,
                "syncPlaylists: DIAG playlistVideosCount=${playlistVideos.size} playlistVideoIds=${playlistVideos.keys}"
            )
            Log.d(
                TAG,
                "syncPlaylists: DIAG existingFilesCount=${existingFiles.size} filesWithBracketedIds=${fileVideoIdMap.keys}"
            )
            Log.d(TAG, "syncPlaylists: DIAG normalizedFileBasenames=${fileBasenameSet}")
            Log.d(TAG, "syncPlaylists: DIAG matchedVideoIds=${matchedVideoIds}")
            Log.d(
                TAG,
                "syncPlaylists: DIAG videosToDownloadCount=${videosToDownload.size} videosToDownload=${videosToDownload.keys}"
            )

            // If we detect an unexpected 'already synced' case, emit an additional warning log
            if (playlistVideos.isNotEmpty() && existingFiles.isEmpty() && downloadCount == 0) {
                Log.w(
                    TAG,
                    "syncPlaylists: WARNING - playlist has items but no files found, yet downloadCount==0. Inspect DIAG logs above."
                )
            }

            if (downloadCount == 0) {
                ToastUtil.makeToastSuspend("Folder is already synced")
                // Clear any progress/service notifications and post a single final sync notification
                NotificationUtil.cancelAllNotifications()
                NotificationUtil.finishNotification(
                    notificationId = NotificationUtil.SERVICE_NOTIFICATION_ID,
                    title = "Finished syncing",
                    text = "Sync complete: $downloadCount downloaded, ${filesToDelete.size} deleted"
                )
                finishProcessing()
                return@launch
            }

            videosToDownload.entries.forEachIndexed { index, (videoId, urlAndIndex) ->
                if (downloaderState.value !is State.DownloadingPlaylist) {
                    Log.d(TAG, "syncPlaylists: Sync cancelled")
                    return@launch
                }

                mutableDownloaderState.update {
                    if (it is State.DownloadingPlaylist)
                        it.copy(currentItem = index + 1, itemCount = downloadCount)
                    else return@launch
                }

                NotificationUtil.updateServiceNotificationForPlaylist(
                    index = index + 1, itemCount = downloadCount
                )

                val (playlistUrl, playlistIndex) = urlAndIndex

                Log.d(
                    TAG,
                    "syncPlaylists: [${index + 1}/$downloadCount] Downloading video ID: $videoId"
                )

                // Fetch video info
                val playlistItemParam = if (playlistIndex > 0) playlistIndex else 0
                val playlistUrlParam = if (playlistIndex > 0) playlistUrl else ""

                DownloadUtil.fetchVideoInfoFromUrl(
                    url = playlistUrl,
                    playlistItem = playlistItemParam,
                    preferences = preferences
                ).onSuccess { videoInfo ->
                    if (downloaderState.value !is State.DownloadingPlaylist)
                        return@launch

                    downloadResultTemp = downloadVideo(
                        videoInfo = videoInfo,
                        playlistIndex = playlistItemParam,
                        playlistUrl = playlistUrlParam,
                        preferences = preferences,
                    ).onFailure { th ->
                        manageDownloadError(
                            th = th,
                            url = videoInfo.originalUrl,
                            title = videoInfo.title,
                            isFetchingInfo = false,
                            isTaskAborted = false
                        )
                    }
                }.onFailure { th ->
                    Log.e(TAG, "syncPlaylists: Failed to fetch video $videoId", th)
                    manageDownloadError(
                        th = th,
                        url = playlistUrl,
                        title = videoId,
                        isFetchingInfo = true,
                        isTaskAborted = false
                    )
                }
            }

            Log.d(TAG, "syncPlaylists: Sync complete")
            ToastUtil.makeToastSuspend("Sync complete: $downloadCount downloaded, ${filesToDelete.size} deleted")
            // Replace multiple notifications with one final sync notification
            NotificationUtil.cancelAllNotifications()
            NotificationUtil.finishNotification(
                notificationId = NotificationUtil.SERVICE_NOTIFICATION_ID,
                title = "Finished syncing",
                text = "Sync complete: $downloadCount downloaded, ${filesToDelete.size} deleted"
            )
            finishProcessing()
        }
    }

    private fun finishProcessing() {
        if (downloaderState.value is State.Idle) return
        mutableTaskState.update {
            it.copy(progress = 100f, progressText = "")
        }
        clearProgressState(isFinished = true)
        updateState(State.Idle)
        clearErrorState()
    }

    /**
     * @param isTaskAborted Determines if the download task is aborted due to the given `Exception`
     */
    fun manageDownloadError(
        th: Throwable,
        url: String?,
        title: String? = null,
        isFetchingInfo: Boolean,
        isTaskAborted: Boolean = true,
        notificationId: Int? = null,
    ) {
        if (th is YoutubeDL.CanceledException) return
        th.printStackTrace()
        val resId =
            if (isFetchingInfo) R.string.fetch_info_error_msg else R.string.download_error_msg
        ToastUtil.makeToastSuspend(context.getString(resId))

        val notificationTitle = title ?: url

        if (isFetchingInfo) {
            fetchInfoError(url = url.toString(), errorReport = th.message.toString())
        } else {
            downloadError(url = url.toString(), errorReport = th.message.toString())
        }

        notificationId?.let {
            NotificationUtil.finishNotification(
                notificationId = it,
                title = notificationTitle,
                text = context.getString(R.string.download_error_msg),
            )
        }
        if (isTaskAborted) {
            updateState(State.Idle)
            clearProgressState(isFinished = false)
        }

    }

    fun cancelDownload() {
        ToastUtil.makeToast(context.getString(R.string.task_canceled))
        currentJob?.cancel(CancellationException(context.getString(R.string.task_canceled)))
        updateState(State.Idle)
        clearProgressState(isFinished = false)
        taskState.value.taskId.run {
            YoutubeDL.destroyProcessById(this)
            NotificationUtil.cancelNotification(this.toNotificationId())
        }
    }

    fun executeCommandWithUrl(url: String) =
        applicationScope.launch(Dispatchers.IO) {
            DownloadUtil.executeCommandInBackground(
                url
            )
        }

    fun openDownloadResult() {
        if (taskState.value.progress == 100f) FileUtil.openFileFromResult(downloadResultTemp)
    }

    fun onProcessStarted() = mutableProcessCount.update { it + 1 }
}

// Keep the notification id extension at top-level so other files can import it
fun String.toNotificationId(): Int = this.hashCode()
