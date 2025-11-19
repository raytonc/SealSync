package com.junkfood.seal.ui.page.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junkfood.seal.database.objects.PlaylistEntry
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.YOUTUBE_API_KEY
import com.junkfood.seal.util.YOUTUBE_CHANNEL_HANDLE
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

sealed class ChannelPlaylistsState {
    object Idle : ChannelPlaylistsState()
    object Loading : ChannelPlaylistsState()
    data class Success(val playlists: List<YouTubeApiService.ChannelPlaylistInfo>) :
        ChannelPlaylistsState()

    data class Error(val message: String) : ChannelPlaylistsState()
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

    private val _channelPlaylistsState =
        MutableStateFlow<ChannelPlaylistsState>(ChannelPlaylistsState.Idle)
    val channelPlaylistsState: StateFlow<ChannelPlaylistsState> =
        _channelPlaylistsState.asStateFlow()

    fun addPlaylistFromUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _addPlaylistState.value = AddPlaylistState.Loading

            try {
                // Check if API key is configured
                val apiKey = YOUTUBE_API_KEY.getString()
                if (apiKey.isBlank()) {
                    _addPlaylistState.value =
                        AddPlaylistState.Error("YouTube API key not configured. Please add one in Settings.")
                    return@launch
                }

                // Extract playlist ID
                val playlistId = YouTubeApiService.extractPlaylistId(url)
                if (playlistId == null) {
                    _addPlaylistState.value = AddPlaylistState.Error("Invalid YouTube playlist URL")
                    return@launch
                }

                // Check for duplicates
                val duplicate = DatabaseUtil.findDuplicatePlaylist(url, playlistId)
                if (duplicate != null) {
                    _addPlaylistState.value =
                        AddPlaylistState.Error("This playlist is already in your library")
                    return@launch
                }

                // Fetch playlist info from YouTube API
                val info = YouTubeApiService.getPlaylistInfo(playlistId, apiKey)
                if (info == null) {
                    _addPlaylistState.value =
                        AddPlaylistState.Error("Failed to fetch playlist info. Check your API key and internet connection.")
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
                _addPlaylistState.value =
                    AddPlaylistState.Error(e.message ?: "Unknown error occurred")
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

    fun fetchChannelPlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            _channelPlaylistsState.value = ChannelPlaylistsState.Loading

            try {
                // Check if API key is configured
                val apiKey = YOUTUBE_API_KEY.getString()
                if (apiKey.isBlank()) {
                    _channelPlaylistsState.value =
                        ChannelPlaylistsState.Error("YouTube API key not configured. Please add one in Settings.")
                    return@launch
                }

                // Check if channel handle is configured
                val handle = YOUTUBE_CHANNEL_HANDLE.getString()
                if (handle.isBlank()) {
                    _channelPlaylistsState.value =
                        ChannelPlaylistsState.Error("YouTube channel handle not configured. Please add one in Settings.")
                    return@launch
                }

                // Convert handle to channel ID
                val channelId = YouTubeApiService.getChannelIdFromHandle(handle, apiKey)
                if (channelId == null) {
                    _channelPlaylistsState.value =
                        ChannelPlaylistsState.Error("Failed to find channel. Check your channel handle.")
                    return@launch
                }

                // Fetch channel playlists
                val playlists = YouTubeApiService.getChannelPlaylists(channelId, apiKey)
                if (playlists == null) {
                    _channelPlaylistsState.value =
                        ChannelPlaylistsState.Error("Failed to fetch playlists. Check your API key and internet connection.")
                    return@launch
                }

                if (playlists.isEmpty()) {
                    _channelPlaylistsState.value =
                        ChannelPlaylistsState.Error("This channel has no public playlists.")
                    return@launch
                }

                _channelPlaylistsState.value = ChannelPlaylistsState.Success(playlists)
            } catch (e: Exception) {
                _channelPlaylistsState.value =
                    ChannelPlaylistsState.Error(e.message ?: "Unknown error occurred")
            }
        }
    }

    fun addPlaylistFromChannel(channelPlaylist: YouTubeApiService.ChannelPlaylistInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiKey = YOUTUBE_API_KEY.getString()
                if (apiKey.isBlank()) return@launch

                val playlistUrl = "https://www.youtube.com/playlist?list=${channelPlaylist.id}"

                // Check for duplicates
                val duplicate = DatabaseUtil.findDuplicatePlaylist(playlistUrl, channelPlaylist.id)
                if (duplicate != null) {
                    // Silently skip duplicates when adding from channel
                    return@launch
                }

                // Create PlaylistEntry with channel playlist info
                val playlist = PlaylistEntry(
                    id = 0,
                    title = channelPlaylist.title,
                    url = playlistUrl,
                    thumbnailUrl = channelPlaylist.thumbnailUrl,
                    playlistId = channelPlaylist.id,
                    videoCount = channelPlaylist.itemCount,
                    channelTitle = null, // Will be filled during sync
                    description = channelPlaylist.description,
                    lastSynced = 0 // Will be synced later
                )

                // Insert to database
                DatabaseUtil.insertPlaylist(playlist)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun resetChannelPlaylistsState() {
        _channelPlaylistsState.value = ChannelPlaylistsState.Idle
    }
}
