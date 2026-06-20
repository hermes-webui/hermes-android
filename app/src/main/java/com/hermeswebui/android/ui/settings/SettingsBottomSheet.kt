package com.hermeswebui.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsBottomSheet(
    initialServerUrl: String,
    initialDashboardTerminalUrl: String,
    initialHideWebUIMenuButton: Boolean = true,
    onSave: (String, String) -> Unit,
    onHideWebUIMenuButtonChanged: (Boolean) -> Unit,
    onResetSession: () -> Unit,
    onDismiss: () -> Unit
) {
    var serverUrl by remember(initialServerUrl) { mutableStateOf(initialServerUrl) }
    var dashboardTerminalUrl by remember(initialDashboardTerminalUrl) { mutableStateOf(initialDashboardTerminalUrl) }
    var hideWebUIMenuButton by remember(initialHideWebUIMenuButton) { mutableStateOf(initialHideWebUIMenuButton) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "App settings", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = serverUrl,
            onValueChange = { serverUrl = it },
            singleLine = true,
            label = { Text("Hermes server URL") },
            supportingText = { Text("HTTPS only. Host is automatically allowlisted.") }
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = dashboardTerminalUrl,
            onValueChange = { dashboardTerminalUrl = it },
            singleLine = true,
            label = { Text("Dashboard terminal URL") },
            supportingText = { Text("Optional. Example: https://host:8455/chat") }
        )

        // Hide WebUI menu button toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hide WebUI menu button",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Avoids conflict with the native menu. WebUI sidebar remains accessible.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = hideWebUIMenuButton,
                onCheckedChange = { hideWebUIMenuButton = it }
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onResetSession) {
                Text("Reset web session")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Button(onClick = {
                    onHideWebUIMenuButtonChanged(hideWebUIMenuButton)
                    onSave(serverUrl.trim(), dashboardTerminalUrl.trim())
                }) {
                    Text("Save")
                }
            }
        }
    }
}
