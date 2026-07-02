package com.hermeswebui.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermeswebui.android.data.ServerProfile
import com.hermeswebui.android.ui.ServerValidationUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    initialServerUrl: String,
    isConfigured: Boolean,
    backgroundReconnectEnabled: Boolean,
    backgroundActivityFullTextEnabled: Boolean,
    reconnectPollIntervalSeconds: Int,
    sseTransportEnabled: Boolean,
    sseSupportStatus: String?,
    debugLoggingEnabled: Boolean,
    blockScreenshotsEnabled: Boolean,
    appUpdateAlertsEnabled: Boolean,
    automaticAppUpdateChecksEnabled: Boolean,
    appUpdateChannelLabel: String,
    appUpdateStatus: String?,
    appUpdateReleaseUrl: String?,
    appUpdateDownloadUrl: String?,
    appUpdateReleaseNotes: String?,
    serverValidation: ServerValidationUiState,
    appVersionLabel: String,
    serverProfiles: List<ServerProfile>,
    onSave: (String) -> Unit,
    onResetSession: () -> Unit,
    onDismiss: () -> Unit,
    onSetBackgroundReconnect: (Boolean) -> Unit,
    onSetBackgroundActivityFullTextEnabled: (Boolean) -> Unit,
    onSetReconnectPollIntervalSeconds: (Int) -> Unit,
    onSetSseTransportEnabled: (Boolean) -> Unit,
    onCheckSseSupport: () -> Unit,
    onCopySsePrompt: () -> Unit,
    onSetDebugLoggingEnabled: (Boolean) -> Unit,
    onSetBlockScreenshotsEnabled: (Boolean) -> Unit,
    onSetAppUpdateAlertsEnabled: (Boolean) -> Unit,
    onSetAutomaticAppUpdateChecksEnabled: (Boolean) -> Unit,
    onCheckAppUpdates: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onOpenAppUpdateRelease: () -> Unit,
    onShareDebugLog: () -> Unit,
    onDownloadDebugLog: () -> Unit,
    onViewGithubIssues: () -> Unit,
    onNewGithubIssue: () -> Unit,
    onAddProfile: (String, String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onEditProfile: (String, String, String) -> Unit,
    onSwitchProfile: (String) -> Unit,
    onClearServerValidation: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onDismiss)

    var showAddProfileDialog by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<ServerProfile?>(null) }
    var profileToEdit by remember { mutableStateOf<ServerProfile?>(null) }
    var editCurrentServerWithoutProfile by remember { mutableStateOf(false) }
    var showResetSessionConfirm by remember { mutableStateOf(false) }
    var serverUrl by remember(initialServerUrl, isConfigured) {
        mutableStateOf(if (isConfigured) initialServerUrl else "")
    }

    // Derived theme colors kept local for readability
    val bgColor = MaterialTheme.colorScheme.background          // #0D0D1A deep navy
    val surfaceColor = MaterialTheme.colorScheme.surface         // #141425 card navy
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant // #1A1A2E list row navy
    val primaryColor = MaterialTheme.colorScheme.primary          // #FFD700 gold
    val onSurface = MaterialTheme.colorScheme.onSurface           // #FFF8DC cream
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant // #E6E0C8 muted cream
    val outlineVar = MaterialTheme.colorScheme.outlineVariant     // #3A3A55 subtle divider

    // ── Dialogs ──────────────────────────────────────────────────────────────
    if (showAddProfileDialog) {
        AddServerProfileDialog(
            existingProfiles = serverProfiles,
            onConfirm = { name, url -> onAddProfile(name, url); showAddProfileDialog = false },
            onDismiss = { showAddProfileDialog = false }
        )
    }
    profileToEdit?.let { editing ->
        EditProfileDialog(
            currentName = editing.name,
            currentUrl = editing.url,
            onConfirm = { newName, newUrl ->
                onEditProfile(editing.id, newName, newUrl)
                profileToEdit = null
            },
            onDismiss = { profileToEdit = null }
        )
    }

    if (editCurrentServerWithoutProfile) {
        EditProfileDialog(
            currentName = "Current server",
            currentUrl = initialServerUrl,
            onConfirm = { _, newUrl ->
                onSave(newUrl)
                editCurrentServerWithoutProfile = false
            },
            onDismiss = { editCurrentServerWithoutProfile = false }
        )
    }
    profileToDelete?.let { deleting ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("Delete server?") },
            text = { Text("Remove \"${deleting.name}\" from your saved servers?") },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(onClick = { onDeleteProfile(deleting.id); profileToDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
    if (showResetSessionConfirm) {
        AlertDialog(
            onDismissRequest = { showResetSessionConfirm = false },
            title = { Text("Reset web session?") },
            text = {
                Text("This clears all cookies, local storage, and cached data. You will be signed out of Hermes.")
            },
            dismissButton = {
                TextButton(onClick = { showResetSessionConfirm = false }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = { onResetSession(); showResetSessionConfirm = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Reset") }
            }
        )
    }

    // ── Full-screen settings surface ─────────────────────────────────────────
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = primaryColor   // gold back arrow = matches app's interactive accent
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceColor,          // #141425 — same as card/surface
                    scrolledContainerColor = surfaceColor,
                    titleContentColor = onSurface,
                    navigationIconContentColor = primaryColor,
                    actionIconContentColor = primaryColor
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
        ) {
            if (!isConfigured) {
                // ── First-run: connect ────────────────────────────────────
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Connect to Hermes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor
                    )
                    Text(
                        text = "Enter your Hermes server URL to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurfaceVar
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            onClearServerValidation()
                        },
                        singleLine = true,
                        label = { Text("Hermes server URL") },
                        placeholder = { Text("https://hermes.example.com") },
                        supportingText = { Text("HTTP or HTTPS. Host is automatically allowlisted.") }
                    )
                    ServerValidationStatus(serverValidation = serverValidation)
                    Button(
                        onClick = { onSave(serverUrl.trim()) },
                        enabled = serverUrl.isNotBlank() && !serverValidation.isChecking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            if (serverValidation.isChecking) "Checking server..." else "Connect",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))

                // ── Servers ───────────────────────────────────────────────
                SectionHeader("Servers")

                val activeProfile = serverProfiles.firstOrNull { profile ->
                    profile.url.trimEnd('/').equals(initialServerUrl.trimEnd('/'), ignoreCase = true)
                } ?: serverProfiles.firstOrNull { it.isActive }
                val sortedProfiles = listOfNotNull(activeProfile) +
                    serverProfiles.filter { it.id != activeProfile?.id }

                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(surfaceColor)
                        .fillMaxWidth()
                ) {
                    Column {
                        if (sortedProfiles.isEmpty()) {
                            ListItem(
                                headlineContent = { Text("Current server", maxLines = 1, color = onSurface) },
                                supportingContent = {
                                    Text(
                                        initialServerUrl,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = onSurfaceVar
                                    )
                                },
                                trailingContent = { ServerCurrentBadge() },
                                colors = ListItemDefaults.colors(containerColor = surfaceVariant),
                                modifier = Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { editCurrentServerWithoutProfile = true }
                                )
                            )
                        } else {
                            sortedProfiles.forEachIndexed { index, profile ->
                                val isCurrent = profile.id == activeProfile?.id
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            profile.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isCurrent) primaryColor else onSurface
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            profile.url,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = onSurfaceVar
                                        )
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isCurrent) ServerCurrentBadge()
                                            IconButton(onClick = { profileToDelete = profile }) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete \"${profile.name}\"",
                                                    tint = onSurfaceVar
                                                )
                                            }
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = surfaceVariant),
                                    modifier = Modifier
                                        .combinedClickable(
                                            onClick = { if (!isCurrent) onSwitchProfile(profile.id) },
                                            onLongClick = { profileToEdit = profile }
                                        )
                                        .alpha(if (isCurrent) 1f else 0.85f)
                                )
                                if (index < sortedProfiles.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = outlineVar.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }

                        // Add server row
                        HorizontalDivider(color = outlineVar.copy(alpha = 0.5f))
                        ListItem(
                            headlineContent = {
                                Text("Add server", color = primaryColor, fontWeight = FontWeight.Medium)
                            },
                            leadingContent = {
                                Icon(Icons.Default.Add, contentDescription = null, tint = primaryColor)
                            },
                            colors = ListItemDefaults.colors(containerColor = surfaceColor),
                            modifier = Modifier.clickable { showAddProfileDialog = true }
                        )
                    }
                }

                Text(
                    text = "Tap to check connection  •  Long-press to edit",
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurfaceVar.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
                if (serverValidation.isChecking || !serverValidation.message.isNullOrBlank()) {
                    ServerValidationStatus(
                        serverValidation = serverValidation,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Application ───────────────────────────────────────────
                SectionHeader("Application")

                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(surfaceColor)
                        .fillMaxWidth()
                ) {
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(
                                    "Background activity notification",
                                    color = onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            supportingContent = {
                                Text(
                                    "Show the latest safe Hermes session activity while the app is backgrounded",
                                    color = onSurfaceVar,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = backgroundReconnectEnabled,
                                    onCheckedChange = onSetBackgroundReconnect,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = primaryColor,
                                        uncheckedThumbColor = onSurfaceVar,
                                        uncheckedTrackColor = surfaceVariant
                                    )
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = surfaceColor),
                            modifier = Modifier.clickable {
                                onSetBackgroundReconnect(!backgroundReconnectEnabled)
                            }
                        )

                        HorizontalDivider(color = outlineVar.copy(alpha = 0.5f))

                        ListItem(
                            headlineContent = {
                                Text(
                                    "Show full activity text on lock screen",
                                    color = onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            supportingContent = {
                                Text(
                                    "Off keeps the lock screen redacted with a generic Hermes status message.",
                                    color = onSurfaceVar,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = backgroundActivityFullTextEnabled,
                                    onCheckedChange = onSetBackgroundActivityFullTextEnabled,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = primaryColor,
                                        uncheckedThumbColor = onSurfaceVar,
                                        uncheckedTrackColor = surfaceVariant
                                    )
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = surfaceColor),
                            modifier = Modifier.clickable {
                                onSetBackgroundActivityFullTextEnabled(!backgroundActivityFullTextEnabled)
                            }
                        )

                        HorizontalDivider(color = outlineVar.copy(alpha = 0.5f))

                        ListItem(
                            headlineContent = {
                                Text(
                                    "App update alerts",
                                    color = onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            supportingContent = {
                                Text(
                                    "Checks $appUpdateChannelLabel and alerts through Hermes updates.",
                                    color = onSurfaceVar,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = appUpdateAlertsEnabled,
                                    onCheckedChange = onSetAppUpdateAlertsEnabled,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = primaryColor,
                                        uncheckedThumbColor = onSurfaceVar,
                                        uncheckedTrackColor = surfaceVariant
                                    )
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = surfaceColor),
                            modifier = Modifier.clickable {
                                onSetAppUpdateAlertsEnabled(!appUpdateAlertsEnabled)
                            }
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onCheckAppUpdates,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Check for app update now")
                            }
                            ListItem(
                                headlineContent = {
                                    Text(
                                        "Automatic daily checks",
                                        color = onSurface,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        "Waits about one minute after opening, then checks at most once per day.",
                                        color = onSurfaceVar,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = automaticAppUpdateChecksEnabled,
                                        onCheckedChange = onSetAutomaticAppUpdateChecksEnabled,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = primaryColor,
                                            uncheckedThumbColor = onSurfaceVar,
                                            uncheckedTrackColor = surfaceVariant
                                        )
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = surfaceColor),
                                modifier = Modifier.clickable {
                                    onSetAutomaticAppUpdateChecksEnabled(!automaticAppUpdateChecksEnabled)
                                }
                            )
                            if (!appUpdateStatus.isNullOrBlank()) {
                                Text(
                                    text = appUpdateStatus,
                                    color = onSurfaceVar.copy(alpha = 0.82f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (!appUpdateReleaseNotes.isNullOrBlank()) {
                                Text(
                                    text = "What's changed",
                                    color = onSurface,
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = appUpdateReleaseNotes,
                                    color = onSurfaceVar.copy(alpha = 0.82f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (!appUpdateDownloadUrl.isNullOrBlank() || !appUpdateReleaseUrl.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (!appUpdateDownloadUrl.isNullOrBlank()) {
                                        Button(
                                            onClick = onDownloadAppUpdate,
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = primaryColor,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        ) {
                                            Text("Download APK")
                                        }
                                    }
                                    if (!appUpdateReleaseUrl.isNullOrBlank()) {
                                        OutlinedButton(
                                            onClick = onOpenAppUpdateRelease,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Release notes")
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = outlineVar.copy(alpha = 0.5f))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Reconnect polling interval",
                                color = onSurface,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "$reconnectPollIntervalSeconds seconds between reconnect checks",
                                color = onSurfaceVar,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = reconnectPollIntervalSeconds.toFloat(),
                                onValueChange = { value ->
                                    onSetReconnectPollIntervalSeconds(value.roundToInt())
                                },
                                valueRange = 1f..10f,
                                steps = 8,
                                colors = androidx.compose.material3.SliderDefaults.colors(
                                    thumbColor = primaryColor,
                                    activeTrackColor = primaryColor,
                                    inactiveTrackColor = surfaceVariant
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { onSetReconnectPollIntervalSeconds(1) }) {
                                    Text("Reset to 1s")
                                }
                            }
                            Text(
                                text = "Used for reconnect fallback polling when Hermes is recovering after a disconnect.",
                                color = onSurfaceVar.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = "Recommended: 1-3 seconds for quick recovery.",
                                color = onSurfaceVar.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.labelSmall
                            )
                            if (reconnectPollIntervalSeconds >= 6) {
                                Text(
                                    text = "Higher intervals reduce checks but may delay reconnect updates.",
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            HorizontalDivider(color = outlineVar.copy(alpha = 0.35f))
                            Spacer(modifier = Modifier.height(2.dp))

                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = "Use SSE transport",
                                        color = onSurface,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = "Beta: uses Hermes SSE endpoints for reconnect detection and falls back to polling if the server does not expose them.",
                                        color = onSurfaceVar,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = sseTransportEnabled,
                                        onCheckedChange = onSetSseTransportEnabled,
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = primaryColor,
                                            uncheckedThumbColor = onSurfaceVar,
                                            uncheckedTrackColor = surfaceVariant
                                        )
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = surfaceColor),
                                modifier = Modifier.clickable {
                                    onSetSseTransportEnabled(!sseTransportEnabled)
                                }
                            )
                            Text(
                                text = "When you turn SSE transport on, Android checks support automatically. If support is unavailable, the toggle turns back off. Use 'Check SSE support now' for a manual re-check.",
                                color = onSurfaceVar.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onCheckSseSupport,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Check SSE support now")
                                }
                                OutlinedButton(
                                    onClick = onCopySsePrompt,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Copy enable prompt")
                                }
                            }
                            if (!sseSupportStatus.isNullOrBlank()) {
                                val isFeatureDisabled = sseSupportStatus.startsWith("🚫")
                                val statusColor = when {
                                    sseSupportStatus.startsWith("✅") -> Color(0xFF4CAF50)
                                    sseSupportStatus.startsWith("❔") -> onSurfaceVar.copy(alpha = 0.82f)
                                    isFeatureDisabled -> MaterialTheme.colorScheme.error
                                    sseSupportStatus.startsWith("❌") -> MaterialTheme.colorScheme.error
                                    else -> onSurfaceVar.copy(alpha = 0.72f)
                                }
                                Text(
                                    text = sseSupportStatus,
                                    color = statusColor,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                if (isFeatureDisabled) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedButton(
                                        onClick = onCopySsePrompt,
                                        modifier = Modifier.fillMaxWidth(),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                        )
                                    ) {
                                        Text(
                                            "📋  Copy enable-SSE prompt for Hermes",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        text = "Paste this into the Hermes chat to ask it to set the flag and restart.",
                                        color = onSurfaceVar.copy(alpha = 0.65f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Session ───────────────────────────────────────────────
                SectionHeader("Session")

                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(surfaceColor)
                        .fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                "Reset web session",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        supportingContent = {
                            Text(
                                "Clear cookies, local storage, cached data, and saved credentials. Signs you out.",
                                color = onSurfaceVar,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = surfaceColor),
                        modifier = Modifier.clickable { showResetSessionConfirm = true }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Privacy ───────────────────────────────────────────────
                SectionHeader("Privacy")

                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(surfaceColor)
                        .fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = {
                            Text("Block screenshots", fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            Text(
                                "Prevent screenshots and screen recording, and hide app content in the recent-apps switcher.",
                                color = onSurfaceVar,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = blockScreenshotsEnabled,
                                onCheckedChange = onSetBlockScreenshotsEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = onSurfaceVar,
                                    uncheckedTrackColor = surfaceVariant
                                )
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = surfaceColor),
                        modifier = Modifier.clickable {
                            onSetBlockScreenshotsEnabled(!blockScreenshotsEnabled)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Troubleshooting ────────────────────────────────────────
                SectionHeader("Troubleshooting")

                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(surfaceColor)
                        .fillMaxWidth()
                ) {
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(
                                    "CAPTURE DEBUG LOGS",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            },
                            supportingContent = {
                                Text(
                                    "Captures app, WebView/system logs, and device/server metadata while running. Use Stop on the notification to end capture.",
                                    color = onSurfaceVar,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = debugLoggingEnabled,
                                    onCheckedChange = onSetDebugLoggingEnabled,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = MaterialTheme.colorScheme.error,
                                        uncheckedThumbColor = onSurfaceVar,
                                        uncheckedTrackColor = surfaceVariant
                                    )
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = surfaceColor),
                            modifier = Modifier.clickable {
                                onSetDebugLoggingEnabled(!debugLoggingEnabled)
                            }
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Troubleshooting actions",
                                color = onSurfaceVar,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = onShareDebugLog,
                                    modifier = Modifier.weight(1f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, onSurfaceVar.copy(alpha = 0.4f)
                                    )
                                ) { Text("Share/email log") }
                                TextButton(
                                    onClick = onDownloadDebugLog,
                                    modifier = Modifier.weight(1f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, onSurfaceVar.copy(alpha = 0.4f)
                                    )
                                ) { Text("Download log") }
                            }
                            androidx.compose.material3.OutlinedButton(
                                onClick = onViewGithubIssues,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("🔍  View existing issues  — is anyone else seeing this?")
                            }
                            Button(
                                onClick = onNewGithubIssue,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("🏆  New Achievement!  You found a bug!  Report it →")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            Text(
                text = appVersionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = onSurfaceVar.copy(alpha = 0.75f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ServerValidationStatus(
    serverValidation: ServerValidationUiState,
    modifier: Modifier = Modifier
) {
    val message = serverValidation.message
    if (!serverValidation.isChecking && message.isNullOrBlank()) return

    val containerColor = if (serverValidation.isError) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    }
    val contentColor = if (serverValidation.isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (serverValidation.isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.height(18.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = message ?: "Checking Hermes server readiness...",
                color = contentColor,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (serverValidation.isError) FontWeight.Medium else FontWeight.Normal
            )
            val details = serverValidation.details
            if (!details.isNullOrBlank()) {
                Text(
                    text = details,
                    color = contentColor.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
    )
}

@Composable
internal fun ServerCurrentBadge() {
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
internal fun EditProfileDialog(
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && isValidUrl) onConfirm(name, url) },
                enabled = name.isNotBlank() && isValidUrl
            ) { Text("Save") }
        }
    )
}

@Composable
internal fun AddServerProfileDialog(
    existingProfiles: List<ServerProfile>,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var profileName by remember { mutableStateOf("") }
    var profileUrl by remember { mutableStateOf("") }
    val trimmedName = profileName.trim()
    val trimmedUrl = profileUrl.trim()
    val normalizedUrl = trimmedUrl.trimEnd('/').lowercase()
    val isValidUrl = trimmedUrl.isNotBlank() &&
        (trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://"))
    val isDuplicateUrl = existingProfiles.any {
        it.url.trim().trimEnd('/').lowercase() == normalizedUrl
    }
    val isDuplicateName = trimmedName.isNotBlank() &&
        existingProfiles.any { it.name.trim().equals(trimmedName, ignoreCase = true) }
    val canSubmit = isValidUrl && !isDuplicateUrl && !isDuplicateName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(
                onClick = { if (canSubmit) { onConfirm(trimmedName.ifBlank { trimmedUrl }, trimmedUrl); onDismiss() } },
                enabled = canSubmit
            ) { Text("Add") }
        }
    )
}
