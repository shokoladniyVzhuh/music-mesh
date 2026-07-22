package com.mesh.app.ui.createplaylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mesh.app.CreatePlaylistDraft
import com.mesh.app.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreatePlaylistUiState(
    val name: String = "",
    val selectedCount: Int = 0,
)

class CreatePlaylistViewModel(
    private val playlistRepository: PlaylistRepository,
    private val draft: CreatePlaylistDraft,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        CreatePlaylistUiState(
            name = draft.name,
            selectedCount = draft.selectedTrackIds.size,
        ),
    )
    val uiState: StateFlow<CreatePlaylistUiState> = _uiState.asStateFlow()

    fun onNameChange(value: String) {
        draft.name = value
        _uiState.update { it.copy(name = value) }
    }

    fun refreshSelectionCount() {
        _uiState.update { it.copy(selectedCount = draft.selectedTrackIds.size) }
    }

    fun createPlaylist(onCreated: (String) -> Unit) {
        val name = draft.name.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            val id = playlistRepository.createPlaylist(name, draft.selectedTrackIds.toList())
            draft.clear()
            onCreated(id)
        }
    }

    fun clearDraft() {
        draft.clear()
    }
}
