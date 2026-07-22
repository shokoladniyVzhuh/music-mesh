package com.mesh.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val fileSize: Long,
    val fileHash: String,
    val localPath: String,
    val source: String,
    val addedAt: Long,
)
