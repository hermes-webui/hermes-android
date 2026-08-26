package com.hermeswebui.android.data

interface SettingsStore {
    fun getSettings(defaultUrl: String, defaultDashboardUrl: String): AppSettings
    fun saveAppUrls(serverUrl: String, dashboardUrl: String)
    fun saveLastLoadedUrl(url: String)
}
