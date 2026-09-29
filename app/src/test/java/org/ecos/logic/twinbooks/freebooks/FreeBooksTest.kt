package org.ecos.logic.twinbooks.freebooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeBooksTest {

    @Test
    fun `epub is recognised by mime type or file name`() {
        assertTrue(WebBookDownloader.looksLikeEpub("application/epub+zip", "download"))
        assertTrue(WebBookDownloader.looksLikeEpub("application/octet-stream", "un-escandalo-en-bohemia.epub"))
        assertTrue(WebBookDownloader.looksLikeEpub(null, "Libro.EPUB"))
        assertFalse(WebBookDownloader.looksLikeEpub("application/pdf", "libro.pdf"))
        assertFalse(WebBookDownloader.looksLikeEpub("application/x-mobipocket-ebook", "libro.azw3"))
    }

    @Test
    fun `site pages and subdomains stay in the app, other hosts do not`() {
        val site = FreeBooksSite.ELEJANDRIA
        assertTrue(site.owns("elejandria.com"))
        assertTrue(site.owns("www.elejandria.com"))
        assertTrue(site.owns("WWW.Elejandria.com"))
        assertFalse(site.owns("elejandria.com.evil.example"))
        assertFalse(site.owns("notelejandria.com"))
        assertFalse(site.owns("facebook.com"))
        assertFalse(site.owns(null))
    }
}
