package com.mesh.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProtocolTest {
    @Test
    fun trackList_roundTrip_empty() {
        val message = ControlMessage.TrackList(emptyList())
        val decoded = TransferProtocol.decode(TransferProtocol.encode(message))
        assertTrue(decoded is ControlMessage.TrackList)
        assertEquals(0, (decoded as ControlMessage.TrackList).tracks.size)
    }

    @Test
    fun trackList_roundTrip_multipleTracks() {
        val tracks = listOf(
            RemoteTrack("1", "Song A", "Artist", 120_000, 3_000_000, "abc"),
            RemoteTrack("2", "Song B", null, 90_000, 2_000_000, "def"),
        )
        val decoded = TransferProtocol.decode(
            TransferProtocol.encode(ControlMessage.TrackList(tracks)),
        ) as ControlMessage.TrackList
        assertEquals(2, decoded.tracks.size)
        assertEquals("Song A", decoded.tracks[0].title)
        assertEquals("Artist", decoded.tracks[0].artist)
        assertEquals(null, decoded.tracks[1].artist)
        assertEquals("def", decoded.tracks[1].fileHash)
    }

    @Test
    fun downloadRequest_roundTrip() {
        val decoded = TransferProtocol.decode(
            TransferProtocol.encode(ControlMessage.DownloadRequest(listOf("a", "b", "c"))),
        ) as ControlMessage.DownloadRequest
        assertEquals(listOf("a", "b", "c"), decoded.trackIds)
    }

    @Test
    fun fileOffer_roundTrip() {
        val track = RemoteTrack("id", "Title", "Art", 1L, 2L, "hash")
        val decoded = TransferProtocol.decode(
            TransferProtocol.encode(ControlMessage.FileOffer(track, 42L)),
        ) as ControlMessage.FileOffer
        assertEquals(42L, decoded.payloadId)
        assertEquals("hash", decoded.track.fileHash)
        assertEquals("Title", decoded.track.title)
    }

    @Test
    fun transferFinished_and_disconnect_roundTrip() {
        assertTrue(
            TransferProtocol.decode(
                TransferProtocol.encode(ControlMessage.TransferFinished),
            ) is ControlMessage.TransferFinished,
        )
        val disconnect = TransferProtocol.decode(
            TransferProtocol.encode(ControlMessage.Disconnect("peer left")),
        ) as ControlMessage.Disconnect
        assertEquals("peer left", disconnect.reason)
    }
}

class TransferSessionStateTest {
    @Test
    fun isInTransferFlow_coversCatalogAndProgressPhases() {
        assertTrue(
            TransferSessionState(phase = TransferPhase.WaitingForCatalog).isInTransferFlow,
        )
        assertTrue(
            TransferSessionState(phase = TransferPhase.ChoosingTracks).isInTransferFlow,
        )
        assertTrue(
            TransferSessionState(phase = TransferPhase.WaitingForDownloadRequest).isInTransferFlow,
        )
        assertTrue(
            TransferSessionState(
                phase = TransferPhase.Transferring(1, 3, "x", 50),
            ).isInTransferFlow,
        )
        assertTrue(
            TransferSessionState(phase = TransferPhase.Success(1, 0)).isInTransferFlow,
        )
        assertTrue(
            !TransferSessionState(phase = TransferPhase.Discovering).isInTransferFlow,
        )
        assertTrue(
            !TransferSessionState(phase = TransferPhase.Idle).isInTransferFlow,
        )
    }

    @Test
    fun duplicateSkipLogic_filtersExistingHashes() {
        val catalog = listOf(
            RemoteTrack("1", "A", null, 1, 1, "aaa"),
            RemoteTrack("2", "B", null, 1, 1, "BBB"),
            RemoteTrack("3", "C", null, 1, 1, "ccc"),
        )
        val selected = setOf("1", "2", "3")
        val existing = setOf("aaa", "bbb")
        val toRequest = catalog
            .filter { it.id in selected && it.fileHash.lowercase() !in existing }
            .map { it.id }
        val skipped = catalog.count {
            it.id in selected && it.fileHash.lowercase() in existing
        }
        assertEquals(listOf("3"), toRequest)
        assertEquals(2, skipped)
    }
}

class TransferFinishLogicTest {
    @Test
    fun allProcessedWithImportSkips_isSuccess() {
        val phase = TransferFinishLogic.receiverPhase(
            transferred = 1,
            skipped = 2,
            failed = 0,
            processed = 3,
            totalRequested = 3,
        )
        assertTrue(phase is TransferPhase.Success)
        val success = phase as TransferPhase.Success
        assertEquals(1, success.transferred)
        assertEquals(2, success.skipped)
    }

    @Test
    fun disconnectMidQueue_isPartial() {
        val phase = TransferFinishLogic.receiverPhase(
            transferred = 1,
            skipped = 0,
            failed = 0,
            processed = 1,
            totalRequested = 3,
        )
        assertTrue(phase is TransferPhase.PartialSuccess)
        val partial = phase as TransferPhase.PartialSuccess
        assertEquals(1, partial.transferred)
        assertEquals(2, partial.failed)
    }

    @Test
    fun hashMismatchCountedAsFailed_isPartial() {
        val phase = TransferFinishLogic.receiverPhase(
            transferred = 1,
            skipped = 0,
            failed = 1,
            processed = 2,
            totalRequested = 2,
        )
        assertTrue(phase is TransferPhase.PartialSuccess)
        assertEquals(1, (phase as TransferPhase.PartialSuccess).failed)
    }

    @Test
    fun emptyRequest_isSuccess() {
        val phase = TransferFinishLogic.receiverPhase(
            transferred = 0,
            skipped = 4,
            failed = 0,
            processed = 0,
            totalRequested = 0,
        )
        assertTrue(phase is TransferPhase.Success)
    }

    @Test
    fun hashMismatchDetectsCaseInsensitiveInequality() {
        val expected = "AbCdEf"
        val actual = "deadbeef"
        assertTrue(!actual.equals(expected, ignoreCase = true))
    }
}
