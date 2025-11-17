package com.junkfood.seal.ui.page.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junkfood.seal.database.objects.PlaylistEntry
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.YOUTUBE_API_KEY
import com.junkfood.seal.util.YouTubeApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AddPlaylistState {
    object Idle : AddPlaylistState()
    object Loading : AddPlaylistState()
    data class Success(val playlist: PlaylistEntry) : AddPlaylistState()
    data class Error(val message: String) : AddPlaylistState()
}

@HiltViewModel
class PlaylistViewModel @Inject constructor() : ViewModel() {

    val playlistsFlow: StateFlow<List<PlaylistEntry>> = DatabaseUtil.getPlaylistsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _addPlaylistState = MutableStateFlow<AddPlaylistState>(AddPlaylistState.Idle)
    val addPlaylistState: StateFlow<AddPlaylistState> = _addPlaylistState.asStateFlow()

    fun addPlaylistFromUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _addPlaylistState.value = AddPlaylistState.Loading

            try {
                // Check if API key is configured
                val apiKey = YOUTUBE_API_KEY.getString()
                if (apiKey.isBlank()) {
                    _addPlaylistState.value = AddPlaylistState.Error("YouTube API key not configured. Please add one in Settings.")
                    return@launch
                }

                // Extract playlist ID
                val playlistId = YouTubeApiService.extractPlaylistId(url)
                if (playlistId == null) {
                    _addPlaylistState.value = AddPlaylistState.Error("Invalid YouTube playlist URL")
                    return@launch
                }

                // Fetch playlist info from YouTube API
                val info = YouTubeApiService.getPlaylistInfo(playlistId, apiKey)
                if (info == null) {
                    _addPlaylistState.value = AddPlaylistState.Error("Failed to fetch playlist info. Check your API key and internet connection.")
                    return@launch
                }

                // Create PlaylistEntry with all metadata
                val playlist = PlaylistEntry(
                    id = 0,
                    title = info.title,
                    url = url,
                    thumbnailUrl = info.thumbnailUrl,
                    playlistId = playlistId,
                    videoCount = info.videoCount,
                    channelTitle = info.channelTitle,
                    description = info.description,
                    lastSynced = System.currentTimeMillis()
                )

                // Insert to database
                DatabaseUtil.insertPlaylist(playlist)

                _addPlaylistState.value = AddPlaylistState.Success(playlist)
            } catch (e: Exception) {
                _addPlaylistState.value = AddPlaylistState.Error(e.message ?: "Unknown error occurred")
            }
        }
    }

    fun refreshPlaylistMetadata(playlist: PlaylistEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiKey = YOUTUBE_API_KEY.getString()
                if (apiKey.isBlank()) return@launch

                val playlistId = playlist.playlistId
                    ?: YouTubeApiService.extractPlaylistId(playlist.url)
                    ?: return@launch

                val info = YouTubeApiService.getPlaylistInfo(playlistId, apiKey)
                    ?: return@launch

                val updated = playlist.copy(
                    title = info.title,
                    thumbnailUrl = info.thumbnailUrl,
                    videoCount = info.videoCount,
                    channelTitle = info.channelTitle,
                    description = info.description,
                    lastSynced = System.currentTimeMillis(),
                    playlistId = playlistId
                )

                DatabaseUtil.updatePlaylist(updated)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deletePlaylist(playlist: PlaylistEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseUtil.deletePlaylist(playlist)
        }
    }

    fun resetAddPlaylistState() {
        _addPlaylistState.value = AddPlaylistState.Idle
    }

    fun getNextPlaylistNumber(): Int {
        return (playlistsFlow.value.size + 1)
    }
}
