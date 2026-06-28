package com.hermeswebui.android.update

object AppVersionComparator {
    fun normalize(version: String): String {
        return version
            .trim()
            .removePrefix("v")
            .removeSuffix("-github")
            .substringBefore("-")
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val candidateParts = parts(candidate) ?: return false
        val currentParts = parts(current) ?: return false
        return candidateParts.zip(currentParts).firstOrNull { it.first != it.second }
            ?.let { it.first > it.second }
            ?: false
    }

    private fun parts(version: String): List<Int>? {
        val normalized = normalize(version)
        val pieces = normalized.split('.')
        if (pieces.size != 3) return null
        return pieces.map { piece -> piece.toIntOrNull() ?: return null }
    }
}
