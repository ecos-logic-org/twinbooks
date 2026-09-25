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
    private var isInitializing = false

    // Utterance requested while the engine wasn't ready (still connecting, or reconnecting
    // after the engine process died). Spoken as soon as the connection is up; otherwise the
    // sentence would be dropped and, with no onDone callback, the reading chain would stall.
    private var pendingUtterance: (() -> Unit)? = null
    // Reconnections in a row without a successful speak (guards against a broken engine)
    private var reconnectAttempts = 0

    var onSentenceComplete: ((String?) -> Unit)? = null

    fun init() {
        if (tts != null && (isInitialized || isInitializing)) return

        tts?.shutdown()
        tts = null
        isInitialized = false
        isInitializing = true

        tts = TextToSpeech(context) { status ->
            isInitializing = false
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TtsManager", "English language not supported")
                    isInitialized = false
                    pendingUtterance = null
                    return@TextToSpeech
                }

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        onSentenceComplete?.invoke(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onSentenceComplete?.invoke(utteranceId)
                    }
                })

                isInitialized = true
                pendingUtterance?.let {
                    pendingUtterance = null
                    it()
                }
            } else {
                Log.e("TtsManager", "TTS initialization failed with status: $status")
                isInitialized = false
                pendingUtterance = null
            }
        }
    }

    fun speak(text: String, sentenceIndex: Int, speed: Float = 1.0f): Boolean =
        speakWithRetry(retry = { speak(text, sentenceIndex, speed) }) {
            tts?.setLanguage(Locale.US)
            tts?.setSpeechRate(speed)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, android.os.Bundle(), "sentence_$sentenceIndex")
        }

    /**
     * Speak text in Spanish (for bilingual TTS mode).
     */
    fun speakSpanish(text: String, sentenceIndex: Int): Boolean =
        speakWithRetry(retry = { speakSpanish(text, sentenceIndex) }) {
            val langResult = tts?.setLanguage(Locale.forLanguageTag("es-ES"))
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("TtsManager", "Spanish not available, using default locale")
            }
            tts?.setSpeechRate(1.0f)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, android.os.Bundle(), "sentence_es_$sentenceIndex")
        }

    /**
     * Runs [speakCall] if the engine is ready, otherwise queues [retry] until it is. If the
     * engine is gone (its process died or was updated by Play Store while the app was open:
     * "speak failed: not bound to TTS engine"), reconnects once and retries.
     */
    private fun speakWithRetry(retry: () -> Unit, speakCall: () -> Int?): Boolean {
        if (!isInitialized || tts == null) {
            pendingUtterance = retry
            init()
            return true
        }
        return try {
            if (speakCall() == TextToSpeech.SUCCESS) {
                reconnectAttempts = 0
                true
            } else if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Log.e("TtsManager", "speak keeps failing after $reconnectAttempts reconnections, giving up")
                reconnectAttempts = 0
                false
            } else {
                reconnectAttempts++
                Log.w("TtsManager", "speak failed, reconnecting to the TTS engine and retrying")
                pendingUtterance = retry
                isInitialized = false
                tts?.shutdown()
                tts = null
                init()
                true
            }
        } catch (e: Exception) {
            Log.e("TtsManager", "Error speaking text", e)
            false
        }
    }

    fun stop() {
        pendingUtterance = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e("TtsManager", "Error stopping TTS", e)
        }
    }

    fun shutdown() {
        pendingUtterance = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e("TtsManager", "Error shutting down TTS", e)
        }
        tts = null
        isInitialized = false
        isInitializing = false
    }

    fun isSpeaking(): Boolean = try {
        tts?.isSpeaking == true
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 2
    }
}
