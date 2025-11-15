package com.junkfood.seal.ui.page.videolist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junkfood.seal.App
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@HiltViewModel
class VideoListViewModel @Inject constructor() : ViewModel() {

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
                    file.isFile && file.extension.lowercase() in listOf(
                        "mp3", "m4a", "aac", "opus", "ogg", "webm", "flac", "wav"
                    )
                }?.sortedByDescending { it.lastModified() }?.map { file ->
                    AudioFileInfo(
                        file = file,
                        name = file.name,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        thumbnailUrl = null,
                        videoTitle = null,
                        videoAuthor = null
                    )
                } ?: emptyList()
            } else {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                _audioFilesFlow.value = files
            }
        }
    }

    fun deleteFile(fileInfo: AudioFileInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            fileInfo.file.delete()
            refreshFileList()
        }
    }

    fun deleteFiles(fileInfos: List<AudioFileInfo>) {
        viewModelScope.launch(Dispatchers.IO) {
            fileInfos.forEach { it.file.delete() }
            refreshFileList()
        }
    }
}
