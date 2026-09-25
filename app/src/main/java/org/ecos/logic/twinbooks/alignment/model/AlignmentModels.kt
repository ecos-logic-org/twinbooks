package org.ecos.logic.twinbooks.alignment.model

import com.squareup.moshi.Json
import kotlinx.serialization.Serializable

/**
 * Request for single chapter pair alignment (sync endpoint).
 */
@Serializable
data class ChapterAlignRequest(
    @Json(name = "left_sentences")
    val leftSentences: List<String>,
    
    @Json(name = "right_sentences")
    val rightSentences: List<String>,
    
    val method: String = "hungarian",
    
    @Json(name = "similarity_threshold")
    val similarityThreshold: Float = 0.3f,
    
    @Json(name = "gap_penalty")
    val gapPenalty: Float = -0.15f,
)

/**
 * A single aligned sentence pair.
 */
@Serializable
data class SentencePair(
    @Json(name = "left_idx")
    val leftIdx: Int,
    
    @Json(name = "right_idx")
    val rightIdx: Int,
    
    val score: Float,
    
    @Json(name = "left_text")
    val leftText: String,
    
    @Json(name = "right_text")
    val rightText: String,
)

/**
 * Response for single chapter alignment.
 */
@Serializable
data class ChapterAlignResponse(
    val alignment: List<SentencePair>,
    val method: String,
    
    @Json(name = "latency_ms")
    val latencyMs: Int,
    
    @Json(name = "left_count")
    val leftCount: Int,
    
    @Json(name = "right_count")
    val rightCount: Int,
)

/**
 * Response when submitting a full book alignment job.
 */
@Serializable
data class JobResponse(
    @Json(name = "job_id")
    val jobId: String,
    
    val status: String,
    
    @Json(name = "status_url")
    val statusUrl: String,
    
    @Json(name = "result_url")
    val resultUrl: String?,
    
    val message: String = "Job accepted for processing",
)

/**
 * Job status response.
 */
@Serializable
data class JobStatus(
    @Json(name = "job_id")
    val jobId: String,
    
    val status: String,
    
    val progress: Float,
    
    @Json(name = "result_url")
    val resultUrl: String?,
    
    val error: String?,
    
    @Json(name = "started_at")
    val startedAt: String?,
    
    @Json(name = "completed_at")
    val completedAt: String?,
)

/**
 * Chapter alignment result for full book.
 */
@Serializable
data class ChapterAlignmentResult(
    @Json(name = "left_chapter_idx")
    val leftChapterIdx: Int,
    
    @Json(name = "right_chapter_idx")
    val rightChapterIdx: Int,
    
    @Json(name = "sentence_pairs")
    val sentencePairs: List<SentencePair>,
)

/**
 * Complete alignment result for a book pair.
 */
@Serializable
data class AlignmentResult(
    @Json(name = "job_id")
    val jobId: String,
    
    @Json(name = "left_book_id")
    val leftBookId: String,
    
    @Json(name = "right_book_id")
    val rightBookId: String,
    
    @Json(name = "left_chapters")
    val leftChapters: Int,
    
    @Json(name = "right_chapters")
    val rightChapters: Int,
    
    @Json(name = "chapter_alignments")
    val chapterAlignments: List<ChapterAlignmentResult>,
    
    @Json(name = "created_at")
    val createdAt: String,
    
    val method: String,
    
    @Json(name = "model_used")
    val modelUsed: String,
)

/**
 * Health check response.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String = "0.1.0",
    
    @Json(name = "model_loaded")
    val modelLoaded: Boolean,
    
    @Json(name = "model_name")
    val modelName: String,
    
    val device: String,
    
    @Json(name = "queue_size")
    val queueSize: Int?,
)

/**
 * Result wrapper for alignment operations.
 */
sealed interface AlignmentResultWrapper<out T> {
    data class Success<T>(val data: T) : AlignmentResultWrapper<T>
    data class Error(val message: String, val code: Int?) : AlignmentResultWrapper<Nothing>
    
    companion object {
        fun <T> success(data: T) = Success(data)
        fun <T> error(message: String, code: Int? = null) = Error(message, code)
    }
}

/**
 * Local cached alignment (saved in Room).
 */
@Serializable
data class CachedAlignment(
    val jobId: String,
    val leftBookUri: String,
    val rightBookUri: String,
    val leftChapterIdx: Int,
    val rightChapterIdx: Int,
    val alignment: List<SentencePair>,
    val method: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Availability of the alignment server, as last observed by the app (health checks and
 * real alignment calls). Shown in the bookshelf and the reader.
 */
enum class ServerStatus {
    UNKNOWN,
    CHECKING,
    ONLINE,
    /** Not reachable (down, no network, timeout, 5xx) */
    OFFLINE,
    /** Reachable but rejects our X-API-Key (missing or wrong key in secrets.properties) */
    UNAUTHORIZED
}
