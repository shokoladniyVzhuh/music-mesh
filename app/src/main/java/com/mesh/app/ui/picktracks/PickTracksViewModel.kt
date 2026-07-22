package com.mesh.app.ui.picktracks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mesh.app.CreatePlaylistDraft
import com.mesh.app.data.model.Track
import com.mesh.app.data.repository.PlaylistRepository
import com.mesh.app.data.repository.TrackRepository
import com.mesh.app.ui.navigation.PickTracksMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PickTracksUiState(
    val tracks: List<Track> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val mode: PickTracksMode = PickTracksMode.CREATE,
    val playlistId: String? = null,
)

class PickTracksViewModel(
    private val trackRepository: TrackRepository,
    private val playlistRepository: PlaylistRepository,
    private val draft: CreatePlaylistDraft,
) : ViewModel() {
    private val modeState = MutableStateFlow(PickTracksMode.CREATE)
    private val playlistIdState = MutableStateFlow<String?>(null)
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<PickTracksUiState> =
        combine(
            trackRepository.observeTracks(),
            selectedIds,
            modeState,
            playlistIdState,
        ) { tracks, selected, mode, playlistId ->
            PickTracksUiState(
                tracks = tracks,
                selectedIds = selected,
                mode = mode,
                playlistId = playlistId,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PickTracksUiState())

    fun init(mode: PickTracksMode, playlistId: String?) {
        modeState.value = mode
        playlistIdState.value = playlistId
        selectedIds.value = when (mode) {
            PickTracksMode.CREATE -> draft.selectedTrackIds.toSet()
            PickTracksMode.ADD_TO_PLAYLIST -> emptySet()
        }
    }

    fun toggleTrack(trackId: String) {
        selectedIds.update { current ->
            if (trackId in current) current - trackId else current + trackId
        }
    }

    fun confirm(onDone: () -> Unit) {
        val state = uiState.value
        if (state.selectedIds.isEmpty()) return
        viewModelScope.launch {
            when (state.mode) {
                PickTracksMode.CREATE -> {
                    draft.selectedTrackIds.clear()
                    draft.selectedTrackIds.addAll(state.selectedIds)
                }
                PickTracksMode.ADD_TO_PLAYLIST -> {
                    val playlistId = state.playlistId ?: return@launch
                    playlistRepository.addTracksToPlaylist(playlistId, state.selectedIds.toList())
                }
            }
            onDone()
        }
    }
}
