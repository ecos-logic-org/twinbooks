package org.ecos.logic.twinbooks.translation

/**
 * On-device EN → ES translation engine behind [TranslationManager].
 *
 * Implementations own the model lifecycle (download, load, release); the manager adds
 * caching and the "return the input unchanged when unavailable" contract on top.
 */
interface OfflineTranslator {

    /**
     * Get the engine ready, downloading the model if needed.
     * @return true when [translate] can be called.
     */
    suspend fun prepare(): Boolean

    /** Translate English [text] into Spanish. Throws if the engine fails. */
    suspend fun translate(text: String): String

    /** Release the model; [prepare] must be called again before translating. */
    fun close()
}
