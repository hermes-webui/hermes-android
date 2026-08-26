package com.hermeswebui.android.server

import com.hermeswebui.android.data.ServerProfile
import java.util.Locale

enum class ServerProfileConflict {
    URL,
    NAME
}

object ServerProfileRules {
    fun findConflict(
        existingProfiles: List<ServerProfile>,
        candidateName: String,
        candidateUrl: String,
        excludedProfileId: String? = null
    ): ServerProfileConflict? {
        val comparableProfiles = existingProfiles.filter { it.id != excludedProfileId }
        val normalizedUrl = normalizeUrl(candidateUrl)
        if (comparableProfiles.any { normalizeUrl(it.url) == normalizedUrl }) {
            return ServerProfileConflict.URL
        }

        val normalizedName = candidateName.trim()
        if (
            normalizedName.isNotBlank() &&
            comparableProfiles.any { it.name.trim().equals(normalizedName, ignoreCase = true) }
        ) {
            return ServerProfileConflict.NAME
        }

        return null
    }

    private fun normalizeUrl(url: String): String {
        return url.trim().trimEnd('/').lowercase(Locale.US)
    }
}
