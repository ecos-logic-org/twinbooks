package org.ecos.logic.twinbooks.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ecos.logic.twinbooks.domain.model.BookContent
import org.ecos.logic.twinbooks.domain.model.BookRepository
import org.ecos.logic.twinbooks.domain.model.ReadingPosition
import org.ecos.logic.twinbooks.domain.model.ReadingSession
import org.ecos.logic.twinbooks.domain.model.ReadingState
import org.ecos.logic.twinbooks.tts.TtsManager
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val ttsManager: TtsManager
) : ViewModel() {

    private val _state = MutableStateFlow(ReadingState())
    val state: StateFlow<ReadingState> = _state.asStateFlow()

    private var ttsTimerJob: Job? = null
    var ttsRefreshTrigger = 0
        private set

    init {
        restoreLatestSession()
        ttsManager.onSentenceComplete = { onTtsSentenceComplete() }
        ttsManager.init()
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown()
        ttsTimerJob?.cancel()
    }

    fun loadLeftBook(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val book = bookRepository.loadBookFromUri(uri.toString())
                if (book != null) {
                    val existingSession = bookRepository.findSessionByLeftBook(uri.toString())
                    val position = if (existingSession != null) {
                        ReadingPosition(
                            chapterIndex = existingSession.leftChapterIndex,
                            scrollOffset = existingSession.leftScrollOffset,
                            progressPercent = existingSession.leftProgressPercent,
                            paragraphText = existingSession.leftParagraphText
                        )
                    } else {
                        ReadingPosition()
                    }
                    _state.update {
                        it.copy(
                            leftBook = book,
                            leftBookUri = uri.toString(),
                            leftPosition = position,
                            isLoading = false
                        )
                    }
                    saveCurrentSession()
                } else {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load book"
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    fun loadRightBook(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val book = bookRepository.loadBookFromUri(uri.toString())
                if (book != null) {
                    _state.update {
                        it.copy(
                            rightBook = book,
                            rightBookUri = uri.toString(),
                            isLoading = false
                        )
                    }
                    saveCurrentSession()
                } else {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load book"
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    fun updateLeftPosition(chapterIndex: Int, scrollOffset: Int, paragraphText: String = "") {
        val leftBook = _state.value.leftBook ?: return
        val progress = calculateProgress(chapterIndex, scrollOffset, leftBook)
        _state.update {
            it.copy(
                leftPosition = ReadingPosition(
                    chapterIndex = chapterIndex,
                    scrollOffset = scrollOffset,
                    progressPercent = progress,
                    paragraphText = paragraphText
                )
            )
        }
        saveCurrentSession()
    }

    fun updateRightPosition(chapterIndex: Int, scrollOffset: Int, paragraphText: String = "") {
        val rightBook = _state.value.rightBook ?: return
        val progress = calculateProgress(chapterIndex, scrollOffset, rightBook)
        _state.update {
            it.copy(
                rightPosition = ReadingPosition(
                    chapterIndex = chapterIndex,
                    scrollOffset = scrollOffset,
                    progressPercent = progress,
                    paragraphText = paragraphText
                )
            )
        }
        saveCurrentSession()
    }

    fun navigateToChapter(isLeft: Boolean, chapterIndex: Int) {
        _state.update { state ->
            if (isLeft) {
                state.copy(leftPosition = state.leftPosition.copy(chapterIndex = chapterIndex, scrollOffset = 0, paragraphText = ""))
            } else {
                state.copy(rightPosition = state.rightPosition.copy(chapterIndex = chapterIndex, scrollOffset = 0, paragraphText = ""))
            }
        }
        saveCurrentSession()
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun increaseFontSize() {
        _state.update { it.copy(fontSize = (it.fontSize + 1f).coerceAtMost(24f)) }
        saveCurrentSession()
    }

    fun decreaseFontSize() {
        _state.update { it.copy(fontSize = (it.fontSize - 1f).coerceAtLeast(6f)) }
        saveCurrentSession()
    }

    fun resetBooks() {
        stopTts()
        _state.update {
            it.copy(
                leftBook = null,
                rightBook = null,
                leftBookUri = null,
                rightBookUri = null,
                leftPosition = ReadingPosition(),
                rightPosition = ReadingPosition(),
                isSynchronized = false,
                syncOffset = 0,
                leftParagraphIndex = -1,
                rightParagraphIndex = -1
            )
        }
    }

    fun onLeftParagraphDoubleClicked(paragraphIndex: Int) {
        _state.update { it.copy(leftParagraphIndex = paragraphIndex) }
    }

    fun onRightParagraphDoubleClicked(paragraphIndex: Int) {
        val leftIndex = _state.value.leftParagraphIndex
        if (leftIndex >= 0) {
            val offset = paragraphIndex - leftIndex
            _state.update {
                it.copy(
                    isSynchronized = true,
                    syncOffset = offset,
                    rightParagraphIndex = paragraphIndex
                )
            }
            saveCurrentSession()
        }
    }

    fun toggleSync() {
        _state.update {
            it.copy(isSynchronized = !it.isSynchronized)
        }
        saveCurrentSession()
    }

    fun toggleBottomBarVisibility() {
        _state.update { it.copy(isBottomBarVisible = !it.isBottomBarVisible) }
        saveCurrentSession()
    }

    fun updateLeftParagraphIndex(index: Int) {
        _state.update { it.copy(leftParagraphIndex = index) }
    }

    fun updateRightParagraphIndex(index: Int) {
        val currentState = _state.value
        if (currentState.isSynchronized) {
            val leftIndex = currentState.leftParagraphIndex
            if (leftIndex >= 0) {
                val newOffset = index - leftIndex
                _state.update { it.copy(rightParagraphIndex = index, syncOffset = newOffset) }
                saveCurrentSession()
                return
            }
        }
        _state.update { it.copy(rightParagraphIndex = index) }
    }

    // --- TTS Methods ---

    fun toggleTts() {
        if (_state.value.isTtsPlaying) {
            pauseTts()
        } else {
            startTts()
        }
    }

    private fun startTts() {
        if (_state.value.leftBook == null) return
        ttsManager.init()
        _state.update { it.copy(isTtsPlaying = true) }
        if (_state.value.ttsTimeLimitMinutes > 0) {
            startTtsTimer()
        }
        ttsRefreshTrigger++
    }

    private fun pauseTts() {
        ttsManager.stop()
        _state.update { it.copy(isTtsPlaying = false) }
        ttsTimerJob?.cancel()
    }

    fun stopTts() {
        ttsManager.stop()
        _state.update { it.copy(isTtsPlaying = false, ttsRemainingSeconds = 0) }
        ttsTimerJob?.cancel()
    }

    fun cycleTtsTimeLimit() {
        val current = _state.value.ttsTimeLimitMinutes
        val next = when (current) {
            0 -> 1
            1 -> 15
            15 -> 30
            30 -> 45
            else -> 0
        }
        _state.update { it.copy(ttsTimeLimitMinutes = next, ttsRemainingSeconds = 0) }
        ttsTimerJob?.cancel()
        if (next > 0 && _state.value.isTtsPlaying) {
            startTtsTimer()
        }
    }

    fun onTtsSentenceTextReceived(text: String) {
        if (!_state.value.isTtsPlaying) return
        ttsManager.speak(text, _state.value.leftSentenceIndex)
    }

    private fun onTtsSentenceComplete() {
        viewModelScope.launch {
            if (!_state.value.isTtsPlaying) return@launch
            val current = _state.value.leftSentenceIndex
            val nextIndex = current + 1
            if (nextIndex < _state.value.leftSentenceCount) {
                _state.update { it.copy(leftSentenceIndex = nextIndex) }
                ttsRefreshTrigger++
            } else {
                advanceTtsParagraph()
            }
        }
    }

    private fun advanceTtsParagraph() {
        _state.update { it.copy(leftSentenceIndex = 0, leftSentenceCount = 0) }
        _state.update { it.copy(ttsScrollToNextParagraphTrigger = it.ttsScrollToNextParagraphTrigger + 1) }
    }

    fun advanceTtsToNextChapter() {
        val leftBook = _state.value.leftBook ?: return
        val currentChapter = _state.value.leftPosition.chapterIndex
        val totalChapters = leftBook.totalChapters
        if (currentChapter < totalChapters - 1) {
            navigateToChapter(true, currentChapter + 1)
            _state.update { it.copy(leftSentenceIndex = 0, leftSentenceCount = 0) }
            viewModelScope.launch {
                delay(500)
                if (_state.value.isTtsPlaying) {
                    ttsRefreshTrigger++
                }
            }
        } else {
            stopTts()
        }
    }

    fun updateTtsSentenceIndex(index: Int) {
        _state.update { it.copy(leftSentenceIndex = index) }
    }

    fun updateTtsSentenceCount(count: Int) {
        val prev = _state.value.leftSentenceCount
        val currentIdx = _state.value.leftSentenceIndex
        _state.update {
            it.copy(
                leftSentenceCount = count,
                leftSentenceIndex = if (count > 0 && currentIdx == -1) 0 else currentIdx
            )
        }
        if (count > 0 && prev == 0 && _state.value.isTtsPlaying) {
            ttsRefreshTrigger++
        }
    }

    private fun startTtsTimer() {
        ttsTimerJob?.cancel()
        val totalSeconds = _state.value.ttsTimeLimitMinutes * 60L
        _state.update { it.copy(ttsRemainingSeconds = totalSeconds) }
        ttsTimerJob = viewModelScope.launch {
            var remaining = totalSeconds
            while (remaining > 0 && _state.value.isTtsPlaying) {
                delay(1000)
                remaining--
                _state.update { it.copy(ttsRemainingSeconds = remaining) }
            }
            if (remaining <= 0) {
                stopTts()
            }
        }
    }

    // --- Persistence Methods ---

    private fun calculateProgress(chapterIndex: Int, scrollOffset: Int, book: BookContent): Float {
        if (book.totalChapters == 0) return 0f
        val chapterProgress = chapterIndex.toFloat() / book.totalChapters
        return (chapterProgress * 100).coerceIn(0f, 100f)
    }

    private fun restoreLatestSession() {
        viewModelScope.launch {
            val session = bookRepository.getLatestSession() ?: return@launch
            _state.update { it.copy(isLoading = true) }

            val leftBook = bookRepository.loadBookFromUri(session.leftBookUri)
            val rightBook = session.rightBookUri?.let { bookRepository.loadBookFromUri(it) }

            _state.update {
                it.copy(
                    leftBook = leftBook,
                    rightBook = rightBook,
                    leftBookUri = session.leftBookUri,
                    rightBookUri = session.rightBookUri,
                    leftPosition = ReadingPosition(
                        chapterIndex = session.leftChapterIndex,
                        scrollOffset = session.leftScrollOffset,
                        progressPercent = session.leftProgressPercent,
                        paragraphText = session.leftParagraphText
                    ),
                    rightPosition = ReadingPosition(
                        chapterIndex = session.rightChapterIndex,
                        scrollOffset = session.rightScrollOffset,
                        progressPercent = session.rightProgressPercent,
                        paragraphText = session.rightParagraphText
                    ),
                    fontSize = session.fontSize,
                    isSynchronized = session.isSynchronized,
                    syncOffset = session.syncOffset,
                    isLoading = false
                )
            }
        }
    }

    private fun saveCurrentSession() {
        viewModelScope.launch {
            val s = _state.value
            val leftUri = s.leftBookUri ?: return@launch
            val leftTitle = s.leftBook?.title ?: return@launch

            val session = ReadingSession(
                leftBookUri = leftUri,
                rightBookUri = s.rightBookUri,
                leftTitle = leftTitle,
                rightTitle = s.rightBook?.title,
                leftChapterIndex = s.leftPosition.chapterIndex,
                rightChapterIndex = s.rightPosition.chapterIndex,
                leftScrollOffset = s.leftPosition.scrollOffset,
                rightScrollOffset = s.rightPosition.scrollOffset,
                leftProgressPercent = s.leftPosition.progressPercent,
                rightProgressPercent = s.rightPosition.progressPercent,
                leftParagraphText = s.leftPosition.paragraphText,
                rightParagraphText = s.rightPosition.paragraphText,
                fontSize = s.fontSize,
                isSynchronized = s.isSynchronized,
                syncOffset = s.syncOffset
            )
            bookRepository.saveSession(session)
        }
    }
}
