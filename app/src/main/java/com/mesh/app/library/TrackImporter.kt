package com.mesh.app.library

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.mesh.app.data.local.dao.TrackDao
import com.mesh.app.data.model.Track
import com.mesh.app.data.model.TrackSource
import com.mesh.app.data.model.toEntity
import com.mesh.app.transfer.RemoteTrack
import com.mesh.app.util.FileHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ImportFailure(
    val displayName: String,
    val reason: String,
)

sealed class ImportItemResult {
    data class Success(val track: Track) : ImportItemResult()
    data class SkippedDuplicate(val displayName: String) : ImportItemResult()
    data class Failed(val failure: ImportFailure) : ImportItemResult()
}

data class ImportBatchResult(
    val imported: List<Track>,
    val skippedDuplicates: List<String>,
    val failures: List<ImportFailure>,
)

class TrackImporter(
    private val context: Context,
    private val trackDao: TrackDao,
    private val metadataReader: MetadataReader,
) {
    private val importMutex = Mutex()

    suspend fun importUris(uris: List<Uri>): ImportBatchResult = withContext(Dispatchers.IO) {
        val imported = mutableListOf<Track>()
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<ImportFailure>()

        val tracksDir = File(context.filesDir, "tracks").apply { mkdirs() }

        for (uri in uris) {
            var displayName = uri.lastPathSegment ?: "track.mp3"
            val result = try {
                displayName = resolveDisplayName(uri)
                importMutex.withLock { importSingle(uri, displayName, tracksDir) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportItemResult.Failed(ImportFailure(displayName, storageError(e)))
            }
            when (result) {
                is ImportItemResult.Success -> imported += result.track
                is ImportItemResult.SkippedDuplicate -> skipped += result.displayName
                is ImportItemResult.Failed -> failures += result.failure
            }
        }

        ImportBatchResult(
            imported = imported,
            skippedDuplicates = skipped,
            failures = failures,
        )
    }

    /**
     * Import a file received over Nearby. Copies into sandbox, verifies SHA-256,
     * skips duplicates, and stores with [TrackSource.P2P].
     */
    suspend fun importReceivedFile(
        sourceFile: File,
        remote: RemoteTrack,
    ): ImportItemResult = withContext(Dispatchers.IO) {
        importMutex.withLock { importReceivedFileLocked(sourceFile, remote) }
    }

    private suspend fun importReceivedFileLocked(sourceFile: File, remote: RemoteTrack): ImportItemResult {
        val displayName = remote.title
        if (!sourceFile.exists()) {
            return ImportItemResult.Failed(
                ImportFailure(displayName, "Received file missing"),
            )
        }

        val tracksDir = File(context.filesDir, "tracks").apply { mkdirs() }
        val trackId = UUID.randomUUID().toString()
        val destFile = File(tracksDir, "$trackId.mp3")

        try {
            require(remote.fileSize > 0 && sourceFile.length() == remote.fileSize) {
                "Received file size does not match the catalog"
            }
            StoragePolicy.ensureSpace(tracksDir.usableSpace, listOf(remote.fileSize))
            sourceFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    StoragePolicy.copy(input, output, remote.fileSize, remote.fileSize)
                }
            }

            val hash = FileHasher.sha256(destFile)
            if (!hash.equals(remote.fileHash, ignoreCase = true)) {
                destFile.delete()
                return ImportItemResult.Failed(
                    ImportFailure(displayName, "Hash mismatch"),
                )
            }

            if (trackDao.getByHash(hash) != null) {
                destFile.delete()
                return ImportItemResult.SkippedDuplicate(displayName)
            }

            val track = Track(
                id = trackId,
                title = remote.title.ifBlank { displayName },
                artist = remote.artist,
                durationMs = remote.durationMs,
                fileSize = destFile.length(),
                fileHash = hash,
                localPath = destFile.absolutePath,
                source = TrackSource.P2P,
                addedAt = System.currentTimeMillis(),
            )
            trackDao.insert(track.toEntity())
            return ImportItemResult.Success(track)
        } catch (e: CancellationException) {
            destFile.delete()
            throw e
        } catch (e: Exception) {
            destFile.delete()
            return ImportItemResult.Failed(
                ImportFailure(displayName, storageError(e)),
            )
        }
    }

    private suspend fun importSingle(
        uri: Uri,
        displayName: String,
        tracksDir: File,
    ): ImportItemResult {
        if (!isMp3(uri, displayName)) {
            return ImportItemResult.Failed(
                ImportFailure(displayName, "Only MP3 files are supported"),
            )
        }

        val trackId = UUID.randomUUID().toString()
        val destFile = File(tracksDir, "$trackId.mp3")

        return try {
            val available = tracksDir.usableSpace
            StoragePolicy.ensureSpace(available, listOf(resolveSize(uri)))
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    StoragePolicy.copy(input, output, (available - StoragePolicy.RESERVE_BYTES).coerceAtLeast(0))
                }
            } ?: return ImportItemResult.Failed(
                ImportFailure(displayName, "Could not read file"),
            )

            val hash = FileHasher.sha256(destFile)
            if (trackDao.getByHash(hash) != null) {
                destFile.delete()
                return ImportItemResult.SkippedDuplicate(displayName)
            }

            val metadata = try {
                metadataReader.readFromFile(destFile, displayName)
            } catch (e: Exception) {
                destFile.delete()
                return ImportItemResult.Failed(
                    ImportFailure(displayName, "Could not read audio metadata"),
                )
            }

            if (metadata.durationMs <= 0L) {
                destFile.delete()
                return ImportItemResult.Failed(
                    ImportFailure(displayName, "Invalid or unreadable audio file"),
                )
            }

            val track = Track(
                id = trackId,
                title = metadata.title,
                artist = metadata.artist,
                durationMs = metadata.durationMs,
                fileSize = metadata.fileSize,
                fileHash = hash,
                localPath = destFile.absolutePath,
                source = TrackSource.IMPORT,
                addedAt = System.currentTimeMillis(),
            )
            trackDao.insert(track.toEntity())
            ImportItemResult.Success(track)
        } catch (e: CancellationException) {
            destFile.delete()
            throw e
        } catch (e: Exception) {
            destFile.delete()
            ImportItemResult.Failed(
                ImportFailure(displayName, storageError(e)),
            )
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                return cursor.getString(nameIndex) ?: uri.lastPathSegment ?: "track.mp3"
            }
        }
        return uri.lastPathSegment ?: "track.mp3"
    }

    private fun resolveSize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0).coerceAtLeast(0)
            }
        return 0L
    }

    private fun storageError(error: Exception): String {
        if (generateSequence<Throwable>(error) { it.cause }.any {
                it is NotEnoughSpaceException ||
                    it.message?.contains("ENOSPC", ignoreCase = true) == true
            }
        ) return "Not enough storage space. Free up space and try again."
        return error.message ?: "Could not save track"
    }

    private fun isMp3(uri: Uri, displayName: String): Boolean {
        val mime = context.contentResolver.getType(uri)
        if (mime == "audio/mpeg" || mime == "audio/mp3") return true
        val ext = MimeTypeMap.getFileExtensionFromUrl(displayName)
            ?: displayName.substringAfterLast('.', "")
        return ext.equals("mp3", ignoreCase = true)
    }
}
