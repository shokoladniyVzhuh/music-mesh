package com.mesh.app.transfer

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.mesh.app.data.model.Track
import com.mesh.app.data.repository.TrackRepository
import com.mesh.app.library.ImportItemResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Nearby discovery/connect plus Stage 3 catalog + file transfer.
 */
class NearbyManager(
    context: Context,
    private val trackRepository: TrackRepository,
) {
    private val appContext = context.applicationContext
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(TransferSessionState())
    val state: StateFlow<TransferSessionState> = _state.asStateFlow()

    private var localNickname: String = "Mesh"
    private var pendingPeerNickname: String? = null
    private var isAdvertising = false
    private var isDiscovering = false

    /** Sender: tracks still to send. */
    private var sendQueue: List<Track> = emptyList()
    private var sendIndex: Int = 0
    private var activeOutgoingPayloadId: Long? = null

    /** Receiver: payloadId → remote track metadata. */
    private val pendingOffers = mutableMapOf<Long, RemoteTrack>()
    private val receivedFilePayloads = mutableMapOf<Long, Payload>()
    /** Receiver: FILE payloads that reached SUCCESS before FILE_OFFER arrived. */
    private val completedIncomingPayloadIds = mutableSetOf<Long>()

    fun setLocalNickname(nickname: String) {
        localNickname = nickname.ifBlank { "Mesh" }
    }

    fun startAdvertising() {
        if (isAdvertising) return
        stopDiscovery()
        resetSessionKeepingIdle()
        _state.update {
            it.copy(
                phase = TransferPhase.Advertising,
                role = null,
                errorMessage = null,
                incomingConnection = null,
            )
        }
        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()
        connectionsClient
            .startAdvertising(localNickname, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                isAdvertising = true
                Log.d(TAG, "Advertising as $localNickname")
            }
            .addOnFailureListener { e ->
                isAdvertising = false
                Log.e(TAG, "Advertising failed", e)
                fail(e.message ?: "Advertising failed")
            }
    }

    fun stopAdvertising() {
        if (!isAdvertising) return
        connectionsClient.stopAdvertising()
        isAdvertising = false
        Log.d(TAG, "Stopped advertising")
        val phase = _state.value.phase
        if (phase is TransferPhase.Advertising || phase is TransferPhase.AwaitingAccept) {
            _state.update {
                it.copy(
                    phase = TransferPhase.Idle,
                    incomingConnection = null,
                )
            }
        }
    }

    fun startDiscovery() {
        if (isDiscovering) return
        stopAdvertising()
        disconnectQuietly()
        clearTransferInternals()
        _state.update {
            it.copy(
                phase = TransferPhase.Discovering,
                role = null,
                discoveredPeers = emptyList(),
                errorMessage = null,
                incomingConnection = null,
                connectedEndpointId = null,
                remoteCatalog = emptyList(),
                selectedTrackIds = emptySet(),
            )
        }
        val options = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()
        connectionsClient
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                isDiscovering = true
                Log.d(TAG, "Discovery started")
            }
            .addOnFailureListener { e ->
                isDiscovering = false
                Log.e(TAG, "Discovery failed", e)
                fail(e.message ?: "Discovery failed")
            }
    }

    fun stopDiscovery(clearPeers: Boolean = true) {
        if (!isDiscovering) return
        connectionsClient.stopDiscovery()
        isDiscovering = false
        Log.d(TAG, "Stopped discovery")
        if (_state.value.phase is TransferPhase.Discovering) {
            _state.update {
                it.copy(
                    phase = TransferPhase.Idle,
                    discoveredPeers = if (clearPeers) emptyList() else it.discoveredPeers,
                )
            }
        }
    }

    fun requestConnection(endpointId: String) {
        val peer = _state.value.discoveredPeers.find { it.endpointId == endpointId }
        val peerName = peer?.nickname ?: "peer"
        pendingPeerNickname = peerName
        _state.update {
            it.copy(
                phase = TransferPhase.Connecting,
                role = TransferRole.Sender,
                peerNickname = peerName,
                errorMessage = null,
            )
        }
        stopDiscovery(clearPeers = false)
        connectionsClient
            .requestConnection(localNickname, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                Log.d(TAG, "Connection request sent to $peerName ($endpointId)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "requestConnection failed", e)
                pendingPeerNickname = null
                fail(e.message ?: "Connection request failed")
            }
    }

    fun acceptConnection(endpointId: String) {
        connectionsClient
            .acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener {
                Log.d(TAG, "Accepted connection $endpointId")
                _state.update {
                    it.copy(
                        incomingConnection = null,
                        role = TransferRole.Receiver,
                    )
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "acceptConnection failed", e)
                fail(e.message ?: "Accept failed", clearIncoming = true)
            }
    }

    fun rejectConnection(endpointId: String) {
        connectionsClient.rejectConnection(endpointId)
            .addOnCompleteListener {
                _state.update {
                    it.copy(
                        incomingConnection = null,
                        phase = if (isAdvertising) TransferPhase.Advertising else TransferPhase.Idle,
                        role = null,
                    )
                }
            }
    }

    fun toggleTrackSelection(trackId: String) {
        _state.update { current ->
            if (current.phase !is TransferPhase.ChoosingTracks) return@update current
            val next = current.selectedTrackIds.toMutableSet()
            if (!next.add(trackId)) next.remove(trackId)
            current.copy(selectedTrackIds = next)
        }
    }

    fun requestDownload() {
        val current = _state.value
        if (current.role != TransferRole.Receiver) return
        if (current.phase !is TransferPhase.ChoosingTracks) return
        val endpointId = current.connectedEndpointId ?: return
        val selected = current.selectedTrackIds
        if (selected.isEmpty()) return

        scope.launch {
            val existing = trackRepository.existingHashes()
            val toRequest = mutableListOf<String>()
            var skipped = 0
            for (track in current.remoteCatalog) {
                if (track.id !in selected) continue
                if (track.fileHash.lowercase() in existing) {
                    skipped++
                } else {
                    toRequest += track.id
                }
            }

            _state.update {
                it.copy(
                    skippedCount = skipped,
                    transferredCount = 0,
                    failedCount = 0,
                    processedCount = 0,
                    totalRequested = toRequest.size,
                    selectedTrackIds = selected,
                )
            }

            if (toRequest.isEmpty()) {
                _state.update {
                    it.copy(
                        phase = TransferPhase.Success(
                            transferred = 0,
                            skipped = skipped,
                        ),
                    )
                }
                sendControl(endpointId, ControlMessage.Disconnect("Nothing to download"))
                return@launch
            }

            _state.update {
                it.copy(
                    phase = TransferPhase.Transferring(
                        completed = 0,
                        total = toRequest.size,
                        currentTrackTitle = null,
                        currentFilePercent = 0,
                    ),
                )
            }
            sendControl(endpointId, ControlMessage.DownloadRequest(toRequest))
        }
    }

    fun disconnect(reason: String = "Closed by user") {
        val endpointId = _state.value.connectedEndpointId
        if (endpointId != null) {
            runCatching {
                sendControl(endpointId, ControlMessage.Disconnect(reason))
            }
            connectionsClient.disconnectFromEndpoint(endpointId)
        }
        connectionsClient.stopAllEndpoints()
        isAdvertising = false
        isDiscovering = false
        clearTransferInternals()
        _state.value = TransferSessionState()
        Log.d(TAG, "Disconnected: $reason")
    }

    /**
     * Leave Send/Receive discovery screens without tearing down an active transfer session.
     */
    fun leaveDiscoverOrAdvertise() {
        when (_state.value.phase) {
            is TransferPhase.Connected,
            is TransferPhase.WaitingForCatalog,
            is TransferPhase.ChoosingTracks,
            is TransferPhase.WaitingForDownloadRequest,
            is TransferPhase.Transferring,
            is TransferPhase.Success,
            is TransferPhase.PartialSuccess,
            -> {
                // Keep connection for transfer UI routes.
            }
            is TransferPhase.Connecting,
            is TransferPhase.AwaitingAccept,
            -> disconnect("Cancelled")
            else -> {
                stopAdvertising()
                stopDiscovery()
                _state.value.incomingConnection?.endpointId?.let { rejectConnection(it) }
                if (!_state.value.isInTransferFlow) {
                    _state.update {
                        it.copy(
                            phase = TransferPhase.Idle,
                            discoveredPeers = emptyList(),
                            incomingConnection = null,
                            errorMessage = null,
                            role = null,
                        )
                    }
                }
            }
        }
    }

    fun resetAfterFinished() {
        clearTransferInternals()
        connectionsClient.stopAllEndpoints()
        isAdvertising = false
        isDiscovering = false
        _state.value = TransferSessionState()
    }

    private fun onConnected(endpointId: String, peerName: String, role: TransferRole) {
        pendingPeerNickname = null
        stopDiscovery(clearPeers = true)
        if (isAdvertising) {
            connectionsClient.stopAdvertising()
            isAdvertising = false
        }
        Log.d(TAG, "Connected to $peerName ($endpointId) as $role")
        _state.update {
            it.copy(
                phase = TransferPhase.Connected(peerName),
                role = role,
                peerNickname = peerName,
                connectedEndpointId = endpointId,
                incomingConnection = null,
                discoveredPeers = emptyList(),
                errorMessage = null,
                remoteCatalog = emptyList(),
                selectedTrackIds = emptySet(),
                transferredCount = 0,
                skippedCount = 0,
                failedCount = 0,
                processedCount = 0,
                totalRequested = 0,
            )
        }
        if (role == TransferRole.Sender) {
            scope.launch { publishCatalog(endpointId) }
        } else {
            _state.update { it.copy(phase = TransferPhase.WaitingForCatalog) }
        }
    }

    private suspend fun publishCatalog(endpointId: String) {
        val tracks = withContext(Dispatchers.IO) { trackRepository.getAllTracks() }
        val remote = tracks.map {
            RemoteTrack(
                id = it.id,
                title = it.title,
                artist = it.artist,
                durationMs = it.durationMs,
                fileSize = it.fileSize,
                fileHash = it.fileHash,
            )
        }
        sendControl(endpointId, ControlMessage.TrackList(remote))
        _state.update {
            it.copy(phase = TransferPhase.WaitingForDownloadRequest)
        }
    }

    private fun handleControlMessage(endpointId: String, message: ControlMessage) {
        when (message) {
            is ControlMessage.TrackList -> {
                if (_state.value.role != TransferRole.Receiver) return
                _state.update {
                    it.copy(
                        remoteCatalog = message.tracks,
                        phase = TransferPhase.ChoosingTracks,
                        selectedTrackIds = emptySet(),
                    )
                }
            }
            is ControlMessage.DownloadRequest -> {
                if (_state.value.role != TransferRole.Sender) return
                scope.launch { startSending(endpointId, message.trackIds) }
            }
            is ControlMessage.FileOffer -> {
                if (_state.value.role != TransferRole.Receiver) return
                pendingOffers[message.payloadId] = message.track
                _state.update {
                    val total = it.totalRequested.coerceAtLeast(1)
                    it.copy(
                        currentTrackTitle = message.track.title,
                        phase = TransferPhase.Transferring(
                            completed = it.processedCount,
                            total = total,
                            currentTrackTitle = message.track.title,
                            currentFilePercent = 0,
                        ),
                    )
                }
                val payload = receivedFilePayloads[message.payloadId]
                if (payload != null && completedIncomingPayloadIds.remove(message.payloadId)) {
                    // SUCCESS already happened before offer — import now.
                    onIncomingFileComplete(endpointId, message.payloadId, payload)
                }
            }
            is ControlMessage.TransferFinished -> {
                if (_state.value.role != TransferRole.Receiver) return
                finishReceiverSession()
            }
            is ControlMessage.Disconnect -> {
                val current = _state.value
                if (current.phase is TransferPhase.Transferring ||
                    current.phase is TransferPhase.ChoosingTracks ||
                    current.phase is TransferPhase.WaitingForDownloadRequest ||
                    current.phase is TransferPhase.WaitingForCatalog
                ) {
                    val transferred = current.transferredCount
                    val total = current.totalRequested
                    if (transferred > 0 && transferred < total) {
                        _state.update {
                            it.copy(
                                phase = TransferPhase.PartialSuccess(
                                    transferred = transferred,
                                    skipped = it.skippedCount,
                                    failed = it.failedCount,
                                    message = message.reason,
                                ),
                                errorMessage = message.reason,
                            )
                        }
                    } else if (transferred > 0 || current.skippedCount > 0) {
                        _state.update {
                            it.copy(
                                phase = TransferPhase.Success(
                                    transferred = transferred,
                                    skipped = it.skippedCount,
                                ),
                            )
                        }
                    } else {
                        fail(message.reason)
                    }
                } else {
                    fail(message.reason)
                }
                connectionsClient.stopAllEndpoints()
                isAdvertising = false
                isDiscovering = false
                clearTransferInternals()
            }
        }
    }

    private suspend fun startSending(endpointId: String, trackIds: List<String>) {
        val tracks = withContext(Dispatchers.IO) {
            trackIds.mapNotNull { trackRepository.getById(it) }
        }
        sendQueue = tracks
        sendIndex = 0
        activeOutgoingPayloadId = null
        _state.update {
            it.copy(
                totalRequested = tracks.size,
                transferredCount = 0,
                failedCount = 0,
                processedCount = 0,
                phase = TransferPhase.Transferring(
                    completed = 0,
                    total = tracks.size,
                    currentTrackTitle = tracks.firstOrNull()?.title,
                    currentFilePercent = 0,
                ),
            )
        }
        if (tracks.isEmpty()) {
            sendControl(endpointId, ControlMessage.TransferFinished)
            _state.update {
                it.copy(phase = TransferPhase.Success(transferred = 0, skipped = it.skippedCount))
            }
            return
        }
        sendNextFile(endpointId)
    }

    private fun sendNextFile(endpointId: String) {
        if (sendIndex >= sendQueue.size) {
            sendControl(endpointId, ControlMessage.TransferFinished)
            val s = _state.value
            val phase = if (s.failedCount > 0) {
                TransferPhase.PartialSuccess(
                    transferred = s.transferredCount,
                    skipped = s.skippedCount,
                    failed = s.failedCount,
                    message = "Some tracks failed to send",
                )
            } else {
                TransferPhase.Success(
                    transferred = s.transferredCount,
                    skipped = s.skippedCount,
                )
            }
            _state.update { it.copy(phase = phase, currentTrackTitle = null, currentFilePercent = 0) }
            return
        }

        val track = sendQueue[sendIndex]
        val file = File(track.localPath)
        if (!file.exists()) {
            Log.e(TAG, "Missing file for ${track.id}")
            _state.update { it.copy(failedCount = it.failedCount + 1) }
            sendIndex++
            sendNextFile(endpointId)
            return
        }

        val filePayload = Payload.fromFile(file)
        activeOutgoingPayloadId = filePayload.id
        val remote = RemoteTrack(
            id = track.id,
            title = track.title,
            artist = track.artist,
            durationMs = track.durationMs,
            fileSize = track.fileSize,
            fileHash = track.fileHash,
        )
        sendControl(endpointId, ControlMessage.FileOffer(remote, filePayload.id))
        _state.update {
            it.copy(
                currentTrackTitle = track.title,
                currentFilePercent = 0,
                phase = TransferPhase.Transferring(
                    completed = it.transferredCount,
                    total = it.totalRequested,
                    currentTrackTitle = track.title,
                    currentFilePercent = 0,
                ),
            )
        }
        connectionsClient
            .sendPayload(endpointId, filePayload)
            .addOnFailureListener { e ->
                Log.e(TAG, "sendPayload failed", e)
                _state.update { it.copy(failedCount = it.failedCount + 1) }
                sendIndex++
                activeOutgoingPayloadId = null
                sendNextFile(endpointId)
            }
    }

    private fun onOutgoingFileComplete(endpointId: String, payloadId: Long) {
        if (payloadId != activeOutgoingPayloadId) return
        activeOutgoingPayloadId = null
        _state.update {
            val completed = it.transferredCount + 1
            it.copy(
                transferredCount = completed,
                currentFilePercent = 100,
                phase = TransferPhase.Transferring(
                    completed = completed,
                    total = it.totalRequested,
                    currentTrackTitle = it.currentTrackTitle,
                    currentFilePercent = 100,
                ),
            )
        }
        sendIndex++
        sendNextFile(endpointId)
    }

    private fun onIncomingFileComplete(endpointId: String, payloadId: Long, payload: Payload) {
        val remote = pendingOffers.remove(payloadId) ?: run {
            // Offer not yet received — keep payload until FILE_OFFER arrives.
            completedIncomingPayloadIds.add(payloadId)
            receivedFilePayloads[payloadId] = payload
            Log.w(TAG, "SUCCESS before FILE_OFFER for payload $payloadId — waiting for offer")
            return
        }
        completedIncomingPayloadIds.remove(payloadId)
        receivedFilePayloads.remove(payloadId)
        scope.launch {
            val tempFile = withContext(Dispatchers.IO) {
                materializePayloadFile(payload, remote.id)
            }
            if (tempFile == null) {
                cleanupPayloadFile(payload)
                _state.update {
                    val failed = it.failedCount + 1
                    val processed = it.processedCount + 1
                    it.copy(
                        failedCount = failed,
                        processedCount = processed,
                        phase = TransferPhase.Transferring(
                            completed = processed,
                            total = it.totalRequested,
                            currentTrackTitle = remote.title,
                            currentFilePercent = 0,
                        ),
                    )
                }
                return@launch
            }
            when (val result = trackRepository.importP2pTrack(tempFile, remote)) {
                is ImportItemResult.Success -> {
                    _state.update {
                        val transferred = it.transferredCount + 1
                        val processed = it.processedCount + 1
                        it.copy(
                            transferredCount = transferred,
                            processedCount = processed,
                            currentFilePercent = 100,
                            phase = TransferPhase.Transferring(
                                completed = processed,
                                total = it.totalRequested,
                                currentTrackTitle = remote.title,
                                currentFilePercent = 100,
                            ),
                        )
                    }
                }
                is ImportItemResult.SkippedDuplicate -> {
                    _state.update {
                        val skipped = it.skippedCount + 1
                        val processed = it.processedCount + 1
                        it.copy(
                            skippedCount = skipped,
                            processedCount = processed,
                            phase = TransferPhase.Transferring(
                                completed = processed,
                                total = it.totalRequested,
                                currentTrackTitle = remote.title,
                                currentFilePercent = 100,
                            ),
                        )
                    }
                }
                is ImportItemResult.Failed -> {
                    Log.e(TAG, "Import failed: ${result.failure.reason}")
                    _state.update {
                        val failed = it.failedCount + 1
                        val processed = it.processedCount + 1
                        it.copy(
                            failedCount = failed,
                            processedCount = processed,
                            phase = TransferPhase.Transferring(
                                completed = processed,
                                total = it.totalRequested,
                                currentTrackTitle = remote.title,
                                currentFilePercent = 0,
                            ),
                        )
                    }
                }
            }
            tempFile.delete()
            cleanupPayloadFile(payload)
        }
    }

    private fun finishReceiverSession() {
        val s = _state.value
        val phase = TransferFinishLogic.receiverPhase(
            transferred = s.transferredCount,
            skipped = s.skippedCount,
            failed = s.failedCount,
            processed = s.processedCount,
            totalRequested = s.totalRequested,
        )
        _state.update {
            it.copy(
                phase = phase,
                currentTrackTitle = null,
                currentFilePercent = 0,
            )
        }
    }

    private fun materializePayloadFile(payload: Payload, trackId: String): File? {
        val payloadFile = payload.asFile() ?: return null
        val cacheDir = File(appContext.cacheDir, "nearby_incoming").apply { mkdirs() }
        val out = File(cacheDir, "$trackId-${payload.id}.mp3")
        return try {
            val javaFile = payloadFile.asJavaFile()
            if (javaFile != null) {
                javaFile.inputStream().use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            } else {
                val pfd = payloadFile.asParcelFileDescriptor() ?: return null
                pfd.use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "Failed to materialize payload", e)
            out.delete()
            null
        }
    }

    private fun cleanupPayloadFile(payload: Payload) {
        runCatching {
            payload.asFile()?.asJavaFile()?.delete()
        }
    }

    private fun sendControl(endpointId: String, message: ControlMessage) {
        val bytes = TransferProtocol.encode(message)
        connectionsClient
            .sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send ${message::class.simpleName}", e)
            }
    }

    private fun fail(message: String, clearIncoming: Boolean = false) {
        _state.update {
            it.copy(
                phase = TransferPhase.Failed(message),
                errorMessage = message,
                incomingConnection = if (clearIncoming) null else it.incomingConnection,
            )
        }
    }

    private fun disconnectQuietly() {
        val endpointId = _state.value.connectedEndpointId
        if (endpointId != null) {
            connectionsClient.disconnectFromEndpoint(endpointId)
        }
        connectionsClient.stopAllEndpoints()
    }

    private fun resetSessionKeepingIdle() {
        disconnectQuietly()
        clearTransferInternals()
        _state.value = TransferSessionState()
    }

    private fun clearTransferInternals() {
        sendQueue = emptyList()
        sendIndex = 0
        activeOutgoingPayloadId = null
        pendingOffers.clear()
        receivedFilePayloads.clear()
        completedIncomingPayloadIds.clear()
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val nickname = info.endpointName.ifBlank { endpointId }
            Log.d(TAG, "Found $nickname ($endpointId)")
            _state.update { current ->
                val without = current.discoveredPeers.filterNot { it.endpointId == endpointId }
                current.copy(
                    discoveredPeers = without + DiscoveredPeer(endpointId, nickname),
                )
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost $endpointId")
            _state.update { current ->
                current.copy(
                    discoveredPeers = current.discoveredPeers.filterNot { it.endpointId == endpointId },
                )
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            val nickname = connectionInfo.endpointName.ifBlank { endpointId }
            Log.d(TAG, "Connection initiated from $nickname ($endpointId), incoming=${connectionInfo.isIncomingConnection}")
            if (connectionInfo.isIncomingConnection) {
                _state.update {
                    it.copy(
                        phase = TransferPhase.AwaitingAccept(nickname),
                        incomingConnection = IncomingConnection(endpointId, nickname),
                        peerNickname = nickname,
                        role = TransferRole.Receiver,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        phase = TransferPhase.Connecting,
                        role = TransferRole.Sender,
                        peerNickname = nickname,
                    )
                }
                connectionsClient
                    .acceptConnection(endpointId, payloadCallback)
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Sender acceptConnection failed", e)
                        fail(e.message ?: "Accept failed")
                    }
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val role = _state.value.role
                        ?: if (_state.value.incomingConnection != null) {
                            TransferRole.Receiver
                        } else {
                            TransferRole.Sender
                        }
                    val peerName = when (val p = _state.value.phase) {
                        is TransferPhase.AwaitingAccept -> p.peerNickname
                        else -> pendingPeerNickname
                            ?: _state.value.peerNickname
                            ?: _state.value.incomingConnection?.nickname
                            ?: _state.value.discoveredPeers.find { it.endpointId == endpointId }?.nickname
                            ?: endpointId
                    }
                    onConnected(endpointId, peerName, role)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d(TAG, "Connection rejected by $endpointId")
                    pendingPeerNickname = null
                    _state.update {
                        it.copy(
                            phase = if (isAdvertising) {
                                TransferPhase.Advertising
                            } else {
                                TransferPhase.Failed("Connection rejected")
                            },
                            incomingConnection = null,
                            role = if (isAdvertising) null else it.role,
                            errorMessage = if (!isAdvertising) "Connection rejected" else null,
                        )
                    }
                    if (!isAdvertising) {
                        startDiscovery()
                    }
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    pendingPeerNickname = null
                    fail("Connection error", clearIncoming = true)
                }
                else -> {
                    pendingPeerNickname = null
                    fail(
                        resolution.status.statusMessage ?: "Connection failed",
                        clearIncoming = true,
                    )
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")
            val current = _state.value
            when (current.phase) {
                is TransferPhase.Success,
                is TransferPhase.PartialSuccess,
                -> {
                    // Already finished; leave result visible.
                    clearTransferInternals()
                }
                is TransferPhase.Transferring -> {
                    val transferred = current.transferredCount
                    if (transferred > 0) {
                        _state.update {
                            TransferSessionState(
                                phase = TransferPhase.PartialSuccess(
                                    transferred = transferred,
                                    skipped = current.skippedCount,
                                    failed = current.failedCount,
                                    message = "Connection closed, transfer interrupted",
                                ),
                                role = current.role,
                                peerNickname = current.peerNickname,
                                transferredCount = transferred,
                                skippedCount = current.skippedCount,
                                failedCount = current.failedCount,
                                totalRequested = current.totalRequested,
                                errorMessage = "Peer disconnected",
                            )
                        }
                    } else {
                        fail("Peer disconnected")
                    }
                    clearTransferInternals()
                }
                else -> {
                    fail("Peer disconnected")
                    clearTransferInternals()
                }
            }
            isAdvertising = false
            isDiscovering = false
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    runCatching { TransferProtocol.decode(bytes) }
                        .onSuccess { handleControlMessage(endpointId, it) }
                        .onFailure { e -> Log.e(TAG, "Bad control payload", e) }
                }
                Payload.Type.FILE -> {
                    receivedFilePayloads[payload.id] = payload
                    Log.d(TAG, "File payload received id=${payload.id}")
                }
                else -> Log.d(TAG, "Ignoring payload type=${payload.type}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val percent = if (update.totalBytes > 0) {
                ((update.bytesTransferred * 100) / update.totalBytes).toInt().coerceIn(0, 100)
            } else {
                0
            }
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    if (_state.value.role == TransferRole.Receiver &&
                        pendingOffers.containsKey(update.payloadId)
                    ) {
                        _state.update {
                            val total = it.totalRequested.coerceAtLeast(1)
                            it.copy(
                                currentFilePercent = percent,
                                phase = TransferPhase.Transferring(
                                    completed = it.transferredCount,
                                    total = total,
                                    currentTrackTitle = it.currentTrackTitle,
                                    currentFilePercent = percent,
                                ),
                            )
                        }
                    } else if (_state.value.role == TransferRole.Sender &&
                        update.payloadId == activeOutgoingPayloadId
                    ) {
                        _state.update {
                            it.copy(
                                currentFilePercent = percent,
                                phase = TransferPhase.Transferring(
                                    completed = it.transferredCount,
                                    total = it.totalRequested,
                                    currentTrackTitle = it.currentTrackTitle,
                                    currentFilePercent = percent,
                                ),
                            )
                        }
                    }
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    if (_state.value.role == TransferRole.Sender &&
                        update.payloadId == activeOutgoingPayloadId
                    ) {
                        onOutgoingFileComplete(endpointId, update.payloadId)
                    } else if (_state.value.role == TransferRole.Receiver) {
                        val payload = receivedFilePayloads[update.payloadId]
                        if (payload != null) {
                            if (pendingOffers.containsKey(update.payloadId)) {
                                onIncomingFileComplete(endpointId, update.payloadId, payload)
                            } else {
                                // Keep file until FILE_OFFER arrives.
                                completedIncomingPayloadIds.add(update.payloadId)
                                Log.d(TAG, "File SUCCESS before offer id=${update.payloadId}")
                            }
                        }
                    }
                }
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED,
                -> {
                    Log.e(TAG, "Payload ${update.payloadId} failed status=${update.status}")
                    if (_state.value.role == TransferRole.Sender &&
                        update.payloadId == activeOutgoingPayloadId
                    ) {
                        activeOutgoingPayloadId = null
                        _state.update { it.copy(failedCount = it.failedCount + 1) }
                        sendIndex++
                        sendNextFile(endpointId)
                    } else if (_state.value.role == TransferRole.Receiver) {
                        pendingOffers.remove(update.payloadId)
                        completedIncomingPayloadIds.remove(update.payloadId)
                        receivedFilePayloads.remove(update.payloadId)?.let { cleanupPayloadFile(it) }
                        _state.update {
                            val failed = it.failedCount + 1
                            val processed = it.processedCount + 1
                            it.copy(
                                failedCount = failed,
                                processedCount = processed,
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "NearbyManager"
        const val SERVICE_ID = "com.mesh.app.nearby"
        private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    }
}
