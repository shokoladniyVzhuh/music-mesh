package com.mesh.app.data.model

import com.mesh.app.data.local.entity.TrackEntity

data class Track(
    val id: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val fileSize: Long,
    val fileHash: String,
    val localPath: String,
    val source: TrackSource,
    val addedAt: Long,
)

enum class TrackSource(val raw: String) {
    IMPORT("import"),
    P2P("p2p"),
    ;

    companion object {
        fun fromRaw(raw: String): TrackSource =
            entries.firstOrNull { it.raw == raw } ?: IMPORT
    }
}

fun TrackEntity.toModel(): Track =
    Track(
        id = id,
        title = title,
        artist = artist,
        durationMs = durationMs,
        fileSize = fileSize,
        fileHash = fileHash,
        localPath = localPath,
        source = TrackSource.fromRaw(source),
        addedAt = addedAt,
    )

fun Track.toEntity(): TrackEntity =
    TrackEntity(
        id = id,
        title = title,
        artist = artist,
        durationMs = durationMs,
        fileSize = fileSize,
        fileHash = fileHash,
        localPath = localPath,
        source = source.raw,
        addedAt = addedAt,
    )
