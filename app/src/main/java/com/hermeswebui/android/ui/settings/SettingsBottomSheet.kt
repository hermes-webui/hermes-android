package com.hermeswebui.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermeswebui.android.data.ServerProfile

@Composable
fun SettingsBottomSheet(
    initialServerUrl: String,
    isConfigured: Boolean,
    onSave: (String) -> Unit,
    onResetSession: () -> Unit,
    onDismiss: () -> Unit,
    serverProfiles: List<ServerProfile> = emptyList(),
    onAddProfile: (String, String) -> Unit = { _, _ -> },
    onDeleteProfile: (String) -> Unit = {}
) {
    var serverUrl by remember(initialServerUrl, isConfigured) {
        mutableStateOf(if (isConfigured) initialServerUrl else "")
    }
    var isServerUrlFocused by remember { mutableStateOf(false) }
    var showAddProfileDialog by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<ServerProfile?>(null) }

    if (showAddProfileDialog) {
        AddServerProfileDialog(
            onConfirm = { name, url ->
                onAddProfile(name, url)
                showAddProfileDialog = false
            },
            onDismiss = { showAddProfileDialog = false }
        )
    }

    if (profileToDelete != null) {
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("Delete server profile?") },
            text = { Text("Remove \"${profileToDelete!!.name}\" from your saved servers?") },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteProfile(profileToDelete!!.id)
                    profileToDelete = null
                }) { Text("Delete") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Application Settings", style = MaterialTheme.typography.headlineSmall)

        if (!isConfigured) {
            // First-run setup: editable URL input + Connect button
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isServerUrlFocused = it.isFocused },
                value = serverUrl,
                onValueChange = { serverUrl = it },
                singleLine = true,
                label = { Text("Hermes server URL") },
                placeholder = {
                    if (!isServerUrlFocused && serverUrl.isBlank()) {
                        Text(initialServerUrl)
                    }
                },
                supportingText = { Text("HTTP or HTTPS. Host is automatically allowlisted.") }
            )
            Button(
                onClick = { onSave(serverUrl.trim()) },
                enabled = serverUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect")
            }
        } else {
            // Configured: current server is read-only
            Text(text = "Current Server", style = MaterialTheme.typography.titleSmall)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = initialServerUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            // Server Profiles — always shown when configured
            Text(text = "Server Profiles", style = MaterialTheme.typography.titleSmall)

            if (serverProfiles.isEmpty()) {
                Text(
                    text = "No saved profiles yet. Add a server below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Regular Column inside verticalScroll (LazyColumn can't nest inside ScrollState)
                Column(modifier = Modifier.fillMaxWidth()) {
                    serverProfiles.forEach { profile ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    profile.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    profile.url,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { profileToDelete = profile }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete \"${profile.name}\""
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { showAddProfileDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add new server")
            }

            HorizontalDivider()

            // Bottom actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onResetSession) {
                    Text("Reset web session")
                }
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun AddServerProfileDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var profileName by remember { mutableStateOf("") }
    var profileUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add server profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("Server name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = profileUrl,
                    onValueChange = { profileUrl = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://hermes.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("HTTP or HTTPS") }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (profileUrl.isNotBlank()) {
                        val name = profileName.ifBlank { profileUrl }
                        onConfirm(name, profileUrl)
                        onDismiss()
                    }
                },
                enabled = profileUrl.isNotBlank()
            ) {
                Text("Add")
            }
        }
    )
}
