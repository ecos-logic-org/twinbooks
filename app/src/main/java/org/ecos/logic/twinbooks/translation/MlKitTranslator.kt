package org.ecos.logic.twinbooks.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/** [OfflineTranslator] backed by Google ML Kit (model downloaded on Wi-Fi). */
class MlKitTranslator : OfflineTranslator {

    private var translator: Translator? = null

    override suspend fun prepare(): Boolean {
        val client = translator ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.SPANISH)
                .build()
        ).also { translator = it }

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()
        client.downloadModelIfNeeded(conditions).await()
        return true
    }

    override suspend fun translate(text: String): String {
        val client = checkNotNull(translator) { "prepare() was not called" }
        return client.translate(text).await()
    }

    override fun close() {
        translator?.close()
        translator = null
    }
}
