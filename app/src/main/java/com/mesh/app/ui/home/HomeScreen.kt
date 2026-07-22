package com.mesh.app.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mesh.app.MeshApplication
import com.mesh.app.ui.components.ImportErrorsDialog
import com.mesh.app.ui.components.MeshScreenTitle
import com.mesh.app.util.PermissionsHelper

@Composable
fun HomeScreen(
    onPlaylists: () -> Unit,
    onDownloaded: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = (LocalContext.current.applicationContext as MeshApplication).viewModelFactory),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                    // Some providers don't support persistable permissions.
                }
            }
            viewModel.importTracks(uris)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            pickerLauncher.launch(arrayOf("audio/mpeg", "audio/mp3"))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MeshScreenTitle(title = "Mesh", modifier = Modifier.padding(0.dp))
        if (uiState.isImporting) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        HomeButton("Add") {
            if (PermissionsHelper.hasAudioReadPermission(context)) {
                pickerLauncher.launch(arrayOf("audio/mpeg", "audio/mp3"))
            } else {
                permissionLauncher.launch(PermissionsHelper.audioReadPermissions())
            }
        }
        HomeButton("Playlists", onPlaylists)
        HomeButton("Downloaded", onDownloaded)
        HomeButton("Send", onSend)
        HomeButton("Receive", onReceive)
    }

    ImportErrorsDialog(
        failures = uiState.importFailures,
        onDismiss = viewModel::dismissImportErrors,
    )
}

@Composable
private fun HomeButton(
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}
