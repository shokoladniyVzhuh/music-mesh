package com.mesh.app.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mesh.app.data.prefs.UserPrefs
import com.mesh.app.transfer.DiscoveredPeer
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SendUiState(
    val session: TransferSessionState = TransferSessionState(),
    val selectedEndpointId: String? = null,
    val permissionsGranted: Boolean = false,
) {
    val peers: List<DiscoveredPeer> get() = session.discoveredPeers
    val phase: TransferPhase get() = session.phase
    val canGoOn: Boolean
        get() = permissionsGranted &&
            selectedEndpointId != null &&
            phase is TransferPhase.Discovering
    val isConnecting: Boolean get() = phase is TransferPhase.Connecting
    val readyForTransfer: Boolean
        get() = session.role == TransferRole.Sender && session.isInTransferFlow
    val errorMessage: String? get() = session.errorMessage
}

class SendViewModel(
    private val nearbyManager: NearbyManager,
    private val userPrefs: UserPrefs,
) : ViewModel() {
    private val selectedEndpointId = MutableStateFlow<String?>(null)
    private val permissionsGranted = MutableStateFlow(false)

    val uiState: StateFlow<SendUiState> = combine(
        nearbyManager.state,
        selectedEndpointId,
        permissionsGranted,
    ) { session, selected, granted ->
        SendUiState(
            session = session,
            selectedEndpointId = selected,
            permissionsGranted = granted,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SendUiState(),
    )

    fun onPermissionsResult(granted: Boolean) {
        permissionsGranted.value = granted
        if (granted) {
            startDiscovery()
        }
    }

    fun startDiscovery() {
        if (!permissionsGranted.value) return
        viewModelScope.launch {
            val nickname = userPrefs.nickname.first()?.takeIf { it.isNotBlank() } ?: "Mesh"
            nearbyManager.setLocalNickname(nickname)
            userPrefs.ensureDeviceId()
            selectedEndpointId.value = null
            nearbyManager.startDiscovery()
        }
    }

    fun selectPeer(endpointId: String) {
        selectedEndpointId.update { current ->
            if (current == endpointId) null else endpointId
        }
    }

    fun goOn() {
        val endpointId = selectedEndpointId.value ?: return
        nearbyManager.requestConnection(endpointId)
    }

    fun disconnect() {
        nearbyManager.disconnect()
        selectedEndpointId.value = null
    }

    fun retryAfterError() {
        nearbyManager.disconnect()
        selectedEndpointId.value = null
        startDiscovery()
    }

    fun onLeave() {
        nearbyManager.leaveDiscoverOrAdvertise()
        selectedEndpointId.value = null
    }
}
