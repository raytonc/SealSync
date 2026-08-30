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

    fun deletePlaylist(playlist: PlaylistEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseUtil.deletePlaylist(playlist)
        }
    }

    /**
     * Puts back a playlist removed by a swipe, for the snackbar's undo action. The row is
     * reinserted with a fresh id (the column autogenerates), which is fine because nothing
     * references a playlist by id outside the list itself.
     */
    fun restorePlaylist(playlist: PlaylistEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            // Guard against a double-tap on undo, or the playlist being re-added by hand
            // in the meantime, either of which would otherwise duplicate the row.
            if (DatabaseUtil.findDuplicatePlaylist(playlist.url, playlist.playlistId) == null) {
                DatabaseUtil.insertPlaylist(playlist.copy(id = 0))
            }
        }
    }

    fun resetAddPlaylistState() {
        _addPlaylistState.value = AddPlaylistState.Idle
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

    /**
     * Removes a playlist that was added from the channel picker, so tapping an already-added
     * row in that dialog toggles it back off. Matches the same way the picker decides a row
     * is already added: by playlist id, or by the id appearing in the stored URL for rows
     * saved before the id column existed.
     */
    fun removePlaylistByChannelId(channelPlaylistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistsFlow.value
                .filter { it.playlistId == channelPlaylistId || it.url.contains(channelPlaylistId) }
                .forEach { DatabaseUtil.deletePlaylist(it) }
        }
    }

    fun resetChannelPlaylistsState() {
        _channelPlaylistsState.value = ChannelPlaylistsState.Idle
    }
}
