package com.mesh.app.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mesh.app.data.model.PlaylistDetail
import com.mesh.app.data.model.Track
import com.mesh.app.data.repository.PlaylistRepository
import com.mesh.app.data.repository.TrackRepository
import com.mesh.app.player.PlayerController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlaylistDetailUiState(
    val detail: PlaylistDetail? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModel(
    private val playlistRepository: PlaylistRepository,
    private val trackRepository: TrackRepository,
    private val playerController: PlayerController,
) : ViewModel() {
    private val playlistId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PlaylistDetailUiState> =
        playlistId
            .flatMapLatest { id ->
                if (id.isNullOrBlank()) {
                    flowOf(null)
                } else {
                    playlistRepository.observePlaylistDetail(id)
                }
            }
            .map { PlaylistDetailUiState(detail = it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaylistDetailUiState())

    fun init(playlistId: String) {
        this.playlistId.value = playlistId
    }

    fun playTrack(track: Track) {
        val queue = uiState.value.detail?.tracks.orEmpty()
        if (queue.isNotEmpty()) {
            playerController.play(track, queue)
        }
    }

    fun deletePlaylist(onDeleted: () -> Unit) {
        val id = playlistId.value ?: return
        viewModelScope.launch {
            playlistRepository.deletePlaylist(id)
            onDeleted()
        }
    }

    fun removeTrackFromPlaylist(trackId: String) {
        val id = playlistId.value ?: return
        viewModelScope.launch {
            playlistRepository.removeTrackFromPlaylist(id, trackId)
        }
    }

    fun deleteTrack(trackId: String) {
        viewModelScope.launch {
            trackRepository.deleteTrack(trackId)
        }
    }
}
