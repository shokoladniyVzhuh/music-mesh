package com.mesh.app.ui.picktracks

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mesh.app.MeshApplication
import com.mesh.app.ui.components.TrackRow
import com.mesh.app.ui.navigation.PickTracksMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickTracksScreen(
    mode: PickTracksMode,
    playlistId: String?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: PickTracksViewModel = viewModel(factory = (LocalContext.current.applicationContext as MeshApplication).viewModelFactory),
) {
    LaunchedEffect(mode, playlistId) { viewModel.init(mode, playlistId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pick tracks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.confirm(onDone) },
                        enabled = uiState.selectedIds.isNotEmpty(),
                    ) {
                        Text("done")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items(uiState.tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    selected = track.id in uiState.selectedIds,
                    onClick = { viewModel.toggleTrack(track.id) },
                    trailing = {
                        Checkbox(
                            checked = track.id in uiState.selectedIds,
                            onCheckedChange = { viewModel.toggleTrack(track.id) },
                        )
                    },
                )
            }
        }
    }
}
