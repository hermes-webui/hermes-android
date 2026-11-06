package com.hermeswebui.android.data

import java.util.UUID

data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = false,
    val serverType: ServerType = ServerType.UNSPECIFIED,
    val detectedServerType: ServerType = ServerType.UNSPECIFIED
) {
    val effectiveServerType: ServerType
        get() = if (serverType == ServerType.UNSPECIFIED) detectedServerType else serverType
}
