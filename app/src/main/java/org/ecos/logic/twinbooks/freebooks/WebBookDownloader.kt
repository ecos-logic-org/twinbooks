package org.ecos.logic.twinbooks.freebooks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads an EPUB the user started from a page in the in-app browser, with that page's
 * cookies and User-Agent so the site sees the same visitor.
 */
@Singleton
class WebBookDownloader @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Saves [url] as [target], reporting progress 0..1 (-1 when the size is unknown).
     * Fails if what arrives isn't a ZIP container (an EPUB is one), e.g. an HTML error page.
     */
    suspend fun download(
        url: String,
        userAgent: String?,
        cookies: String?,
        referer: String?,
        target: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        try {
            val request = Request.Builder().url(url).apply {
                userAgent?.let { header("User-Agent", it) }
                cookies?.let { header("Cookie", it) }
                referer?.let { header("Referer", it) }
            }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
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
            if (!isZip(partial)) throw IOException("Not an EPUB")
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw IOException("Could not store ${target.name}")
        } finally {
            partial.delete()
        }
    }

    private fun isZip(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(4)
        input.read(header) == 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
    }

    companion object {
        /** Whether a download the page started is an EPUB (by MIME type or file name). */
        fun looksLikeEpub(mimeType: String?, fileName: String): Boolean =
            mimeType.equals("application/epub+zip", ignoreCase = true) ||
                fileName.endsWith(".epub", ignoreCase = true)

        private val ENCODED_FILENAME = Regex("""filename\*\s*=\s*(?:[\w-]+)?'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
        private val PLAIN_FILENAME = Regex("""filename\s*=\s*(?:"([^"]*)"|([^;]+))""", RegexOption.IGNORE_CASE)

        /**
         * The file name the server gave in Content-Disposition, as is. Unlike
         * URLUtil.guessFileName, it never swaps the extension to match the MIME type
         * (Elejandría sends `book.epub` as application/octet-stream, which became `book.bin`).
         */
        fun fileNameFromDisposition(contentDisposition: String?): String? {
            if (contentDisposition.isNullOrBlank()) return null
            ENCODED_FILENAME.find(contentDisposition)?.let { match ->
                val decoded = runCatching { java.net.URLDecoder.decode(match.groupValues[1].trim(), "UTF-8") }.getOrNull()
                if (!decoded.isNullOrBlank()) return decoded.substringAfterLast('/')
            }
            val match = PLAIN_FILENAME.find(contentDisposition) ?: return null
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
            return name.substringAfterLast('/').ifBlank { null }
        }
    }
}
