package org.ecos.logic.twinbooks.domain.model

interface BookRepository {
    suspend fun loadBookFromUri(uri: String): BookContent?
    suspend fun getLatestSession(): ReadingSession?
    suspend fun getAllSessions(): List<ReadingSession>
    suspend fun saveSession(session: ReadingSession)
    suspend fun findSessionByLeftBook(bookUri: String): ReadingSession?
    suspend fun deleteSession(sessionId: Long)
}

data class ReadingSession(
    val id: Long = 0,
    val leftBookUri: String,
    val rightBookUri: String?,
    val leftTitle: String,
    val rightTitle: String?,
    val leftChapterIndex: Int = 0,
    val rightChapterIndex: Int = 0,
    val leftScrollOffset: Int = 0,
    val rightScrollOffset: Int = 0,
    val leftProgressPercent: Float = 0f,
    val rightProgressPercent: Float = 0f,
    val leftParagraphText: String = "",
    val rightParagraphText: String = "",
    val fontSize: Float = 12f,
    val isSynchronized: Boolean = false,
    val syncOffset: Int = 0,
    val ttsTimeLimitMinutes: Int = 0,
    val ttsBilingualMode: String = "OFF",
    val ttsSpeed: Float = 1.0f
)
