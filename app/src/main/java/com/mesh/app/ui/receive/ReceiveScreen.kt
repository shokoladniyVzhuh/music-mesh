package com.mesh.app.ui.receive

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
fun ReceiveScreen(
    onBack: () -> Unit,
    onConnected: () -> Unit,
    viewModel: ReceiveViewModel = viewModel(
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
                title = { Text("Receive") },
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
                    Text("Nearby permissions are required to be visible to senders.")
                    Button(onClick = {
                        permissionLauncher.launch(PermissionsHelper.nearbyPermissions())
                    }) {
                        Text("Grant permissions")
                    }
                }
                uiState.readyForTransfer -> {
                    Text("Connected. Opening catalog…")
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
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
                    Text(
                        text = "Ready to receive",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text("Stay on this screen to be visible to senders nearby.")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            if (uiState.isAdvertising || uiState.isAwaitingAccept) {
                                "Waiting for a connection…"
                            } else {
                                "Starting…"
                            },
                        )
                    }
                }
            }
        }
    }

    val incoming = uiState.incoming
    if (incoming != null && !uiState.readyForTransfer) {
        AlertDialog(
            onDismissRequest = viewModel::rejectIncoming,
            title = { Text("Connection request") },
            text = { Text("${incoming.nickname} wants to connect.") },
            confirmButton = {
                Button(onClick = viewModel::acceptIncoming) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::rejectIncoming) {
                    Text("Reject")
                }
            },
        )
    }
}
