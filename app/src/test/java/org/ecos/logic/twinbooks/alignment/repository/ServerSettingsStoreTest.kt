package org.ecos.logic.twinbooks.alignment.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSettingsStoreTest {

    private fun normalize(input: String) = ServerSettingsStore.normalizeBaseUrl(input)

    @Test
    fun `bare host gets https and the API path`() {
        assertEquals("https://twinbooks.example.org/api/v1/", normalize("twinbooks.example.org"))
        assertEquals("https://twinbooks.example.org/api/v1/", normalize("  twinbooks.example.org/ "))
    }

    @Test
    fun `scheme and port are kept`() {
        assertEquals("http://192.168.1.20:8000/api/v1/", normalize("http://192.168.1.20:8000"))
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
}
