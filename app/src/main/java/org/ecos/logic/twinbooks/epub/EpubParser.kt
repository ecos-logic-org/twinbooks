package org.ecos.logic.twinbooks.epub

import android.content.Context
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import io.documentnode.epub4j.epub.EpubReader
import org.ecos.logic.twinbooks.domain.model.BookContent
import org.ecos.logic.twinbooks.domain.model.Chapter
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpubParser @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val epubReader = EpubReader()

    fun loadBook(uri: Uri): BookContent? {
        return try {
            val inputStream = getInputStream(uri) ?: return null
            val book = epubReader.readEpub(inputStream)
            inputStream.close()

            val title = book.metadata.firstTitle ?: "Untitled"
            val spine = book.spine
            val spineReferences = spine.spineReferences

            // Extract cover image as base64 data URI
            val coverImage = extractCoverImage(book)

            // Load TOC from NCX/NAV for proper chapter titles
            val tocMap = buildTocMap(book.tableOfContents.tocReferences, title)

            val imageResources = mutableMapOf<String, Pair<String, ByteArray>>()
            for (resource in book.contents) {
                val mediaType = resource.mediaType?.toString() ?: continue
                if (mediaType.startsWith("image/")) {
                    imageResources[resource.href] = Pair(mediaType, resource.data)
                }
            }

            val chapters = spineReferences.mapIndexed { index, spineRef ->
                val resource = spineRef.resource
                var html = String(resource.data, StandardCharsets.UTF_8)

                // Try to get title from TOC (NCX/NAV) first, fall back to HTML title
                val chapterTitle = getChapterTitle(
                    index = index,
                    spineHref = resource.href ?: "",
                    html = html,
                    tocMap = tocMap,
                    bookTitle = title
                )

                html = embedImagesAsDataUris(html, resource.href ?: "", imageResources)

                Chapter(
                    index = index,
                    title = chapterTitle,
                    htmlContent = html,
                    resourceHref = resource.href ?: ""
                )
            }

            BookContent(
                title = title,
                chapters = chapters,
                totalChapters = chapters.size,
                coverImage = coverImage
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Extracts the cover image from the EPUB as a base64 data URI.
     * Uses epub4j's cover image detection (looks for cover in metadata, then first image).
     */
    private fun extractCoverImage(book: io.documentnode.epub4j.domain.Book): String? {
        return try {
            // Try to get cover image from epub4j's built-in cover detection
            val coverResource = book.coverImage
            if (coverResource != null && coverResource.data.isNotEmpty()) {
                val mimeType = coverResource.mediaType?.toString() ?: "image/jpeg"
                val base64 = Base64.encodeToString(coverResource.data, Base64.NO_WRAP)
                "data:$mimeType;base64,$base64"
            } else {
                // Fallback: find first image resource that might be a cover
                // (often named "cover", "cover.jpg", etc.)
                val coverCandidate = book.contents.firstOrNull { resource ->
                    val mediaType = resource.mediaType?.toString() ?: ""
                    val href = resource.href?.lowercase() ?: ""
                    mediaType.startsWith("image/") && (
                        href.contains("cover") ||
                        href.contains("portada") ||
                        resource.id?.lowercase()?.contains("cover") == true
                    )
                }
                coverCandidate?.let { resource ->
                    val mimeType = resource.mediaType?.toString() ?: "image/jpeg"
                    val base64 = Base64.encodeToString(resource.data, Base64.NO_WRAP)
                    "data:$mimeType;base64,$base64"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Builds a map from normalized href to chapter title from the NCX/NAV table of contents.
     * Filters out entries that have the same title as the book (likely not real chapter titles).
     */
    private fun buildTocMap(tocReferences: List<io.documentnode.epub4j.domain.TOCReference>, bookTitle: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (tocRef in tocReferences) {
            val href = tocRef.completeHref ?: continue
            val tocTitle = tocRef.title?.trim() ?: continue
            // Skip entries that just have the book title (not useful chapter titles)
            if (tocTitle.equals(bookTitle, ignoreCase = true)) continue
            val normalizedHref = normalizeHref(href)
            if (normalizedHref.isNotEmpty()) {
                map[normalizedHref] = tocTitle
            }
        }
        return map
    }

    /**
     * Gets the best available chapter title:
     * 1. From TOC (NCX/NAV) matched by href
     * 2. From HTML <title> tag (if different from book title)
     * 3. Fallback to "Chapter N"
     */
    private fun getChapterTitle(
        index: Int,
        spineHref: String,
        html: String,
        tocMap: Map<String, String>,
        bookTitle: String
    ): String {
        // 1. Try to match by href in TOC map
        val normalizedSpineHref = normalizeHref(spineHref)
        if (normalizedSpineHref.isNotEmpty()) {
            tocMap[normalizedSpineHref]?.let { return it }
            // Also try matching without fragment
            val spineHrefNoFragment = normalizedSpineHref.substringBefore('#')
            if (spineHrefNoFragment.isNotEmpty()) {
                tocMap[spineHrefNoFragment]?.let { return it }
            }
        }

        // 2. Try HTML title tag
        val htmlTitle = extractTitleFromHtml(html)
        if (htmlTitle != null && !htmlTitle.equals(bookTitle, ignoreCase = true) && htmlTitle.isNotBlank()) {
            return htmlTitle
        }

        // 3. Fallback
        return "Chapter ${index + 1}"
    }

    /** Normalizes href by removing fragment and query, and resolving relative paths */
    private fun normalizeHref(href: String): String {
        var clean = href.substringBefore('#').substringBefore('?')
        // Handle relative paths like "OEBPS/chapter1.xhtml#section" -> "chapter1.xhtml"
        val lastSlash = clean.lastIndexOf('/')
        if (lastSlash >= 0) {
            clean = clean.substring(lastSlash + 1)
        }
        return clean
    }

    private fun embedImagesAsDataUris(
        html: String,
        baseHref: String,
        imageResources: Map<String, Pair<String, ByteArray>>
    ): String {
        val imgRegex = Regex("""<img\s[^>]*src=["']([^"']+)["'][^>]*/?>""", RegexOption.IGNORE_CASE)
        return imgRegex.replace(html) { match ->
            val fullMatch = match.value
            val src = match.groupValues[1]

            if (src.startsWith("data:")) return@replace fullMatch

            val resolvedHref = resolveRelativePath(baseHref, src)
            val resource = imageResources[resolvedHref]
                ?: imageResources[src]
                ?: return@replace fullMatch

            val (mimeType, data) = resource
            val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
            fullMatch.replace(src, "data:$mimeType;base64,$base64")
        }
    }

    private fun resolveRelativePath(baseHref: String, relativePath: String): String {
        val baseDir = baseHref.substringBeforeLast("/", "")
        val segments = mutableListOf<String>()

        if (baseDir.isNotEmpty()) {
            segments.addAll(baseDir.split("/"))
        }

        for (part in relativePath.split("/")) {
            when (part) {
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                ".", "" -> {}
                else -> segments.add(part)
            }
        }

        return segments.joinToString("/")
    }

    fun loadTableOfContents(uri: Uri): List<Pair<String, String>> {
        return try {
            val inputStream = getInputStream(uri) ?: return emptyList()
            val book = epubReader.readEpub(inputStream)
            inputStream.close()

            book.tableOfContents.tocReferences.map { tocRef ->
                tocRef.title to tocRef.completeHref
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun getInputStream(uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun extractTitleFromHtml(html: String): String? {
        val titleRegex = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
        val match = titleRegex.find(html)
        return match?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }
}
