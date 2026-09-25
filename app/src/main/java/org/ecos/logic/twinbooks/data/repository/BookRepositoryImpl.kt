package org.ecos.logic.twinbooks.data.repository

import android.net.Uri
import org.ecos.logic.twinbooks.data.local.ReadingSessionDao
import org.ecos.logic.twinbooks.data.local.ReadingSessionEntity
import org.ecos.logic.twinbooks.domain.model.BookContent
import org.ecos.logic.twinbooks.domain.model.BookRepository
import org.ecos.logic.twinbooks.domain.model.ReadingSession
import org.ecos.logic.twinbooks.epub.EpubParser
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val epubParser: EpubParser,
    private val readingSessionDao: ReadingSessionDao
) : BookRepository {

    override suspend fun loadBookFromUri(uri: String): BookContent? {
        return epubParser.loadBook(Uri.parse(uri))
    }

    override suspend fun getLatestSession(): ReadingSession? {
        return readingSessionDao.getLatestSession()?.toDomain()
    }

    override suspend fun getAllSessions(): List<ReadingSession> {
        return readingSessionDao.getAllSessions().map { it.toDomain() }
    }

    override suspend fun saveSession(session: ReadingSession) {
        val entity = session.toEntity()
        val existing = readingSessionDao.getSessionByLeftBook(session.leftBookUri)
        if (existing != null) {
            readingSessionDao.updateSession(entity.copy(id = existing.id))
        } else {
            readingSessionDao.insertSession(entity)
        }
    }

    override suspend fun findSessionByLeftBook(bookUri: String): ReadingSession? {
        return readingSessionDao.getSessionByLeftBook(bookUri)?.toDomain()
    }

    override suspend fun deleteSession(sessionId: Long) {
        readingSessionDao.deleteSession(sessionId)
    }

    private fun ReadingSessionEntity.toDomain() = ReadingSession(
        id = id,
        leftBookUri = leftBookUri,
        rightBookUri = rightBookUri,
        leftTitle = leftTitle,
        rightTitle = rightTitle,
        leftChapterIndex = leftChapterIndex,
        rightChapterIndex = rightChapterIndex,
        leftScrollOffset = leftScrollOffset,
        rightScrollOffset = rightScrollOffset,
        leftProgressPercent = leftProgressPercent,
        rightProgressPercent = rightProgressPercent,
        leftParagraphText = leftParagraphText,
        rightParagraphText = rightParagraphText,
        fontSize = fontSize,
        isSynchronized = isSynchronized,
        syncOffset = syncOffset,
        ttsTimeLimitMinutes = ttsTimeLimitMinutes,
        ttsBilingualMode = ttsBilingualMode,
        ttsSpeed = ttsSpeed,
        isSingleBookMode = isSingleBookMode,
        autoTranslationEnabled = autoTranslationEnabled,
        chapterOverrides = chapterOverrides
    )

    private fun ReadingSession.toEntity() = ReadingSessionEntity(
        id = id,
        leftBookUri = leftBookUri,
        rightBookUri = rightBookUri,
        leftTitle = leftTitle,
        rightTitle = rightTitle,
        leftChapterIndex = leftChapterIndex,
        rightChapterIndex = rightChapterIndex,
        leftScrollOffset = leftScrollOffset,
        rightScrollOffset = rightScrollOffset,
        leftProgressPercent = leftProgressPercent,
        rightProgressPercent = rightProgressPercent,
        leftParagraphText = leftParagraphText,
        rightParagraphText = rightParagraphText,
        fontSize = fontSize,
        isSynchronized = isSynchronized,
        syncOffset = syncOffset,
        ttsTimeLimitMinutes = ttsTimeLimitMinutes,
        ttsBilingualMode = ttsBilingualMode,
        ttsSpeed = ttsSpeed,
        isSingleBookMode = isSingleBookMode,
        autoTranslationEnabled = autoTranslationEnabled,
        chapterOverrides = chapterOverrides
    )
}
