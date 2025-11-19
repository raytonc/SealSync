package com.junkfood.seal.ui.page.videolist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junkfood.seal.App
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
    val file: File,
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

    init {
        refreshFileList()
    }

    fun refreshFileList() {
        viewModelScope.launch(Dispatchers.IO) {
            val audioDir = File(App.audioDownloadDir)
            val files = if (audioDir.exists() && audioDir.isDirectory) {
                audioDir.listFiles()?.filter { file ->
                    file.isFile &&
                    file.extension.lowercase() in listOf(
                        "mp3", "m4a", "aac", "opus", "ogg", "webm", "flac", "wav"
                    ) &&
                    !file.name.startsWith(".trashed-")  // Exclude trashed files
                }?.map { file ->
                    // Try to read metadata from .info.json file
                    val metadata = readMetadataFromJson(file)

                    // Also check for thumbnail image file
                    val thumbnailUrl = findThumbnailFile(file) ?: metadata?.thumbnail

                    Log.d(TAG, "File: ${file.name}")
                    Log.d(TAG, "  Metadata: title=${metadata?.title}, uploader=${metadata?.uploader}")
                    Log.d(TAG, "  Thumbnail: $thumbnailUrl")

                    AudioFileInfo(
                        file = file,
                        name = file.name,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        thumbnailUrl = thumbnailUrl,
                        videoTitle = metadata?.title,
                        videoAuthor = metadata?.uploader ?: metadata?.channel
                    )
                }?.sortedBy { it.videoTitle?.lowercase() ?: it.name.lowercase() } ?: emptyList()
            } else {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                _audioFilesFlow.value = files
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

    fun deleteFile(fileInfo: AudioFileInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteFileWithMetadata(fileInfo.file)
            refreshFileList()
        }
    }

    fun deleteFiles(fileInfos: List<AudioFileInfo>) {
        viewModelScope.launch(Dispatchers.IO) {
            fileInfos.forEach { deleteFileWithMetadata(it.file) }
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
            // Log error but don't crash
            e.printStackTrace()
        }
    }
}
