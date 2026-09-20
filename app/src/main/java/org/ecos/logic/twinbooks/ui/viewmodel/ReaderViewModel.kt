package org.ecos.logic.twinbooks.ui.viewmodel

import android.net.Uri
import android.util.Log
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
        val newSyncState = !_state.value.isSynchronized
        _state.update {
            it.copy(isSynchronized = newSyncState)
        }
        saveCurrentSession()
        
        // Run analysis when sync is activated
        if (newSyncState) {
            analyzeSyncQuality()
        }
    }

    fun toggleBottomBarVisibility() {
        _state.update { it.copy(isBottomBarVisible = !it.isBottomBarVisible) }
        saveCurrentSession()
    }

    fun updateLeftParagraphIndex(index: Int) {
        _state.update { it.copy(leftParagraphIndex = index) }
        
        // Analyze drift every 10 paragraph changes during sync
        if (_state.value.isSynchronized && index % 10 == 0 && index > 0) {
            analyzeSyncDrift()
        }
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

    // --- Sync Analysis Methods ---

    /**
     * Analyzes paragraph distribution in both books to understand sync quality.
     * Call this when sync is activated or when debugging sync issues.
     */
    fun analyzeSyncQuality() {
        val leftBook = _state.value.leftBook
        val rightBook = _state.value.rightBook
        
        if (leftBook == null || rightBook == null) {
            Log.d("SyncAnalysis", "Cannot analyze: both books must be loaded")
            return
        }

        val leftChapterIndex = _state.value.leftPosition.chapterIndex
        val rightChapterIndex = _state.value.rightPosition.chapterIndex
        
        val leftChapter = leftBook.chapters.getOrNull(leftChapterIndex)
        val rightChapter = rightBook.chapters.getOrNull(rightChapterIndex)
        
        if (leftChapter == null || rightChapter == null) {
            Log.d("SyncAnalysis", "Cannot analyze: chapter not found")
            return
        }

        // Count paragraphs in each chapter
        val leftParagraphs = countParagraphs(leftChapter.htmlContent)
        val rightParagraphs = countParagraphs(rightChapter.htmlContent)
        
        // Count short paragraphs (< 50 chars)
        val leftShort = countShortParagraphs(leftChapter.htmlContent, 50)
        val rightShort = countShortParagraphs(rightChapter.htmlContent, 50)
        
        // Calculate current positions as percentages
        val leftParagraphIndex = _state.value.leftParagraphIndex
        val rightParagraphIndex = _state.value.rightParagraphIndex
        
        val leftProgress = if (leftParagraphs > 0) {
            (leftParagraphIndex.toFloat() / leftParagraphs) * 100f
        } else 0f
        
        val rightProgress = if (rightParagraphs > 0) {
            (rightParagraphIndex.toFloat() / rightParagraphs) * 100f
        } else 0f

        Log.d("SyncAnalysis", "=== Sync Quality Analysis ===")
        Log.d("SyncAnalysis", "Chapter: Left=$leftChapterIndex, Right=$rightChapterIndex")
        Log.d("SyncAnalysis", "Paragraphs: Left=$leftParagraphs, Right=$rightParagraphs")
        Log.d("SyncAnalysis", "Short (<50 chars): Left=$leftShort, Right=$rightShort")
        Log.d("SyncAnalysis", "Current position: Left=$leftParagraphIndex, Right=$rightParagraphIndex")
        Log.d("SyncAnalysis", "Progress: Left=${"%.1f".format(leftProgress)}%, Right=${"%.1f".format(rightProgress)}%")
        Log.d("SyncAnalysis", "Sync offset: ${_state.value.syncOffset}")
        Log.d("SyncAnalysis", "Paragraph ratio: ${"%.2f".format(rightParagraphs.toFloat() / leftParagraphs)}")
        
        // Recommendation
        val shortPercent = ((leftShort + rightShort).toFloat() / (leftParagraphs + rightParagraphs)) * 100f
        if (shortPercent > 30) {
            Log.d("SyncAnalysis", "RECOMMENDATION: High short paragraph ratio (${shortPercent}%) - consider progress-based sync")
        } else if (leftParagraphs != rightParagraphs) {
            Log.d("SyncAnalysis", "RECOMMENDATION: Different paragraph counts - consider ratio-based sync")
        } else {
            Log.d("SyncAnalysis", "RECOMMENDATION: Current index-based sync may work")
        }
    }

    /**
     * Counts total paragraphs in HTML content
     */
    private fun countParagraphs(html: String): Int {
        val regex = Regex("<p[^>]*>", RegexOption.IGNORE_CASE)
        return regex.findAll(html).count()
    }

    /**
     * Counts paragraphs shorter than minChars characters
     */
    private fun countShortParagraphs(html: String, minChars: Int): Int {
        val paragraphRegex = Regex(
            "<p[^>]*>(.*?)</p>", 
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return paragraphRegex.findAll(html).count { match ->
            val text = match.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            text.length < minChars
        }
    }

    /**
     * Analyzes sync drift - how much the positions diverge over time
     */
    fun analyzeSyncDrift() {
        val leftProgress = _state.value.leftPosition.progressPercent
        val rightProgress = _state.value.rightPosition.progressPercent
        val drift = leftProgress - rightProgress
        
        Log.d("SyncDrift", "Left: ${"%.1f".format(leftProgress)}%, Right: ${"%.1f".format(rightProgress)}%, Drift: ${"%.1f".format(drift)}%")
        
        if (Math.abs(drift) > 5) {
            Log.w("SyncDrift", "WARNING: Significant drift detected (${drift}%)")
        }
    }
}
