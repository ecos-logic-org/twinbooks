package org.ecos.logic.twinbooks.embedding

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.components.containers.Embedding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe Text Embedder wrapper for semantic similarity matching.
 * Uses MobileBERT (default) or Average Word Embedding model for on-device embeddings.
 */
@Singleton
class EmbeddingManager @Inject constructor(
    private val context: Context
) {

    private var textEmbedder: TextEmbedder? = null
    
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    
    private val _isInitializing = MutableStateFlow(false)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()
    
    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    // Cache for embeddings (LRU, max 100 entries)
    private val embeddingCache = mutableMapOf<String, List<Float>>()
    private val cacheMaxSize = 100

    /**
     * Initialize the Text Embedder with MobileBERT model.
     * Downloads/runs on background thread.
     */
    suspend fun initialize(useMobileBert: Boolean = true): Boolean {
        if (_isReady.value) return true
        if (_isInitializing.value) return false

        return withContext(Dispatchers.IO) {
            _isInitializing.value = true
            _initError.value = null
            Log.d("EmbeddingManager", "Initializing Text Embedder (MobileBERT=$useMobileBert)...")
            
            try {
                val modelName = if (useMobileBert) "mobile_bert.tflite" else "average_word.tflite"
                
                val options = TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath(modelName)
                            // Note: CPU delegate is default, no need to specify
                            .build()
                    )
                    .build()
                
                textEmbedder = TextEmbedder.createFromOptions(context, options)
                _isReady.value = true
                _isInitializing.value = false
                Log.d("EmbeddingManager", "Text Embedder ready with model: $modelName")
                true
            } catch (e: Exception) {
                Log.e("EmbeddingManager", "Failed to initialize Text Embedder", e)
                _isInitializing.value = false
                _isReady.value = false
                _initError.value = e.message ?: "Unknown error"
                false
            }
        }
    }

    /**
     * Get embedding vector for text. Uses cache if available.
     */
    suspend fun getEmbedding(text: String): List<Float>? {
        if (!_isReady.value || textEmbedder == null) {
            Log.w("EmbeddingManager", "Embedder not ready")
            return null
        }
        
        if (text.isBlank()) return emptyList()
        
        // Check cache
        val cacheKey = text.hashCode().toString()
        embeddingCache[cacheKey]?.let { return it }
        
        return withContext(Dispatchers.IO) {
            try {
                val result = textEmbedder?.embed(text) ?: return@withContext null
                // TextEmbedderResult has embeddingResult() which returns EmbeddingResult
                // EmbeddingResult has embeddings() which returns List<Embedding>
                // Each Embedding has floatEmbedding (FloatArray) or quantizedEmbedding (ByteArray)
                val embeddingResult = result.embeddingResult() ?: return@withContext null
                val embeddings = embeddingResult.embeddings() ?: return@withContext null
                if (embeddings.isEmpty()) return@withContext null
                
                val embedding = embeddings.first()  // Get first embedding head
                val floatArray = embedding.floatEmbedding()  // FloatArray
                
                if (floatArray == null) return@withContext null
                
                val embeddingList = floatArray.toList()
                
                // Update cache (LRU)
                if (embeddingCache.size >= cacheMaxSize) {
                    embeddingCache.keys.first()?.let { embeddingCache.remove(it) }
                }
                embeddingCache[cacheKey] = embeddingList
                
                embeddingList
            } catch (e: Exception) {
                Log.e("EmbeddingManager", "Failed to get embedding", e)
                null
            }
        }
    }

    /**
     * Compute cosine similarity between two embedding vectors.
     */
    fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size) return 0f
        
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        val denominator = Math.sqrt(normA.toDouble() * normB.toDouble())
        return if (denominator > 0) (dotProduct / denominator).toFloat() else 0f
    }

    /**
     * Find best matching paragraph using semantic embeddings.
     * 
     * @param sourceText English text to match
     * @param candidates List of Spanish paragraphs
     * @param startIndex Progress-based starting index
     * @param range Search window size (use candidates.size for global search)
     * @return Pair of (bestIndex, similarityScore)
     */
    suspend fun findBestMatch(
        sourceText: String,
        candidates: List<String>,
        startIndex: Int,
        range: Int
    ): Pair<Int, Float> {
        if (candidates.isEmpty()) return Pair(startIndex, 0f)
        if (sourceText.isBlank()) return Pair(startIndex, 0f)
        if (!_isReady.value) return Pair(startIndex, 0f)

        // Get source embedding
        val sourceEmbedding = getEmbedding(sourceText) ?: return Pair(startIndex, 0f)
        
        // Determine search range
        val searchStart = maxOf(0, startIndex - range)
        val searchEnd = minOf(candidates.size - 1, startIndex + range)
        
        var bestScore = -1f
        var bestIndex = startIndex
        
        // Compute embeddings and similarities for candidates in range
        for (i in searchStart..searchEnd) {
            val candidate = candidates[i]
            if (candidate.isBlank()) continue
            
            val candidateEmbedding = getEmbedding(candidate) ?: continue
            val similarity = cosineSimilarity(sourceEmbedding, candidateEmbedding)
            
            if (similarity > bestScore) {
                bestScore = similarity
                bestIndex = i
            }
            
            Log.d("EmbeddingManager", "  Candidate [$i]: similarity=${(similarity * 100).roundToInt()}%")
        }
        
        Log.d("EmbeddingManager", "Best match: index=$bestIndex, score=${(bestScore * 100).roundToInt()}%")
        return Pair(bestIndex, bestScore)
    }

    /**
     * Find best matching sentence within a paragraph using embeddings.
     */
    suspend fun findBestSentenceMatch(
        sourceSentence: String,
        candidateSentences: List<String>
    ): Pair<Int, Float> {
        if (candidateSentences.isEmpty()) return Pair(0, 0f)
        if (sourceSentence.isBlank()) return Pair(0, 0f)
        if (!_isReady.value) return Pair(0, 0f)

        val sourceEmbedding = getEmbedding(sourceSentence) ?: return Pair(0, 0f)
        
        var bestScore = -1f
        var bestIndex = 0
        
        for (i in candidateSentences.indices) {
            val candidate = candidateSentences[i]
            if (candidate.isBlank()) continue
            
            val candidateEmbedding = getEmbedding(candidate) ?: continue
            val similarity = cosineSimilarity(sourceEmbedding, candidateEmbedding)
            
            if (similarity > bestScore) {
                bestScore = similarity
                bestIndex = i
            }
        }
        
        return Pair(bestIndex, bestScore)
    }

    fun shutdown() {
        textEmbedder?.close()
        textEmbedder = null
        _isReady.value = false
        embeddingCache.clear()
    }
    
    companion object {
        const val MIN_SEMANTIC_SCORE = 0.3f // 30% minimum cosine similarity
    }
}