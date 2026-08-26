package com.hermeswebui.android.domain

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentParserTest {
    private val parser = ShareIntentParser()

    @Test
    fun unsupportedOrEmptyIntentReturnsNull() {
        assertThat(parser.parse(null)).isNull()
        assertThat(parser.parse(Intent(Intent.ACTION_VIEW))).isNull()
        assertThat(parser.parse(Intent(Intent.ACTION_SEND))).isNull()
        assertThat(parser.parse(Intent(Intent.ACTION_SEND_MULTIPLE))).isNull()
    }

    @Test
    fun singleShareTrimsTextAndKeepsStreamUri() {
        val streamUri = Uri.parse("content://share/image.png")
        val payload = parser.parse(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_TEXT, "  shared context  ")
                putExtra(Intent.EXTRA_STREAM, streamUri)
            }
        )

        assertThat(payload?.sharedText).isEqualTo("shared context")
        assertThat(payload?.fileUris).containsExactly(streamUri)
    }

    @Test
    fun whitespaceOnlyTextWithoutStreamReturnsNull() {
        val payload = parser.parse(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "   ")
            }
        )

        assertThat(payload).isNull()
    }

    @Test
    fun multipleShareKeepsEveryUriAndOptionalText() {
        val firstUri = Uri.parse("content://share/first.txt")
        val secondUri = Uri.parse("content://share/second.txt")
        val payload = parser.parse(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    arrayListOf(firstUri, secondUri)
                )
                putExtra(Intent.EXTRA_TEXT, "  files  ")
            }
        )

        assertThat(payload?.sharedText).isEqualTo("files")
        assertThat(payload?.fileUris).containsExactly(firstUri, secondUri).inOrder()
    }
}
