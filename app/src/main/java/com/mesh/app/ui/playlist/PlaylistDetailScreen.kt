package com.mesh.app.ui.playlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mesh.app.MeshApplication
import com.mesh.app.ui.components.MiniPlayer
import com.mesh.app.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit,
    onAddTracks: (String) -> Unit,
    onDeleted: () -> Unit,
    viewModel: PlaylistDetailViewModel = viewModel(factory = (LocalContext.current.applicationContext as MeshApplication).viewModelFactory),
) {
    val app = LocalContext.current.applicationContext as MeshApplication
    LaunchedEffect(playlistId) { viewModel.init(playlistId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by app.playerController.playerState.collectAsStateWithLifecycle()
    val detail = uiState.detail

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.playlist?.name ?: "Playlist") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (detail?.playlist?.isSystem == false) {
                        IconButton(onClick = { viewModel.deletePlaylist(onDeleted) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete playlist")
                        }
                    }
                    IconButton(onClick = { onAddTracks(playlistId) }) {
                        Icon(Icons.Default.Add, contentDescription = "Add tracks")
                    }
                },
            )
        },
        bottomBar = {
            MiniPlayer(
                playerState = playerState,
                onPlayPause = app.playerController::togglePlayPause,
                onPrevious = app.playerController::playPrevious,
                onNext = app.playerController::playNext,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val tracks = detail?.tracks.orEmpty()
            if (tracks.isEmpty()) {
                Text("No tracks in this playlist.", modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn {
                    items(tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            onClick = { viewModel.playTrack(track) },
                            trailing = {
                                IconButton(onClick = { viewModel.removeTrackFromPlaylist(track.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove from playlist")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
