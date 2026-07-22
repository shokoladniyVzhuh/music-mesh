package com.mesh.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mesh.app.library.ImportFailure

@Composable
fun ImportErrorsDialog(
    failures: List<ImportFailure>,
    onDismiss: () -> Unit,
) {
    if (failures.isEmpty()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Could not import some files") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(failures) { failure ->
                    Column {
                        Text(
                            text = failure.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = failure.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        },
    )
}

@Composable
fun MeshScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.padding(16.dp),
    )
}
