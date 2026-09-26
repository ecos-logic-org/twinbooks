package org.ecos.logic.twinbooks.translation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * [OfflineTranslator] running Mozilla's open EN → ES translation model (the one behind
 * Firefox Translations) with slimt, fully on-device and without Google services.
 *
 * The model (~25 MB compressed) is downloaded on first use from Mozilla's public bucket,
 * checked against pinned SHA-256 hashes and kept in the app's private storage.
 */
class SlimtTranslator(context: Context) : OfflineTranslator {

    private val modelDir = File(context.filesDir, "translation/$MODEL_ID")
    private val mutex = Mutex()
    private var handle = 0L

    override suspend fun prepare(): Boolean = mutex.withLock {
        if (handle != 0L) return@withLock true
        withContext(Dispatchers.IO) {
            MODEL_FILES.forEach { ensureDownloaded(it) }
            handle = SlimtNative.nativeCreate(
                model = file(MODEL_FILES[0]).path,
                vocabulary = file(MODEL_FILES[1]).path,
                shortlist = file(MODEL_FILES[2]).path
            )
        }
        if (handle == 0L) Log.e(TAG, "slimt could not load the model in $modelDir")
        handle != 0L
    }

    override suspend fun translate(text: String): String = mutex.withLock {
        check(handle != 0L) { "prepare() was not called" }
        withContext(Dispatchers.Default) {
            val result = SlimtNative.nativeTranslate(handle, arrayOf(text.toByteArray()))
                ?: throw IOException("slimt failed to translate")
            result.first().decodeToString()
        }
    }

    override fun close() {
        // Called on shutdown: wait for an in-flight translation before freeing the engine
        if (!mutex.tryLock()) return
        try {
            if (handle != 0L) SlimtNative.nativeDestroy(handle)
            handle = 0L
        } finally {
            mutex.unlock()
        }
    }

    private fun file(model: ModelFile) = File(modelDir, model.name)

    /** Downloads, gunzips and verifies [model] unless a verified copy is already there. */
    private fun ensureDownloaded(model: ModelFile) {
        val target = file(model)
        if (target.length() == model.size) return

        modelDir.mkdirs()
        val partial = File(modelDir, "${model.name}.part")
        Log.d(TAG, "Downloading ${model.name}...")
        val connection = URL("$BASE_URL/${model.path}.gz").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} for ${model.name}")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            GZIPInputStream(connection.inputStream).use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != model.sha256) {
                partial.delete()
                throw IOException("Checksum mismatch for ${model.name}")
            }
            if (!partial.renameTo(target)) throw IOException("Could not store ${model.name}")
            Log.d(TAG, "${model.name} ready (${model.size} bytes)")
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    private class ModelFile(val name: String, val path: String, val size: Long, val sha256: String)

    private companion object {
        const val TAG = "SlimtTranslator"
        const val TIMEOUT_MS = 30_000

        // Mozilla Firefox Translations models (models.json manifest in the same bucket).
        // Bump MODEL_ID when switching model so the old files aren't reused.
        const val BASE_URL = "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data"
        const val MODEL_ID = "en-es-base-memory-1"
        const val RUN = "models/en-es/retrain_hr_fix_names_CUAEXUHoQum_cFqh-ZAryw/exported"

        // Order matters: model, vocabulary, shortlist
        val MODEL_FILES = listOf(
            ModelFile(
                "model.enes.intgemm.alphas.bin", "$RUN/model.enes.intgemm.alphas.bin", 31_561_787,
                "3b1c399511c01c84c36fae5c0524df44096288efdc8236e182b5c97d7ad2244c"
            ),
            ModelFile(
                "vocab.enes.spm", "$RUN/vocab.enes.spm", 816_054,
                "5ae254fa9b15aa182e70fd2a6186b1333c63a29a48043a9224c6aa4fcac058ad"
            ),
            ModelFile(
                "lex.50.50.enes.s2t.bin", "$RUN/lex.50.50.enes.s2t.bin", 4_198_436,
                "7d51237c0a07027dcd61643cfbbb0f8c48597d19907ef53d2cae9d6bec2cf25c"
            )
        )
    }
}
