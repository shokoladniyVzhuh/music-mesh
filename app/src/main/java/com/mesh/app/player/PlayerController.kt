package com.mesh.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mesh.app.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class PlayerState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
)

class PlayerController(context: Context) {
    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    init {
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playerState.update { it.copy(isPlaying = isPlaying) }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        playNext()
                    }
                }
            },
        )
    }

    fun play(track: Track, queue: List<Track> = listOf(track)) {
        val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        _playerState.value = PlayerState(
            currentTrack = track,
            queue = queue,
            queueIndex = index,
            isPlaying = true,
        )
        exoPlayer.setMediaItem(MediaItem.fromUri(File(track.localPath).toURI().toString()))
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
        }
    }

    fun playPrevious() {
        val state = _playerState.value
        if (state.queue.isEmpty()) return
        val newIndex = (state.queueIndex - 1).coerceAtLeast(0)
        play(state.queue[newIndex], state.queue)
    }

    fun playNext() {
        val state = _playerState.value
        if (state.queue.isEmpty()) return
        val newIndex = (state.queueIndex + 1).coerceAtMost(state.queue.lastIndex)
        if (newIndex == state.queueIndex) {
            exoPlayer.pause()
            _playerState.update { it.copy(isPlaying = false) }
            return
        }
        play(state.queue[newIndex], state.queue)
    }

    fun release() {
        exoPlayer.release()
    }
}
