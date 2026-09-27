package org.ecos.logic.twinbooks.alignment.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSettingsStoreTest {

    private fun normalize(input: String) = ServerSettingsStore.normalizeBaseUrl(input)

    @Test
    fun `bare host gets https and the API path`() {
        assertEquals("https://twinbooks.example.org/api/v1/", normalize("twinbooks.example.org"))
        assertEquals("https://twinbooks.example.org/api/v1/", normalize("  twinbooks.example.org/ "))
    }

    @Test
    fun `port is kept`() {
        assertEquals("https://192.168.1.20:8443/api/v1/", normalize("https://192.168.1.20:8443"))
    }

    @Test
    fun `plain http is rejected`() {
        assertNull(normalize("http://192.168.1.20:8000"))
        assertNull(normalize("HTTP://twinbooks.example.org"))
        assertTrue(ServerSettingsStore.isInsecureUrl(" http://192.168.1.20:8000"))
        assertFalse(ServerSettingsStore.isInsecureUrl("twinbooks.example.org"))
    }

    @Test
    fun `explicit path is kept and gets a trailing slash`() {
        assertEquals("https://twinbooks.example.org/api/v1/", normalize("https://twinbooks.example.org/api/v1"))
        assertEquals("https://example.org/twinbooks/api/v1/", normalize("https://example.org/twinbooks/api/v1/"))
    }

    @Test
    fun `invalid input is rejected`() {
        assertNull(normalize(""))
        assertNull(normalize("   "))
        assertNull(normalize("ftp://example.org"))
        assertNull(normalize("https://"))
    }

    @Test
    fun `connect link with encoded address and key`() {
        val config = ServerSettingsStore.parseConnectLink(
            "twinbooks://connect?url=https%3A%2F%2Ftwinbooks.example.org&key=abc123"
        )
        assertEquals(ServerConfig("https://twinbooks.example.org/api/v1/", "abc123"), config)
    }

    @Test
    fun `connect link with plain address, extra params and no key`() {
        assertEquals(
            ServerConfig("https://192.168.1.20:8443/api/v1/", ""),
            ServerSettingsStore.parseConnectLink("twinbooks://connect/?url=https://192.168.1.20:8443&v=1")
        )
        assertEquals(
            ServerConfig("https://twinbooks.example.org/api/v1/", "k"),
            ServerSettingsStore.parseConnectLink(" TwinBooks://Connect?key=k&url=twinbooks.example.org ")
        )
    }

    @Test
    fun `not a connect link or no valid address`() {
        assertNull(ServerSettingsStore.parseConnectLink("https://twinbooks.example.org"))
        assertNull(ServerSettingsStore.parseConnectLink("twinbooks://connect"))
        assertNull(ServerSettingsStore.parseConnectLink("twinbooks://connect?key=abc"))
        assertNull(ServerSettingsStore.parseConnectLink("twinbooks://other?url=example.org"))
        assertNull(ServerSettingsStore.parseConnectLink("twinbooks://connect?url=http%3A%2F%2Fexample.org"))
    }
}
