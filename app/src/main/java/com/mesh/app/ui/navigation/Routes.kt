package com.mesh.app.ui.navigation

object Routes {
    const val SPLASH = "splash"
    const val AUTH = "auth"
    const val HOME = "home"
    const val DOWNLOADED = "downloaded"
    const val PLAYLISTS = "playlists"
    const val CREATE_PLAYLIST = "create_playlist"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    const val PICK_TRACKS = "pick_tracks/{mode}/{playlistId}"
    const val SEND = "send"
    const val RECEIVE = "receive"
    const val SENDER_WAIT = "sender_wait"
    const val RECEIVER_PICK = "receiver_pick"
    const val TRANSFER_PROGRESS = "transfer_progress"

    fun playlistDetail(playlistId: String) = "playlist/$playlistId"

    fun pickTracks(mode: PickTracksMode, playlistId: String = "none") =
        "pick_tracks/${mode.name}/$playlistId"
}

enum class PickTracksMode {
    CREATE,
    ADD_TO_PLAYLIST,
}
