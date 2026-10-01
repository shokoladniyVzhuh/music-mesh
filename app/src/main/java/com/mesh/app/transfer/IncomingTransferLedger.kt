package com.mesh.app.transfer

/** Tracks requested files, independent of the ordering of Nearby BYTES / FILE callbacks. */
class IncomingTransferLedger(trackIds: Collection<String>) {
    private val requested = trackIds.toSet()
    private val processed = mutableSetOf<String>()
    private val offers = mutableMapOf<Long, String>()

    val processedCount: Int get() = processed.size
    val isComplete: Boolean get() = processed.size == requested.size

    fun offer(payloadId: Long, trackId: String): Boolean {
        if (trackId !in requested || trackId in processed) return false
        if (payloadId in offers || trackId in offers.values) return false
        offers[payloadId] = trackId
        return true
    }

    /** Claims a track once before async import. Session processedCount advances only after import. */
    fun recordProcessed(trackId: String): Boolean =
        trackId in requested && processed.add(trackId)

    /** A failed outgoing file may never produce a receiver payload callback. */
    fun recordSenderFailures(trackIds: Collection<String>): Int {
        require(trackIds.all { it in requested }) { "Unknown failed track ID" }
        return trackIds.count(::recordProcessed)
    }
}
