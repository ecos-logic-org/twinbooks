package org.ecos.logic.twinbooks.alignment.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import org.ecos.logic.twinbooks.alignment.api.AlignmentApiService
import org.ecos.logic.twinbooks.alignment.model.*
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlignmentRepository @Inject constructor(
    private val context: Context,
) {
    
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    
    private val apiService: AlignmentApiService = Retrofit.Builder()
        .baseUrl(AlignmentApiService.LOCAL_BASE_URL)
        .client(
            OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
                // LAN server: fail fast when away from home (local alignment takes over)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(AlignmentApiService::class.java)
    
    // --- Public API ---
    
    /**
     * Check if alignment server is available.
     */
    suspend fun checkHealth(): HealthResponse? {
        return try {
            val response = apiService.healthCheck()
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            Log.e("AlignmentRepository", "Health check failed", e)
            null
        }
    }
    
    /**
     * Align a single chapter pair (sync, fast).
     * Use for real-time TTS sentence matching.
     */
    suspend fun alignChapter(
        leftSentences: List<String>,
        rightSentences: List<String>,
        method: String = "dtw",
        similarityThreshold: Float = 0.3f,
        gapPenalty: Float = -0.15f,
    ): ChapterAlignResponse? {
        val request = ChapterAlignRequest(
            leftSentences = leftSentences,
            rightSentences = rightSentences,
            method = method,
            similarityThreshold = similarityThreshold,
            gapPenalty = gapPenalty,
        )
        
        return try {
            val response = apiService.alignChapter(request)
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            Log.e("AlignmentRepository", "Chapter alignment failed", e)
            null
        }
    }
    
    /**
     * Submit full book pair for async alignment.
     */
    suspend fun submitBookAlignment(
        leftBookUri: Uri,
        rightBookUri: Uri,
        leftLang: String = "en",
        rightLang: String = "es",
        priority: String = "normal",
    ): JobResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val leftFile = uriToFile(leftBookUri)
                val rightFile = uriToFile(rightBookUri)
                
                if (leftFile == null || rightFile == null) {
                    return@withContext null
                }
                
                val leftPart = MultipartBody.Part.createFormData(
                    "left_book",
                    leftFile.name,
                    leftFile.asRequestBody(EPUB_MEDIA_TYPE)
                )
                val rightPart = MultipartBody.Part.createFormData(
                    "right_book",
                    rightFile.name,
                    rightFile.asRequestBody(EPUB_MEDIA_TYPE)
                )
                
                val leftLangBody = leftLang.toRequestBody(TEXT_MEDIA_TYPE)
                val rightLangBody = rightLang.toRequestBody(TEXT_MEDIA_TYPE)
                val priorityBody = priority.toRequestBody(TEXT_MEDIA_TYPE)
                
                val response = apiService.submitBookAlignment(
                    leftPart, rightPart, leftLangBody, rightLangBody, priorityBody
                )
                
                if (response.isSuccessful) response.body() else null
            } catch (e: Exception) {
                Log.e("AlignmentRepository", "Submit book alignment failed", e)
                null
            }
        }
    }
    
    /**
     * Poll job status until completion or timeout.
     */
    suspend fun pollJobStatus(
        jobId: String,
        intervalMs: Long = 2000,
        timeoutMs: Long = 300000, // 5 minutes
        onProgress: ((Float) -> Unit)? = null,
    ): JobStatus? {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val status = checkJobStatus(jobId) ?: return null
            
            if (status.status == "completed" || status.status == "failed") {
                return status
            }
            
            onProgress?.invoke(status.progress)
            kotlinx.coroutines.delay(intervalMs)
        }
        
        return null
    }
    
    /**
     * Get job status once.
     */
    suspend fun checkJobStatus(jobId: String): JobStatus? {
        return try {
            val response = apiService.getJobStatus(jobId)
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            Log.e("AlignmentRepository", "Job status check failed", e)
            null
        }
    }
    
    /**
     * Get completed job result.
     */
    suspend fun getJobResult(jobId: String): AlignmentResult? {
        return try {
            val response = apiService.getJobResult(jobId)
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            Log.e("AlignmentRepository", "Get job result failed", e)
            null
        }
    }
    
    /**
     * Submit book and wait for completion (convenience method).
     */
    suspend fun submitAndWaitForAlignment(
        leftBookUri: Uri,
        rightBookUri: Uri,
        onProgress: ((Float) -> Unit)? = null,
    ): AlignmentResult? {
        val submitResult = submitBookAlignment(leftBookUri, rightBookUri) ?: return null
        
        val jobId = submitResult.jobId
        
        // Poll for completion
        val status = pollJobStatus(jobId) { progress ->
            onProgress?.invoke(progress)
        } ?: return null
        
        if (status.status == "completed") {
            return getJobResult(jobId)
        }
        
        return null
    }
    
    // --- Helpers ---
    
    private fun uriToFile(uri: Uri): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) return null
            
            val tempFile = File(context.cacheDir, "alignment_${System.currentTimeMillis()}.epub")
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            tempFile
        } catch (e: Exception) {
            Log.e("AlignmentRepository", "Failed to create temp file from URI", e)
            null
        }
    }
    
    // --- Configuration ---
    
    /**
     * Update base URL (e.g., for production deployment).
     */
    fun setBaseUrl(baseUrl: String) {
        // Would need to recreate Retrofit instance
        Log.w("AlignmentRepository", "Base URL change requires app restart: $baseUrl")
    }

    private companion object {
        val EPUB_MEDIA_TYPE = "application/epub+zip".toMediaType()
        val TEXT_MEDIA_TYPE = "text/plain".toMediaType()
    }
}
