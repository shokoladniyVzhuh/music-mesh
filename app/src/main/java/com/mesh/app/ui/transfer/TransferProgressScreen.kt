package com.mesh.app.ui.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.mesh.app.transfer.TransferRole

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
                .verticalScroll(rememberScrollState())
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
                    if (uiState.session.failedCount > 0) {
                        Text("${uiState.session.failedCount} tracks failed")
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
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Close connection")
                    }
                }
                is TransferPhase.WaitingForResult -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("Files sent. Waiting for the receiver to confirm saving them…")
                    TextButton(onClick = viewModel::closeConnection, modifier = Modifier.fillMaxWidth()) {
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
                        text = "Transfer finished with issues",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(phase.message)
                    val countText = if (uiState.role == TransferRole.Sender && uiState.session.errorMessage != null) {
                        "${phase.transferred} files sent; saving was not confirmed. Check the receiver's library."
                    } else {
                        "${phase.transferred} tracks saved to the receiver's library"
                    }
                    Text(countText)
                    val total = uiState.session.totalRequested
                    if (total > 0 && uiState.role == TransferRole.Receiver) {
                        val completed = (total - phase.failed).coerceAtLeast(0)
                        Text("${(completed.toLong() * 100 / total).coerceIn(0, 100)}% completed ($total requested, excluding known duplicates)")
                    }
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
