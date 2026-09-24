package org.ecos.logic.twinbooks.alignment

import org.ecos.logic.twinbooks.embedding.EmbeddingManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Computes and manages sentence-level alignments between left (EN) and right (ES) chapters
 * using Dynamic Programming on embedding similarity matrix.
 */
class AlignmentManager(
    private val embeddingManager: EmbeddingManager
) {

    private val _isComputing = AtomicBoolean(false)
    val isComputing: Boolean
        get() = _isComputing.get()

    /**
     * Compute full sentence alignment for a chapter pair using DP.
     * Runs on IO thread, returns List of (leftSentenceIdx, rightSentenceIdx) pairs.
     */
    suspend fun computeAlignment(
        leftSentences: List<String>,
        rightSentences: List<String>,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): List<Pair<Int, Int>> {
        if (_isComputing.getAndSet(true)) {
            return emptyList()
        }

        return try {
            // Step 1: Compute all embeddings
            val leftEmbeddings = computeAllEmbeddings(leftSentences, "L", progressCallback)
            val rightEmbeddings = computeAllEmbeddings(rightSentences, "R", progressCallback)
            
            // Step 2: Build similarity matrix
            val similarityMatrix = buildSimilarityMatrix(leftEmbeddings, rightEmbeddings)
            
            // Step 3: DP alignment
            dpAlignment(similarityMatrix)
        } finally {
            _isComputing.set(false)
        }
    }

    private suspend fun computeAllEmbeddings(
        sentences: List<String>,
        prefix: String,
        progressCallback: ((Int, Int) -> Unit)?
    ): List<List<Float>> {
        val embeddings = mutableListOf<List<Float>>()
        val batchSize = 10
        
        for (i in sentences.indices.step(batchSize)) {
            val batch = sentences.subList(i, minOf(i + batchSize, sentences.size))
            val batchEmbeddings = mutableListOf<List<Float>>()
            
            for (sentence in batch) {
                val emb = embeddingManager.getEmbedding(sentence) ?: emptyList()
                batchEmbeddings.add(emb)
            }
            
            embeddings.addAll(batchEmbeddings)
            progressCallback?.invoke(embeddings.size, sentences.size)
        }
        
        return embeddings
    }

    private fun buildSimilarityMatrix(
        leftEmbeddings: List<List<Float>>,
        rightEmbeddings: List<List<Float>>
    ): Array<FloatArray> {
        val matrix = Array(leftEmbeddings.size) { FloatArray(rightEmbeddings.size) { 0f } }
        
        for (i in leftEmbeddings.indices) {
            val leftEmb = leftEmbeddings[i]
            if (leftEmb.isEmpty()) continue
            
            for (j in rightEmbeddings.indices) {
                val rightEmb = rightEmbeddings[j]
                if (rightEmb.isEmpty()) continue
                
                matrix[i][j] = embeddingManager.cosineSimilarity(leftEmb, rightEmb)
            }
        }
        
        return matrix
    }

    /**
     * Dynamic Programming alignment (Needleman-Wunsch for global alignment).
     */
    private fun dpAlignment(similarityMatrix: Array<FloatArray>): List<Pair<Int, Int>> {
        val n = similarityMatrix.size
        val m = similarityMatrix[0].size
        
        if (n == 0 || m == 0) return emptyList()
        
        val gapPenalty = -0.15f
        
        val dp = Array(n + 1) { FloatArray(m + 1) { -Float.MAX_VALUE } }
        val trace = Array(n + 1) { IntArray(m + 1) { 0 } }
        
        dp[0][0] = 0f
        for (i in 1..n) { dp[i][0] = i * gapPenalty; trace[i][0] = 1 }
        for (j in 1..m) { dp[0][j] = j * gapPenalty; trace[0][j] = 2 }
        
        for (i in 1..n) {
            for (j in 1..m) {
                val sim = similarityMatrix[i - 1][j - 1]
                
                var best = dp[i - 1][j - 1] + sim
                var bestDir = 0
                
                val skipLeft = dp[i - 1][j] + gapPenalty
                if (skipLeft > best) {
                    best = skipLeft
                    bestDir = 1
                }
                
                val skipRight = dp[i][j - 1] + gapPenalty
                if (skipRight > best) {
                    best = skipRight
                    bestDir = 2
                }
                
                dp[i][j] = best
                trace[i][j] = bestDir
            }
        }
        
        // Backtrack
        val alignment = mutableListOf<Pair<Int, Int>>()
        var i = n
        var j = m
        
        while (i > 0 || j > 0) {
            when (trace[i][j]) {
                0 -> { alignment.add(Pair(i - 1, j - 1)); i--; j-- }
                1 -> i--
                2 -> j--
            }
        }
        
        return alignment.reversed()
    }

    /**
     * Get right sentence index for a given left sentence index from alignment.
     */
    fun getRightSentenceIndex(alignment: List<Pair<Int, Int>>, leftIdx: Int): Int? {
        return alignment.firstOrNull { it.first == leftIdx }?.second
    }

    /**
     * Get left sentence index for a given right sentence index from alignment.
     */
    fun getLeftSentenceIndex(alignment: List<Pair<Int, Int>>, rightIdx: Int): Int? {
        return alignment.firstOrNull { it.second == rightIdx }?.first
    }
}