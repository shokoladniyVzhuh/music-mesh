package com.mesh.app.library

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class StoragePolicyTest {
    @Test
    fun preflight_accountsForTemporaryCopiesAndReserve() {
        assertEquals(StoragePolicy.RESERVE_BYTES + 900, StoragePolicy.requiredBytes(listOf(100L, 200L), 3))
    }

    @Test
    fun lowDisk_isRejectedBeforeCopy() {
        assertThrows(NotEnoughSpaceException::class.java) {
            StoragePolicy.ensureSpace(StoragePolicy.RESERVE_BYTES + 99, listOf(100L))
        }
        StoragePolicy.ensureSpace(StoragePolicy.RESERVE_BYTES + 100, listOf(100L))
    }

    @Test
    fun hugeDeclaredSize_cannotOverflowSpaceCheck() {
        assertEquals(Long.MAX_VALUE, StoragePolicy.requiredBytes(listOf(Long.MAX_VALUE), 3))
        assertEquals(Long.MAX_VALUE, StoragePolicy.requiredBytes(listOf(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2)))
        assertThrows(NotEnoughSpaceException::class.java) {
            StoragePolicy.ensureSpace(100_000_000, listOf(Long.MAX_VALUE), 3)
        }
    }

    @Test
    fun invalidSizes_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { StoragePolicy.requiredBytes(listOf(-1L)) }
        assertThrows(IllegalArgumentException::class.java) { StoragePolicy.requiredBytes(listOf(1L), 0) }
    }

    @Test
    fun completeFile_isCopiedExactly() {
        val data = ByteArray(20_000) { (it % 127).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(data.size.toLong(), StoragePolicy.copy(ByteArrayInputStream(data), output,
            data.size.toLong(), data.size.toLong()))
        assertArrayEquals(data, output.toByteArray())
    }

    @Test
    fun truncatedFile_isNotAccepted() {
        assertThrows(IOException::class.java) {
            StoragePolicy.copy(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayOutputStream(), 3, 3)
        }
    }

    @Test
    fun oversizedFile_isRejectedBeforeExcessBytesAreWritten() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            StoragePolicy.copy(ByteArrayInputStream(ByteArray(20_000)), output, 10_000, 10_000)
        }
        assertTrue(output.size() <= 10_000)
    }

    @Test
    fun unknownSourceSize_isStillBoundedByAvailableSpace() {
        val output = ByteArrayOutputStream()
        assertThrows(NotEnoughSpaceException::class.java) {
            StoragePolicy.copy(ByteArrayInputStream(ByteArray(20_000)), output, 10_000)
        }
        assertTrue(output.size() <= 10_000)
    }

    @Test
    fun unknownSourceSize_canFitExactly() {
        val output = ByteArrayOutputStream()
        assertEquals(2L, StoragePolicy.copy(ByteArrayInputStream(byteArrayOf(1, 2)), output, 2))
    }
}
