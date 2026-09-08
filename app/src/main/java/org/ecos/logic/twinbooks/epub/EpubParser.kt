package org.ecos.logic.twinbooks.epub

import android.content.Context
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import io.documentnode.epub4j.epub.EpubReader
import org.ecos.logic.twinbooks.domain.model.BookContent
import org.ecos.logic.twinbooks.domain.model.Chapter
import java.io.InputStream
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

            val imageResources = mutableMapOf<String, Pair<String, ByteArray>>()
            for (resource in book.contents) {
                val mediaType = resource.mediaType?.toString() ?: continue
                if (mediaType.startsWith("image/")) {
                    imageResources[resource.href] = Pair(mediaType, resource.data)
                }
            }

            val chapters = spineReferences.mapIndexed { index, spineRef ->
                val resource = spineRef.resource
                var html = String(resource.data, Charsets.UTF_8)
                val chapterTitle = extractTitleFromHtml(html) ?: "Chapter ${index + 1}"

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
                totalChapters = chapters.size
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
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
