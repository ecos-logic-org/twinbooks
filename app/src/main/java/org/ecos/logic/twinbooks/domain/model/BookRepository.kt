package org.ecos.logic.twinbooks.domain.model

interface BookRepository {
    suspend fun loadBookFromUri(uri: String): BookContent?
    suspend fun getLatestSession(): ReadingSession?
    suspend fun saveSession(session: ReadingSession)
    suspend fun findSessionByLeftBook(bookUri: String): ReadingSession?
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
    val rightParagraphText: String = ""
)
