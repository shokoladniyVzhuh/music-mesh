package com.mesh.app.ui.send

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mesh.app.MeshApplication
import com.mesh.app.transfer.TransferPhase
import com.mesh.app.util.PermissionsHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    onBack: () -> Unit,
    onConnected: () -> Unit,
    viewModel: SendViewModel = viewModel(
        factory = (LocalContext.current.applicationContext as MeshApplication).viewModelFactory,
    ),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        viewModel.onPermissionsResult(results.values.all { it })
    }

    LaunchedEffect(Unit) {
        if (PermissionsHelper.hasNearbyPermissions(context)) {
            viewModel.onPermissionsResult(true)
        } else {
            permissionLauncher.launch(PermissionsHelper.nearbyPermissions())
        }
    }

    LaunchedEffect(uiState.readyForTransfer) {
        if (uiState.readyForTransfer) {
            onConnected()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onLeave() }
    }

    BackHandler {
        viewModel.disconnect()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.disconnect()
                            onBack()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !uiState.permissionsGranted -> {
                    Text("Nearby permissions are required to find receivers.")
                    Button(onClick = {
                        permissionLauncher.launch(PermissionsHelper.nearbyPermissions())
                    }) {
                        Text("Grant permissions")
                    }
                }
                uiState.readyForTransfer -> {
                    Text("Connected. Opening transfer…")
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                uiState.isConnecting -> {
                    Text("Waiting for receiver to accept…")
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    TextButton(onClick = {
                        viewModel.disconnect()
                        onBack()
                    }) {
                        Text("Cancel")
                    }
                }
                uiState.phase is TransferPhase.Failed -> {
                    Text(
                        text = uiState.errorMessage ?: "Something went wrong",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = viewModel::retryAfterError) {
                        Text("Try again")
                    }
                }
                else -> {
                    Text("Pick a receiver who is on the Receive screen.")
                    if (uiState.peers.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("Searching nearby…")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(uiState.peers, key = { it.endpointId }) { peer ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.selectPeer(peer.endpointId) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = uiState.selectedEndpointId == peer.endpointId,
                                        onClick = { viewModel.selectPeer(peer.endpointId) },
                                    )
                                    Text(
                                        text = peer.nickname,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = viewModel::goOn,
                        enabled = uiState.canGoOn,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Go on")
                    }
                }
            }
        }
    }
}
