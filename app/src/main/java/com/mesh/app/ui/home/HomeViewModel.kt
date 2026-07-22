package com.mesh.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mesh.app.data.repository.TrackRepository
import com.mesh.app.library.ImportBatchResult
import com.mesh.app.library.ImportFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val importFailures: List<ImportFailure> = emptyList(),
    val isImporting: Boolean = false,
)

class HomeViewModel(
    private val trackRepository: TrackRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun importTracks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            val result: ImportBatchResult = trackRepository.importTracks(uris)
            _uiState.update {
                it.copy(
                    isImporting = false,
                    importFailures = result.failures,
                )
            }
        }
    }

    fun dismissImportErrors() {
        _uiState.update { it.copy(importFailures = emptyList()) }
    }
}
