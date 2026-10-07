package com.hermeswebui.android

import java.net.URLEncoder

internal object DeepLinkRoutes {
    /** Hermes WebUI serves a conversation at `/session/{id}`; `/{id}` is a JSON 404. */
    fun sessionUrl(serverUrl: String, sessionId: String): String =
        "${serverUrl.trimEnd('/')}/session/${URLEncoder.encode(sessionId, "UTF-8").replace("+", "%20")}"
}
