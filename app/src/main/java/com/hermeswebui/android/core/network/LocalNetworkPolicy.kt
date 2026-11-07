package com.hermeswebui.android.core.network

import java.net.InetAddress

/**
 * Pure decisions for the best-effort Android 16/17 `ACCESS_LOCAL_NETWORK` preflight (#47, #49).
 * Android permission calls stay in `MainActivity`; this holds only the host predicate and the
 * grant/prompt/continue decision so they can be unit tested on the JVM.
 */
object LocalNetworkPolicy {
    enum class PreflightDecision {
        /** Not an obvious LAN host, or the permission is already granted: load immediately. */
        PROCEED,

        /** Obvious LAN host without the permission: show the system prompt first. */
        REQUEST_PERMISSION
    }

    /**
     * True for `localhost`, `.local` names, and hosts that resolve to loopback, any-local,
     * link-local or site-local (private) addresses. [host] may be bracketed IPv6.
     * [resolve] is injectable so tests never touch DNS.
     */
    fun isLikelyLocalNetworkHost(
        host: String?,
        resolve: (String) -> InetAddress? = { InetAddress.getByName(it) }
    ): Boolean {
        val trimmed = host?.trim().orEmpty()
        if (trimmed.isBlank()) return false
        val normalizedHost = trimmed.lowercase()
        if (normalizedHost == "localhost" || normalizedHost.endsWith(".local")) return true
        val stripped = normalizedHost.removePrefix("[").removeSuffix("]")
        return runCatching {
            val address = resolve(stripped) ?: return@runCatching false
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress
        }.getOrDefault(false)
    }

    fun preflightDecision(isLocalHost: Boolean, hasPermission: Boolean): PreflightDecision =
        if (isLocalHost && !hasPermission) PreflightDecision.REQUEST_PERMISSION
        else PreflightDecision.PROCEED
}
