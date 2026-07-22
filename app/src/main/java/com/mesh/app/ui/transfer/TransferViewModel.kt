package com.mesh.app.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mesh.app.transfer.NearbyManager
import com.mesh.app.transfer.RemoteTrack
import com.mesh.app.transfer.TransferPhase
import com.mesh.app.transfer.TransferRole
import com.mesh.app.transfer.TransferSessionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class TransferUiState(
    val session: TransferSessionState = TransferSessionState(),
) {
    val phase: TransferPhase get() = session.phase
    val role: TransferRole? get() = session.role
    val peerNickname: String? get() = session.peerNickname
    val catalog: List<RemoteTrack> get() = session.remoteCatalog
    val selectedIds: Set<String> get() = session.selectedTrackIds
    val skippedCount: Int get() = session.skippedCount
    val canDownload: Boolean
        get() = phase is TransferPhase.ChoosingTracks && selectedIds.isNotEmpty()
    val isFinished: Boolean
        get() = phase is TransferPhase.Success || phase is TransferPhase.PartialSuccess
    val isFailed: Boolean get() = phase is TransferPhase.Failed
    val progressTitle: String
        get() = when (session.role) {
            TransferRole.Receiver -> "Receiving"
            else -> "Sending"
        }
}

class TransferViewModel(
    private val nearbyManager: NearbyManager,
) : ViewModel() {
    val uiState: StateFlow<TransferUiState> = nearbyManager.state
        .map { TransferUiState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransferUiState())

    fun toggleTrack(trackId: String) {
        nearbyManager.toggleTrackSelection(trackId)
    }

    fun download() {
        nearbyManager.requestDownload()
    }

    fun closeConnection() {
        nearbyManager.disconnect("Closed by user")
    }

    fun continueListening() {
        nearbyManager.resetAfterFinished()
    }
}
