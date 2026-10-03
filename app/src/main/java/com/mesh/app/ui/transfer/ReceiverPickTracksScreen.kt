package com.mesh.app.ui.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mesh.app.MeshApplication
import com.mesh.app.transfer.RemoteTrack
import com.mesh.app.transfer.TransferPhase
import com.mesh.app.util.formatDuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverPickTracksScreen(
    onTransferring: () -> Unit,
    onFinished: () -> Unit,
    viewModel: TransferViewModel = viewModel(
        factory = (LocalContext.current.applicationContext as MeshApplication).viewModelFactory,
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.phase) {
        when (uiState.phase) {
            is TransferPhase.Transferring -> onTransferring()
            is TransferPhase.Success,
            is TransferPhase.PartialSuccess,
            is TransferPhase.Failed,
            -> onFinished()
            // Idle is the stateIn placeholder / post-reset — do not treat as cancel.
            else -> Unit
        }
    }

    BackHandler {
        viewModel.closeConnection()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pick tracks") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.closeConnection()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = viewModel::download,
                        enabled = uiState.canDownload,
                    ) {
                        Text("Download")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (uiState.phase) {
                is TransferPhase.WaitingForCatalog,
                is TransferPhase.Connected,
                -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text("Loading catalog…")
                    }
                }
                is TransferPhase.ChoosingTracks -> {
                    if (uiState.catalog.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Sender has no tracks to share.")
                            TextButton(
                                onClick = {
                                    viewModel.closeConnection()
                                },
                            ) {
                                Text("Close connection")
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(uiState.catalog, key = { it.id }) { track ->
                                RemoteTrackRow(
                                    track = track,
                                    selected = track.id in uiState.selectedIds,
                                    onClick = { viewModel.toggleTrack(track.id) },
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                viewModel.closeConnection()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Text("Close connection")
                        }
                    }
                }
                else -> {
                    // Navigating away via LaunchedEffect
                }
            }
        }
    }
}

@Composable
private fun RemoteTrackRow(
    track: RemoteTrack,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                track.artist?.let { append(it).append(" · ") }
                append(track.durationMs.formatDuration())
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(
            checked = selected,
            onCheckedChange = { onClick() },
        )
    }
}
