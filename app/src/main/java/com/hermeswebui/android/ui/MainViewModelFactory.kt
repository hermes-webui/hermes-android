package com.hermeswebui.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.data.SettingsStore

class MainViewModelFactory(
    private val settingsRepository: SettingsStore,
    private val settingsRepositoryImpl: SettingsRepository? = null,
    private val defaultUrl: String,
    private val defaultDashboardUrl: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(
                settingsRepository,
                settingsRepositoryImpl,
                defaultUrl,
                defaultDashboardUrl
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
