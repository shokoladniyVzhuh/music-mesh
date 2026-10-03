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
import com.mesh.app.library.StoragePolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
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
 * Nearby discovery/connect, catalog exchange and resilient file transfer.
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
    private var pendingEndpointId: String? = null
    private var isAdvertising = false
    private var isDiscovering = false
    private var wantsAdvertising = false
    private var wantsDiscovery = false
    private var advertisingStartInFlight = false
    private var discoveryStartInFlight = false
    private val intentionallyClosingEndpoints = mutableSetOf<String>()

    /** Sender: tracks still to send. */
    private var sendQueue: List<Track> = emptyList()
    private var sendIndex: Int = 0
    private var activeOutgoingPayloadId: Long? = null
    private var activeOutgoingPayload: Payload? = null
    private val failedOutgoingTrackIds = mutableSetOf<String>()
    private var publishedTrackIds: Set<String> = emptySet()
    private var incomingLedger = IncomingTransferLedger(emptyList())
    private val failedIncomingPayloadIds = mutableSetOf<Long>()
    private val seenIncomingPayloadIds = mutableSetOf<Long>()
    private val controlPayloadIds = mutableSetOf<Long>()
    private var watchdog: Job? = null

    /** Receiver: payloadId → remote track metadata. */
    private val pendingOffers = mutableMapOf<Long, RemoteTrack>()
    private val receivedFilePayloads = mutableMapOf<Long, Payload>()
    /** Receiver: FILE payloads that reached SUCCESS before FILE_OFFER arrived. */
    private val completedIncomingPayloadIds = mutableSetOf<Long>()
    private var inflightImports: Int = 0
    private var transferFinishedReceived: Boolean = false
    private var receiverInterruptedReason: String? = null
    private var sessionToken: Long = 0

    fun setLocalNickname(nickname: String) {
        localNickname = nickname.ifBlank { "Mesh" }
    }

    fun startAdvertising() {
        stopDiscovery()
        if (isAdvertising) {
            wantsAdvertising = true
            return
        }
        if (advertisingStartInFlight) {
            wantsAdvertising = true
            _state.update {
                it.copy(
                    phase = TransferPhase.Advertising,
                    role = null,
                    errorMessage = null,
                    incomingConnection = null,
                )
            }
            return
        }
        resetSessionKeepingIdle()
        wantsAdvertising = true
        advertisingStartInFlight = true
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
                advertisingStartInFlight = false
                if (!wantsAdvertising) {
                    connectionsClient.stopAdvertising()
                    return@addOnSuccessListener
                }
                isAdvertising = true
                Log.d(TAG, "Advertising as $localNickname")
            }
            .addOnFailureListener { e ->
                advertisingStartInFlight = false
                isAdvertising = false
                if (!wantsAdvertising) return@addOnFailureListener
                wantsAdvertising = false
                Log.e(TAG, "Advertising failed", e)
                fail(e.message ?: "Advertising failed")
            }
    }

    fun stopAdvertising() {
        val wasActive = isAdvertising || advertisingStartInFlight || wantsAdvertising
        wantsAdvertising = false
        connectionsClient.stopAdvertising()
        isAdvertising = false
        if (wasActive) Log.d(TAG, "Stopped advertising")
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
        stopAdvertising()
        if (isDiscovering) {
            wantsDiscovery = true
            return
        }
        if (discoveryStartInFlight) {
            wantsDiscovery = true
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
            return
        }
        disconnectQuietly()
        clearTransferInternals()
        wantsDiscovery = true
        discoveryStartInFlight = true
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
                discoveryStartInFlight = false
                if (!wantsDiscovery) {
                    connectionsClient.stopDiscovery()
                    return@addOnSuccessListener
                }
                isDiscovering = true
                Log.d(TAG, "Discovery started")
            }
            .addOnFailureListener { e ->
                discoveryStartInFlight = false
                isDiscovering = false
                if (!wantsDiscovery) return@addOnFailureListener
                wantsDiscovery = false
                Log.e(TAG, "Discovery failed", e)
                fail(e.message ?: "Discovery failed")
            }
    }

    fun stopDiscovery(clearPeers: Boolean = true) {
        val wasActive = isDiscovering || discoveryStartInFlight || wantsDiscovery
        wantsDiscovery = false
        connectionsClient.stopDiscovery()
        isDiscovering = false
        if (wasActive) Log.d(TAG, "Stopped discovery")
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
        if (_state.value.phase !is TransferPhase.Discovering) return
        val peer = _state.value.discoveredPeers.find { it.endpointId == endpointId } ?: return
        val peerName = peer.nickname
        pendingPeerNickname = peerName
        intentionallyClosingEndpoints.remove(endpointId)
        pendingEndpointId = endpointId
        _state.update {
            it.copy(
                phase = TransferPhase.Connecting,
                role = TransferRole.Sender,
                peerNickname = peerName,
                errorMessage = null,
            )
        }
        stopDiscovery(clearPeers = false)
        armWatchdog("Connection timed out. Try connecting again.")
        val token = sessionToken
        connectionsClient
            .requestConnection(localNickname, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                Log.d(TAG, "Connection request sent to $peerName ($endpointId)")
            }
            .addOnFailureListener { e ->
                if (token != sessionToken || pendingEndpointId != endpointId) return@addOnFailureListener
                Log.e(TAG, "requestConnection failed", e)
                pendingPeerNickname = null
                pendingEndpointId = null
                fail(e.message ?: "Connection request failed")
                closeTransport()
            }
    }

    fun acceptConnection(endpointId: String) {
        if (pendingEndpointId != endpointId || _state.value.phase !is TransferPhase.AwaitingAccept) return
        val token = sessionToken
        armWatchdog("Connection timed out. Try connecting again.")
        connectionsClient
            .acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener {
                if (token != sessionToken || endpointId in intentionallyClosingEndpoints) return@addOnSuccessListener
                Log.d(TAG, "Accepted connection $endpointId")
                _state.update {
                    it.copy(
                        incomingConnection = null,
                        role = TransferRole.Receiver,
                    )
                }
            }
            .addOnFailureListener { e ->
                if (token != sessionToken || endpointId in intentionallyClosingEndpoints) return@addOnFailureListener
                Log.e(TAG, "acceptConnection failed", e)
                fail(e.message ?: "Accept failed", clearIncoming = true)
                closeTransport()
            }
    }

    fun rejectConnection(endpointId: String) {
        val token = sessionToken
        connectionsClient.rejectConnection(endpointId)
            .addOnCompleteListener {
                if (token != sessionToken || pendingEndpointId != endpointId) return@addOnCompleteListener
                if (pendingEndpointId == endpointId) pendingEndpointId = null
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
        val token = sessionToken
        // Claim the action before the first Room suspension so double-taps cannot launch two batches.
        _state.update {
            it.copy(phase = TransferPhase.Transferring(0, selected.size, null, 0))
        }
        armWatchdog("Could not prepare the download. Try again.")
        scope.launch {
          try {
            val existing = trackRepository.existingHashes()
            if (!isActiveSession(endpointId, token)) return@launch
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

            val sizes = current.remoteCatalog.filter { it.id in toRequest }.map { it.fileSize }
            // Nearby download + cache copy + final sandbox copy may coexist temporarily.
            if (sizes.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    StoragePolicy.ensureSpace(appContext.filesDir.usableSpace, sizes, copies = 3)
                }
            }
            if (!isActiveSession(endpointId, token)) return@launch
            incomingLedger = IncomingTransferLedger(toRequest)

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
            armWatchdog("Transfer stalled. The completed tracks are still in your library.")
            sendControl(endpointId, ControlMessage.DownloadRequest(toRequest, skipped))
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            if (isActiveSession(endpointId, token)) {
                fail(e.message ?: "Could not start download")
                closeTransport()
            }
          }
        }
    }

    /** End a transfer while keeping its result visible; fully received imports are allowed to finish. */
    fun cancelTransfer(reason: String = "Connection closed by you") {
        _state.value.connectedEndpointId?.let { sendControl(it, ControlMessage.Disconnect(reason), critical = false) }
        handleConnectionClosed(reason)
        closeTransport()
    }

    fun disconnect(reason: String = "Closed by user") {
        val endpointIds = setOfNotNull(
            _state.value.connectedEndpointId,
            pendingEndpointId,
            _state.value.incomingConnection?.endpointId,
        )
        endpointIds.forEach { endpointId ->
            intentionallyClosingEndpoints.add(endpointId)
            if (endpointId == _state.value.connectedEndpointId) {
                runCatching {
                    sendControl(endpointId, ControlMessage.Disconnect(reason), critical = false)
                }
            }
            connectionsClient.disconnectFromEndpoint(endpointId)
        }
        stopAdvertising()
        stopDiscovery()
        connectionsClient.stopAllEndpoints()
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
            is TransferPhase.WaitingForResult,
            is TransferPhase.Transferring,
            is TransferPhase.Success,
            is TransferPhase.PartialSuccess,
            -> {
                // Keep connection for transfer UI routes.
            }
            // Connecting / AwaitingAccept: only cancel on explicit back, not on forward nav.
            // Forward nav happens after Connected; if dispose races here, keep waiting.
            is TransferPhase.Connecting,
            is TransferPhase.AwaitingAccept,
            -> {
                // no-op: do not disconnect mid-handshake
            }
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
        _state.value.connectedEndpointId?.let { intentionallyClosingEndpoints.add(it) }
        stopAdvertising()
        stopDiscovery()
        clearTransferInternals()
        connectionsClient.stopAllEndpoints()
        _state.value = TransferSessionState()
    }

    private fun onConnected(endpointId: String, peerName: String, role: TransferRole) {
        watchdog?.cancel()
        pendingPeerNickname = null
        pendingEndpointId = null
        stopDiscovery(clearPeers = true)
        intentionallyClosingEndpoints.remove(endpointId)
        stopAdvertising()
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
            armWatchdog("The sender did not send a catalog. Try connecting again.")
        }
    }

    private suspend fun publishCatalog(endpointId: String) {
        val token = sessionToken
        val tracks = try {
            withContext(Dispatchers.IO) { trackRepository.getAllTracks() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isActiveSession(endpointId, token)) cancelTransfer("Could not read the library")
            return
        }
        if (!isActiveSession(endpointId, token)) return
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
        _state.update {
            it.copy(phase = TransferPhase.WaitingForDownloadRequest)
        }
        publishedTrackIds = remote.map { it.id }.toSet()
        sendControl(endpointId, ControlMessage.TrackList(remote))
    }

    private fun handleControlMessage(endpointId: String, message: ControlMessage) {
        if (_state.value.connectedEndpointId != endpointId) return
        if (_state.value.isTerminal && message !is ControlMessage.Disconnect) return
        when (message) {
            is ControlMessage.TrackList -> {
                if (_state.value.role != TransferRole.Receiver) return
                if (_state.value.phase !is TransferPhase.WaitingForCatalog) return
                require(message.tracks.map { it.id }.distinct().size == message.tracks.size) { "Repeated catalog IDs" }
                require(message.tracks.all { it.fileSize > 0 && it.fileHash.isNotBlank() }) { "Invalid track catalog" }
                watchdog?.cancel()
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
                if (_state.value.phase !is TransferPhase.WaitingForDownloadRequest) return
                require(message.trackIds.all { it in publishedTrackIds } &&
                    message.trackIds.size.toLong() + message.skipped <= publishedTrackIds.size) {
                    "Download request does not match the catalog"
                }
                _state.update {
                    it.copy(
                        skippedCount = message.skipped,
                        totalRequested = message.trackIds.size,
                        phase = TransferPhase.Transferring(0, message.trackIds.size, null, 0),
                    )
                }
                armWatchdog("Transfer stalled. Try connecting again.")
                scope.launch { startSending(endpointId, message.trackIds) }
            }
            is ControlMessage.FileOffer -> {
                if (_state.value.role != TransferRole.Receiver) return
                if (_state.value.phase !is TransferPhase.Transferring) return
                require(_state.value.remoteCatalog.any {
                    it.id in _state.value.selectedTrackIds && it == message.track
                }) { "File does not match the requested catalog" }
                if (!incomingLedger.offer(message.payloadId, message.track.id)) return
                pendingOffers[message.payloadId] = message.track
                if (failedIncomingPayloadIds.remove(message.payloadId)) {
                    pendingOffers.remove(message.payloadId)
                    recordIncomingFailure(message.track.id)
                    return
                }
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
                if (_state.value.phase !is TransferPhase.Transferring) return
                val failed = incomingLedger.recordSenderFailures(message.failedTrackIds)
                _state.update { it.copy(failedCount = it.failedCount + failed, processedCount = it.processedCount + failed) }
                transferFinishedReceived = true
                maybeFinishReceiver()
            }
            is ControlMessage.TransferResult -> {
                if (_state.value.role != TransferRole.Sender || _state.value.phase !is TransferPhase.WaitingForResult) return
                TransferFinishLogic.validateReceiverResult(message, _state.value.totalRequested, _state.value.skippedCount)
                watchdog?.cancel()
                val resultPhase = if (message.failed == 0) {
                    TransferPhase.Success(message.transferred, message.skipped)
                } else {
                    TransferPhase.PartialSuccess(message.transferred, message.skipped, message.failed,
                        message.message ?: "Some tracks could not be saved by the receiver")
                }
                _state.update { it.copy(phase = resultPhase, transferredCount = message.transferred,
                    skippedCount = message.skipped, failedCount = message.failed, currentTrackTitle = null) }
            }
            is ControlMessage.Disconnect -> {
                handleConnectionClosed(message.reason)
                closeTransport()
            }
        }
    }

    private suspend fun startSending(endpointId: String, trackIds: List<String>) {
        val token = sessionToken
        val tracks = try {
            withContext(Dispatchers.IO) { trackIds.mapNotNull { trackRepository.getById(it) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isActiveSession(endpointId, token)) cancelTransfer("Could not read the requested tracks")
            return
        }
        if (!isActiveSession(endpointId, token)) return
        failedOutgoingTrackIds.addAll(trackIds - tracks.map { it.id }.toSet())
        sendQueue = tracks
        sendIndex = 0
        activeOutgoingPayloadId = null
        _state.update {
            it.copy(
                totalRequested = trackIds.size,
                transferredCount = 0,
                failedCount = failedOutgoingTrackIds.size,
                processedCount = 0,
                phase = TransferPhase.Transferring(
                    completed = 0,
                    total = trackIds.size,
                    currentTrackTitle = tracks.firstOrNull()?.title,
                    currentFilePercent = 0,
                ),
            )
        }
        sendNextFile(endpointId)
    }

    private fun sendNextFile(endpointId: String) {
        if (!isActiveSession(endpointId, sessionToken)) return
        if (sendIndex >= sendQueue.size) {
            _state.update { it.copy(phase = TransferPhase.WaitingForResult, currentTrackTitle = null, currentFilePercent = 0) }
            armWatchdog("The receiver did not confirm saving the tracks. Check their library before retrying.")
            sendControl(endpointId, ControlMessage.TransferFinished(failedOutgoingTrackIds.toList()))
            return
        }

        val track = sendQueue[sendIndex]
        val file = File(track.localPath)
        if (!file.isFile || file.length() != track.fileSize) {
            Log.e(TAG, "Missing file for ${track.id}")
            _state.update { it.copy(failedCount = it.failedCount + 1) }
            failedOutgoingTrackIds.add(track.id)
            sendIndex++
            sendNextFile(endpointId)
            return
        }

        val filePayload = try {
            Payload.fromFile(file)
        } catch (e: Exception) {
            Log.e(TAG, "Payload.fromFile failed for ${track.id}", e)
            _state.update { it.copy(failedCount = it.failedCount + 1) }
            failedOutgoingTrackIds.add(track.id)
            sendIndex++
            sendNextFile(endpointId)
            return
        }
        activeOutgoingPayloadId = filePayload.id
        activeOutgoingPayload = filePayload
        val remote = RemoteTrack(
            id = track.id,
            title = track.title,
            artist = track.artist,
            durationMs = track.durationMs,
            fileSize = track.fileSize,
            fileHash = track.fileHash,
        )
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
        // sendPayload task success only schedules a send; receiver handles either arrival order.
        val offerBytes = TransferProtocol.encode(ControlMessage.FileOffer(remote, filePayload.id))
        if (offerBytes.size > MAX_CONTROL_BYTES) {
            cancelTransfer("Track metadata is too large to send.")
            return
        }
        val offerPayload = Payload.fromBytes(offerBytes)
        controlPayloadIds.add(offerPayload.id)
        val token = sessionToken
        connectionsClient
            .sendPayload(endpointId, offerPayload)
            .addOnSuccessListener {
                if (!isActiveSession(endpointId, token) || activeOutgoingPayloadId != filePayload.id) {
                    filePayload.close()
                    return@addOnSuccessListener
                }
                connectionsClient
                    .sendPayload(endpointId, filePayload)
                    .addOnFailureListener { e ->
                        if (!isActiveSession(endpointId, token)) return@addOnFailureListener
                        Log.e(TAG, "sendPayload(file) failed", e)
                        onOutgoingFileFailed(endpointId, filePayload.id)
                    }
            }
            .addOnFailureListener { e ->
                if (!isActiveSession(endpointId, token)) return@addOnFailureListener
                Log.e(TAG, "sendPayload(FILE_OFFER) failed", e)
                cancelTransfer("Could not send file information. Try connecting again.")
            }
    }

    private fun onOutgoingFileComplete(endpointId: String, payloadId: Long) {
        if (payloadId != activeOutgoingPayloadId) return
        activeOutgoingPayload?.close()
        activeOutgoingPayload = null
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

    private fun onOutgoingFileFailed(endpointId: String, payloadId: Long) {
        if (payloadId != activeOutgoingPayloadId) return
        activeOutgoingPayloadId = null
        activeOutgoingPayload?.close()
        activeOutgoingPayload = null
        failedOutgoingTrackIds.add(sendQueue[sendIndex].id)
        _state.update { it.copy(failedCount = it.failedCount + 1) }
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
        if (!incomingLedger.recordProcessed(remote.id)) {
            cleanupPayloadFile(payload)
            return
        }
        inflightImports++
        val importSessionToken = sessionToken
        scope.launch {
            var tempFile: File? = null
            try {
                tempFile = withContext(Dispatchers.IO) {
                    materializePayloadFile(payload, remote)
                }
                if (tempFile == null) {
                    cleanupPayloadFile(payload)
                    if (importSessionToken != sessionToken) return@launch
                    Log.e(TAG, "Could not materialize incoming file for ${remote.title}")
                    _state.update {
                        val failed = it.failedCount + 1
                        val processed = it.processedCount + 1
                        it.copy(
                            failedCount = failed,
                            processedCount = processed,
                            errorMessage = "Could not read the received file",
                            phase = (it.phase as? TransferPhase.Transferring)?.copy(completed = processed) ?: it.phase,
                        )
                    }
                    return@launch
                }
                val result = trackRepository.importP2pTrack(tempFile, remote)
                if (importSessionToken != sessionToken) {
                    tempFile.delete()
                    cleanupPayloadFile(payload)
                    return@launch
                }
                when (result) {
                    is ImportItemResult.Success -> {
                        Log.d(TAG, "Imported P2P track ${result.track.id}")
                        _state.update {
                            val transferred = it.transferredCount + 1
                            val processed = it.processedCount + 1
                            it.copy(
                                transferredCount = transferred,
                                processedCount = processed,
                                phase = (it.phase as? TransferPhase.Transferring)?.copy(completed = processed) ?: it.phase,
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
                                phase = (it.phase as? TransferPhase.Transferring)?.copy(completed = processed) ?: it.phase,
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
                                errorMessage = result.failure.reason,
                                phase = (it.phase as? TransferPhase.Transferring)?.copy(completed = processed) ?: it.phase,
                            )
                        }
                    }
                }
                tempFile.delete()
                cleanupPayloadFile(payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Incoming import failed", e)
                if (importSessionToken == sessionToken) {
                    _state.update { it.copy(failedCount = it.failedCount + 1,
                        processedCount = it.processedCount + 1, errorMessage = e.message ?: "Could not save track") }
                }
            } finally {
                tempFile?.delete()
                cleanupPayloadFile(payload)
                if (importSessionToken == sessionToken) {
                    inflightImports = (inflightImports - 1).coerceAtLeast(0)
                    maybeFinishReceiver()
                }
            }
        }
    }

    private fun maybeFinishReceiver() {
        if (inflightImports > 0) return
        val interruptedReason = receiverInterruptedReason
        if (interruptedReason != null) {
            finishReceiverSession(interruptedReason)
            return
        }
        if (!transferFinishedReceived) return
        if (!TransferFinishLogic.receiverCanFinishNormally(
                processed = _state.value.processedCount,
                totalRequested = _state.value.totalRequested,
                inflightImports = inflightImports,
            )
        ) {
            return
        }
        transferFinishedReceived = false
        finishReceiverSession()
    }

    private fun finishReceiverSession(interruptedReason: String? = null) {
        val s = _state.value
        val calculated = TransferFinishLogic.receiverPhase(
            transferred = s.transferredCount,
            skipped = s.skippedCount,
            failed = s.failedCount,
            processed = s.processedCount,
            totalRequested = s.totalRequested,
        )
        val phase = if (calculated is TransferPhase.PartialSuccess) {
            calculated.copy(message = interruptedReason ?: s.errorMessage ?: calculated.message)
        } else {
            calculated
        }
        _state.update {
            it.copy(
                phase = phase,
                currentTrackTitle = null,
                currentFilePercent = 0,
                errorMessage = interruptedReason ?: s.errorMessage,
            )
        }
        watchdog?.cancel()
        s.connectedEndpointId?.let { endpointId ->
            val failed = (phase as? TransferPhase.PartialSuccess)?.failed ?: 0
            sendControl(endpointId, ControlMessage.TransferResult(s.transferredCount, s.skippedCount,
                failed, interruptedReason ?: s.errorMessage), critical = false)
        }
        receiverInterruptedReason = null
        transferFinishedReceived = false
        discardPendingIncomingFiles()
    }

    private fun materializePayloadFile(payload: Payload, remote: RemoteTrack): File? {
        val payloadFile = payload.asFile() ?: return null
        val cacheDir = File(appContext.cacheDir, "nearby_incoming").apply { mkdirs() }
        // Never use a remote ID as a filesystem path.
        val out = File(cacheDir, "${payload.id}.mp3")
        return try {
            StoragePolicy.ensureSpace(cacheDir.usableSpace, listOf(remote.fileSize), copies = 2)
            val uri = payloadFile.asUri()
            if (uri != null) {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { output ->
                        StoragePolicy.copy(input, output, remote.fileSize, remote.fileSize)
                    }
                } ?: run {
                    out.delete()
                    return null
                }
                runCatching { appContext.contentResolver.delete(uri, null, null) }
            } else {
                payloadFile.asParcelFileDescriptor().use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { input ->
                        FileOutputStream(out).use { output ->
                            StoragePolicy.copy(input, output, remote.fileSize, remote.fileSize)
                        }
                    }
                }
            }
            if (!out.exists() || out.length() <= 0L) {
                Log.e(TAG, "Materialized file empty for payload ${payload.id}")
                out.delete()
                return null
            }
            Log.d(TAG, "Materialized ${out.length()} bytes for ${remote.id}")
            out
        } catch (e: Exception) {
            Log.e(TAG, "Failed to materialize payload", e)
            out.delete()
            throw e
        }
    }

    private fun cleanupPayloadFile(payload: Payload) {
        runCatching {
            payload.asFile()?.let { file ->
                val uri = file.asUri()
                if (uri != null) {
                    appContext.contentResolver.delete(uri, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    file.asJavaFile()?.delete()
                }
            }
        }
        runCatching { payload.close() }
    }

    private fun sendControl(endpointId: String, message: ControlMessage, critical: Boolean = true) {
        val bytes = TransferProtocol.encode(message)
        if (bytes.size > MAX_CONTROL_BYTES) {
            if (critical) cancelTransfer("The catalog is too large to send in this version of Music Mesh.")
            return
        }
        val token = sessionToken
        val payload = Payload.fromBytes(bytes)
        controlPayloadIds.add(payload.id)
        connectionsClient
            .sendPayload(endpointId, payload)
            .addOnFailureListener { e ->
                if (token != sessionToken) return@addOnFailureListener
                controlPayloadIds.remove(payload.id)
                Log.e(TAG, "Failed to send ${message::class.simpleName}", e)
                if (critical && isActiveSession(endpointId, token)) {
                    cancelTransfer("Could not send transfer instructions. Try connecting again.")
                }
            }
    }

    private fun isActiveSession(endpointId: String, token: Long): Boolean =
        token == sessionToken && _state.value.connectedEndpointId == endpointId && !_state.value.isTerminal

    private fun armWatchdog(reason: String) {
        watchdog?.cancel()
        val token = sessionToken
        watchdog = scope.launch {
            delay(STALL_TIMEOUT_MS)
            if (token == sessionToken && !_state.value.isTerminal) cancelTransfer(reason)
        }
    }

    private fun recordIncomingFailure(trackId: String) {
        if (!incomingLedger.recordProcessed(trackId)) return
        _state.update { it.copy(failedCount = it.failedCount + 1, processedCount = it.processedCount + 1) }
        maybeFinishReceiver()
    }

    /** Stop network work, but don't invalidate imports of FILE payloads already at SUCCESS. */
    private fun closeTransport() {
        watchdog?.cancel()
        setOfNotNull(_state.value.connectedEndpointId, pendingEndpointId,
            _state.value.incomingConnection?.endpointId).forEach { intentionallyClosingEndpoints.add(it) }
        activeOutgoingPayloadId?.let { connectionsClient.cancelPayload(it) }
        activeOutgoingPayload?.let { runCatching { it.close() } }
        activeOutgoingPayload = null
        activeOutgoingPayloadId = null
        receivedFilePayloads.keys.filterNot { it in completedIncomingPayloadIds }
            .forEach { connectionsClient.cancelPayload(it) }
        stopAdvertising()
        stopDiscovery()
        connectionsClient.stopAllEndpoints()
        pendingEndpointId = null
        _state.update { it.copy(connectedEndpointId = null, incomingConnection = null) }
    }

    private fun fail(message: String, clearIncoming: Boolean = false) {
        watchdog?.cancel()
        _state.update {
            it.copy(
                phase = TransferPhase.Failed(message),
                errorMessage = message,
                incomingConnection = if (clearIncoming) null else it.incomingConnection,
            )
        }
    }

    private fun disconnectQuietly() {
        val endpointIds = setOfNotNull(
            _state.value.connectedEndpointId,
            pendingEndpointId,
            _state.value.incomingConnection?.endpointId,
        )
        endpointIds.forEach { endpointId ->
            intentionallyClosingEndpoints.add(endpointId)
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
        sessionToken++
        watchdog?.cancel()
        watchdog = null
        activeOutgoingPayloadId?.let { connectionsClient.cancelPayload(it) }
        activeOutgoingPayload?.let { runCatching { it.close() } }
        activeOutgoingPayload = null
        failedOutgoingTrackIds.clear()
        publishedTrackIds = emptySet()
        incomingLedger = IncomingTransferLedger(emptyList())
        failedIncomingPayloadIds.clear()
        seenIncomingPayloadIds.clear()
        controlPayloadIds.clear()
        sendQueue = emptyList()
        sendIndex = 0
        activeOutgoingPayloadId = null
        pendingEndpointId = null
        discardPendingIncomingFiles()
        inflightImports = 0
        transferFinishedReceived = false
        receiverInterruptedReason = null
    }

    private fun discardPendingIncomingFiles() {
        receivedFilePayloads.keys.filterNot { it in completedIncomingPayloadIds }
            .forEach { connectionsClient.cancelPayload(it) }
        receivedFilePayloads.values.toList().forEach(::cleanupPayloadFile)
        pendingOffers.clear()
        receivedFilePayloads.clear()
        completedIncomingPayloadIds.clear()
    }

    private fun handleConnectionClosed(reason: String) {
        val current = _state.value
        if (current.phase is TransferPhase.Success ||
            current.phase is TransferPhase.PartialSuccess ||
            current.phase is TransferPhase.Failed
        ) {
            return
        }

        if (current.role == TransferRole.Receiver && current.totalRequested > 0) {
            receiverInterruptedReason = reason
            maybeFinishReceiver()
            return
        }

        val processed = current.transferredCount + current.failedCount
        if (processed > 0 || current.skippedCount > 0) {
            val missing = (current.totalRequested - processed).coerceAtLeast(0)
            _state.update {
                it.copy(
                    phase = TransferPhase.PartialSuccess(
                        transferred = current.transferredCount,
                        skipped = current.skippedCount,
                        failed = current.failedCount + missing,
                        message = reason,
                    ),
                    errorMessage = reason,
                )
            }
            clearTransferInternals()
        } else {
            fail(reason)
            clearTransferInternals()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!wantsDiscovery) return
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
            if (endpointId in intentionallyClosingEndpoints) {
                connectionsClient.rejectConnection(endpointId)
                Log.d(TAG, "Rejected late initiation for cancelled endpoint $endpointId")
                return
            }
            if (_state.value.connectedEndpointId != null ||
                (connectionInfo.isIncomingConnection && (!wantsAdvertising ||
                    (pendingEndpointId != null && pendingEndpointId != endpointId))) ||
                (!connectionInfo.isIncomingConnection && pendingEndpointId != endpointId)
            ) {
                connectionsClient.rejectConnection(endpointId)
                return
            }
            val nickname = connectionInfo.endpointName.ifBlank { endpointId }
            pendingEndpointId = endpointId
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
                val token = sessionToken
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
                        if (token != sessionToken || pendingEndpointId != endpointId || _state.value.isTerminal) return@addOnFailureListener
                        Log.e(TAG, "Sender acceptConnection failed", e)
                        fail(e.message ?: "Accept failed")
                        closeTransport()
                    }
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (endpointId in intentionallyClosingEndpoints) {
                if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                    connectionsClient.disconnectFromEndpoint(endpointId)
                } else {
                    intentionallyClosingEndpoints.remove(endpointId)
                }
                Log.d(TAG, "Ignored late result for cancelled endpoint $endpointId")
                return
            }
            if (pendingEndpointId != endpointId || _state.value.isTerminal) {
                if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK &&
                    _state.value.connectedEndpointId != endpointId) connectionsClient.disconnectFromEndpoint(endpointId)
                return
            }
            watchdog?.cancel()
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
                    pendingEndpointId = null
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
                        closeTransport()
                    }
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    pendingPeerNickname = null
                    pendingEndpointId = null
                    fail("Connection error", clearIncoming = true)
                    closeTransport()
                }
                else -> {
                    pendingPeerNickname = null
                    pendingEndpointId = null
                    fail(
                        resolution.status.statusMessage ?: "Connection failed",
                        clearIncoming = true,
                    )
                    closeTransport()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")
            if (intentionallyClosingEndpoints.remove(endpointId)) {
                Log.d(TAG, "Ignoring expected disconnect callback for $endpointId")
                return
            }
            if (endpointId != _state.value.connectedEndpointId && endpointId != pendingEndpointId) return
            handleConnectionClosed("Connection closed, transfer interrupted")
            closeTransport()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (_state.value.connectedEndpointId != endpointId || _state.value.isTerminal) {
                if (payload.type == Payload.Type.FILE) cleanupPayloadFile(payload)
                return
            }
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    runCatching { handleControlMessage(endpointId, TransferProtocol.decode(bytes)) }
                        .onFailure { e ->
                            Log.e(TAG, "Bad control payload", e)
                            cancelTransfer(e.message ?: "Invalid transfer instructions")
                        }
                }
                Payload.Type.FILE -> {
                    if (_state.value.role != TransferRole.Receiver ||
                        _state.value.phase !is TransferPhase.Transferring) {
                        cleanupPayloadFile(payload)
                        return
                    }
                    if (!seenIncomingPayloadIds.add(payload.id)) return
                    receivedFilePayloads[payload.id] = payload
                    Log.d(TAG, "File payload received id=${payload.id}")
                }
                else -> Log.d(TAG, "Ignoring payload type=${payload.type}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (!isActiveSession(endpointId, sessionToken)) return
            if (update.payloadId in controlPayloadIds) {
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> controlPayloadIds.remove(update.payloadId)
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                        controlPayloadIds.remove(update.payloadId)
                        cancelTransfer("Transfer instructions could not be delivered. Try connecting again.")
                    }
                }
                return
            }
            val percent = if (update.totalBytes > 0) {
                ((update.bytesTransferred * 100) / update.totalBytes).toInt().coerceIn(0, 100)
            } else {
                0
            }
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    if (_state.value.role == TransferRole.Receiver &&
                        (pendingOffers.containsKey(update.payloadId) ||
                            receivedFilePayloads.containsKey(update.payloadId))
                    ) {
                        armWatchdog("Transfer stalled. The completed tracks are still in your library.")
                        _state.update {
                            val total = it.totalRequested.coerceAtLeast(1)
                            it.copy(
                                currentFilePercent = percent,
                                phase = TransferPhase.Transferring(
                                    completed = it.processedCount,
                                    total = total,
                                    currentTrackTitle = it.currentTrackTitle,
                                    currentFilePercent = percent,
                                ),
                            )
                        }
                    } else if (_state.value.role == TransferRole.Sender &&
                        update.payloadId == activeOutgoingPayloadId
                    ) {
                        armWatchdog("Transfer stalled. Check the receiver's library before retrying.")
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
                        onOutgoingFileFailed(endpointId, update.payloadId)
                    } else if (_state.value.role == TransferRole.Receiver) {
                        val offer = pendingOffers.remove(update.payloadId)
                        val payload = receivedFilePayloads.remove(update.payloadId)
                        // BYTES callbacks must never count as failed tracks.
                        if (offer == null && payload == null) return
                        completedIncomingPayloadIds.remove(update.payloadId)
                        payload?.let(::cleanupPayloadFile)
                        if (offer != null) recordIncomingFailure(offer.id)
                        else failedIncomingPayloadIds.add(update.payloadId)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "NearbyManager"
        private const val STALL_TIMEOUT_MS = 120_000L
        // Nearby Connections MAX_BYTES_DATA_SIZE; catalog chunking is a separate future feature.
        private const val MAX_CONTROL_BYTES = 32_768
        const val SERVICE_ID = "com.mesh.app.nearby"
        private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    }
}
