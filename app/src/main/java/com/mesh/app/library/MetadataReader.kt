package com.mesh.app.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.mesh.app.data.model.TrackSource
import java.io.File

data class TrackMetadata(
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val fileSize: Long,
)

class MetadataReader(private val context: Context) {
    fun read(uri: Uri, fallbackName: String): TrackMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: fallbackName.substringBeforeLast('.')
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val size = context.contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            } ?: 0L
            TrackMetadata(
                title = title,
                artist = artist,
                durationMs = duration,
                fileSize = size,
            )
        } finally {
            retriever.release()
        }
    }

    fun readFromFile(file: File, fallbackName: String): TrackMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: fallbackName.substringBeforeLast('.')
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            TrackMetadata(
                title = title,
                artist = artist,
                durationMs = duration,
                fileSize = file.length(),
            )
        } finally {
            retriever.release()
        }
    }
}
