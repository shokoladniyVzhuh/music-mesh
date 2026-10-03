package com.mesh.app.library

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class NotEnoughSpaceException : IOException("Not enough storage space. Free up space and try again.")

object StoragePolicy {
    const val RESERVE_BYTES = 16L * 1024 * 1024

    fun requiredBytes(sizes: Collection<Long>, copies: Int = 1): Long {
        require(copies > 0)
        var required = RESERVE_BYTES
        for (size in sizes) {
            require(size >= 0) { "Invalid file size" }
            if (size > (Long.MAX_VALUE - required) / copies) return Long.MAX_VALUE
            required += size * copies
        }
        return required
    }

    fun ensureSpace(available: Long, sizes: Collection<Long>, copies: Int = 1) {
        if (available < requiredBytes(sizes, copies)) throw NotEnoughSpaceException()
    }

    /** Limits each copy to the space budget and, for P2P, the advertised file size. */
    fun copy(input: InputStream, output: OutputStream, limit: Long, expectedSize: Long? = null): Long {
        var copied = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (expectedSize != null && read.toLong() > expectedSize - copied) {
                throw IOException("Received file size does not match the catalog")
            }
            if (read.toLong() > limit - copied) throw NotEnoughSpaceException()
            output.write(buffer, 0, read)
            copied += read
        }
        if (expectedSize != null && copied != expectedSize) {
            throw IOException("Received file size does not match the catalog")
        }
        return copied
    }
}
