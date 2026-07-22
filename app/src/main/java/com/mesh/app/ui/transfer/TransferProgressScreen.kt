package com.mesh.app.ui.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mesh.app.MeshApplication
import com.mesh.app.transfer.TransferPhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferProgressScreen(
    onContinueListening: () -> Unit,
    viewModel: TransferViewModel = viewModel(
        factory = (LocalContext.current.applicationContext as MeshApplication).viewModelFactory,
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val phase = uiState.phase

    BackHandler {
        when (phase) {
            is TransferPhase.Success,
            is TransferPhase.PartialSuccess,
            is TransferPhase.Failed,
            is TransferPhase.Idle,
            -> {
                viewModel.continueListening()
                onContinueListening()
            }
            else -> {
                viewModel.closeConnection()
                onContinueListening()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.progressTitle) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when (phase) {
                                is TransferPhase.Success,
                                is TransferPhase.PartialSuccess,
                                is TransferPhase.Failed,
                                -> {
                                    viewModel.continueListening()
                                    onContinueListening()
                                }
                                else -> {
                                    viewModel.closeConnection()
                                    onContinueListening()
                                }
                            }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (phase) {
                is TransferPhase.Transferring -> {
                    Text(
                        text = "${phase.completed}/${phase.total} tracks",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    phase.currentTrackTitle?.let {
                        Text("Current: $it")
                    }
                    if (uiState.skippedCount > 0) {
                        Text("${uiState.skippedCount} skipped (already in library)")
                    }
                    LinearProgressIndicator(
                        progress = { phase.currentFilePercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${phase.currentFilePercent}% of current file")
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    TextButton(
                        onClick = {
                            viewModel.closeConnection()
                            onContinueListening()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Close connection")
                    }
                }
                is TransferPhase.Success -> {
                    Text(
                        text = "Success! Enjoy your music",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text("${phase.transferred} tracks transferred")
                    if (phase.skipped > 0) {
                        Text("${phase.skipped} tracks skipped (already in library)")
                    }
                    Button(
                        onClick = {
                            viewModel.continueListening()
                            onContinueListening()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Continue listening")
                    }
                }
                is TransferPhase.PartialSuccess -> {
                    Text(
                        text = "Connection closed",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(phase.message)
                    Text("${phase.transferred} tracks downloaded")
                    if (phase.skipped > 0) {
                        Text("${phase.skipped} tracks skipped (already in library)")
                    }
                    if (phase.failed > 0) {
                        Text("${phase.failed} tracks failed")
                    }
                    Button(
                        onClick = {
                            viewModel.continueListening()
                            onContinueListening()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Continue listening")
                    }
                }
                is TransferPhase.Failed -> {
                    Text(
                        text = phase.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(
                        onClick = {
                            viewModel.continueListening()
                            onContinueListening()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Continue listening")
                    }
                }
                else -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("Preparing transfer…")
                }
            }
        }
    }
}
