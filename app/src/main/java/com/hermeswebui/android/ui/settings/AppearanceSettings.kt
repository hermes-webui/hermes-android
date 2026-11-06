package com.hermeswebui.android.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import com.hermeswebui.android.R
import com.hermeswebui.android.data.ServerType
import com.hermeswebui.android.launcher.LauncherIcon

@Composable
internal fun serverTypeLabel(type: ServerType): String = stringResource(when (type) {
    ServerType.UNSPECIFIED -> R.string.server_type_unspecified
    ServerType.AGENT -> R.string.server_type_agent
    ServerType.WEBUI -> R.string.server_type_webui
})

@Composable
internal fun ServerTypeBadge(type: ServerType) {
    if (type == ServerType.UNSPECIFIED) return
    val background = if (type == ServerType.AGENT) Color(0xFFDCEBFF) else Color(0xFFFFE6A6)
    val foreground = if (type == ServerType.AGENT) Color(0xFF123F7D) else Color(0xFF573B00)
    Surface(color = background, contentColor = foreground, shape = MaterialTheme.shapes.small) {
        Text(
            serverTypeLabel(type),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
internal fun ServerTypePicker(selected: ServerType, onSelect: (ServerType) -> Unit) {
    Column {
        Text(stringResource(R.string.server_type_title), style = MaterialTheme.typography.titleSmall)
        // A vertical selector stays readable at large font sizes and on narrow phones.
        ServerType.entries.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(serverTypeLabel(type)) },
                leadingIcon = if (type == ServerType.UNSPECIFIED) null else ({
                    Box(Modifier.size(12.dp).background(
                        if (type == ServerType.AGENT) Color(0xFF1976D2) else Color(0xFFB8860B),
                        CircleShape
                    ))
                })
            )
        }
        Text(stringResource(R.string.server_type_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun LauncherIconPicker(selected: LauncherIcon, onSelect: (LauncherIcon) -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(stringResource(R.string.launcher_icon_title), style = MaterialTheme.typography.titleSmall)
        LauncherIcon.entries.forEach { icon ->
            val label = serverTypeLabel(if (icon == LauncherIcon.AGENT) ServerType.AGENT else ServerType.WEBUI)
            FilterChip(
                selected = selected == icon,
                onClick = { onSelect(icon) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Image(
                            painterResource(if (icon == LauncherIcon.AGENT) R.drawable.hermes_agent_icon else R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(label)
                    }
                }
            )
        }
        Text(stringResource(R.string.launcher_icon_hint), style = MaterialTheme.typography.bodySmall)
    }
}
