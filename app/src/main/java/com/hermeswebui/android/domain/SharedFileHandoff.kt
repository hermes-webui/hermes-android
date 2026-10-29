package com.hermeswebui.android.domain

/**
 * Holds files received through the Android share sheet until a trusted Hermes WebUI page asks for
 * them. Untrusted requesters (proxy sign-in, OAuth provider, or any other non-WebUI page) never
 * receive or consume the staged files, so they remain available for the configured WebUI.
 */
class SharedFileHandoff {
    private var staged: List<String> = emptyList()

    val hasStagedFiles: Boolean get() = staged.isNotEmpty()

    fun stage(uris: List<String>) {
        staged = uris.toList()
    }

    fun takeFor(requesterIsTrustedWebUi: Boolean): List<String> {
        if (!requesterIsTrustedWebUi) return emptyList()
        val files = staged
        staged = emptyList()
        return files
    }
}
