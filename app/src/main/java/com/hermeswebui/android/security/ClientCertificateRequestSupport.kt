package com.hermeswebui.android.security

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import com.hermeswebui.android.data.ClientCertificateConfig
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Locale

data class ClientCertificate(
    val privateKey: PrivateKey,
    val certChain: Array<X509Certificate>
)

object ClientCertificateRequestSupport {
    fun isAllowedHost(host: String?, allowedHosts: Set<String>): Boolean {
        val normalizedHost = host
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotEmpty() }
            ?: return false

        val normalizedAllowedHosts = allowedHosts
            .asSequence()
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .toSet()

        return normalizedHost in normalizedAllowedHosts || normalizedAllowedHosts.any { normalizedHost.endsWith(".$it") }
    }

    fun resolveRequest(
        contentResolver: ContentResolver,
        config: ClientCertificateConfig,
        request: android.webkit.ClientCertRequest,
        allowedHosts: Set<String>
    ): ClientCertificate? {
        val certUri = config.uri?.trim()
        if (certUri.isNullOrBlank()) return null

        val host = request.host?.trim().orEmpty()
        if (!isAllowedHost(host, allowedHosts)) return null

        val parsedUri = runCatching { certUri.toUri() }.getOrNull() ?: return null
        val password = config.password?.trim().orEmpty()

        val keyStore = KeyStore.getInstance("PKCS12")
        contentResolver.openInputStream(parsedUri)?.use { stream ->
            keyStore.load(stream, password.toCharArray())
        } ?: return null

        val aliases = keyStore.aliases()
        if (!aliases.hasMoreElements()) return null

        val alias = aliases.nextElement()
        val privateKey = keyStore.getKey(alias, password.toCharArray()) as? PrivateKey ?: return null
        val chain = keyStore.getCertificateChain(alias)
            ?.mapNotNull { it as? X509Certificate }
            ?.toTypedArray()
            ?: return null

        return ClientCertificate(privateKey = privateKey, certChain = chain)
    }
}
