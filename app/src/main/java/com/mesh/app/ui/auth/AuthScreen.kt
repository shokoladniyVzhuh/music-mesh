package com.mesh.app.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mesh.app.MeshApplication
import com.mesh.app.ui.components.MeshScreenTitle

@Composable
fun AuthScreen(
    onDone: () -> Unit,
    viewModel: AuthViewModel = viewModel(factory = (LocalContext.current.applicationContext as MeshApplication).viewModelFactory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        MeshScreenTitle(title = "Introduce yourself")
        OutlinedTextField(
            value = uiState.nickname,
            onValueChange = viewModel::onNicknameChange,
            label = { Text("Nickname") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            singleLine = true,
        )
        Button(
            onClick = { viewModel.saveNickname(onDone) },
            enabled = uiState.nickname.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done")
        }
    }
}
