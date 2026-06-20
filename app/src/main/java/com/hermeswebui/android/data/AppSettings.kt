package com.hermeswebui.android.data

data class AppSettings(
    val serverUrl: String,
    val dashboardUrl: String,
    val allowedHosts: Set<String>,
    val isConfigured: Boolean
)
