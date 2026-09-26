package org.ecos.logic.twinbooks.translation

/**
 * JNI entry points of libtwinbooks_translate (app/src/main/cpp): slimt running a
 * Bergamot/Marian model. Book text crosses as standard UTF-8 byte arrays.
 */
internal object SlimtNative {

    init {
        System.loadLibrary("twinbooks_translate")
    }

    /** Loads the model package; returns an engine handle, or 0 on failure (see logcat). */
    external fun nativeCreate(model: String, vocabulary: String, shortlist: String): Long

    /** One output per input, same order; null if the engine failed. */
    external fun nativeTranslate(handle: Long, texts: Array<ByteArray>): Array<ByteArray>?

    external fun nativeDestroy(handle: Long)
}
