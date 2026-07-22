package com.mesh.app.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mesh.app.data.model.Playlist
import com.mesh.app.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class PlaylistsUiState(
    val playlists: List<Playlist> = emptyList(),
)

class PlaylistsViewModel(
    playlistRepository: PlaylistRepository,
) : ViewModel() {
    val uiState: StateFlow<PlaylistsUiState> =
        playlistRepository.observePlaylists()
            .map { PlaylistsUiState(playlists = it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaylistsUiState())
}
