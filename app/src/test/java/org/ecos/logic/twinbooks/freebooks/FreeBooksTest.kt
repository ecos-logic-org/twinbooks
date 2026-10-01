package org.ecos.logic.twinbooks.freebooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `file name is taken from content disposition as is`() {
        // Elejandría: unquoted name, served as application/octet-stream
        assertEquals("Mi_lucha_-Hitler_Adolf.epub",
            WebBookDownloader.fileNameFromDisposition("attachment; filename=Mi_lucha_-Hitler_Adolf.epub"))
        assertEquals("El principito.epub",
            WebBookDownloader.fileNameFromDisposition("attachment; filename=\"El principito.epub\""))
        assertEquals("Niño.epub",
            WebBookDownloader.fileNameFromDisposition("attachment; filename=\"x.epub\"; filename*=UTF-8''Ni%C3%B1o.epub"))
        assertEquals("libro.epub",
            WebBookDownloader.fileNameFromDisposition("attachment; filename=libro.epub; size=123"))
        assertNull(WebBookDownloader.fileNameFromDisposition("attachment"))
        assertNull(WebBookDownloader.fileNameFromDisposition(null))
    }

    @Test
    fun `public download name is safe and always an epub`() {
        assertEquals("Niebla - Miguel de Unamuno.epub", PublicDownloads.fileName("Niebla - Miguel de Unamuno"))
        assertEquals("Libro.epub", PublicDownloads.fileName("Libro.epub"))
        assertEquals("¿Qué_ A_B.epub", PublicDownloads.fileName("¿Qué? A/B"))
        assertEquals("libro.epub", PublicDownloads.fileName("  "))
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
