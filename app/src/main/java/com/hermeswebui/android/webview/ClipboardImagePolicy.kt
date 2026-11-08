package com.hermeswebui.android.webview

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * Pure rules for images the host hands to a trusted Hermes page from the Android clipboard or an
 * IME (Gboard) `commitContent` insertion (Issue #180). Raster formats only: SVG can carry script
 * and is never accepted.
 */
object ClipboardImagePolicy {
    const val MAX_IMAGE_BYTES: Int = 8 * 1024 * 1024
    const val MAX_IMAGES_PER_READ: Int = 4

    private val allowedMimeTypes = listOf("image/png", "image/jpeg", "image/gif", "image/webp")

    /** MIME types advertised to the IME so it offers image insertion into the composer. */
    fun imeMimeTypes(): Array<String> = allowedMimeTypes.toTypedArray()

    /** Returns the canonical allowed MIME type, or null when the type is not an accepted image. */
    fun normalizeMimeType(raw: String?): String? {
        val base = raw?.substringBefore(';')?.trim()?.lowercase(Locale.US) ?: return null
        val canonical = if (base == "image/jpg" || base == "image/pjpeg") "image/jpeg" else base
        return canonical.takeIf { it in allowedMimeTypes }
    }

    /** First accepted type among [candidates], or null. */
    fun firstAllowedMimeType(candidates: List<String?>): String? {
        return candidates.firstNotNullOfOrNull { normalizeMimeType(it) }
    }

    /** Reads at most [maxBytes]; returns null when the stream is larger or empty. */
    fun readBounded(input: InputStream, maxBytes: Int = MAX_IMAGE_BYTES): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }
}
