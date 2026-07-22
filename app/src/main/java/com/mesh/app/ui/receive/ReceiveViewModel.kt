package com.mesh.app.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mesh.app.data.prefs.UserPrefs
import com.mesh.app.transfer.IncomingConnection
import com.mesh.app.transfer.NearbyManager
import com.mesh.app.transfer.TransferPhase
import com.mesh.app.transfer.TransferRole
import com.mesh.app.transfer.TransferSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReceiveUiState(
    val session: TransferSessionState = TransferSessionState(),
    val permissionsGranted: Boolean = false,
) {
    val phase: TransferPhase get() = session.phase
    val incoming: IncomingConnection? get() = session.incomingConnection
    val readyForTransfer: Boolean
        get() = session.role == TransferRole.Receiver && session.isInTransferFlow
    val errorMessage: String? get() = session.errorMessage
    val isAdvertising: Boolean get() = phase is TransferPhase.Advertising
    val isAwaitingAccept: Boolean get() = phase is TransferPhase.AwaitingAccept
}

class ReceiveViewModel(
    private val nearbyManager: NearbyManager,
    private val userPrefs: UserPrefs,
) : ViewModel() {
    private val permissionsGranted = MutableStateFlow(false)

    val uiState: StateFlow<ReceiveUiState> = combine(
        nearbyManager.state,
        permissionsGranted,
    ) { session, granted ->
        ReceiveUiState(session = session, permissionsGranted = granted)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ReceiveUiState(),
    )

    fun onPermissionsResult(granted: Boolean) {
        permissionsGranted.value = granted
        if (granted) {
            startAdvertising()
        }
    }

    fun startAdvertising() {
        if (!permissionsGranted.value) return
        viewModelScope.launch {
            val nickname = userPrefs.nickname.first()?.takeIf { it.isNotBlank() } ?: "Mesh"
            nearbyManager.setLocalNickname(nickname)
            userPrefs.ensureDeviceId()
            nearbyManager.startAdvertising()
        }
    }

    fun acceptIncoming() {
        val incoming = nearbyManager.state.value.incomingConnection ?: return
        nearbyManager.acceptConnection(incoming.endpointId)
    }

    fun rejectIncoming() {
        val incoming = nearbyManager.state.value.incomingConnection ?: return
        nearbyManager.rejectConnection(incoming.endpointId)
    }

    fun disconnect() {
        nearbyManager.disconnect()
    }

    fun retryAfterError() {
        nearbyManager.disconnect()
        startAdvertising()
    }

    fun onLeave() {
        nearbyManager.leaveDiscoverOrAdvertise()
    }
}
