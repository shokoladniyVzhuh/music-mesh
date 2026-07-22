package com.mesh.app.data.model

import com.mesh.app.data.local.entity.PlaylistEntity
import com.mesh.app.data.local.entity.PlaylistWithTracks

data class Playlist(
    val id: String,
    val name: String,
    val isSystem: Boolean,
    val createdAt: Long,
)

data class PlaylistDetail(
    val playlist: Playlist,
    val tracks: List<Track>,
)

fun PlaylistEntity.toModel(): Playlist =
    Playlist(
        id = id,
        name = name,
        isSystem = isSystem,
        createdAt = createdAt,
    )

fun PlaylistWithTracks.toDetail(): PlaylistDetail =
    PlaylistDetail(
        playlist = playlist.toModel(),
        tracks = tracks.map { it.toModel() },
    )
