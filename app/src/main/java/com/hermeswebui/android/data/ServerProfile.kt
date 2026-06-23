package com.hermeswebui.android.data

import java.util.UUID

data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = false
)

