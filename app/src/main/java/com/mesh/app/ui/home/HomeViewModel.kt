package com.mesh.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mesh.app.data.repository.TrackRepository
import com.mesh.app.library.ImportFailure
import kotlinx.coroutines.CancellationException
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
        if (uris.isEmpty() || _uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            try {
                val result = trackRepository.importTracks(uris)
                _uiState.update { it.copy(importFailures = result.failures) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(importFailures = listOf(
                    ImportFailure("Import", e.message ?: "Could not import tracks"),
                )) }
            } finally {
                _uiState.update { it.copy(isImporting = false) }
            }
        }
    }

    fun dismissImportErrors() {
        _uiState.update { it.copy(importFailures = emptyList()) }
    }
}
