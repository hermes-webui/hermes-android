package com.hermeswebui.android

import android.content.res.Configuration
import com.hermeswebui.android.core.network.HardwareKeyboardState
import com.hermeswebui.android.core.network.LocalNetworkPolicy
import com.hermeswebui.android.core.network.LocalNetworkPolicy.PreflightDecision
import com.hermeswebui.android.data.HermesApiClient
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6 JVM extractions for issue #150: #47/#49 LAN preflight, #83 keyboard, #75 cookie, #61 fallback. */
class LocalNetworkPolicyTest {
    private fun local(host: String?) = LocalNetworkPolicy.isLikelyLocalNetworkHost(host) {
        // Literal addresses only, so tests never touch DNS.
        InetAddress.getByName(it)
    }

    @Test
    fun `localhost and mdns names are local`() {
        assertTrue(local("localhost"))
        assertTrue(local("LOCALHOST"))
        assertTrue(local("hermes.local"))
        assertTrue(local("  Hermes.LOCAL "))
    }

    @Test
    fun `private link-local and loopback addresses are local`() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.4.4", "169.254.10.1", "127.0.0.1", "[::1]", "[fe80::1]")
            .forEach { assertTrue("$it should be local", local(it)) }
    }

    @Test
    fun `public addresses and blank hosts are not local`() {
        assertFalse(local("8.8.8.8"))
        assertFalse(local("172.32.0.1"))
        assertFalse(local("[2001:4860:4860::8888]"))
        assertFalse(local(""))
        assertFalse(local("   "))
        assertFalse(local(null))
    }

    @Test
    fun `resolver failure or null result is not local`() {
        assertFalse(LocalNetworkPolicy.isLikelyLocalNetworkHost("nas.example.net") { null })
        assertFalse(LocalNetworkPolicy.isLikelyLocalNetworkHost("nas.example.net") { error("dns down") })
    }

    @Test
    fun `hostname resolving to a private address is local`() {
        val resolved = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 0, 9))
        assertTrue(LocalNetworkPolicy.isLikelyLocalNetworkHost("nas.example.net") { resolved })
    }

    @Test
    fun `preflight prompts only for a local host without the permission`() {
        assertEquals(PreflightDecision.REQUEST_PERMISSION, LocalNetworkPolicy.preflightDecision(true, false))
        assertEquals(PreflightDecision.PROCEED, LocalNetworkPolicy.preflightDecision(true, true))
        assertEquals(PreflightDecision.PROCEED, LocalNetworkPolicy.preflightDecision(false, false))
        assertEquals(PreflightDecision.PROCEED, LocalNetworkPolicy.preflightDecision(false, true))
    }

    @Test
    fun `hardware keyboard needs keys present and not hidden`() {
        assertTrue(
            HardwareKeyboardState.isAttached(
                Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO
            )
        )
        assertFalse(
            HardwareKeyboardState.isAttached(
                Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_NO
            )
        )
        assertFalse(
            HardwareKeyboardState.isAttached(
                Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_YES
            )
        )
        assertFalse(
            HardwareKeyboardState.isAttached(
                Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES
            )
        )
    }

    @Test
    fun `explicit session cookie wins and shared store is not read`() {
        var read = false
        val cookie = HermesApiClient.resolveSessionCookie("a=1") { read = true; "b=2" }
        assertEquals("a=1", cookie)
        assertFalse(read)
    }

    @Test
    fun `blank or missing explicit cookie falls back to the shared store`() {
        assertEquals("b=2", HermesApiClient.resolveSessionCookie(null) { "b=2" })
        assertEquals("b=2", HermesApiClient.resolveSessionCookie("  ") { "b=2" })
    }

    @Test
    fun `no usable cookie yields null so no header is sent`() {
        assertNull(HermesApiClient.resolveSessionCookie(null) { null })
        assertNull(HermesApiClient.resolveSessionCookie("") { " " })
    }

    @Test
    fun `status failures that fall back to the root page`() {
        listOf(401, 403, 404).forEach { assertTrue(HermesApiClient.shouldProbeRootAfterStatusFailure(it)) }
        listOf(200, 302, 400, 500, 503).forEach {
            assertFalse(HermesApiClient.shouldProbeRootAfterStatusFailure(it))
        }
    }
}
