package com.mesh.app.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object FileHasher {
    fun sha256(file: File): String = sha256(file.inputStream())

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
