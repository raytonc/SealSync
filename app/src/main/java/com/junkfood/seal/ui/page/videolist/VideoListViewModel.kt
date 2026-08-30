package com.junkfood.seal.ui.page.videolist

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App
import com.junkfood.seal.util.AUDIO_DIRECTORY_URI
import com.junkfood.seal.util.AUDIO_EXTENSIONS
import com.junkfood.seal.util.THUMBNAIL_EXTENSIONS
import com.junkfood.seal.util.clearCachedDataForAudio
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.scanAudioFilesWithDocumentFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

private const val TAG = "VideoListViewModel"

data class AudioFileInfo(
    val uri: Uri? = null,  // For DocumentFile (SAF) - primary method
    val file: File? = null,  // For legacy File API - fallback
    val name: String,
    val size: Long,
    val lastModified: Long,
    val thumbnailUrl: String? = null,
    val videoTitle: String? = null,
    val videoAuthor: String? = null,
)

@Serializable
data class VideoInfoJson(
    val title: String? = null,
    val uploader: String? = null,
    val channel: String? = null,
    val thumbnail: String? = null,
    val thumbnails: List<ThumbnailInfo>? = null
)

@Serializable
data class ThumbnailInfo(
    val url: String? = null,
    val id: String? = null
)

@HiltViewModel
class VideoListViewModel @Inject constructor() : ViewModel() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _audioFilesFlow = MutableStateFlow<List<AudioFileInfo>>(emptyList())
    val audioFilesFlow = _audioFilesFlow.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    init {
        refreshFileList()
    }

    fun refreshFileList() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val uriString = AUDIO_DIRECTORY_URI.getString()
                val files =
                    if (uriString.isNotEmpty()) scanWithSaf(Uri.parse(uriString))
                    else scanWithFileApi()

                Log.d(TAG, "refreshFileList: total files found: ${files.size}")
                // A StateFlow is safe to write from any thread and Compose collects it on
                // the main one, so there is nothing to hop threads for here.
                _audioFilesFlow.value = files
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** The scoped-storage path: the SAF tree the user picked. */
    private fun scanWithSaf(treeUri: Uri): List<AudioFileInfo> = runCatching {
        scanAudioFilesWithDocumentFile(App.context, treeUri)
            .map { audioData ->
                val metadata = readMetadataFromCache(audioData.name.substringBeforeLast('.'))
                AudioFileInfo(
                    uri = audioData.uri,
                    name = audioData.name,
                    size = audioData.size,
                    lastModified = audioData.lastModified,
                    // Handed to Coil as-is: AudioThumbnailFetcher extracts the embedded
                    // artwork lazily, so the list renders before any of it is decoded.
                    thumbnailUrl = audioData.uri.toString(),
                    videoTitle = metadata?.title,
                    videoAuthor = metadata?.uploader ?: metadata?.channel
                )
            }
            .sortedBy { it.videoTitle?.lowercase() ?: it.name.lowercase() }
    }.getOrElse {
        Log.e(TAG, "scanWithSaf: failed to scan $treeUri", it)
        emptyList()
    }

    /** Legacy path for installs that predate the folder picker. */
    private fun scanWithFileApi(): List<AudioFileInfo> {
        Log.w(TAG, "scanWithFileApi: no SAF URI set, falling back to the File API")
        val audioDir = File(App.audioDownloadDir)
        if (!audioDir.isDirectory) {
            Log.e(TAG, "scanWithFileApi: ${App.audioDownloadDir} is not a directory")
            return emptyList()
        }

        return runCatching {
            audioDir.walkTopDown()
                .filter {
                    it.isFile &&
                            it.extension.lowercase() in AUDIO_EXTENSIONS &&
                            !it.name.startsWith(".trashed-")
                }
                .map { file ->
                    val metadata = readMetadataFromJson(file)
                    AudioFileInfo(
                        file = file,
                        name = file.name,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        thumbnailUrl = findThumbnailFile(file) ?: metadata?.thumbnail,
                        videoTitle = metadata?.title,
                        videoAuthor = metadata?.uploader ?: metadata?.channel
                    )
                }
                .sortedBy { it.videoTitle?.lowercase() ?: it.name.lowercase() }
                .toList()
        }.getOrElse {
            Log.e(TAG, "scanWithFileApi: failed to scan ${App.audioDownloadDir}", it)
            emptyList()
        }
    }

    private fun readMetadataFromJson(audioFile: File): VideoInfoJson? =
        decodeMetadata(File(audioFile.parent, "${audioFile.nameWithoutExtension}.info.json"))

    /** Downloads write their sidecar json into the cache dir, keyed by the audio basename. */
    private fun readMetadataFromCache(baseName: String): VideoInfoJson? =
        decodeMetadata(File(App.context.cacheDir, "$baseName.info.json"))

    private fun decodeMetadata(jsonFile: File): VideoInfoJson? {
        if (!jsonFile.exists()) return null
        return runCatching { json.decodeFromString<VideoInfoJson>(jsonFile.readText()) }
            .getOrElse {
                Log.e(TAG, "decodeMetadata: could not read ${jsonFile.name}", it)
                null
            }
    }

    private fun findThumbnailFile(audioFile: File): String? {
        // Thumbnails are written to the cache dir, not alongside the audio.
        val cacheDir = App.context.cacheDir
        val baseName = audioFile.nameWithoutExtension
        return THUMBNAIL_EXTENSIONS
            .asSequence()
            .map { File(cacheDir, "$baseName.$it") }
            .firstOrNull { it.exists() }
            ?.absolutePath
    }

    fun deleteFile(fileInfo: AudioFileInfo) = deleteFiles(listOf(fileInfo))

    /**
     * Deletes the given files and reloads the list once, at the end.
     *
     * The deletes are issued concurrently: each SAF delete is a blocking IPC round trip to
     * the storage provider, so a multi-select of a few dozen files paid that latency once
     * per file when it could overlap them instead.
     */
    fun deleteFiles(fileInfos: List<AudioFileInfo>) {
        viewModelScope.launch(Dispatchers.IO) {
            coroutineScope {
                fileInfos.map { fileInfo ->
                    async {
                        runCatching {
                            if (fileInfo.uri != null) {
                                DocumentFile.fromSingleUri(App.context, fileInfo.uri)?.delete()
                            } else {
                                fileInfo.file?.let(::deleteFileWithMetadata)
                            }
                            // Both paths: the extracted artwork and the download's
                            // sidecars live in app storage, not beside the audio, so
                            // nothing above reaches them. Left behind they grow without
                            // bound and, because the sidecars are keyed by basename, are
                            // read back as the metadata for whatever is downloaded under
                            // that name next.
                            clearCachedDataForAudio(App.context, fileInfo.uri, fileInfo.name)
                        }.onFailure {
                            Log.e(TAG, "deleteFiles: failed to delete ${fileInfo.name}", it)
                        }
                    }
                }.awaitAll()
            }
            refreshFileList()
        }
    }

    /** Removes a legacy File-API download along with the sidecars it was saved with. */
    private fun deleteFileWithMetadata(audioFile: File) {
        audioFile.delete()
        val parentDir = audioFile.parentFile ?: return
        val baseName = audioFile.nameWithoutExtension
        File(parentDir, "$baseName.info.json").delete()
        THUMBNAIL_EXTENSIONS.forEach { File(parentDir, "$baseName.$it").delete() }
    }
}
