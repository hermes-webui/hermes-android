package com.hermeswebui.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp

@Composable
fun SettingsBottomSheet(
    initialServerUrl: String,
    isConfigured: Boolean,
    onSave: (String) -> Unit,
    onResetSession: () -> Unit,
    onDismiss: () -> Unit
) {
    var serverUrl by remember(initialServerUrl, isConfigured) {
        mutableStateOf(if (isConfigured) initialServerUrl else "")
    }
    var isServerUrlFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "App settings", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isServerUrlFocused = it.isFocused },
            value = serverUrl,
            onValueChange = { serverUrl = it },
            singleLine = true,
            label = { Text("Hermes server URL") },
            placeholder = {
                if (!isConfigured && !isServerUrlFocused && serverUrl.isBlank()) {
                    Text(initialServerUrl)
                }
            },
            supportingText = { Text("HTTP or HTTPS. Host is automatically allowlisted.") }
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onResetSession) {
                Text("Reset web session")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Button(onClick = {
                    onSave(serverUrl.trim())
                }) {
                    Text("Save")
                }
            }
        }
    }
}
