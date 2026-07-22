package com.mesh.app.data.repository

import com.mesh.app.data.local.dao.PlaylistDao
import com.mesh.app.data.local.entity.PlaylistEntity
import com.mesh.app.data.local.entity.PlaylistTrackCrossRef
import com.mesh.app.data.model.Playlist
import com.mesh.app.data.model.PlaylistDetail
import com.mesh.app.data.model.toDetail
import com.mesh.app.data.model.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class PlaylistRepository(
    private val playlistDao: PlaylistDao,
) {
    fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observePlaylistDetail(playlistId: String): Flow<PlaylistDetail?> =
        playlistDao.observeWithTracks(playlistId).map { it?.toDetail() }

    fun observePlaylistIdsForTrack(trackId: String): Flow<Set<String>> =
        playlistDao.observePlaylistIdsForTrack(trackId).map { it.toSet() }

    suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): String {
        val playlistId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        playlistDao.insert(
            PlaylistEntity(
                id = playlistId,
                name = name.trim(),
                isSystem = false,
                createdAt = now,
            ),
        )
        if (trackIds.isNotEmpty()) {
            addTracksToPlaylist(playlistId, trackIds)
        }
        return playlistId
    }

    suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deleteUserPlaylist(playlistId)
    }

    suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        var position = playlistDao.maxPosition(playlistId) + 1
        val refs = trackIds.map { trackId ->
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = position++,
            )
        }
        playlistDao.insertCrossRefs(refs)
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    suspend fun updateTrackPlaylists(
        trackId: String,
        currentPlaylistIds: Set<String>,
        selectedPlaylistIds: Set<String>,
    ) {
        val toAdd = selectedPlaylistIds - currentPlaylistIds
        val toRemove = currentPlaylistIds - selectedPlaylistIds
        toAdd.forEach { playlistId ->
            val position = playlistDao.maxPosition(playlistId) + 1
            playlistDao.insertCrossRefs(
                listOf(
                    PlaylistTrackCrossRef(
                        playlistId = playlistId,
                        trackId = trackId,
                        position = position,
                    ),
                ),
            )
        }
        toRemove.forEach { playlistId ->
            playlistDao.removeTrackFromPlaylist(playlistId, trackId)
        }
    }
}
