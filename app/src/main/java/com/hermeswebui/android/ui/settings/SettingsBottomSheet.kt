package com.hermeswebui.android.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import com.hermeswebui.android.data.ServerProfile

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsBottomSheet(
    initialServerUrl: String,
    isConfigured: Boolean,
    onSave: (String) -> Unit,
    onResetSession: () -> Unit,
    onDismiss: () -> Unit,
    serverProfiles: List<ServerProfile> = emptyList(),
    onAddProfile: (String, String) -> Unit = { _, _ -> },
    onDeleteProfile: (String) -> Unit = {},
    onRenameProfile: (String, String) -> Unit = { _, _ -> },
    onEditProfile: (String, String, String) -> Unit = { _, _, _ -> },
    onSwitchProfile: (String) -> Unit = { _ -> }
) {
    var serverUrl by remember(initialServerUrl, isConfigured) {
        mutableStateOf(if (isConfigured) initialServerUrl else "")
    }
    var isServerUrlFocused by remember { mutableStateOf(false) }
    var showAddProfileDialog by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<ServerProfile?>(null) }
    var profileToEdit by remember { mutableStateOf<ServerProfile?>(null) }

    if (showAddProfileDialog) {
        AddServerProfileDialog(
            existingProfiles = serverProfiles,
            onConfirm = { name, url -> onAddProfile(name, url); showAddProfileDialog = false },
            onDismiss = { showAddProfileDialog = false }
        )
    }

    if (profileToEdit != null) {
        EditProfileDialog(
            currentName = profileToEdit!!.name,
            currentUrl = profileToEdit!!.url,
            onConfirm = { newName, newUrl ->
                onEditProfile(profileToEdit!!.id, newName, newUrl)
                profileToEdit = null
            },
            onDismiss = { profileToEdit = null }
        )
    }

    if (profileToDelete != null) {
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("Delete server?") },
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
            // First-run: editable URL + Connect
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isServerUrlFocused = it.isFocused },
                value = serverUrl,
                onValueChange = { serverUrl = it },
                singleLine = true,
                label = { Text("Hermes server URL") },
                placeholder = {
                    if (!isServerUrlFocused && serverUrl.isBlank()) Text(initialServerUrl)
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
            Text(text = "Servers", style = MaterialTheme.typography.titleSmall)

            // Active profile = URL match first, then isActive flag
            val activeProfile = serverProfiles.firstOrNull { profile ->
                profile.url.trimEnd('/').equals(initialServerUrl.trimEnd('/'), ignoreCase = true)
            } ?: serverProfiles.firstOrNull { it.isActive }
            val sortedProfiles = listOfNotNull(activeProfile) +
                serverProfiles.filter { it.id != activeProfile?.id }

            if (sortedProfiles.isEmpty()) {
                // Edge case: server configured but no profiles yet
                ListItem(
                    headlineContent = { Text("Current server", maxLines = 1) },
                    supportingContent = {
                        Text(
                            initialServerUrl, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = { CurrentBadge() },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    sortedProfiles.forEach { profile ->
                        val isCurrent = profile.id == activeProfile?.id
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
                                    profile.url, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isCurrent) CurrentBadge()
                                    IconButton(onClick = { profileToDelete = profile }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete \"${profile.name}\""
                                        )
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (isCurrent)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = { if (!isCurrent) onSwitchProfile(profile.id) },
                                    onLongClick = { profileToEdit = profile }
                                )
                                .alpha(if (isCurrent) 1f else 0.9f)
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Text(
                text = "Tap a server to switch. Long-press to edit.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = { showAddProfileDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add new server")
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onResetSession) { Text("Reset web session") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun CurrentBadge() {
    SuggestionChip(
        onClick = {},
        label = { Text("Current", style = MaterialTheme.typography.labelSmall) },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
private fun EditProfileDialog(
    currentName: String,
    currentUrl: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    val isValidUrl = url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { 
                        Text(
                            if (!isValidUrl && url.isNotBlank()) "Must start with http:// or https://" 
                            else "HTTP or HTTPS"
                        )
                    },
                    isError = !isValidUrl && url.isNotBlank()
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && isValidUrl) onConfirm(name, url) },
                enabled = name.isNotBlank() && isValidUrl
            ) {
                Text("Save")
            }
        }
    )
}

@Composable
private fun AddServerProfileDialog(
    existingProfiles: List<ServerProfile>,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var profileName by remember { mutableStateOf("") }
    var profileUrl by remember { mutableStateOf("") }
    val trimmedName = profileName.trim()
    val trimmedUrl = profileUrl.trim()
    val normalizedUrl = normalizeServerUrl(trimmedUrl)
    val isValidUrl = trimmedUrl.isNotBlank() && (trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://"))
    val isDuplicateUrl = existingProfiles.any { normalizeServerUrl(it.url) == normalizedUrl }
    val isDuplicateName = trimmedName.isNotBlank() &&
        existingProfiles.any { it.name.trim().equals(trimmedName, ignoreCase = true) }
    val canSubmit = isValidUrl && !isDuplicateUrl && !isDuplicateName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("Server name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            if (isDuplicateName) "A server with this name already exists"
                            else "Optional friendly name"
                        )
                    },
                    isError = isDuplicateName
                )
                OutlinedTextField(
                    value = profileUrl,
                    onValueChange = { profileUrl = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://hermes.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { 
                        Text(
                            if (!isValidUrl && trimmedUrl.isNotBlank()) "Must start with http:// or https://"
                            else if (isDuplicateUrl) "A server with this URL already exists"
                            else "HTTP or HTTPS"
                        )
                    },
                    isError = (!isValidUrl && trimmedUrl.isNotBlank()) || isDuplicateUrl
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSubmit) {
                        onConfirm(trimmedName.ifBlank { trimmedUrl }, trimmedUrl)
                        onDismiss()
                    }
                },
                enabled = canSubmit
            ) {
                Text("Add")
            }
        }
    )
}

private fun normalizeServerUrl(url: String): String {
    return url.trim().trimEnd('/').lowercase()
}

