package com.hermeswebui.android

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.background.HermesDebugLoggingService
import com.hermeswebui.android.background.HermesReconnectService
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager = context.packageManager

    @Test
    fun settingsAndSessionDeepLinksResolveToMainActivity() {
        val settingsActivity = resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("hermes://app/settings"))
        )
        val sessionActivity = resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("hermes://session/session-123"))
        )

        assertThat(settingsActivity).isEqualTo(MainActivity::class.java.name)
        assertThat(sessionActivity).isEqualTo(MainActivity::class.java.name)
    }

    @Test
    fun unsupportedHermesDeepLinkDoesNotResolve() {
        assertThat(
            packageManager.resolveActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("hermes://unknown/path")).setPackage(context.packageName),
                PackageManager.MATCH_DEFAULT_ONLY
            )
        ).isNull()
    }

    @Test
    fun textAndMultipleFileSharesResolveToMainActivity() {
        val textShareActivity = resolveActivity(
            Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        )
        val multipleShareActivity = resolveActivity(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "application/octet-stream" }
        )

        assertThat(textShareActivity).isEqualTo(MainActivity::class.java.name)
        assertThat(multipleShareActivity).isEqualTo(MainActivity::class.java.name)
    }

    @Test
    fun internalServicesAndFileProviderAreNotExported() {
        val reconnectService = packageManager.getServiceInfo(
            ComponentName(context, HermesReconnectService::class.java),
            PackageManager.ComponentInfoFlags.of(0)
        )
        val debugService = packageManager.getServiceInfo(
            ComponentName(context, HermesDebugLoggingService::class.java),
            PackageManager.ComponentInfoFlags.of(0)
        )
        val provider = packageManager.getProviderInfo(
            ComponentName(context.packageName, "androidx.core.content.FileProvider"),
            PackageManager.ComponentInfoFlags.of(0)
        )

        assertThat(reconnectService.exported).isFalse()
        assertThat(debugService.exported).isFalse()
        assertThat(provider.exported).isFalse()
        assertThat(provider.grantUriPermissions).isTrue()
    }

    private fun resolveActivity(intent: Intent): String? {
        return packageManager.resolveActivity(
            intent.setPackage(context.packageName),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.name
    }
}
