package com.hermeswebui.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun UpdatesSettingsSection(
    appUpdateAlertsEnabled: Boolean,
    automaticAppUpdateChecksEnabled: Boolean,
    appUpdateChannelLabel: String,
    appUpdateStatus: String?,
    appUpdateReleaseUrl: String?,
    appUpdateDownloadUrl: String?,
    appUpdateInstallReady: Boolean,
    appUpdateReleaseNotes: String?,
    onSetAppUpdateAlertsEnabled: (Boolean) -> Unit,
    onSetAutomaticAppUpdateChecksEnabled: (Boolean) -> Unit,
    onCheckAppUpdates: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onOpenAppUpdateRelease: () -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    SectionHeader("Updates")
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceColor)
            .fillMaxWidth()
    ) {
        Column {
            ListItem(
                headlineContent = {
                    Text("App update alerts", color = onSurface, fontWeight = FontWeight.Medium)
                },
                supportingContent = {
                    Text(
                        "Check $appUpdateChannelLabel and notify when an update is available.",
                        color = onSurfaceVariant,
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
                            uncheckedThumbColor = onSurfaceVariant,
                            uncheckedTrackColor = surfaceVariant
                        )
                    )
                },
                colors = ListItemDefaults.colors(containerColor = surfaceColor),
                modifier = Modifier.clickable {
                    onSetAppUpdateAlertsEnabled(!appUpdateAlertsEnabled)
                }
            )

            HorizontalDivider(color = outlineVariant.copy(alpha = 0.5f))

            ListItem(
                headlineContent = {
                    Text("Automatic checks", color = onSurface, fontWeight = FontWeight.Medium)
                },
                supportingContent = {
                    Text(
                        "Check for updates whenever Hermes opens.",
                        color = onSurfaceVariant,
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
                            uncheckedThumbColor = onSurfaceVariant,
                            uncheckedTrackColor = surfaceVariant
                        )
                    )
                },
                colors = ListItemDefaults.colors(containerColor = surfaceColor),
                modifier = Modifier.clickable {
                    onSetAutomaticAppUpdateChecksEnabled(!automaticAppUpdateChecksEnabled)
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isPlayUpdateReady = appUpdateReleaseUrl?.startsWith("play://") == true
                val primaryActionLabel = when {
                    appUpdateInstallReady -> "Install APK"
                    !appUpdateDownloadUrl.isNullOrBlank() -> "Download APK"
                    isPlayUpdateReady -> "Update now"
                    else -> "Check for updates"
                }
                val primaryAction: () -> Unit = when {
                    appUpdateInstallReady || !appUpdateDownloadUrl.isNullOrBlank() -> onDownloadAppUpdate
                    isPlayUpdateReady -> onOpenAppUpdateRelease
                    else -> onCheckAppUpdates
                }

                if (appUpdateInstallReady || !appUpdateDownloadUrl.isNullOrBlank() || isPlayUpdateReady) {
                    Button(
                        onClick = primaryAction,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (appUpdateInstallReady) Color(0xFFC62828) else Color(0xFF2E7D32),
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(primaryActionLabel)
                    }
                } else {
                    OutlinedButton(onClick = primaryAction, modifier = Modifier.fillMaxWidth()) {
                        Text(primaryActionLabel)
                    }
                }

                if (!appUpdateStatus.isNullOrBlank()) {
                    Text(
                        text = appUpdateStatus,
                        color = onSurfaceVariant.copy(alpha = 0.82f),
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
                        color = onSurfaceVariant.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (!appUpdateReleaseUrl.isNullOrBlank() && !appUpdateReleaseUrl.startsWith("play://")) {
                    OutlinedButton(onClick = onOpenAppUpdateRelease, modifier = Modifier.fillMaxWidth()) {
                        Text("Release notes")
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdvancedSettingsSection(
    clientCertificateConfigured: Boolean,
    onOpenClientCertificate: () -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    SectionHeader("Advanced")
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceColor)
            .fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text("Client certificate", fontWeight = FontWeight.Medium) },
            supportingContent = {
                Text(
                    if (clientCertificateConfigured) {
                        "Configured for mutual TLS"
                    } else {
                        "Not configured"
                    },
                    color = onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = onSurfaceVariant
                )
            },
            colors = ListItemDefaults.colors(containerColor = surfaceColor),
            modifier = Modifier.clickable(onClick = onOpenClientCertificate)
        )
    }
}

@Composable
internal fun AboutSettingsSection(appVersionLabel: String, appUpdateChannelLabel: String) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    SectionHeader("About")
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceColor)
            .fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text("Hermes WebUI", fontWeight = FontWeight.Medium) },
            supportingContent = {
                Text(
                    "$appVersionLabel - Updates via $appUpdateChannelLabel",
                    color = onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            colors = ListItemDefaults.colors(containerColor = surfaceColor)
        )
    }
}