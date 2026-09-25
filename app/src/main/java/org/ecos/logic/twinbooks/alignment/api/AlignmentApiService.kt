package org.ecos.logic.twinbooks.alignment.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.ecos.logic.twinbooks.alignment.model.*
import retrofit2.Response
import retrofit2.http.*

interface AlignmentApiService {
    
    // --- Sync chapter alignment ---
    
    @POST("align/chapter")
    suspend fun alignChapter(
        @Body request: ChapterAlignRequest
    ): Response<ChapterAlignResponse>
    
    // --- Async full book alignment ---
    
    @Multipart
    @POST("align/book")
    suspend fun submitBookAlignment(
        @Part("left_book") leftBook: MultipartBody.Part,
        @Part("right_book") rightBook: MultipartBody.Part,
        @Part("left_lang") leftLang: RequestBody,
        @Part("right_lang") rightLang: RequestBody,
        @Part("priority") priority: RequestBody,
    ): Response<JobResponse>
    
    @GET("align/status/{jobId}")
    suspend fun getJobStatus(
        @Path("jobId") jobId: String
    ): Response<JobStatus>
    
    @GET("align/result/{jobId}")
    suspend fun getJobResult(
        @Path("jobId") jobId: String
    ): Response<AlignmentResult>
    
    // --- Paragraph translation (sentence by sentence) ---

    @POST("translate")
    suspend fun translate(
        @Body request: TranslateRequest
    ): Response<TranslateResponse>

    // --- Health check ---
    
    @GET("align/health")
    suspend fun healthCheck(): Response<HealthResponse>
}