package org.ecos.logic.twinbooks.freebooks

import org.ecos.logic.twinbooks.gutenberg.GutenbergLanguage

/**
 * Free, legal ebook websites without an open catalog API. They are browsed inside the app
 * (the user uses the site as any visitor would) and their EPUB downloads are captured.
 */
enum class FreeBooksSite(
    val title: String,
    val subtitle: String,
    val startUrl: String,
    /** Pages on this domain (or its subdomains) stay in the app; other links go to the browser */
    val domain: String,
    /** Language of the site's books, so only the reading options that make sense are offered */
    val language: GutenbergLanguage,
) {
    STANDARD_EBOOKS(
        title = "Standard Ebooks",
        subtitle = "Clásicos en inglés, EPUB de gran calidad",
        startUrl = "https://standardebooks.org/ebooks",
        domain = "standardebooks.org",
        language = GutenbergLanguage.ENGLISH,
    ),
    ELEJANDRIA(
        title = "Elejandría",
        subtitle = "Libros gratuitos en castellano, con traducciones de clásicos",
        startUrl = "https://www.elejandria.com/",
        domain = "elejandria.com",
        language = GutenbergLanguage.SPANISH,
    );

    fun owns(host: String?): Boolean =
        host != null && (host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true))
}
