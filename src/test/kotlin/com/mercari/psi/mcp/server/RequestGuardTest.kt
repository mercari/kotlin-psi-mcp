package com.mercari.psi.mcp.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security tests for the HTTP transport's authorization rule. The server exposes
 * code-modification tools, so it must only serve local, non-browser callers.
 */
class RequestGuardTest {

    @Test
    fun `native local client (loopback Host, no Origin) is allowed`() {
        assertTrue(RequestGuard.isAllowed("127.0.0.1:51234", null))
        assertTrue(RequestGuard.isAllowed("localhost:51234", null))
        assertTrue(RequestGuard.isAllowed("localhost", null)) // no port
        // Host names are case-insensitive.
        assertTrue(RequestGuard.isAllowed("LOCALHOST:51234", null))
        assertTrue(RequestGuard.isAllowed("LocalHost", null))
    }

    @Test
    fun `any Origin is rejected - no browser client is supported`() {
        // A drive-by / cross-origin page.
        assertFalse(RequestGuard.isAllowed("127.0.0.1:51234", "http://evil.com"))
        // Even a localhost-origin web page is rejected (stricter than an allowlist).
        assertFalse(RequestGuard.isAllowed("127.0.0.1:51234", "http://localhost:5173"))
        assertFalse(RequestGuard.isAllowed("127.0.0.1:51234", "https://127.0.0.1"))
        // Opaque / non-http origins are rejected too (no scheme parsing needed).
        assertFalse(RequestGuard.isAllowed("127.0.0.1:51234", "null"))
        assertFalse(RequestGuard.isAllowed("127.0.0.1:51234", "file://"))
    }

    @Test
    fun `foreign or missing Host is rejected - DNS-rebinding defense`() {
        assertFalse(RequestGuard.isAllowed("evil.com:51234", null))
        assertFalse(RequestGuard.isAllowed("attacker.example:80", null))
        // IPv6 loopback is NOT served: the connector binds 127.0.0.1 only.
        assertFalse(RequestGuard.isAllowed("[::1]:51234", null))
        assertFalse(RequestGuard.isAllowed(null, null))
        assertFalse(RequestGuard.isAllowed("", null))
        assertFalse(RequestGuard.isAllowed("   ", null))
    }

    @Test
    fun `hostOf strips port and IPv6 brackets`() {
        assertEquals("127.0.0.1", RequestGuard.hostOf("127.0.0.1:51234"))
        assertEquals("::1", RequestGuard.hostOf("[::1]:51234"))
        assertEquals("localhost", RequestGuard.hostOf("localhost"))
        assertNull(RequestGuard.hostOf(null))
    }
}
