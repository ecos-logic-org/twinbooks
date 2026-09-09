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
import org.ecos.logic.twinbooks.domain.model.TtsPlayState
import org.ecos.logic.twinbooks.domain.model.TtsSpeed
import org.ecos.logic.twinbooks.domain.model.TtsState
import org.ecos.logic.twinbooks.tts.TtsManager
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val ttsManager: TtsManager
) : ViewModel() {

    private val _state = MutableStateFlow(ReadingState())
    val state: StateFlow<ReadingState> = _state.asStateFlow()

    private var ttsSentences: List<String> = emptyList()
    private var ttsTimerJob: Job? = null
    private var ttsInitialized = false

    init {
        ttsManager.init(
            onSentenceCompleted = { onTtsSentenceDone() }
        )
        ttsInitialized = true
        restoreLatestSession()
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
                rightPosition = ReadingPosition()
            )
        }
    }

    // --- TTS Methods ---

    fun startTts(sentences: List<String>) {
        if (sentences.isEmpty()) return
        stopTts()
        ttsSentences = sentences
        _state.update {
            it.copy(ttsState = TtsState(
                playState = TtsPlayState.PLAYING,
                speed = it.ttsState.speed,
                timerMinutes = it.ttsState.timerMinutes,
                currentSentenceIndex = 0,
                totalSentences = sentences.size
            ))
        }
        speakCurrentSentence()
        startTimerIfNeeded()
    }

    fun pauseTts() {
        ttsManager.pause()
        _state.update {
            it.copy(ttsState = it.ttsState.copy(playState = TtsPlayState.PAUSED))
        }
        ttsTimerJob?.cancel()
    }

    fun resumeTts() {
        _state.update {
            it.copy(ttsState = it.ttsState.copy(playState = TtsPlayState.PLAYING))
        }
        speakCurrentSentence()
        startTimerIfNeeded()
    }

    fun stopTts() {
        ttsManager.stop()
        _state.update {
            it.copy(ttsState = TtsState(
                speed = it.ttsState.speed,
                timerMinutes = it.ttsState.timerMinutes
            ))
        }
        ttsTimerJob?.cancel()
        ttsSentences = emptyList()
    }

    fun toggleTtsPlayPause() {
        val currentState = _state.value.ttsState.playState
        when (currentState) {
            TtsPlayState.IDLE -> {}
            TtsPlayState.PLAYING -> pauseTts()
            TtsPlayState.PAUSED -> resumeTts()
        }
    }

    fun setTtsSpeed(speed: TtsSpeed) {
        _state.update {
            it.copy(ttsState = it.ttsState.copy(speed = speed))
        }
    }

    fun setTtsTimer(minutes: Int) {
        _state.update {
            it.copy(ttsState = it.ttsState.copy(timerMinutes = minutes))
        }
    }

    fun onTtsParagraphReady(newSentences: List<String>) {
        if (_state.value.ttsState.playState != TtsPlayState.PLAYING) return
        ttsSentences = newSentences
        _state.update {
            it.copy(ttsState = it.ttsState.copy(
                currentSentenceIndex = 0,
                totalSentences = newSentences.size
            ))
        }
        speakCurrentSentence()
    }

    fun needsNextParagraph(): Boolean {
        return _state.value.ttsState.playState == TtsPlayState.PLAYING
                && ttsSentences.isEmpty()
    }

    // --- Private TTS Methods ---

    private fun speakCurrentSentence() {
        val state = _state.value.ttsState
        if (state.playState != TtsPlayState.PLAYING) return
        val idx = state.currentSentenceIndex
        if (idx >= ttsSentences.size) return
        ttsManager.speak(
            text = ttsSentences[idx],
            utteranceId = "tts_$idx",
            speed = state.speed.value
        )
    }

    private fun onTtsSentenceDone() {
        val state = _state.value.ttsState
        if (state.playState != TtsPlayState.PLAYING) return

        val nextIdx = state.currentSentenceIndex + 1
        if (nextIdx < ttsSentences.size) {
            _state.update {
                it.copy(ttsState = it.ttsState.copy(currentSentenceIndex = nextIdx))
            }
            speakCurrentSentence()
        } else {
            ttsSentences = emptyList()
            _state.update {
                it.copy(ttsState = it.ttsState.copy(
                    currentSentenceIndex = 0,
                    totalSentences = 0
                ))
            }
        }
    }

    private fun startTimerIfNeeded() {
        ttsTimerJob?.cancel()
        val minutes = _state.value.ttsState.timerMinutes
        if (minutes <= 0) return
        _state.update {
            it.copy(ttsState = it.ttsState.copy(
                timerRemainingSec = minutes * 60,
                isTimerRunning = true
            ))
        }
        ttsTimerJob = viewModelScope.launch {
            while (_state.value.ttsState.timerRemainingSec > 0) {
                delay(1000)
                _state.update {
                    it.copy(ttsState = it.ttsState.copy(
                        timerRemainingSec = it.ttsState.timerRemainingSec - 1
                    ))
                }
            }
            stopTts()
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
                fontSize = s.fontSize
            )
            bookRepository.saveSession(session)
        }
    }
}
