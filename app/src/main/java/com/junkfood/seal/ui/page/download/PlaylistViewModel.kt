package com.junkfood.seal.ui.page.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junkfood.seal.database.objects.PlaylistEntry
import com.junkfood.seal.util.DatabaseUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor() : ViewModel() {

    val playlistsFlow: StateFlow<List<PlaylistEntry>> = DatabaseUtil.getPlaylistsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun addPlaylist(title: String, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val playlist = PlaylistEntry(
                id = 0,
                title = title,
                url = url
            )
            DatabaseUtil.insertPlaylist(playlist)
        }
    }

    fun deletePlaylist(playlist: PlaylistEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseUtil.deletePlaylist(playlist)
        }
    }

    fun getNextPlaylistNumber(): Int {
        return (playlistsFlow.value.size + 1)
    }
}
