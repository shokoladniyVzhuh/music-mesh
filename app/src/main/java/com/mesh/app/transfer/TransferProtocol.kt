package com.mesh.app.transfer

import org.json.JSONArray
import org.json.JSONObject

data class RemoteTrack(
    val id: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val fileSize: Long,
    val fileHash: String,
)

sealed class ControlMessage {
    data class TrackList(val tracks: List<RemoteTrack>) : ControlMessage()
    data class DownloadRequest(val trackIds: List<String>) : ControlMessage()
    data class FileOffer(
        val track: RemoteTrack,
        val payloadId: Long,
    ) : ControlMessage()
    data object TransferFinished : ControlMessage()
    data class Disconnect(val reason: String) : ControlMessage()
}

object TransferProtocol {
    private const val KEY_TYPE = "type"
    private const val TYPE_TRACK_LIST = "TRACK_LIST"
    private const val TYPE_DOWNLOAD_REQUEST = "DOWNLOAD_REQUEST"
    private const val TYPE_FILE_OFFER = "FILE_OFFER"
    private const val TYPE_TRANSFER_FINISHED = "TRANSFER_FINISHED"
    private const val TYPE_DISCONNECT = "DISCONNECT"

    fun encode(message: ControlMessage): ByteArray {
        val json = when (message) {
            is ControlMessage.TrackList -> JSONObject()
                .put(KEY_TYPE, TYPE_TRACK_LIST)
                .put("tracks", JSONArray().apply {
                    message.tracks.forEach { put(trackToJson(it)) }
                })
            is ControlMessage.DownloadRequest -> JSONObject()
                .put(KEY_TYPE, TYPE_DOWNLOAD_REQUEST)
                .put("trackIds", JSONArray().apply {
                    message.trackIds.forEach { put(it) }
                })
            is ControlMessage.FileOffer -> JSONObject()
                .put(KEY_TYPE, TYPE_FILE_OFFER)
                .put("payloadId", message.payloadId)
                .put("track", trackToJson(message.track))
            is ControlMessage.TransferFinished -> JSONObject()
                .put(KEY_TYPE, TYPE_TRANSFER_FINISHED)
            is ControlMessage.Disconnect -> JSONObject()
                .put(KEY_TYPE, TYPE_DISCONNECT)
                .put("reason", message.reason)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): ControlMessage {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        return when (val type = json.getString(KEY_TYPE)) {
            TYPE_TRACK_LIST -> {
                val tracks = mutableListOf<RemoteTrack>()
                val array = json.getJSONArray("tracks")
                for (i in 0 until array.length()) {
                    tracks += trackFromJson(array.getJSONObject(i))
                }
                ControlMessage.TrackList(tracks)
            }
            TYPE_DOWNLOAD_REQUEST -> {
                val ids = mutableListOf<String>()
                val array = json.getJSONArray("trackIds")
                for (i in 0 until array.length()) {
                    ids += array.getString(i)
                }
                ControlMessage.DownloadRequest(ids)
            }
            TYPE_FILE_OFFER -> ControlMessage.FileOffer(
                track = trackFromJson(json.getJSONObject("track")),
                payloadId = json.getLong("payloadId"),
            )
            TYPE_TRANSFER_FINISHED -> ControlMessage.TransferFinished
            TYPE_DISCONNECT -> ControlMessage.Disconnect(
                reason = json.optString("reason", "Disconnected"),
            )
            else -> error("Unknown control message type: $type")
        }
    }

    private fun trackToJson(track: RemoteTrack): JSONObject =
        JSONObject()
            .put("id", track.id)
            .put("title", track.title)
            .put("artist", track.artist ?: JSONObject.NULL)
            .put("durationMs", track.durationMs)
            .put("fileSize", track.fileSize)
            .put("fileHash", track.fileHash)

    private fun trackFromJson(json: JSONObject): RemoteTrack =
        RemoteTrack(
            id = json.getString("id"),
            title = json.getString("title"),
            artist = if (json.isNull("artist")) {
                null
            } else {
                json.getString("artist").takeIf { it.isNotBlank() }
            },
            durationMs = json.getLong("durationMs"),
            fileSize = json.getLong("fileSize"),
            fileHash = json.getString("fileHash"),
        )
}
