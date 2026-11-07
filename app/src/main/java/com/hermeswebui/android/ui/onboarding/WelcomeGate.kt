package com.hermeswebui.android.ui.onboarding

/**
 * Decides when the first-run welcome guide appears. Kept free of Android types so the rules can
 * be unit tested on the JVM.
 */
internal object WelcomeGate {
    /**
     * Show the guide only to a brand-new user: no server configured yet, never seen it, and the
     * app was opened normally. A deep link or share intent (for example the `hermes://app/settings`
     * recovery link) is a direct request for something else and must not be interrupted.
     */
    fun shouldShowOnLaunch(isConfigured: Boolean, hasSeen: Boolean, isPlainLaunch: Boolean): Boolean =
        !isConfigured && !hasSeen && isPlainLaunch

    /** An existing user with a server already configured never needs the guide; record that quietly. */
    fun shouldMarkSeenSilently(isConfigured: Boolean, hasSeen: Boolean): Boolean =
        isConfigured && !hasSeen
}

/** Hard-coded external pages the guide links to. Opened in the browser only; nothing is fetched. */
internal object WelcomeLinks {
    const val HERMES_WEBUI = "https://github.com/nesquena/hermes-webui"
    const val HERMES_WEBAPP_DOCS = "https://hermes-agent.nousresearch.com/docs/user-guide/desktop"

    data class Option(val name: String, val url: String)

    /** Private-network and tunnel options for reaching Hermes away from home. */
    val remoteAccessOptions: List<Option> = listOf(
        Option("Tailscale", "https://tailscale.com"),
        Option("ZeroTier", "https://www.zerotier.com"),
        Option("NetBird", "https://netbird.io"),
        Option("WireGuard", "https://www.wireguard.com"),
        Option("Cloudflare Tunnel", "https://www.cloudflare.com/products/tunnel/")
    )

    val all: List<String> = listOf(HERMES_WEBUI, HERMES_WEBAPP_DOCS) + remoteAccessOptions.map { it.url }
}
