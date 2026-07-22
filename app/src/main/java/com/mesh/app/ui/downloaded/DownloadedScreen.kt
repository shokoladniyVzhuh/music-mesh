package com.mesh.app.ui.downloaded

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mesh.app.MeshApplication
import com.mesh.app.ui.addtoplaylist.AddToPlaylistSheet
import com.mesh.app.ui.components.MiniPlayer
import com.mesh.app.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedScreen(
    onBack: () -> Unit,
    viewModel: DownloadedViewModel = viewModel(factory = (LocalContext.current.applicationContext as MeshApplication).viewModelFactory),
) {
    val app = LocalContext.current.applicationContext as MeshApplication
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by app.playerController.playerState.collectAsStateWithLifecycle()
    val sheetTrackId = uiState.playlistSheetTrackId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloaded") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            if (uiState.tracks.isEmpty()) {
                Text(
                    text = "No tracks yet. Use Add on the home screen.",
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn {
                    items(uiState.tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            onClick = { viewModel.playTrack(track) },
                            trailing = {
                                IconButton(onClick = { viewModel.openPlaylistSheet(track.id) }) {
                                    Icon(Icons.Default.PlaylistAdd, contentDescription = "Add to playlist")
                                }
                                IconButton(onClick = { viewModel.deleteTrack(track.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (sheetTrackId != null) {
        val currentIds by viewModel.observePlaylistIdsForTrack(sheetTrackId)
            .collectAsStateWithLifecycle(initialValue = emptySet())
        AddToPlaylistSheet(
            playlists = uiState.allPlaylists,
            selectedPlaylistIds = currentIds,
            onDismiss = viewModel::closePlaylistSheet,
            onSave = { selected ->
                viewModel.savePlaylistSelections(sheetTrackId, selected, currentIds)
            },
        )
    }
}
