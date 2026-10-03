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
    data class DownloadRequest(val trackIds: List<String>, val skipped: Int = 0) : ControlMessage()
    data class FileOffer(
        val track: RemoteTrack,
        val payloadId: Long,
    ) : ControlMessage()
    data class TransferFinished(val failedTrackIds: List<String> = emptyList()) : ControlMessage()
    data class TransferResult(
        val transferred: Int,
        val skipped: Int,
        val failed: Int,
        val message: String? = null,
    ) : ControlMessage()
    data class Disconnect(val reason: String) : ControlMessage()
}

object TransferProtocol {
    const val VERSION = 2
    private const val KEY_TYPE = "type"
    private const val TYPE_TRACK_LIST = "TRACK_LIST"
    private const val TYPE_DOWNLOAD_REQUEST = "DOWNLOAD_REQUEST"
    private const val TYPE_FILE_OFFER = "FILE_OFFER"
    private const val TYPE_TRANSFER_FINISHED = "TRANSFER_FINISHED"
    private const val TYPE_TRANSFER_RESULT = "TRANSFER_RESULT"
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
                .put("skipped", message.skipped)
                .put("trackIds", JSONArray().apply {
                    message.trackIds.forEach { put(it) }
                })
            is ControlMessage.FileOffer -> JSONObject()
                .put(KEY_TYPE, TYPE_FILE_OFFER)
                .put("payloadId", message.payloadId)
                .put("track", trackToJson(message.track))
            is ControlMessage.TransferFinished -> JSONObject()
                .put(KEY_TYPE, TYPE_TRANSFER_FINISHED)
                .put("failedTrackIds", JSONArray(message.failedTrackIds))
            is ControlMessage.TransferResult -> JSONObject()
                .put(KEY_TYPE, TYPE_TRANSFER_RESULT)
                .put("transferred", message.transferred)
                .put("skipped", message.skipped)
                .put("failed", message.failed)
                .put("message", message.message ?: JSONObject.NULL)
            is ControlMessage.Disconnect -> JSONObject()
                .put(KEY_TYPE, TYPE_DISCONNECT)
                .put("reason", message.reason)
        }
        return json.put("version", VERSION).toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): ControlMessage {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.optInt("version", 1) == VERSION) {
            "Incompatible app version. Update Music Mesh on both devices."
        }
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
                val skipped = json.optInt("skipped", 0)
                require(skipped >= 0) { "Invalid duplicate count" }
                require(ids.size == ids.toSet().size) { "Repeated download request IDs" }
                ControlMessage.DownloadRequest(ids, skipped)
            }
            TYPE_FILE_OFFER -> ControlMessage.FileOffer(
                track = trackFromJson(json.getJSONObject("track")),
                payloadId = json.getLong("payloadId"),
            )
            TYPE_TRANSFER_FINISHED -> {
                val array = json.getJSONArray("failedTrackIds")
                ControlMessage.TransferFinished(List(array.length()) { array.getString(it) })
            }
            TYPE_TRANSFER_RESULT -> {
                val result = ControlMessage.TransferResult(
                    transferred = json.getInt("transferred"),
                    skipped = json.getInt("skipped"),
                    failed = json.getInt("failed"),
                    message = if (json.isNull("message")) null else json.getString("message"),
                )
                require(result.transferred >= 0 && result.skipped >= 0 && result.failed >= 0) {
                    "Invalid transfer counts"
                }
                result
            }
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
