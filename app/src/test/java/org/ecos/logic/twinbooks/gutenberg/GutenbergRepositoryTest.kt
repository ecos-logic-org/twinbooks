package org.ecos.logic.twinbooks.gutenberg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses real OPDS search responses saved from gutenberg.org (thumbnails stripped). */
class GutenbergRepositoryTest {

    private fun parse(resource: String, language: GutenbergLanguage): GutenbergPage =
        javaClass.getResourceAsStream("/gutenberg/$resource")!!.use {
            GutenbergRepository.parseSearchFeed(it, language)
        }

    @Test
    fun `books are read and non-book entries skipped`() {
        val page = parse("search-sherlock-en.xml", GutenbergLanguage.ENGLISH)
        // 26 entries: the first one is the "Subjects" sub-catalog, not a book
        assertEquals(25, page.books.size)
        assertTrue(page.books.none { it.title == "Subjects" })
        assertTrue(page.books.contains(GutenbergBook(244, "A Study in Scarlet", "Arthur Conan Doyle")))
        assertEquals(26, page.nextStartIndex)
    }

    @Test
    fun `language suffix is removed and last page has no next index`() {
        val page = parse("search-quijote-es.xml", GutenbergLanguage.SPANISH)
        assertEquals(GutenbergBook(2000, "Don Quijote", "Miguel de Cervantes Saavedra"), page.books.first())
        assertTrue(page.books.none { it.title.endsWith("(Spanish)") })
        assertNull(page.nextStartIndex)
    }

    @Test
    fun `cover url follows the Gutenberg cache layout`() {
        assertEquals(
            "https://www.gutenberg.org/cache/epub/1661/pg1661.cover.medium.jpg",
            GutenbergBook(1661, "The Adventures of Sherlock Holmes", "Arthur Conan Doyle").coverUrl
        )
    }
}
