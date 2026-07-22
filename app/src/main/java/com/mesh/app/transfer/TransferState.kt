package com.mesh.app.transfer

data class DiscoveredPeer(
    val endpointId: String,
    val nickname: String,
)

data class IncomingConnection(
    val endpointId: String,
    val nickname: String,
)

enum class TransferRole {
    Sender,
    Receiver,
}

sealed class TransferPhase {
    data object Idle : TransferPhase()
    data object Discovering : TransferPhase()
    data object Advertising : TransferPhase()
    data object Connecting : TransferPhase()
    data class AwaitingAccept(val peerNickname: String) : TransferPhase()
    data class Connected(val peerNickname: String) : TransferPhase()
    data object WaitingForCatalog : TransferPhase()
    data object ChoosingTracks : TransferPhase()
    data object WaitingForDownloadRequest : TransferPhase()
    data class Transferring(
        val completed: Int,
        val total: Int,
        val currentTrackTitle: String?,
        val currentFilePercent: Int,
    ) : TransferPhase()
    data class Success(
        val transferred: Int,
        val skipped: Int,
    ) : TransferPhase()
    data class PartialSuccess(
        val transferred: Int,
        val skipped: Int,
        val failed: Int,
        val message: String,
    ) : TransferPhase()
    data class Failed(val message: String) : TransferPhase()
}

data class TransferSessionState(
    val phase: TransferPhase = TransferPhase.Idle,
    val role: TransferRole? = null,
    val peerNickname: String? = null,
    val discoveredPeers: List<DiscoveredPeer> = emptyList(),
    val incomingConnection: IncomingConnection? = null,
    val connectedEndpointId: String? = null,
    val remoteCatalog: List<RemoteTrack> = emptyList(),
    val selectedTrackIds: Set<String> = emptySet(),
    val transferredCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    /** Files that finished an import attempt (success / skip / fail) on receiver. */
    val processedCount: Int = 0,
    val totalRequested: Int = 0,
    val currentTrackTitle: String? = null,
    val currentFilePercent: Int = 0,
    val errorMessage: String? = null,
) {
    val isInTransferFlow: Boolean
        get() = when (phase) {
            is TransferPhase.Connected,
            is TransferPhase.WaitingForCatalog,
            is TransferPhase.ChoosingTracks,
            is TransferPhase.WaitingForDownloadRequest,
            is TransferPhase.Transferring,
            is TransferPhase.Success,
            is TransferPhase.PartialSuccess,
            -> true
            else -> false
        }
}
