package com.mesh.app.transfer

import org.junit.Assert.*
import org.junit.Test

class IncomingTransferLedgerTest {
    @Test
    fun missingSenderFile_finishesWithoutAFileCallback() {
        val ledger = IncomingTransferLedger(listOf("sent", "missing"))
        assertTrue(ledger.offer(1, "sent"))
        assertTrue(ledger.recordProcessed("sent"))
        assertFalse(ledger.isComplete)
        assertEquals(1, ledger.recordSenderFailures(listOf("missing")))
        assertTrue(ledger.isComplete)
        assertEquals(2, ledger.processedCount)
    }

    @Test
    fun repeatedFailure_isCountedOnce() {
        val ledger = IncomingTransferLedger(listOf("a"))
        assertEquals(1, ledger.recordSenderFailures(listOf("a", "a")))
        assertEquals(0, ledger.recordSenderFailures(listOf("a")))
        assertFalse(ledger.recordProcessed("a"))
        assertEquals(1, ledger.processedCount)
    }

    @Test
    fun failureAfterImportStarted_doesNotCountTwice() {
        val ledger = IncomingTransferLedger(listOf("a"))
        assertTrue(ledger.offer(1, "a"))
        assertTrue(ledger.recordProcessed("a"))
        assertEquals(0, ledger.recordSenderFailures(listOf("a")))
        assertFalse(ledger.recordProcessed("a"))
    }

    @Test
    fun payloadAndTrackOffers_areUnique() {
        val ledger = IncomingTransferLedger(listOf("a", "b"))
        assertTrue(ledger.offer(1, "a"))
        assertFalse(ledger.offer(1, "a"))
        assertFalse(ledger.offer(1, "b"))
        assertFalse(ledger.offer(2, "a"))
        assertTrue(ledger.offer(2, "b"))
    }

    @Test
    fun unrequestedFiles_areIgnored() {
        val ledger = IncomingTransferLedger(listOf("a"))
        assertFalse(ledger.offer(1, "foreign"))
        assertFalse(ledger.recordProcessed("foreign"))
        assertEquals(0, ledger.processedCount)
    }

    @Test
    fun failureBeforeOffer_preventsLateImport() {
        val ledger = IncomingTransferLedger(listOf("a"))
        ledger.recordSenderFailures(listOf("a"))
        assertFalse(ledger.offer(1, "a"))
        assertTrue(ledger.isComplete)
    }

    @Test
    fun invalidFailureList_isRejectedBeforeAnyMutation() {
        val ledger = IncomingTransferLedger(listOf("a"))
        assertThrows(IllegalArgumentException::class.java) {
            ledger.recordSenderFailures(listOf("a", "foreign"))
        }
        assertEquals(0, ledger.processedCount)
    }

    @Test
    fun duplicateOnlyBatch_isAlreadyComplete() {
        assertTrue(IncomingTransferLedger(emptyList()).isComplete)
    }
}
