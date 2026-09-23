package org.ecos.logic.twinbooks.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ecos.logic.twinbooks.domain.model.BookContent
import org.ecos.logic.twinbooks.domain.model.BookRepository
import org.ecos.logic.twinbooks.domain.model.ReadingSession
import javax.inject.Inject

@HiltViewModel
class BookshelfViewModel @Inject constructor(
    private val bookRepository: BookRepository
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionWithCover>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private var pendingLeftBookUri: String? = null
    private var pendingRightBookUri: String? = null

    init {
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch {
            val sessions = bookRepository.getAllSessions()
            // Load cover images for each session (only left book for bookshelf)
            val sessionsWithCover = sessions.map { session ->
                val leftBook = bookRepository.loadBookFromUri(session.leftBookUri)
                SessionWithCover(
                    session = session,
                    leftCoverImage = leftBook?.coverImage
                )
            }
            _sessions.value = sessionsWithCover
        }
    }

    fun setLeftBookUri(uri: String) {
        pendingLeftBookUri = uri
    }

    fun setRightBookUri(uri: String) {
        pendingRightBookUri = uri
    }

    fun createNewPair() {
        viewModelScope.launch {
            pendingLeftBookUri?.let { leftUri ->
                val leftBook = bookRepository.loadBookFromUri(leftUri)
                val rightBook = pendingRightBookUri?.let { bookRepository.loadBookFromUri(it) }

                if (leftBook != null) {
                    val session = ReadingSession(
                        leftBookUri = leftUri,
                        rightBookUri = pendingRightBookUri,
                        leftTitle = leftBook.title,
                        rightTitle = rightBook?.title,
                        leftChapterIndex = 0,
                        rightChapterIndex = 0,
                        leftScrollOffset = 0,
                        rightScrollOffset = 0,
                        leftProgressPercent = 0f,
                        rightProgressPercent = 0f,
                        leftParagraphText = "",
                        rightParagraphText = "",
                        fontSize = 12f,
                        isSynchronized = false,
                        syncOffset = 0,
                        ttsTimeLimitMinutes = 0,
                        ttsBilingualMode = "OFF",
                        ttsSpeed = 1.0f
                    )
                    bookRepository.saveSession(session)
                    pendingLeftBookUri = null
                    pendingRightBookUri = null
                    loadSessions()
                }
            }
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            bookRepository.deleteSession(sessionId)
            loadSessions()
        }
    }
}

/**
 * ReadingSession with optional cover image for bookshelf display.
 * Cover is loaded on-demand to avoid database bloat.
 */
data class SessionWithCover(
    val session: ReadingSession,
    val leftCoverImage: String? = null
)