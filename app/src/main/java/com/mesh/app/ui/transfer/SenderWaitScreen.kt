package com.mesh.app.ui.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mesh.app.MeshApplication
import com.mesh.app.transfer.TransferPhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderWaitScreen(
    onTransferring: () -> Unit,
    onFinished: () -> Unit,
    onFailedOrClosed: () -> Unit,
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
            -> onFinished()
            is TransferPhase.Failed -> onFailedOrClosed()
            is TransferPhase.Idle -> onFailedOrClosed()
            else -> Unit
        }
    }

    BackHandler {
        viewModel.closeConnection()
        onFailedOrClosed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Waiting") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.closeConnection()
                            onFailedOrClosed()
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Please wait, receiver is choosing tracks",
                style = MaterialTheme.typography.titleMedium,
            )
            uiState.peerNickname?.let {
                Text("Connected to $it")
            }
            CircularProgressIndicator()
            TextButton(
                onClick = {
                    viewModel.closeConnection()
                    onFailedOrClosed()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Close connection")
            }
        }
    }
}
