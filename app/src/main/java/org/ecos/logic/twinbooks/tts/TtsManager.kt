package org.ecos.logic.twinbooks.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    var onSentenceComplete: (() -> Unit)? = null

    fun init() {
        if (isInitialized && tts != null) return
        tts?.shutdown()
        tts = null
        isInitialized = false
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TtsManager", "English language not supported")
                    isInitialized = false
                    return@TextToSpeech
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        onSentenceComplete?.invoke()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onSentenceComplete?.invoke()
                    }
                })
                isInitialized = true
            } else {
                Log.e("TtsManager", "TTS initialization failed with status: $status")
                isInitialized = false
            }
        }
    }

    fun speak(text: String, sentenceIndex: Int): Boolean {
        if (!isInitialized || tts == null) {
            init()
            return false
        }
        return try {
            val params = android.os.Bundle()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "sentence_$sentenceIndex") == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            Log.e("TtsManager", "Error speaking text", e)
            false
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e("TtsManager", "Error stopping TTS", e)
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e("TtsManager", "Error shutting down TTS", e)
        }
        tts = null
        isInitialized = false
    }

    fun isSpeaking(): Boolean = try {
        tts?.isSpeaking == true
    } catch (e: Exception) {
        false
    }
}
