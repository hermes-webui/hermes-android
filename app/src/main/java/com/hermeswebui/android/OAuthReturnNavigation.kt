package com.hermeswebui.android

class OAuthReturnNavigation(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private var callbackFlow: OAuthPopupFlow? = null
    private var knownCallbackFlow: OAuthPopupFlow? = null
    private var expiresAtMs: Long = 0L

    fun begin(flow: OAuthPopupFlow) {
        callbackFlow = flow
        knownCallbackFlow = flow
        expiresAtMs = nowMs() + timeoutMs
    }

    fun shouldStayInMainWebView(url: String): Boolean {
        return activeFlow()?.isRedirectOriginUrl(url) == true
    }

    fun shouldRememberUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (activeFlow() != null) return false
        return knownCallbackFlow?.isCallbackEndpointUrl(url) != true
    }

    fun completeIfHermesPage(url: String?, isHermesPage: Boolean): Boolean {
        if (!isHermesPage) return false
        val flow = activeFlow() ?: return false
        if (!url.isNullOrBlank() && flow.isRedirectOriginUrl(url) && !flow.isCallbackEndpointUrl(url)) {
            clearActiveWindow()
            return true
        }
        return false
    }

    fun clear() {
        clearActiveWindow()
        knownCallbackFlow = null
    }

    private fun clearActiveWindow() {
        callbackFlow = null
        expiresAtMs = 0L
    }

    private fun activeFlow(): OAuthPopupFlow? {
        val flow = callbackFlow ?: return null
        if (nowMs() <= expiresAtMs) return flow
        clearActiveWindow()
        return null
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
