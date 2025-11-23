package com.junkfood.seal.ui.page.videolist

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App
import com.junkfood.seal.util.AUDIO_DIRECTORY_URI
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.scanAudioFilesWithDocumentFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

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

    private val TAG = "VideoListViewModel"

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
                // Try to use SAF DocumentFile first (works with scoped storage)
                val uriString = AUDIO_DIRECTORY_URI.getString()

                val files = if (uriString.isNotEmpty()) {
                // Use DocumentFile (SAF) - this works with scoped storage
                try {
                    val treeUri = Uri.parse(uriString)
                    Log.d(TAG, "refreshFileList: Scanning using DocumentFile with URI: $treeUri")

                    val audioFiles = scanAudioFilesWithDocumentFile(App.context, treeUri)
                    Log.d(TAG, "refreshFileList: Found ${audioFiles.size} audio files via DocumentFile")

                    audioFiles.map { audioData ->
                        // Extract base name from filename (without extension)
                        val baseName = audioData.name.substringBeforeLast('.')

                        // Read metadata from cache
                        val metadata = readMetadataFromCache(baseName)

                        // Pass the audio URI directly to Coil
                        // Our custom AudioThumbnailFetcher will lazily extract thumbnails on-demand
                        // This makes the list appear instantly and thumbnails load progressively
                        val thumbnailUrl = audioData.uri.toString()

                        AudioFileInfo(
                            uri = audioData.uri,
                            name = audioData.name,
                            size = audioData.size,
                            lastModified = audioData.lastModified,
                            thumbnailUrl = thumbnailUrl,
                            videoTitle = metadata?.title,
                            videoAuthor = metadata?.uploader ?: metadata?.channel
                        )
                    }.sortedBy { it.videoTitle?.lowercase() ?: it.name.lowercase() }
                } catch (e: Exception) {
                    Log.e(TAG, "refreshFileList: Failed to scan with DocumentFile", e)
                    emptyList()
                }
            } else {
                // Fallback to File API (legacy, won't work with scoped storage on /Download)
                Log.w(TAG, "refreshFileList: No URI set, falling back to File API (may not work)")
                val audioDir = File(App.audioDownloadDir)
                Log.d(TAG, "refreshFileList: Scanning directory: ${App.audioDownloadDir}")

                if (audioDir.exists() && audioDir.isDirectory) {
                    try {
                        audioDir.walkTopDown()
                            .filter { file ->
                                file.isFile &&
                                        file.extension.lowercase() in listOf(
                                    "mp3",
                                    "m4a",
                                    "aac",
                                    "opus",
                                    "ogg",
                                    "oga",
                                    "webm",
                                    "flac",
                                    "wav"
                                ) &&
                                        !file.name.startsWith(".trashed-")
                            }
                            .map { file ->
                                val metadata = readMetadataFromJson(file)
                                val thumbnailUrl = findThumbnailFile(file) ?: metadata?.thumbnail

                                AudioFileInfo(
                                    file = file,
                                    name = file.name,
                                    size = file.length(),
                                    lastModified = file.lastModified(),
                                    thumbnailUrl = thumbnailUrl,
                                    videoTitle = metadata?.title,
                                    videoAuthor = metadata?.uploader ?: metadata?.channel
                                )
                            }
                            .sortedBy { it.videoTitle?.lowercase() ?: it.name.lowercase() }
                            .toList()
                    } catch (e: Exception) {
                        Log.e(TAG, "refreshFileList: Failed to scan with File API", e)
                        emptyList()
                    }
                } else {
                    Log.e(TAG, "refreshFileList: Directory does not exist or is not valid")
                    emptyList()
                }
            }

                Log.d(TAG, "refreshFileList: Total files found: ${files.size}")

                withContext(Dispatchers.Main) {
                    _audioFilesFlow.value = files
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun readMetadataFromJson(audioFile: File): VideoInfoJson? {
        return try {
            // Try to find .info.json file with same base name
            val baseName = audioFile.nameWithoutExtension
            val jsonFile = File(audioFile.parent, "$baseName.info.json")

            if (jsonFile.exists()) {
                val jsonContent = jsonFile.readText()
                json.decodeFromString<VideoInfoJson>(jsonContent)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readMetadataFromCache(baseName: String): VideoInfoJson? {
        return try {
            // Metadata JSON files are stored in cacheDir
            val cacheDir = App.context.cacheDir
            val jsonFile = File(cacheDir, "$baseName.info.json")

            if (jsonFile.exists()) {
                val jsonContent = jsonFile.readText()
                json.decodeFromString<VideoInfoJson>(jsonContent)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "readMetadataFromCache: Failed to read metadata for $baseName", e)
            null
        }
    }

    private fun findThumbnailFile(audioFile: File): String? {
        return try {
            val baseName = audioFile.nameWithoutExtension
            // Thumbnails are saved to cacheDir, not with the audio files
            val cacheDir = App.context.cacheDir

            // Look for thumbnail with various extensions
            val thumbnailExtensions = listOf("jpg", "jpeg", "png", "webp")
            for (ext in thumbnailExtensions) {
                val thumbFile = File(cacheDir, "$baseName.$ext")
                if (thumbFile.exists()) {
                    // Return absolute path - Coil can load from file paths directly
                    return thumbFile.absolutePath
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun findThumbnailInCache(baseName: String): String? {
        return try {
            // Thumbnails are saved to cacheDir
            val cacheDir = App.context.cacheDir

            // Look for thumbnail with various extensions
            val thumbnailExtensions = listOf("jpg", "jpeg", "png", "webp")
            for (ext in thumbnailExtensions) {
                val thumbFile = File(cacheDir, "$baseName.$ext")
                if (thumbFile.exists()) {
                    // Return absolute path - Coil can load from file paths directly
                    return thumbFile.absolutePath
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "findThumbnailInCache: Failed for $baseName", e)
            null
        }
    }

    fun deleteFile(fileInfo: AudioFileInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (fileInfo.uri != null) {
                    // Use DocumentFile for SAF URIs
                    val docFile = DocumentFile.fromSingleUri(App.context, fileInfo.uri)
                    docFile?.delete()
                    Log.d(TAG, "deleteFile: Deleted via URI: ${fileInfo.name}")
                } else if (fileInfo.file != null) {
                    // Use File API for legacy
                    deleteFileWithMetadata(fileInfo.file)
                    Log.d(TAG, "deleteFile: Deleted via File: ${fileInfo.name}")
                }
                refreshFileList()
            } catch (e: Exception) {
                Log.e(TAG, "deleteFile: Failed to delete ${fileInfo.name}", e)
            }
        }
    }

    fun deleteFiles(fileInfos: List<AudioFileInfo>) {
        viewModelScope.launch(Dispatchers.IO) {
            fileInfos.forEach { fileInfo ->
                try {
                    if (fileInfo.uri != null) {
                        val docFile = DocumentFile.fromSingleUri(App.context, fileInfo.uri)
                        docFile?.delete()
                    } else if (fileInfo.file != null) {
                        deleteFileWithMetadata(fileInfo.file)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "deleteFiles: Failed to delete ${fileInfo.name}", e)
                }
            }
            refreshFileList()
        }
    }

    private fun deleteFileWithMetadata(audioFile: File) {
        try {
            val baseName = audioFile.nameWithoutExtension
            val parentDir = audioFile.parentFile

            // Delete the audio file
            audioFile.delete()

            // Delete associated metadata files
            if (parentDir != null) {
                // Delete .info.json file
                File(parentDir, "$baseName.info.json").takeIf { it.exists() }?.delete()

                // Delete thumbnail files
                val thumbnailExtensions = listOf("jpg", "jpeg", "png", "webp")
                for (ext in thumbnailExtensions) {
                    File(parentDir, "$baseName.$ext").takeIf { it.exists() }?.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteFileWithMetadata: Failed", e)
        }
    }
}
