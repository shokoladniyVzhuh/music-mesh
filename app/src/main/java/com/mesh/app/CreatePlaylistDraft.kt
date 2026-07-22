package com.mesh.app

class CreatePlaylistDraft {
    var name: String = ""
    val selectedTrackIds: MutableSet<String> = mutableSetOf()

    fun clear() {
        name = ""
        selectedTrackIds.clear()
    }
}
