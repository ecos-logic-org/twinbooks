package org.ecos.logic.twinbooks.freebooks

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies downloaded books to the device's public Downloads folder (Download/TwinBooks), so the
 * user can find them with a file manager, add them again with "Añadir" and keep them after
 * uninstalling. The app keeps reading its own copy in filesDir/books. No permission is needed
 * (MediaStore, API 29+).
 */
@Singleton
class PublicDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Best effort: a failure here never fails the download itself. */
    suspend fun save(source: File, displayName: String) = withContext(Dispatchers.IO) {
        val name = fileName(displayName)
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        try {
            if (exists(name)) return@withContext
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, EPUB_MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return@withContext
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { it.copyTo(output) }
                } ?: error("No output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } catch (e: Exception) {
            Log.w("PublicDownloads", "Could not copy $name to Downloads: ${e.message}")
        }
    }

    /** Same book downloaded again: keep the copy already in Downloads instead of adding "name (1).epub". */
    private fun exists(name: String): Boolean = context.contentResolver.query(
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        arrayOf(MediaStore.Downloads._ID),
        "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
        arrayOf(name, RELATIVE_PATH),
        null,
    )?.use { it.count > 0 } ?: false

    companion object {
        private const val EPUB_MIME = "application/epub+zip"
        private val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/TwinBooks/"

        /** A file name safe for shared storage, always ending in .epub. */
        fun fileName(displayName: String): String {
            val cleaned = displayName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120)
                .ifBlank { "libro" }
            return if (cleaned.endsWith(".epub", ignoreCase = true)) cleaned else "$cleaned.epub"
        }
    }
}
