package com.hermeswebui.android.security

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import com.hermeswebui.android.data.ClientCertificateConfig
import java.io.InputStream
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
        val password = config.password.orEmpty()

        return runCatching {
            contentResolver.openInputStream(parsedUri)?.use { stream ->
                loadPkcs12(stream, password.toCharArray())
            }
        }.getOrNull()
    }

    internal fun loadPkcs12(stream: InputStream, password: CharArray): ClientCertificate? {
        return runCatching {
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(stream, password)
            }
            val aliases = keyStore.aliases()
            var resolved: ClientCertificate? = null

            while (aliases.hasMoreElements() && resolved == null) {
                val alias = aliases.nextElement()
                val privateKey = keyStore.getKey(alias, password) as? PrivateKey ?: continue
                val chain = keyStore.getCertificateChain(alias)
                    ?.mapNotNull { it as? X509Certificate }
                    ?.takeIf { it.isNotEmpty() }
                    ?.toTypedArray()
                    ?: continue
                resolved = ClientCertificate(privateKey = privateKey, certChain = chain)
            }

            resolved
        }.getOrNull()
    }
}
