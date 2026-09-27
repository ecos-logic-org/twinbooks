package org.ecos.logic.twinbooks.gutenberg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ecos.logic.twinbooks.BuildConfig
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

/** A book in the Project Gutenberg catalog. */
data class GutenbergBook(
    val id: Int,
    val title: String,
    val author: String,
) {
    val coverUrl: String get() = "${GutenbergRepository.BASE_URL}/cache/epub/$id/pg$id.cover.medium.jpg"
}

/** One page of search results; [nextStartIndex] is null on the last page. */
data class GutenbergPage(val books: List<GutenbergBook>, val nextStartIndex: Int?)

/** Languages offered in the search (Gutenberg's "l.xx" query filter). */
enum class GutenbergLanguage(val code: String, val gutenbergName: String) {
    ENGLISH("en", "English"),
    SPANISH("es", "Spanish"),
}

/**
 * Searches and downloads books from Project Gutenberg through its official OPDS catalog.
 * Every request is started by the user (no crawling), identified by a TwinBooks User-Agent
 * as Gutenberg asks of client software.
 */
@Singleton
class GutenbergRepository @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, language: GutenbergLanguage, startIndex: Int = 1): GutenbergPage =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/ebooks/search.opds/".toHttpUrl().newBuilder()
                .addQueryParameter("query", "${query.trim()} l.${language.code}")
                .apply { if (startIndex > 1) addQueryParameter("start_index", startIndex.toString()) }
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Gutenberg search HTTP ${response.code}")
                parseSearchFeed(response.body.byteStream(), language)
            }
        }

    /** Cover image bytes, or null if the book has none. */
    suspend fun cover(book: GutenbergBook): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(book.coverUrl).build()).execute().use { response ->
                if (response.isSuccessful) response.body.bytes() else null
            }
        }.getOrNull()
    }

    /**
     * Downloads the EPUB 3 (with images) of [book] into [target], reporting progress 0..1
     * (-1 when the size is unknown). A complete earlier download is reused.
     */
    suspend fun download(book: GutenbergBook, target: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            if (target.exists() && target.length() > 0) return@withContext
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            try {
                val request = Request.Builder().url("$BASE_URL/ebooks/${book.id}.epub3.images").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Gutenberg download HTTP ${response.code}")
                    val total = response.body.contentLength()
                    response.body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                onProgress(if (total > 0) done.toFloat() / total else -1f)
                            }
                        }
                    }
                }
                if (!partial.renameTo(target)) throw IOException("Could not store ${target.name}")
            } finally {
                partial.delete()
            }
        }

    companion object {
        const val BASE_URL = "https://www.gutenberg.org"
        private const val USER_AGENT =
            "TwinBooks/${BuildConfig.VERSION_NAME} (Android e-reader; +https://gitlab.com/ecos.logic.org/twinbooks)"
        private val BOOK_ID = Regex("/ebooks/(\\d+)\\.opds$")

        /**
         * Books in an OPDS search feed. Entries that aren't books (subject/author
         * sub-catalogs, sorting links) are skipped; non-English titles lose the
         * " (Spanish)" suffix Gutenberg adds.
         */
        fun parseSearchFeed(xml: InputStream, language: GutenbergLanguage): GutenbergPage {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val feed = factory.newDocumentBuilder().parse(xml).documentElement
            val languageSuffix = Regex("\\s*\\(${language.gutenbergName}\\)$")

            val books = feed.children("entry").mapNotNull { entry ->
                val id = BOOK_ID.find(entry.childText("id"))?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                GutenbergBook(
                    id = id,
                    title = entry.childText("title").replace(languageSuffix, "").trim(),
                    author = entry.childText("content").trim(),
                )
            }
            val next = feed.children("link").firstOrNull { it.getAttribute("rel") == "next" }
                ?.getAttribute("href")
                ?.let { Regex("start_index=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            return GutenbergPage(books, next)
        }

        private fun Element.children(name: String): List<Element> {
            val nodes = childNodes
            return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
                .filter { (it.localName ?: it.tagName) == name }
        }

        private fun Element.childText(name: String): String =
            children(name).firstOrNull()?.textContent.orEmpty()
    }
}
