package com.mesh.app.ui.downloaded

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mesh.app.data.model.Track
import com.mesh.app.data.repository.PlaylistRepository
import com.mesh.app.data.repository.TrackRepository
import com.mesh.app.player.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadedUiState(
    val tracks: List<Track> = emptyList(),
    val playlistSheetTrackId: String? = null,
    val playlistSelections: Map<String, Set<String>> = emptyMap(),
    val allPlaylists: List<com.mesh.app.data.model.Playlist> = emptyList(),
)

class DownloadedViewModel(
    private val trackRepository: TrackRepository,
    private val playlistRepository: PlaylistRepository,
    private val playerController: PlayerController,
) : ViewModel() {
    private val sheetTrackId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DownloadedUiState> =
        combine(
            trackRepository.observeTracks(),
            playlistRepository.observePlaylists(),
            sheetTrackId,
        ) { tracks, playlists, trackId ->
            DownloadedUiState(
                tracks = tracks,
                allPlaylists = playlists,
                playlistSheetTrackId = trackId,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DownloadedUiState())

    fun playTrack(track: Track) {
        val queue = uiState.value.tracks
        playerController.play(track, queue)
    }

    fun deleteTrack(trackId: String) {
        viewModelScope.launch {
            trackRepository.deleteTrack(trackId)
        }
    }

    fun openPlaylistSheet(trackId: String) {
        sheetTrackId.value = trackId
    }

    fun closePlaylistSheet() {
        sheetTrackId.value = null
    }

    fun observePlaylistIdsForTrack(trackId: String) =
        playlistRepository.observePlaylistIdsForTrack(trackId)

    fun savePlaylistSelections(trackId: String, selectedIds: Set<String>, currentIds: Set<String>) {
        viewModelScope.launch {
            playlistRepository.updateTrackPlaylists(trackId, currentIds, selectedIds)
            sheetTrackId.value = null
        }
    }
}
