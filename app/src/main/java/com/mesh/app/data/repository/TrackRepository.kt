package com.mesh.app.data.repository

import com.mesh.app.data.local.dao.TrackDao
import com.mesh.app.data.model.Track
import com.mesh.app.data.model.toModel
import com.mesh.app.library.ImportItemResult
import com.mesh.app.library.TrackImporter
import com.mesh.app.transfer.RemoteTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

class TrackRepository(
    private val trackDao: TrackDao,
    private val trackImporter: TrackImporter,
) {
    fun observeTracks(): Flow<List<Track>> =
        trackDao.observeAll().map { tracks -> tracks.map { it.toModel() } }

    suspend fun getAllTracks(): List<Track> =
        trackDao.getAll().map { it.toModel() }

    suspend fun getById(trackId: String): Track? =
        trackDao.getById(trackId)?.toModel()

    suspend fun hasHash(hash: String): Boolean =
        trackDao.getByHash(hash) != null

    suspend fun existingHashes(): Set<String> =
        trackDao.getAll().map { it.fileHash.lowercase() }.toSet()

    suspend fun importTracks(uris: List<android.net.Uri>) =
        trackImporter.importUris(uris)

    suspend fun importP2pTrack(sourceFile: File, remote: RemoteTrack): ImportItemResult =
        trackImporter.importReceivedFile(sourceFile, remote)

    suspend fun deleteTrack(trackId: String) {
        val track = trackDao.getById(trackId) ?: return
        File(track.localPath).delete()
        trackDao.deleteById(trackId)
    }
}
