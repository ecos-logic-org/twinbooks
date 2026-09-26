package org.ecos.logic.twinbooks.translation

import android.util.Log
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * On-device EN → ES translation used when the server can't help: single-book mode
 * fallback, offline chapter alignment and sentence matching. The engine is pluggable
 * ([OfflineTranslator]); this class adds the cache and never throws: when translation
 * is unavailable [translate] returns its input unchanged.
 */
class TranslationManager(private val engine: OfflineTranslator) {

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private var isPreparing = false

    // Cache for translations to avoid re-translating the same text
    private val translationCache = mutableMapOf<String, String>()
    private val cacheMaxSize = 50

    /**
     * Get the engine ready (downloads the model if not already downloaded).
     */
    suspend fun initialize(): Boolean {
        if (_isReady.value) return true
        if (isPreparing) return false

        return try {
            isPreparing = true
            Log.d("TranslationManager", "Preparing on-device translator...")
            _isReady.value = engine.prepare()
            Log.d("TranslationManager", "On-device translator ready: ${_isReady.value}")
            _isReady.value
        } catch (e: Exception) {
            Log.e("TranslationManager", "Failed to initialize translator", e)
            _isReady.value = false
            false
        } finally {
            isPreparing = false
        }
    }

    /**
     * Translate text from English to Spanish
     */
    suspend fun translate(text: String, useCache: Boolean = true): String {
        // Return from cache if available
        if (useCache) translationCache[text]?.let { return it }
        
        if (!_isReady.value) {
            Log.d("TranslationManager", "Translator not ready, attempting initialization...")
            val initialized = initialize()
            if (!initialized) {
                Log.w("TranslationManager", "Could not initialize translator, returning original text")
                return text
            }
        }

        return try {
            val result = engine.translate(text)
            
            if (!useCache) return result
            // Cache the translation
            if (translationCache.size >= cacheMaxSize) {
                translationCache.remove(translationCache.keys.first())
            }
            translationCache[text] = result
            
            result
        } catch (e: Exception) {
            Log.e("TranslationManager", "Translation failed", e)
            text
        }
    }

    /**
     * Find the best matching paragraph using translation + word overlap analysis.
     * 
     * Strategy: Translate English → Spanish on-device, then find the Spanish book paragraph
     * with the most word overlap. Logs detailed comparison for debugging.
     * 
     * @param sourceText The English text to translate and match
     * @param candidates List of Spanish paragraphs to search in
     * @param startIndex The progress-based starting index
     * @param range How many paragraphs to search in each direction
     * @return Index of the best matching paragraph
     */
    suspend fun findBestMatch(
        sourceText: String,
        candidates: List<String>,
        startIndex: Int,
        range: Int = 10
    ): Int {
        if (candidates.isEmpty()) return startIndex
        if (sourceText.isBlank()) return startIndex

        // Translate the source text to Spanish
        val translatedText = translate(sourceText)
        Log.d("TranslationManager", "=== Word Overlap Analysis ===")
        Log.d("TranslationManager", "Source EN: '${sourceText.take(100)}'")
        Log.d("TranslationManager", "Translated ES: '${translatedText.take(100)}'")
        
        // Extract ALL words from the translation (lowercase, no punctuation)
        val translatedWords = translatedText.lowercase()
            .split(Regex("[\\s,;.:\"'!¡?¿()\\[\\]{}]+"))
            .filter { it.length > 1 }
            .toSet()
        Log.d("TranslationManager", "Translated words (${translatedWords.size}): $translatedWords")

        // Search within range
        val start = maxOf(0, startIndex - range)
        val end = minOf(candidates.size - 1, startIndex + range)

        var bestScore = -1f
        var bestIndex = startIndex
        val wordOverlapResults = mutableListOf<Triple<Int, Int, Float>>() // index, commonWords, overlapRatio

        for (i in start..end) {
            val candidate = candidates[i]
            
            // Extract ALL words from candidate paragraph
            val candidateWords = candidate.lowercase()
                .split(Regex("[\\s,;.:\"'!¡?¿()\\[\\]{}]+"))
                .filter { it.length > 1 }
                .toSet()
            
            // Calculate word overlap
            val commonWords = translatedWords.intersect(candidateWords)
            val overlapRatio = if (translatedWords.isNotEmpty() && candidateWords.isNotEmpty()) {
                commonWords.size.toFloat() / minOf(translatedWords.size, candidateWords.size)
            } else 0f
            
            wordOverlapResults.add(Triple(i, commonWords.size, overlapRatio))
            
            // Use overlap ratio as the score (higher = better match)
            if (overlapRatio > bestScore) {
                bestScore = overlapRatio
                bestIndex = i
            }
        }

        // Log all results sorted by overlap count
        val sortedResults = wordOverlapResults.sortedByDescending { it.second }
        Log.d("TranslationManager", "Top matches by word overlap:")
        sortedResults.take(5).forEach { (idx, common, ratio) ->
            val candidatePreview = candidates[idx].take(60)
            Log.d("TranslationManager", "  [$idx] $common words (${(ratio * 100).roundToInt()}% overlap): '$candidatePreview...'")
        }
        
        // Log the common words for the best match
        val bestCandidateWords = candidates[bestIndex].lowercase()
            .split(Regex("[\\s,;.:\"'!¡?¿()\\[\\]{}]+"))
            .filter { it.length > 1 }
            .toSet()
        val bestCommon = translatedWords.intersect(bestCandidateWords)
        Log.d("TranslationManager", "Best match [$bestIndex]: ${bestCommon.size} common words = $bestCommon")
        Log.d("TranslationManager", "Best match ratio: ${(bestScore * 100).roundToInt()}%")
        
        return bestIndex
    }

    fun shutdown() {
        engine.close()
        _isReady.value = false
        translationCache.clear()
    }
}
