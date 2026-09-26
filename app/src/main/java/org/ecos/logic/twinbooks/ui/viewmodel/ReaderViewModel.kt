package org.ecos.logic.twinbooks.ui.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ecos.logic.twinbooks.alignment.ChapterMatcher
import org.ecos.logic.twinbooks.alignment.LexicalAligner
import org.ecos.logic.twinbooks.alignment.model.ChapterAlignResponse
import org.ecos.logic.twinbooks.alignment.model.ServerStatus
import org.ecos.logic.twinbooks.alignment.model.AlignmentResultWrapper
import org.ecos.logic.twinbooks.alignment.repository.AlignmentRepository
import org.ecos.logic.twinbooks.data.local.ChapterAlignmentDao
import org.ecos.logic.twinbooks.data.local.ChapterAlignmentEntity
import org.ecos.logic.twinbooks.domain.model.BookContent
import org.ecos.logic.twinbooks.domain.model.BookRepository
import org.ecos.logic.twinbooks.domain.model.ChapterPairingHint
import org.ecos.logic.twinbooks.domain.model.Chapter
import org.ecos.logic.twinbooks.domain.model.ReadingPosition
import org.ecos.logic.twinbooks.domain.model.ReadingSession
import org.ecos.logic.twinbooks.domain.model.ReadingState
import org.ecos.logic.twinbooks.domain.model.TtsBilingualMode
import org.ecos.logic.twinbooks.translation.TranslationManager
import org.ecos.logic.twinbooks.text.SentenceSplitter
import org.ecos.logic.twinbooks.tts.PlaybackBridge
import org.ecos.logic.twinbooks.tts.PlaybackCommand
import org.ecos.logic.twinbooks.tts.PlaybackInfo
import org.ecos.logic.twinbooks.tts.TtsManager
import javax.inject.Inject

/** Minimum cosine similarity for the server to accept a sentence pair */
private const val SERVER_ALIGNMENT_MIN_SIMILARITY = 0.3f
/** Single-book server translations kept in memory (current, next and a few recent paragraphs) */
private const val SERVER_TRANSLATION_CACHE_SIZE = 20
/** Back-off before trying the server translator again after a failure */
private const val SERVER_TRANSLATION_RETRY_MS = 5 * 60_000L
/** Paragraphs sent around the translated one so the server resolves grammatical gender */
private const val TRANSLATION_CONTEXT_BEFORE = 3
private const val TRANSLATION_CONTEXT_AFTER = 1
/** How far to look for non-empty neighbours (headings/images leave empty paragraphs) */
private const val TRANSLATION_CONTEXT_SCAN = 10

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val ttsManager: TtsManager,
    private val translationManager: TranslationManager,
    private val alignmentRepository: AlignmentRepository,
    private val chapterAlignmentDao: ChapterAlignmentDao,
    private val playbackBridge: PlaybackBridge
) : ViewModel() {

    private val _state = MutableStateFlow(ReadingState())
    val state: StateFlow<ReadingState> = _state.asStateFlow()

    private var ttsTimerJob: Job? = null
    var ttsRefreshTrigger = 0
        private set
    private var isBilingualPendingTranslation = false
    private var lastSpokenEnglishText = ""
    private var bilingualPhase = 0 // 0=off, tracks which phase in bilingual flow
    private var ttsExpectedSentenceIndex = -1 // Track which sentence index we expect from TTS-driven highlight
    private var ttsExpectedParagraphIndex = -1 // Track which paragraph we're in
    private var ttsExpectedRightParagraphIndex = -1 // Track expected right paragraph for sentence lookup
    // Hybrid sync state: global search first time, then local window around last match
    private var isFirstSyncInChapter = true
    private var lastKnownRightIndex = -1
    private var lastMatchScore = 0f
    private val SERVER_RETRY_MS = 5 * 60 * 1000L
    // Time for the WebView to settle on a new paragraph (it selects sentence 0 after ~50 ms)
    private val PARAGRAPH_SETTLE_MS = 250L
    private val HTML_TAG = Regex("<[^>]+>")
    private val PARAGRAPH_TAG = Regex("<p(?:\\s[^>]*)?>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val WHITESPACE = Regex("\\s+")
    private val MIN_SYNC_SCORE = 0.15f // 15% minimum word overlap to trust match (translation fallback)
    // Sentence alignment state (computed per chapter pair)
    private var currentSentenceAlignment: List<Pair<Int, Int>> = emptyList()
    private var currentLeftSentencesPerPara: List<Int> = emptyList()
    private var currentRightSentencesPerPara: List<Int> = emptyList()
    private var alignmentComputationJob: Job? = null
    // Chapter sentence lists pushed from the WebView (compromise.js is the single splitter).
    // Global index = sum(sentences per paragraph before para) + paragraph-local index.
    private var leftChapterSentencesByPara: List<List<String>> = emptyList()
    private var rightChapterSentencesByPara: List<List<String>> = emptyList()
    private var leftSentencesChapterIdx: Int = -1
    private var rightSentencesChapterIdx: Int = -1
    // Server alignment cache: one HTTP call per chapter pair instead of one per sentence
    private var chapterAlignmentCache: ChapterAlignResponse? = null
    private var chapterAlignmentCacheKey: String? = null
    private var currentAlignmentKey: String? = null
    // Lookup for the current chapter alignment: left global sentence -> right global sentences
    private var alignmentByLeft: Map<Int, List<Int>> = emptyMap()
    private var alignmentFromServer = false
    // Server negative cache: don't retry a chapter pair for a while after a failure
    private var serverFailedKey: String? = null
    private var serverFailedAt = 0L
    // Local (offline) chapter alignment: ML Kit translation + LexicalAligner
    private var localAlignmentJob: Job? = null
    private var localAlignmentJobKey: String? = null
    // Chapter map: left spine index -> right spine index (-1 = unmatched), see ChapterMatcher
    private var chapterMap: IntArray? = null
    private var chapterOverrides: Map<Int, Int> = emptyMap()
    private var chapterMapJob: Job? = null
    // Text length per left chapter (tags stripped), filled with the chapter map
    private var leftChapterLengths: IntArray? = null
    // Left chapters where the user dismissed the pairing suggestion (this screen only)
    private val dismissedPairingHints = mutableSetOf<Int>()
    // Sync state to restore when coming back from auto-translation to two books
    private var syncBeforeAutoTranslation = true
    // Callback to highlight the Spanish sentence(s) being spoken in the right book
    // (paragraph index, first and last paragraph-local sentence index)
    var onHighlightRightSentence: ((paragraphIndex: Int, firstSentence: Int, lastSentence: Int) -> Unit)? = null
    private var currentSessionId: Long = -1

    /** Availability of the alignment server (shown in the reader, drives the fallbacks) */
    val serverStatus: StateFlow<ServerStatus> = alignmentRepository.status
    private var serverMonitorJob: Job? = null

    fun loadSession(sessionId: Long) {
        currentSessionId = sessionId
        restoreSession(sessionId)
        startServerMonitor()
        ttsManager.onSentenceComplete = { utteranceId -> onTtsSentenceComplete(utteranceId) }
        ttsManager.init()
        
        // Initialize translation manager in background
        viewModelScope.launch {
            Log.d("ReaderViewModel", "Initializing translation manager...")
            val ready = translationManager.initialize()
            Log.d("ReaderViewModel", "Translation manager ready: $ready")
        }
    }

    private fun restoreSession(sessionId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val session = bookRepository.getAllSessions().firstOrNull { it.id == sessionId }
            if (session != null) {
                val leftBook = bookRepository.loadBookFromUri(session.leftBookUri)
                val rightBook = session.rightBookUri?.let { bookRepository.loadBookFromUri(it) }
                chapterOverrides = decodeChapterOverrides(session.chapterOverrides)

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
                        isSingleBookMode = session.isSingleBookMode,
                        autoTranslationEnabled = session.autoTranslationEnabled,
                        ttsTimeLimitMinutes = session.ttsTimeLimitMinutes,
                        ttsBilingualMode = try {
                            TtsBilingualMode.valueOf(session.ttsBilingualMode)
                        } catch (_: Exception) {
                            TtsBilingualMode.OFF
                        },
                        ttsSpeed = session.ttsSpeed,
                        isLoading = false
                    )
                }
                computeChapterMap()
            } else {
                _state.update {
                    it.copy(isLoading = false, sessionNotFound = true)
                }
            }
        }
    }

    // --- Lock screen / notification player (TtsPlaybackService) ---------------------
    // Active from the first play until stopTts(): while paused it stays, so reading can be
    // resumed from the lock screen.
    private var playbackActive = false
    private var coverSource: String? = null
    private var coverBitmap: Bitmap? = null

    init {
        viewModelScope.launch {
            ttsManager.currentUtterance.collect { if (playbackActive) publishPlayback() }
        }
        viewModelScope.launch {
            playbackBridge.commands.collect { command ->
                val playing = _state.value.isTtsPlaying
                when (command) {
                    PlaybackCommand.PLAY -> if (!playing) startTts()
                    PlaybackCommand.PAUSE -> if (playing) pauseTts()
                    PlaybackCommand.TOGGLE -> toggleTts()
                    PlaybackCommand.STOP -> stopTts()
                }
            }
        }
    }

    private fun publishPlayback() {
        if (!playbackActive) {
            playbackBridge.publish(PlaybackInfo())
            return
        }
        val s = _state.value
        playbackBridge.publish(
            PlaybackInfo(
                active = true,
                isPlaying = s.isTtsPlaying,
                bookTitle = s.leftBook?.title.orEmpty(),
                sentence = ttsManager.currentUtterance.value,
                cover = coverBitmap(s.leftBook?.coverImage)
            )
        )
    }

    /** Book cover (base64 data URI) as a small bitmap for the lock screen, cached. */
    private fun coverBitmap(dataUri: String?): Bitmap? {
        if (dataUri != coverSource) {
            coverSource = dataUri
            coverBitmap = dataUri?.let { uri ->
                try {
                    val bytes = Base64.decode(uri.substringAfter("base64,"), Base64.NO_WRAP)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } catch (e: Exception) {
                    Log.w("ReaderViewModel", "Cover could not be decoded for the lock screen", e)
                    null
                }
            }
        }
        return coverBitmap
    }

    override fun onCleared() {
        super.onCleared()
        playbackActive = false
        publishPlayback()
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
                    chapterOverrides = decodeChapterOverrides(existingSession?.chapterOverrides ?: "")
                    _state.update {
                        it.copy(
                            leftBook = book,
                            leftBookUri = uri.toString(),
                            leftPosition = position,
                            ttsTimeLimitMinutes = existingSession?.ttsTimeLimitMinutes ?: it.ttsTimeLimitMinutes,
                            ttsBilingualMode = try {
                                TtsBilingualMode.valueOf(existingSession?.ttsBilingualMode ?: "OFF")
                            } catch (_: Exception) { it.ttsBilingualMode },
                            ttsSpeed = existingSession?.ttsSpeed ?: it.ttsSpeed,
                            isLoading = false
                        )
                    }
                    computeChapterMap()
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
                    // A different right book invalidates manual chapter corrections
                    chapterOverrides = emptyMap()
                    computeChapterMap()
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

    /**
     * Load a SINGLE book in full-screen mode. There is no second book: the Spanish
     * side comes from the on-device ML Kit translator (see findMatchingSpanishSentence).
     */
    fun loadSingleBook(uri: Uri) {
        viewModelScope.launch {
            stopTts()
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
                    chapterOverrides = decodeChapterOverrides(existingSession?.chapterOverrides ?: "")
                    _state.update {
                        it.copy(
                            leftBook = book,
                            leftBookUri = uri.toString(),
                            leftPosition = position,
                            rightBook = null,
                            rightBookUri = null,
                            rightPosition = ReadingPosition(),
                            isSingleBookMode = true,
                            isSynchronized = false,
                            syncOffset = 0,
                            leftParagraphIndex = -1,
                            rightParagraphIndex = -1,
                            syncAnchorLeftIndex = -1,
                            syncAnchorRightIndex = -1,
                            ttsTimeLimitMinutes = existingSession?.ttsTimeLimitMinutes ?: it.ttsTimeLimitMinutes,
                            ttsBilingualMode = try {
                                TtsBilingualMode.valueOf(existingSession?.ttsBilingualMode ?: "OFF")
                            } catch (_: Exception) { it.ttsBilingualMode },
                            ttsSpeed = existingSession?.ttsSpeed ?: it.ttsSpeed,
                            isLoading = false
                        )
                    }
                    computeChapterMap()
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

    /** Leave single-book mode: back to the two-panel layout (right panel shows its picker). */
    fun exitSingleBookMode() {
        stopTts()
        val hasPair = _state.value.rightBook != null
        _state.update {
            it.copy(
                isSingleBookMode = false,
                isSynchronized = hasPair && syncBeforeAutoTranslation,
                syncOffset = 0,
                syncAnchorLeftIndex = -1,
                syncAnchorRightIndex = -1
            )
        }
        // The right book didn't follow while hidden: bring it to the mapped chapter
        mappedRightChapter(_state.value.leftPosition.chapterIndex)?.let { setChapter(false, it) }
        saveCurrentSession()
        updateChapterPairingHint()
    }

    /**
     * Pair mode → full-screen auto-translation of the left book, keeping the right book
     * in the session so [exitSingleBookMode] can come back to two books.
     */
    fun switchToAutoTranslation() {
        stopTts()
        syncBeforeAutoTranslation = _state.value.isSynchronized
        _state.update {
            it.copy(
                isSingleBookMode = true,
                autoTranslationEnabled = true,
                isSynchronized = false,
                syncAnchorLeftIndex = -1,
                syncAnchorRightIndex = -1
            )
        }
        saveCurrentSession()
        updateChapterPairingHint()
    }

    fun dismissChapterPairingHint() {
        dismissedPairingHints += _state.value.leftPosition.chapterIndex
        _state.update { it.copy(chapterPairingHint = null) }
    }

    /**
     * Suggest auto-translation when the current left chapter is real content without a
     * counterpart in the right book, and suggest going back once it has one again.
     */
    private fun updateChapterPairingHint() {
        val s = _state.value
        val left = s.leftPosition.chapterIndex
        val length = leftChapterLengths?.getOrNull(left) ?: 0
        val hint = when {
            s.rightBook == null || chapterMap == null -> null
            left in dismissedPairingHints -> null
            length < ChapterMatcher.MIN_CONTENT_LENGTH -> null
            !s.isSingleBookMode && mappedRightChapter(left) == null -> ChapterPairingHint.UNPAIRED
            s.isSingleBookMode && mappedRightChapter(left) != null -> ChapterPairingHint.PAIRED_AGAIN
            else -> null
        }
        if (hint != s.chapterPairingHint) _state.update { it.copy(chapterPairingHint = hint) }
    }

    fun toggleAutoTranslation() {
        _state.update { it.copy(autoTranslationEnabled = !it.autoTranslationEnabled) }
        saveCurrentSession()
    }

    fun updateLeftPosition(chapterIndex: Int, scrollOffset: Int, paragraphText: String = "") {
        val leftBook = _state.value.leftBook ?: return
        val chapterChanged = chapterIndex != _state.value.leftPosition.chapterIndex
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
        if (chapterChanged) updateChapterPairingHint()
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

    /**
     * Chapter chosen from a TOC. With sync ON, the left TOC moves the right book to the
     * mapped chapter, and choosing the right chapter by hand is remembered as a
     * correction of the chapter map (forced pair).
     */
    fun navigateToChapter(isLeft: Boolean, chapterIndex: Int) {
        setChapter(isLeft, chapterIndex)
        val s = _state.value
        if (!s.isSynchronized || s.isSingleBookMode || s.leftBook == null || s.rightBook == null) return
        if (isLeft) {
            mappedRightChapter(chapterIndex)?.let { setChapter(false, it) }
        } else {
            recordChapterOverride(s.leftPosition.chapterIndex, chapterIndex)
        }
    }

    private fun setChapter(isLeft: Boolean, chapterIndex: Int) {
        _state.update { state ->
            if (isLeft) {
                state.copy(
                    leftPosition = state.leftPosition.copy(chapterIndex = chapterIndex, scrollOffset = 0, paragraphText = ""),
                    // Clear anchor when changing chapter (anchor is only valid within same chapter pair)
                    syncAnchorLeftIndex = -1,
                    syncAnchorRightIndex = -1
                )
            } else {
                state.copy(
                    rightPosition = state.rightPosition.copy(chapterIndex = chapterIndex, scrollOffset = 0, paragraphText = ""),
                    syncAnchorLeftIndex = -1,
                    syncAnchorRightIndex = -1
                )
            }
        }
        saveCurrentSession()
        if (isLeft) updateChapterPairingHint()
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
                rightParagraphIndex = -1,
                syncAnchorLeftIndex = -1,
                syncAnchorRightIndex = -1,
                isSingleBookMode = false,
                autoTranslationEnabled = true
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
            it.copy(
                isSynchronized = newSyncState,
                // Clear anchor when toggling sync
                syncAnchorLeftIndex = if (!newSyncState) -1 else it.syncAnchorLeftIndex,
                syncAnchorRightIndex = if (!newSyncState) -1 else it.syncAnchorRightIndex
            )
        }
        saveCurrentSession()
        
        // Run analysis when sync is activated
        if (newSyncState) {
            analyzeSyncQuality()
        }
    }

    /**
     * Called when the LEFT WebView pushes its compromise.js sentence list for a chapter.
     * @param chapterIndex chapter the list belongs to (guards against stale pushes)
     * @param sentencesJson JSON array of arrays: [[sentences of para 0], [para 1], ...]
     */
    fun onLeftChapterSentences(chapterIndex: Int, sentencesJson: String) {
        val parsed = parseSentencesByParagraph(sentencesJson)
        if (parsed.isEmpty()) return
        leftChapterSentencesByPara = parsed
        leftSentencesChapterIdx = chapterIndex
        currentLeftSentencesPerPara = parsed.map { it.size }
        Log.d("SentenceSplit", "Left ch=$chapterIndex: ${parsed.size} paras, ${parsed.sumOf { it.size }} sentences")
        maybeStartLocalAlignment()
    }

    /**
     * Called when the RIGHT WebView pushes its compromise.js sentence list for a chapter.
     */
    fun onRightChapterSentences(chapterIndex: Int, sentencesJson: String) {
        val parsed = parseSentencesByParagraph(sentencesJson)
        if (parsed.isEmpty()) return
        rightChapterSentencesByPara = parsed
        rightSentencesChapterIdx = chapterIndex
        currentRightSentencesPerPara = parsed.map { it.size }
        Log.d("SentenceSplit", "Right ch=$chapterIndex: ${parsed.size} paras, ${parsed.sumOf { it.size }} sentences")
        maybeStartLocalAlignment()
    }

    private fun parseSentencesByParagraph(json: String): List<List<String>> {
        return try {
            val outer = org.json.JSONArray(json)
            (0 until outer.length()).map { i ->
                val inner = outer.getJSONArray(i)
                (0 until inner.length()).map { j -> inner.getString(j) }
            }
        } catch (e: Exception) {
            Log.w("SentenceSplit", "Failed to parse chapter sentences JSON", e)
            emptyList()
        }
    }

    /**
     * Retry server alignment for current paragraph.
     * Called when user presses "retry" button next to sync button.
     */
    fun retryServerAlignment() {
        viewModelScope.launch {
            _state.update { it.copy(isServerAligning = true) }
            serverFailedKey = null
            val s = _state.value
            val leftChapter = s.leftBook?.chapters?.getOrNull(s.leftPosition.chapterIndex)
            val rightChapter = s.rightBook?.chapters?.getOrNull(s.rightPosition.chapterIndex)

            // Force a fresh server alignment (bypasses the per-chapter cache)
            val response = if (!s.isSingleBookMode && leftChapter != null && rightChapter != null) {
                getChapterAlignment(leftChapter, rightChapter, forceRefresh = true)
            } else null

            if (response != null) {
                storeCurrentAlignment(response.alignment.map { it.leftIdx to it.rightIdx })
                Log.d("AlignmentRetry", "Server retry succeeded: ${response.alignment.size} pairs")
            } else {
                // No request was possible (sentences not ready) or it failed: refresh the status
                if (serverStatus.value != ServerStatus.UNAUTHORIZED) alignmentRepository.checkHealth()
                Log.w("AlignmentRetry", "Server retry failed: status=${serverStatus.value}")
            }
            _state.update { it.copy(isServerAligning = false) }
        }
    }

    /**
     * Keeps [serverStatus] fresh while a pair or a single book is open (the server goes down
     * now and then): every minute while it's down, every 5 minutes while it's up. When it
     * comes back, the failure cache is dropped and the current chapter is asked to the server
     * again (single-book mode: the ML Kit back-off is dropped instead).
     */
    private fun startServerMonitor() {
        serverMonitorJob?.cancel()
        serverMonitorJob = viewModelScope.launch {
            while (true) {
                val s = _state.value
                val usesServer = if (s.isSingleBookMode) s.leftBook != null else s.rightBook != null
                if (usesServer) {
                    val before = serverStatus.value
                    alignmentRepository.checkHealth()
                    if (before != ServerStatus.ONLINE && serverStatus.value == ServerStatus.ONLINE) {
                        Log.d("ServerStatus", "Server is back")
                        if (s.isSingleBookMode) {
                            serverTranslationRetryAt = 0L
                            _state.update { it.copy(isServerTranslationFailing = false) }
                        } else {
                            serverFailedKey = null
                            if (!alignmentFromServer) retryServerAlignment()
                        }
                    }
                }
                delay(if (serverStatus.value == ServerStatus.ONLINE) 5 * 60_000L else 60_000L)
            }
        }
    }

    fun toggleBottomBarVisibility() {
        _state.update { it.copy(isBottomBarVisible = !it.isBottomBarVisible) }
        saveCurrentSession()
    }

    fun updateLeftParagraphIndex(index: Int) {
        _state.update { it.copy(leftParagraphIndex = index) }

        // A <- / -> jump to another paragraph landed: select its first / last sentence.
        // Delayed so it runs after the WebView's own "select sentence 0 of the new paragraph".
        pendingLeftSentence?.let { (paragraph, selectLast) ->
            if (paragraph == index) {
                pendingLeftSentence = null
                viewModelScope.launch {
                    delay(PARAGRAPH_SETTLE_MS)
                    val count = _state.value.leftSentenceCount
                    selectLeftSentence(if (selectLast) (count - 1).coerceAtLeast(0) else 0)
                }
            }
        }
        
        // Analyze drift every 10 paragraph changes during sync
        if (_state.value.isSynchronized && index % 10 == 0 && index > 0) {
            analyzeSyncDrift()
        }
    }

    // --- Sentence navigation (<- / -> buttons) ---------------------------------------
    // Inside a paragraph they move one sentence; at its first / last sentence they jump to
    // the last sentence of the previous paragraph / first sentence of the next one.

    // Paragraph being jumped to and whether its LAST sentence must be selected on arrival
    private var pendingLeftSentence: Pair<Int, Boolean>? = null

    /** "->" on the left book. Returns the paragraph to scroll to when it leaves the current one. */
    fun nextLeftSentence(): Int? {
        val s = _state.value
        if (s.leftSentenceIndex < s.leftSentenceCount - 1) {
            selectLeftSentence(s.leftSentenceIndex + 1)
            return null
        }
        return adjacentParagraph(isLeft = true, from = s.leftParagraphIndex, step = 1)
            ?.also { pendingLeftSentence = it to false }
    }

    /** "<-" on the left book. Returns the paragraph to scroll to when it leaves the current one. */
    fun previousLeftSentence(): Int? {
        val s = _state.value
        if (s.leftSentenceIndex > 0) {
            selectLeftSentence(s.leftSentenceIndex - 1)
            return null
        }
        return adjacentParagraph(isLeft = true, from = s.leftParagraphIndex, step = -1)
            ?.also { pendingLeftSentence = it to true }
    }

    /**
     * Nearest paragraph in the [step] direction that has text: empty <p> used as scene
     * breaks are skipped. Null at the start/end of the chapter.
     */
    fun adjacentParagraph(isLeft: Boolean, from: Int, step: Int): Int? {
        if (from < 0) return null
        val paragraphs = webViewParagraphs(isLeft) ?: return (from + step).takeIf { it >= 0 }
        var i = from + step
        while (i in paragraphs.indices) {
            if (paragraphs[i].isNotBlank()) return i
            i += step
        }
        return null
    }

    private fun selectLeftSentence(index: Int) {
        _state.update { it.copy(leftSentenceIndex = index) }
        val s = _state.value
        if (s.isTtsPlaying && !s.isSingleBookMode) {
            // Continue reading from the chosen sentence: the WebView highlight reports its
            // text, which onTtsSentenceTextReceived accepts because it's now the expected one
            ttsManager.stop()
            bilingualPhase = 0
            isBilingualPendingTranslation = false
            ttsExpectedSentenceIndex = index
            ttsExpectedParagraphIndex = s.leftParagraphIndex
        }
    }

    fun updateRightParagraphIndex(index: Int, isManualScroll: Boolean = false) {
        val currentState = _state.value
        if (currentState.isSynchronized) {
            val leftIndex = currentState.leftParagraphIndex
            if (leftIndex >= 0) {
                // Only update anchor when user manually scrolls right book
                // Programmatic sync from left book should NOT update anchor
                if (isManualScroll) {
                    _state.update {
                        it.copy(
                            rightParagraphIndex = index,
                            syncAnchorLeftIndex = leftIndex,
                            syncAnchorRightIndex = index
                        )
                    }
                    Log.d("SyncTranslation", "Anchor updated (manual): Left=$leftIndex → Right=$index (offset=${index - leftIndex})")
                } else {
                    _state.update { it.copy(rightParagraphIndex = index) }
                    Log.d("SyncTranslation", "Right paragraph index updated (sync): Right=$index, anchor unchanged")
                }
                saveCurrentSession()
                return
            }
        }
        _state.update { it.copy(rightParagraphIndex = index) }
    }

    // --- Translation-based Sync ---

    /**
     * Find the best matching paragraph in the right book using translation
     * @param sourceText The English text from the left book
     * @param targetIndex The progress-based target index in the right book
     * @param candidates The list of Spanish paragraphs from the right book
     * @return The index of the best matching paragraph
     */
    suspend fun findBestMatchWithTranslation(
        sourceText: String,
        targetIndex: Int,
        candidates: List<String>
    ): Int {
        return translationManager.findBestMatch(
            sourceText = sourceText,
            candidates = candidates,
            startIndex = targetIndex,
            range = 5
        )
    }

    /**
     * Find and sync the best matching paragraph using translation
     * Called from ReaderScreen when sync is active
     */
    fun findAndSyncBestMatch(
        leftParagraphIndex: Int,
        onBestMatchFound: (Int) -> Unit
    ) {
        val currentState = _state.value
        val rightChapter = currentState.rightBook?.chapters?.getOrNull(currentState.rightPosition.chapterIndex)
        val leftChapter = currentState.leftBook?.chapters?.getOrNull(currentState.leftPosition.chapterIndex)

        if (rightChapter == null || leftChapter == null) {
            Log.d("SyncTranslation", "Cannot sync: rightChapter=${rightChapter != null}, leftChapter=${leftChapter != null}")
            return
        }

        // Count paragraphs in both chapters
        val leftParagraphs = webViewParagraphs(isLeft = true) ?: extractParagraphs(leftChapter.htmlContent)
        val rightParagraphs = webViewParagraphs(isLeft = false) ?: extractParagraphs(rightChapter.htmlContent)
        
        if (rightParagraphs.isEmpty()) {
            Log.d("SyncTranslation", "No paragraphs found in right chapter")
            return
        }

        val leftTotal = leftParagraphs.size
        val rightTotal = rightParagraphs.size

        // BEST: derive the paragraph from the chapter sentence alignment (server or local)
        alignedRightParagraph(leftParagraphIndex)?.let { rightPara ->
            Log.d("SyncTranslation", "Alignment sync: Left=$leftParagraphIndex → Right=$rightPara")
            onBestMatchFound(rightPara)
            return
        }

        // CHECK FOR ANCHOR: if user manually set a sync point, use it
        val anchorLeft = currentState.syncAnchorLeftIndex
        val anchorRight = currentState.syncAnchorRightIndex
        if (anchorLeft >= 0 && anchorRight >= 0) {
            // Calculate offset from anchor
            val offset = leftParagraphIndex - anchorLeft
            val targetIndex = (anchorRight + offset).coerceIn(0, rightTotal - 1)
            Log.d("SyncTranslation", "Using anchor: Left=$anchorLeft→Right=$anchorRight, now Left=$leftParagraphIndex → Right=$targetIndex (offset=$offset)")
            onBestMatchFound(targetIndex)
            return
        }

        // NO ANCHOR: try translation-based sync
        val paragraphText = leftParagraphs.getOrElse(leftParagraphIndex) { "" }
        if (paragraphText.isEmpty()) {
            val targetIndex = ((leftParagraphIndex.toFloat() / leftTotal) * rightTotal).toInt()
                .coerceIn(0, rightTotal - 1)
            Log.d("SyncTranslation", "No left text, progress sync: Left=$leftParagraphIndex/$leftTotal → Right=$targetIndex/$rightTotal")
            onBestMatchFound(targetIndex)
            return
        }

        if (!translationManager.isReady.value) {
            val targetIndex = ((leftParagraphIndex.toFloat() / leftTotal) * rightTotal).toInt()
                .coerceIn(0, rightTotal - 1)
            Log.d("SyncTranslation", "Translation not ready, progress sync: Left=$leftParagraphIndex/$leftTotal → Right=$targetIndex/$rightTotal")
            onBestMatchFound(targetIndex)
            return
        }

        // Translation-based sync
        val targetIndex = ((leftParagraphIndex.toFloat() / leftTotal) * rightTotal).toInt()
            .coerceIn(0, rightTotal - 1)

        viewModelScope.launch {
            Log.d("SyncTranslation", "Translation sync: Left=$leftParagraphIndex/$leftTotal, Target=$targetIndex, Candidates=$rightTotal")

            val bestIndex = translationManager.findBestMatch(
                sourceText = paragraphText,
                candidates = rightParagraphs,
                startIndex = targetIndex,
                range = 5
            )

            Log.d("SyncTranslation", "Result: Left=$leftParagraphIndex → Right=$bestIndex (target was $targetIndex)")
            onBestMatchFound(bestIndex)
        }
    }

    /**
     * Extract paragraphs from HTML content
     */
    private fun extractParagraphs(html: String): List<String> {
        // Indexed like the WebView (document.querySelectorAll('p')): empty <p> (scene
        // breaks) are KEPT, otherwise every index after one is shifted by one. "<p" must be
        // followed by whitespace or ">" so <pre>, <param> or SVG <path> don't count.
        return PARAGRAPH_TAG
            .findAll(html)
            .map { it.groupValues[1].replace(HTML_TAG, "").trim() }
            .toList()
    }

    /**
     * Paragraph texts of the current chapter as the WebView sees them (compromise.js push),
     * so indices are exactly the DOM ones. Null until that panel has pushed its list.
     */
    private fun webViewParagraphs(isLeft: Boolean): List<String>? {
        val s = _state.value
        return if (isLeft) {
            leftChapterSentencesByPara.takeIf { leftSentencesChapterIdx == s.leftPosition.chapterIndex && it.isNotEmpty() }
        } else {
            rightChapterSentencesByPara.takeIf { rightSentencesChapterIdx == s.rightPosition.chapterIndex && it.isNotEmpty() }
        }?.map { it.joinToString(" ").trim() }
    }

    /**
     * Extract sentences from text (rule-based, EN/ES, see [SentenceSplitter]).
     * Two-book mode: cold fallback when the WebView (compromise.js) list isn't ready yet.
     * Single-book mode: the splitter for both the English paragraph and its translation.
     */
    private fun extractSentences(text: String): List<String> = SentenceSplitter.split(text)

    /**
     * Find the best matching Spanish sentence from the synchronized right paragraph
     * Uses server-side alignment first (highest quality, async), then falls back to local DP, translation
     * @param englishSentence The English sentence being spoken
     * @param rightParaIndex Override for right paragraph index (use during paragraph transitions)
     * @return Pair of (spanishSentence, sentenceIndexInParagraph), or null if not found
     */
    /**
     * Spanish text to speak for an English sentence, plus where it lives in the right book:
     * paragraph [paragraphIndex], sentences [firstSentence]..[lastSentence] (paragraph-local,
     * compromise.js indices, the same ones the right WebView uses to highlight).
     */
    private data class SpanishMatch(
        val text: String,
        val paragraphIndex: Int,
        val firstSentence: Int,
        val lastSentence: Int
    )

    private suspend fun findMatchingSpanishSentence(
        englishSentence: String,
        rightParaIndex: Int? = null
    ): SpanishMatch? {
        val currentState = _state.value

        // NOTE: single-book mode never reaches this function: onTtsSentenceComplete
        // intercepts it first and runs the paragraph-level flow (speakSingleParagraph).
        val rightBook = currentState.rightBook ?: return null
        val rightChapter = rightBook.chapters.getOrNull(currentState.rightPosition.chapterIndex) ?: return null
        val leftChapter = currentState.leftBook?.chapters?.getOrNull(currentState.leftPosition.chapterIndex) ?: return null
        
        // Use provided index or fall back to synchronized right paragraph index
        val paraIndex = rightParaIndex ?: currentState.rightParagraphIndex
        if (paraIndex < 0) return null
        
        // Use the TTS-tracked left sentence index directly
        val leftSentenceIdx = currentState.leftSentenceIndex
        if (leftSentenceIdx < 0) return null
        
        // Extract paragraphs from right chapter (cold fallback if the WebView list isn't ready)
        val rightParagraphs = extractParagraphs(rightChapter.htmlContent)

        // Spanish candidates: compromise.js list pushed by the right WebView.
        // Same splitting as the TTS index space, the server alignment input and the offsets.
        val bridgeRightSentences = if (rightSentencesChapterIdx == currentState.rightPosition.chapterIndex) {
            rightChapterSentencesByPara.getOrNull(paraIndex)
        } else null
        val spanishSentences = when {
            !bridgeRightSentences.isNullOrEmpty() -> bridgeRightSentences
            paraIndex < rightParagraphs.size -> extractSentences(rightParagraphs[paraIndex])
            else -> return null
        }
        if (spanishSentences.isEmpty()) return null
        
        // If only one sentence, return it with index 0
        if (spanishSentences.size == 1) return SpanishMatch(spanishSentences[0], paraIndex, 0, 0)

        // Alignment indices are CHAPTER-GLOBAL while TTS indices are PARAGRAPH-LOCAL.
        // Convert using per-paragraph offsets derived from the same compromise lists.
        val leftParaIndex = currentState.leftParagraphIndex
        val sentencesReady = leftSentencesChapterIdx == currentState.leftPosition.chapterIndex &&
                rightSentencesChapterIdx == currentState.rightPosition.chapterIndex &&
                currentLeftSentencesPerPara.isNotEmpty() &&
                currentRightSentencesPerPara.isNotEmpty() &&
                leftParaIndex >= 0

        if (sentencesReady) {
            val leftGlobalIdx = currentLeftSentencesPerPara.take(leftParaIndex).sum() + leftSentenceIdx
            val rightSentencesBeforePara = currentRightSentencesPerPara.take(paraIndex).sum()

            // STRATEGY 1: refresh from the server (one HTTP call per chapter pair, cached;
            // skipped for a while after a failure so TTS doesn't stall away from home)
            try {
                getChapterAlignment(leftChapter, rightChapter)
            } catch (e: Exception) {
                Log.w("TtsBilingual", "Server alignment exception, using local alignment", e)
            }

            // STRATEGY 2: chapter alignment (server result if it answered, otherwise the
            // offline ML Kit + LexicalAligner one). Same global index space in both cases.
            if (currentAlignmentKey == alignmentCacheKey(leftChapter, rightChapter)) {
                val localRight = alignmentByLeft[leftGlobalIdx].orEmpty()
                    .map { it - rightSentencesBeforePara }
                    .filter { it in spanishSentences.indices }
                if (localRight.isNotEmpty()) {
                    // 1:2 alignments speak (and highlight) both Spanish sentences
                    val source = if (alignmentFromServer) "Server" else "Local"
                    Log.d("TtsBilingual", "$source alignment: leftGlobal=$leftGlobalIdx → local=$localRight")
                    return SpanishMatch(
                        localRight.joinToString(" ") { spanishSentences[it] },
                        paraIndex, localRight.min(), localRight.max()
                    )
                } else {
                    Log.w("TtsBilingual", "Alignment has no pair inside right paragraph $paraIndex for leftGlobal=$leftGlobalIdx")
                }
            }
        }
        
        // STRATEGY 3: Translation + Word Overlap fallback
        val bestIndex = translationManager.findBestMatch(
            sourceText = englishSentence,
            candidates = spanishSentences,
            startIndex = 0,
            range = spanishSentences.size
        )
        return if (bestIndex >= 0 && bestIndex < spanishSentences.size) {
            SpanishMatch(spanishSentences[bestIndex], paraIndex, bestIndex, bestIndex)
        } else {
            SpanishMatch(spanishSentences[0], paraIndex, 0, 0) // fallback to first sentence
        }
    }

    /**
     * Push a translation to the left WebView so it renders inline below the current
     * paragraph (single-book mode). [paraIdx] is informational: the JS targets the
     * currently highlighted paragraph.
     */
    private fun pushInlineTranslation(paraIdx: Int, text: String) {
        _state.update {
            it.copy(
                inlineTranslationTrigger = it.inlineTranslationTrigger + 1,
                inlineTranslationSentenceIdx = paraIdx,
                inlineTranslationText = text
            )
        }
    }

    /**
     * Cache key for a chapter pair's alignment. Includes sentence totals so a new
     * compromise.js push with a different split invalidates the cache automatically.
     */
    private fun alignmentCacheKey(leftChapter: Chapter, rightChapter: Chapter): String =
        "${leftChapter.index}:${rightChapter.index}:${currentLeftSentencesPerPara.sum()}:${currentRightSentencesPerPara.sum()}"

    /**
     * Get chapter-level sentence alignment from the server, cached per chapter pair.
     * Input sentences come from the WebView bridge (compromise.js), so global indices
     * match the paragraph-local TTS indices via: global = offset(para) + local.
     */
    private suspend fun getChapterAlignment(
        leftChapter: Chapter,
        rightChapter: Chapter,
        forceRefresh: Boolean = false
    ): ChapterAlignResponse? {
        val key = alignmentCacheKey(leftChapter, rightChapter)
        if (!forceRefresh) {
            chapterAlignmentCache?.let { if (chapterAlignmentCacheKey == key) return it }
            if (serverFailedKey == key && System.currentTimeMillis() - serverFailedAt < SERVER_RETRY_MS) return null
        }

        val leftSentences = leftChapterSentencesByPara.flatten()
        val rightSentences = rightChapterSentencesByPara.flatten()
        if (leftSentences.isEmpty() || rightSentences.isEmpty()) {
            Log.w("AlignmentCache", "Chapter sentences not ready yet (left=${leftSentences.size}, right=${rightSentences.size})")
            return null
        }

        val response = alignmentRepository.alignChapter(
            leftSentences = leftSentences,
            rightSentences = rightSentences,
            method = "dtw",
            similarityThreshold = SERVER_ALIGNMENT_MIN_SIMILARITY,
        )
        if (response != null) {
            chapterAlignmentCache = response
            chapterAlignmentCacheKey = key
            setSentenceAlignment(response.alignment.map { Pair(it.leftIdx, it.rightIdx) }, key, fromServer = true)
            Log.d("AlignmentCache", "Chapter aligned & cached: ${response.alignment.size} pairs (key=$key)")
        } else {
            serverFailedKey = key
            serverFailedAt = System.currentTimeMillis()
        }
        return response
    }

    private fun setSentenceAlignment(pairs: List<Pair<Int, Int>>, key: String, fromServer: Boolean) {
        currentSentenceAlignment = pairs
        currentAlignmentKey = key
        alignmentFromServer = fromServer
        alignmentByLeft = pairs.groupBy({ it.first }, { it.second })
        if (_state.value.isSynchronized) {
            _state.update { it.copy(resyncRightTrigger = it.resyncRightTrigger + 1) }
        }
    }

    /**
     * Offline alignment of the current chapter pair, once both WebViews pushed their
     * compromise.js sentences: left sentences are translated with ML Kit, then aligned
     * with LexicalAligner. The result is stored in Room, so each chapter pair is computed
     * once. A server alignment, when available, takes precedence.
     */
    private fun maybeStartLocalAlignment() {
        val s = _state.value
        if (s.isSingleBookMode) return
        val leftChapterIdx = s.leftPosition.chapterIndex
        val rightChapterIdx = s.rightPosition.chapterIndex
        val leftChapter = s.leftBook?.chapters?.getOrNull(leftChapterIdx) ?: return
        val rightChapter = s.rightBook?.chapters?.getOrNull(rightChapterIdx) ?: return
        val leftUri = s.leftBookUri ?: return
        val rightUri = s.rightBookUri ?: return
        if (leftSentencesChapterIdx != leftChapterIdx || rightSentencesChapterIdx != rightChapterIdx) return

        val key = alignmentCacheKey(leftChapter, rightChapter)
        if (currentAlignmentKey == key && currentSentenceAlignment.isNotEmpty()) return
        if (localAlignmentJobKey == key && localAlignmentJob?.isActive == true) return

        val leftSentences = leftChapterSentencesByPara.flatten()
        val rightSentences = rightChapterSentencesByPara.flatten()
        if (leftSentences.isEmpty() || rightSentences.isEmpty()) return

        localAlignmentJob?.cancel()
        localAlignmentJobKey = key
        localAlignmentJob = viewModelScope.launch {
            try {
                val stored = chapterAlignmentDao.get(leftUri, rightUri, leftChapterIdx, rightChapterIdx)
                val pairs = if (stored != null &&
                    stored.leftCount == leftSentences.size && stored.rightCount == rightSentences.size
                ) {
                    Log.d("LocalAlignment", "Loaded stored alignment for ch $leftChapterIdx↔$rightChapterIdx")
                    decodePairs(stored.pairs)
                } else {
                    // Server first (better model); if it's down, the offline ML Kit + lexical DP.
                    // Either result is stored, so the chapter stays aligned when the server isn't.
                    val serverPairs = if (serverStatus.value != ServerStatus.OFFLINE &&
                        serverStatus.value != ServerStatus.UNAUTHORIZED
                    ) {
                        getChapterAlignment(leftChapter, rightChapter)?.alignment?.map { it.leftIdx to it.rightIdx }
                    } else null
                    if (serverPairs != null) {
                        storeCurrentAlignment(serverPairs)
                        Log.d("LocalAlignment", "Chapter $leftChapterIdx↔$rightChapterIdx aligned by the server: ${serverPairs.size} pairs")
                        return@launch
                    }
                    computeLocalAlignment(leftSentences, rightSentences)?.also { computed ->
                        chapterAlignmentDao.upsert(
                            ChapterAlignmentEntity(
                                leftBookUri = leftUri,
                                rightBookUri = rightUri,
                                leftChapterIndex = leftChapterIdx,
                                rightChapterIndex = rightChapterIdx,
                                leftCount = leftSentences.size,
                                rightCount = rightSentences.size,
                                pairs = encodePairs(computed)
                            )
                        )
                    } ?: return@launch
                }
                if (alignmentFromServer && currentAlignmentKey == key) return@launch
                setSentenceAlignment(pairs, key, fromServer = false)
                Log.d("LocalAlignment", "Chapter $leftChapterIdx↔$rightChapterIdx aligned locally: ${pairs.size} pairs")
            } finally {
                _state.update { it.copy(chapterAlignmentProgress = -1f) }
            }
        }
    }

    private suspend fun computeLocalAlignment(
        leftSentences: List<String>,
        rightSentences: List<String>
    ): List<Pair<Int, Int>>? {
        _state.update { it.copy(chapterAlignmentProgress = 0f) }
        val translated = ArrayList<String>(leftSentences.size)
        var untranslated = 0
        for ((i, sentence) in leftSentences.withIndex()) {
            val t = translationManager.translate(sentence, useCache = false)
            if (t == sentence) untranslated++
            translated.add(t)
            if (i % 10 == 0) {
                _state.update { it.copy(chapterAlignmentProgress = i.toFloat() / leftSentences.size) }
            }
        }
        // translate() returns the input unchanged when ML Kit is unavailable
        if (untranslated > leftSentences.size / 2) {
            Log.w("LocalAlignment", "ML Kit unavailable ($untranslated/${leftSentences.size} untranslated), not aligning")
            return null
        }
        return withContext(Dispatchers.Default) {
            LexicalAligner.align(translated, rightSentences).map { it.left to it.right }
        }
    }

    /**
     * Right paragraph matching left paragraph [leftPara], derived from the chapter
     * sentence alignment. Paragraph indices are the WebView's (one per <p>, like the
     * compromise.js lists). Null when no alignment is available for the current pair.
     */
    private fun alignedRightParagraph(leftPara: Int): Int? {
        val s = _state.value
        val leftChapter = s.leftBook?.chapters?.getOrNull(s.leftPosition.chapterIndex) ?: return null
        val rightChapter = s.rightBook?.chapters?.getOrNull(s.rightPosition.chapterIndex) ?: return null
        if (alignmentByLeft.isEmpty() || currentAlignmentKey != alignmentCacheKey(leftChapter, rightChapter)) return null
        if (leftPara !in currentLeftSentencesPerPara.indices) return null

        val start = currentLeftSentencesPerPara.take(leftPara).sum()
        val end = start + currentLeftSentencesPerPara[leftPara]
        // First aligned sentence of the paragraph; if none is aligned, the nearest aligned one
        val rightGlobal = (start until end).firstNotNullOfOrNull { alignmentByLeft[it]?.firstOrNull() }
            ?: (1..50).firstNotNullOfOrNull { d ->
                alignmentByLeft[start - d]?.lastOrNull() ?: alignmentByLeft[end - 1 + d]?.firstOrNull()
            }
            ?: return null

        var acc = 0
        for ((p, count) in currentRightSentencesPerPara.withIndex()) {
            acc += count
            if (rightGlobal < acc) return p
        }
        return null
    }

    /** Stores [pairs] as the alignment of the chapter pair currently on screen. */
    private suspend fun storeCurrentAlignment(pairs: List<Pair<Int, Int>>) {
        val s = _state.value
        val leftUri = s.leftBookUri ?: return
        val rightUri = s.rightBookUri ?: return
        if (currentLeftSentencesPerPara.isEmpty() || currentRightSentencesPerPara.isEmpty()) return
        chapterAlignmentDao.upsert(
            ChapterAlignmentEntity(
                leftBookUri = leftUri,
                rightBookUri = rightUri,
                leftChapterIndex = s.leftPosition.chapterIndex,
                rightChapterIndex = s.rightPosition.chapterIndex,
                leftCount = currentLeftSentencesPerPara.sum(),
                rightCount = currentRightSentencesPerPara.sum(),
                pairs = encodePairs(pairs)
            )
        )
    }

    private fun encodePairs(pairs: List<Pair<Int, Int>>): String =
        pairs.joinToString(";") { "${it.first}:${it.second}" }

    private fun decodePairs(text: String): List<Pair<Int, Int>> =
        text.split(";").mapNotNull { item ->
            val parts = item.split(":")
            val l = parts.getOrNull(0)?.toIntOrNull()
            val r = parts.getOrNull(1)?.toIntOrNull()
            if (l != null && r != null) l to r else null
        }

    // --- Chapter map ---

    private fun computeChapterMap() {
        val s = _state.value
        val leftBook = s.leftBook
        val rightBook = s.rightBook
        // Also computed in auto-translation mode when a right book is paired, so the
        // "back to two books" suggestion knows which chapters have a counterpart
        if (leftBook == null || rightBook == null) {
            chapterMap = null
            leftChapterLengths = null
            updateChapterPairingHint()
            return
        }
        val forced = chapterOverrides
        chapterMapJob?.cancel()
        chapterMapJob = viewModelScope.launch {
            val leftInfo = withContext(Dispatchers.Default) { leftBook.chapters.map { it.toChapterInfo() } }
            val map = withContext(Dispatchers.Default) {
                ChapterMatcher.match(leftInfo, rightBook.chapters.map { it.toChapterInfo() }, forced)
            }
            chapterMap = map
            leftChapterLengths = leftInfo.map { it.textLength }.toIntArray()
            Log.d("ChapterMap", "Left→Right: ${map.withIndex().joinToString { "${it.index}→${it.value}" }}")
            updateChapterPairingHint()
        }
    }

    private fun Chapter.toChapterInfo() = ChapterMatcher.ChapterInfo(
        textLength = htmlContent.replace(HTML_TAG, " ").replace(WHITESPACE, " ").trim().length,
        title = title
    )

    /** Right chapter mapped to left chapter [left], or null if unknown/unmatched. */
    private fun mappedRightChapter(left: Int): Int? {
        val total = _state.value.rightBook?.totalChapters ?: return null
        return chapterMap?.getOrNull(left)?.takeIf { it in 0 until total }
    }

    /** Right chapter to show with left chapter [left]: chapter map first, else keep the current offset. */
    private fun rightChapterFollowing(left: Int, step: Int): Int? {
        val rightBook = _state.value.rightBook ?: return null
        mappedRightChapter(left)?.let { return it }
        val target = _state.value.rightPosition.chapterIndex + step
        return target.takeIf { it in 0 until rightBook.totalChapters }
    }

    /** Manual correction: left chapter [left] goes with right chapter [right]. */
    private fun recordChapterOverride(left: Int, right: Int) {
        if (mappedRightChapter(left) == right) return
        // Drop earlier corrections that would contradict this one (the map is monotonic)
        chapterOverrides = chapterOverrides.filter { (l, r) ->
            (l < left && r < right) || (l > left && r > right)
        } + (left to right)
        Log.d("ChapterMap", "Manual correction: left $left → right $right (overrides=$chapterOverrides)")
        computeChapterMap()
        saveCurrentSession()
    }

    private fun encodeChapterOverrides(map: Map<Int, Int>): String =
        map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    private fun decodeChapterOverrides(text: String): Map<Int, Int> =
        text.split(",").mapNotNull { item ->
            val parts = item.split(":")
            val l = parts.getOrNull(0)?.toIntOrNull()
            val r = parts.getOrNull(1)?.toIntOrNull()
            if (l != null && r != null) l to r else null
        }.toMap()

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
        playbackActive = true
        publishPlayback()
        if (_state.value.ttsTimeLimitMinutes > 0) {
            startTtsTimer()
        }
        ttsExpectedSentenceIndex = _state.value.leftSentenceIndex
        ttsExpectedParagraphIndex = _state.value.leftParagraphIndex
        ttsExpectedRightParagraphIndex = _state.value.rightParagraphIndex
        if (_state.value.isSingleBookMode) {
            // Paragraph-level flow: Kotlin speaks the paragraph text directly, so the
            // sentence-highlight machinery is not used here (keep the index at -1 so the
            // refresh trigger below never fires highlightSentence()).
            _state.update { it.copy(leftSentenceIndex = -1) }
            viewModelScope.launch { speakSingleParagraph() }
        } else {
            ttsRefreshTrigger++
        }
    }

    private fun pauseTts() {
        ttsManager.stop()
        isBilingualPendingTranslation = false
        bilingualPhase = 0
        ttsExpectedSentenceIndex = -1
        ttsExpectedParagraphIndex = -1
        ttsExpectedRightParagraphIndex = -1
        _state.update { it.copy(isTtsPlaying = false) }
        ttsTimerJob?.cancel()
        publishPlayback()
    }

    fun stopTts() {
        ttsManager.stop()
        isBilingualPendingTranslation = false
        bilingualPhase = 0
        ttsExpectedSentenceIndex = -1
        ttsExpectedParagraphIndex = -1
        ttsExpectedRightParagraphIndex = -1
        lastSpokenEnglishText = ""
        _state.update { it.copy(isTtsPlaying = false, ttsRemainingSeconds = 0) }
        ttsTimerJob?.cancel()
        playbackActive = false
        publishPlayback()
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
        setTtsTimeLimit(next)
    }

    fun setTtsTimeLimit(minutes: Int) {
        _state.update { it.copy(ttsTimeLimitMinutes = minutes, ttsRemainingSeconds = 0) }
        ttsTimerJob?.cancel()
        if (minutes > 0 && _state.value.isTtsPlaying) {
            startTtsTimer()
        }
        saveCurrentSession()
    }

    fun setTtsSpeed(speed: Float) {
        _state.update { it.copy(ttsSpeed = speed) }
        saveCurrentSession()
    }

    fun setTtsBilingualMode(mode: TtsBilingualMode) {
        _state.update { it.copy(ttsBilingualMode = mode) }
        isBilingualPendingTranslation = false
        bilingualPhase = 0
        saveCurrentSession()
    }

    fun onTtsSentenceTextReceived(text: String) {
        if (!_state.value.isTtsPlaying) return

        // Single-book mode: Kotlin drives paragraph speech directly (speakSingleParagraph).
        // JS sentence callbacks must not start a competing sentence-level flow.
        if (_state.value.isSingleBookMode) return

        // Ignore callbacks that don't match our expected TTS position
        val currentSentenceIndex = _state.value.leftSentenceIndex
        val currentParagraphIndex = _state.value.leftParagraphIndex
        
        // During paragraph transitions, WebView may fire highlights for OLD paragraph
        // Only accept if paragraph matches expected OR we're still on the OLD paragraph 
        // (in which case we IGNORE it and wait for the NEW paragraph)
        val paragraphMatches = currentParagraphIndex == ttsExpectedParagraphIndex
        
        if (currentSentenceIndex != ttsExpectedSentenceIndex || !paragraphMatches) {
            if (currentSentenceIndex == -1 && ttsExpectedSentenceIndex >= 0 && paragraphMatches) {
                // Self-heal: the sentence index was reset to -1 (scroll race) while TTS is
                // waiting for a real index on the RIGHT paragraph. Adopt the expected index
                // instead of dropping every callback forever (TTS deadlock).
                Log.w("TtsBilingual", "Adopting expected sentence=$ttsExpectedSentenceIndex after -1 reset (para=$currentParagraphIndex)")
                _state.update { it.copy(leftSentenceIndex = ttsExpectedSentenceIndex) }
            } else {
                Log.d("TtsBilingual", "Ignoring spurious onTtsSentenceTextReceived: expected sentence=$ttsExpectedSentenceIndex para=$ttsExpectedParagraphIndex, got sentence=$currentSentenceIndex para=$currentParagraphIndex")
                return
            }
        }
        
        lastSpokenEnglishText = text
        val mode = _state.value.ttsBilingualMode

        when (mode) {
            TtsBilingualMode.OFF -> {
                ttsManager.speak(text, _state.value.leftSentenceIndex, _state.value.ttsSpeed)
            }
            TtsBilingualMode.EN_TO_ES -> {
                // Phase 0: speak English first
                bilingualPhase = 0
                ttsManager.speak(text, _state.value.leftSentenceIndex, _state.value.ttsSpeed)
            }
            TtsBilingualMode.ES_TO_EN -> {
                // Phase 0: speak Spanish from right book first, then English
                bilingualPhase = 0
                isBilingualPendingTranslation = true
                viewModelScope.launch {
                    try {
                        val rightParaIndex = if (ttsExpectedRightParagraphIndex >= 0 && ttsExpectedRightParagraphIndex != _state.value.rightParagraphIndex) {
                            ttsExpectedRightParagraphIndex
                        } else null
                        val match = findMatchingSpanishSentence(text, rightParaIndex)
                        val spanishSentence = match?.text ?: run {
                            Log.w("TtsBilingual", "No Spanish sentence found, falling back to translation")
                            translationManager.translate(text)
                        }
                        Log.d("TtsBilingual", "ES→EN: Speaking Spanish from right book: '$spanishSentence' (match: $match, rightPara: $rightParaIndex)")
                        // Translated fallback: the sentence isn't in the right book, nothing to highlight
                        match?.let { onHighlightRightSentence?.invoke(it.paragraphIndex, it.firstSentence, it.lastSentence) }
                        ttsManager.speakSpanish(spanishSentence, _state.value.leftSentenceIndex)
                    } catch (e: Exception) {
                        Log.e("TtsBilingual", "Failed to get Spanish sentence", e)
                        isBilingualPendingTranslation = false
                        ttsManager.speak(text, _state.value.leftSentenceIndex, _state.value.ttsSpeed)
                    }
                }
            }
            TtsBilingualMode.EN_ES_EN -> {
                // Phase 0: speak English first
                bilingualPhase = 0
                ttsManager.speak(text, _state.value.leftSentenceIndex, _state.value.ttsSpeed)
            }
        }
    }

    private fun onTtsSentenceComplete(utteranceId: String?) {
        viewModelScope.launch {
            if (!_state.value.isTtsPlaying) return@launch

            val mode = _state.value.ttsBilingualMode
            val isEnglishUtterance = utteranceId?.startsWith("sentence_") == true && !utteranceId.startsWith("sentence_es_")
            val isSpanishUtterance = utteranceId?.startsWith("sentence_es_") == true

            // Single-book mode: sentence-level state machine (see speakSingleParagraph)
            if (_state.value.isSingleBookMode) {
                onSingleParagraphUtteranceComplete()
                return@launch
            }

            when (mode) {
                TtsBilingualMode.OFF -> {
                    advanceToNextSentence()
                }

                TtsBilingualMode.EN_TO_ES -> {
                    if (isEnglishUtterance && bilingualPhase == 0) {
                        // EN done → find matching Spanish sentence from right book → speak ES
                        bilingualPhase = 1
                        isBilingualPendingTranslation = true
                        val englishSentence = lastSpokenEnglishText
                        Log.d("TtsBilingual", "EN→ES: Finding Spanish sentence for: '$englishSentence'")
                        viewModelScope.launch {
                            try {
                                val rightParaIndex = if (ttsExpectedRightParagraphIndex >= 0 && ttsExpectedRightParagraphIndex != _state.value.rightParagraphIndex) {
                                    ttsExpectedRightParagraphIndex
                                } else null
                                val match = findMatchingSpanishSentence(englishSentence, rightParaIndex)
                                val spanishSentence = match?.text ?: run {
                                    Log.w("TtsBilingual", "No Spanish sentence found, falling back to translation")
                                    translationManager.translate(englishSentence)
                                }
                                Log.d("TtsBilingual", "EN→ES: Spanish sentence: '$spanishSentence' (match: $match, rightPara: $rightParaIndex)")
                                // Translated fallback: the sentence isn't in the right book, nothing to highlight
                                match?.let { onHighlightRightSentence?.invoke(it.paragraphIndex, it.firstSentence, it.lastSentence) }
                                ttsManager.speakSpanish(spanishSentence, _state.value.leftSentenceIndex)
                            } catch (e: Exception) {
                                Log.e("TtsBilingual", "EN→ES: Failed to get Spanish sentence", e)
                                isBilingualPendingTranslation = false
                                bilingualPhase = 0
                                advanceToNextSentence()
                            }
                        }
                    } else if (isSpanishUtterance && bilingualPhase == 1) {
                        // ES done → next sentence
                        bilingualPhase = 0
                        isBilingualPendingTranslation = false
                        advanceToNextSentence()
                    }
                }

                TtsBilingualMode.ES_TO_EN -> {
                    if (isSpanishUtterance && bilingualPhase == 0) {
                        // ES done → speak English original
                        bilingualPhase = 1
                        isBilingualPendingTranslation = false
                        Log.d("TtsBilingual", "ES→EN: Speaking English: '$lastSpokenEnglishText'")
                        ttsManager.speak(lastSpokenEnglishText, _state.value.leftSentenceIndex, _state.value.ttsSpeed)
                    } else if (isEnglishUtterance && bilingualPhase == 1) {
                        // EN done → next sentence
                        bilingualPhase = 0
                        advanceToNextSentence()
                    } else if (bilingualPhase == 0 && isEnglishUtterance == false && isSpanishUtterance == false) {
                        // Phase 0: speak Spanish first (from right book)
                        val englishSentence = lastSpokenEnglishText
                        Log.d("TtsBilingual", "ES→EN: Finding Spanish sentence for: '$englishSentence'")
                        viewModelScope.launch {
                            try {
                                val rightParaIndex = if (ttsExpectedRightParagraphIndex >= 0 && ttsExpectedRightParagraphIndex != _state.value.rightParagraphIndex) {
                                    ttsExpectedRightParagraphIndex
                                } else null
                                val match = findMatchingSpanishSentence(englishSentence, rightParaIndex)
                                val spanishSentence = match?.text ?: run {
                                    Log.w("TtsBilingual", "No Spanish sentence found, falling back to translation")
                                    translationManager.translate(englishSentence)
                                }
                                Log.d("TtsBilingual", "ES→EN: Spanish sentence: '$spanishSentence' (match: $match, rightPara: $rightParaIndex)")
                                // Translated fallback: the sentence isn't in the right book, nothing to highlight
                                match?.let { onHighlightRightSentence?.invoke(it.paragraphIndex, it.firstSentence, it.lastSentence) }
                                ttsManager.speakSpanish(spanishSentence, _state.value.leftSentenceIndex)
                                bilingualPhase = 1 // Next will be English
                            } catch (e: Exception) {
                                Log.e("TtsBilingual", "ES→EN: Failed to get Spanish sentence", e)
                                ttsManager.speak(englishSentence, _state.value.leftSentenceIndex, _state.value.ttsSpeed)
                                bilingualPhase = 1
                            }
                        }
                    }
                }

                TtsBilingualMode.EN_ES_EN -> {
                    if (isEnglishUtterance && bilingualPhase == 0) {
                        // EN done → find matching Spanish sentence from right book → speak ES
                        bilingualPhase = 1
                        isBilingualPendingTranslation = true
                        val englishSentence = lastSpokenEnglishText
                        Log.d("TtsBilingual", "EN↔ES: Finding Spanish sentence for: '$englishSentence'")
                        viewModelScope.launch {
                            try {
                                val rightParaIndex = if (ttsExpectedRightParagraphIndex >= 0 && ttsExpectedRightParagraphIndex != _state.value.rightParagraphIndex) {
                                    ttsExpectedRightParagraphIndex
                                } else null
                                val match = findMatchingSpanishSentence(englishSentence, rightParaIndex)
                                val spanishSentence = match?.text ?: run {
                                    Log.w("TtsBilingual", "No Spanish sentence found, falling back to translation")
                                    translationManager.translate(englishSentence)
                                }
                                Log.d("TtsBilingual", "EN↔ES: Spanish sentence: '$spanishSentence' (match: $match, rightPara: $rightParaIndex)")
                                // Translated fallback: the sentence isn't in the right book, nothing to highlight
                                match?.let { onHighlightRightSentence?.invoke(it.paragraphIndex, it.firstSentence, it.lastSentence) }
                                ttsManager.speakSpanish(spanishSentence, _state.value.leftSentenceIndex)
                            } catch (e: Exception) {
                                Log.e("TtsBilingual", "EN↔ES: Failed to get Spanish sentence", e)
                                isBilingualPendingTranslation = false
                                bilingualPhase = 0
                                advanceToNextSentence()
                            }
                        }
                    } else if (isSpanishUtterance && bilingualPhase == 1) {
                        // ES done → speak EN again for reinforcement
                        bilingualPhase = 2
                        isBilingualPendingTranslation = false
                        Log.d("TtsBilingual", "EN↔ES: Repeating English: '$lastSpokenEnglishText'")
                        ttsManager.speak(lastSpokenEnglishText, _state.value.leftSentenceIndex, _state.value.ttsSpeed)
                    } else if (isEnglishUtterance && bilingualPhase == 2) {
                        // EN repeat done → next sentence
                        bilingualPhase = 0
                        advanceToNextSentence()
                    }
                }
            }
        }
    }

    private fun advanceToNextSentence() {
        val current = _state.value.leftSentenceIndex
        val nextIndex = current + 1
        if (nextIndex < _state.value.leftSentenceCount) {
            _state.update { it.copy(leftSentenceIndex = nextIndex) }
            ttsExpectedSentenceIndex = nextIndex
            ttsExpectedParagraphIndex = _state.value.leftParagraphIndex
            // Reset expected right paragraph since we're still in the same paragraph
            ttsExpectedRightParagraphIndex = -1
            ttsRefreshTrigger++
        } else {
            advanceTtsParagraph()
        }
    }

    fun toggleBilingualTtsMode() {
        setTtsBilingualMode(TtsBilingualMode.next(_state.value.ttsBilingualMode))
    }

    fun cycleTtsSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f)
        val current = _state.value.ttsSpeed
        val nextIndex = (speeds.indexOf(current) + 1) % speeds.size
        setTtsSpeed(speeds[nextIndex])
    }

    private fun advanceTtsParagraph() {
        // Advance to next paragraph.
        // NOTE: leftParagraphIndex is deliberately NOT updated here. The WebView confirms
        // the new paragraph via onParagraphFound after the scroll really happened, and that
        // confirmation is what prevents a stale sentence from the OLD paragraph (highlightSentence
        // fires immediately on index change, before the scroll) from being accepted as spoken text.
        val nextParagraphIndex = _state.value.leftParagraphIndex + 1

        // Calculate expected right paragraph using anchor (if available)
        val currentState = _state.value
        val expectedRightPara = alignedRightParagraph(nextParagraphIndex)
            ?: if (currentState.syncAnchorLeftIndex >= 0 && currentState.syncAnchorRightIndex >= 0) {
                val offset = nextParagraphIndex - currentState.syncAnchorLeftIndex
                (currentState.syncAnchorRightIndex + offset).coerceAtLeast(0)
            } else {
                currentState.rightParagraphIndex + 1 // fallback
            }

        // Reset sentence tracking atomically and ask the WebView to scroll to the next paragraph
        _state.update { state ->
            state.copy(
                leftSentenceIndex = 0,
                leftSentenceCount = 0,
                ttsScrollToNextParagraphTrigger = state.ttsScrollToNextParagraphTrigger + 1
            )
        }

        ttsExpectedSentenceIndex = 0
        ttsExpectedParagraphIndex = nextParagraphIndex
        ttsExpectedRightParagraphIndex = expectedRightPara
    }

    fun advanceTtsToNextChapter() {
        val leftBook = _state.value.leftBook ?: return
        val currentChapter = _state.value.leftPosition.chapterIndex
        val totalChapters = leftBook.totalChapters
        if (currentChapter < totalChapters - 1) {
            setChapter(true, currentChapter + 1)
            // Also advance right book if it's loaded (chapter map, else keep the offset)
            val rightBook = _state.value.rightBook
            rightChapterFollowing(currentChapter + 1, step = 1)?.let { setChapter(false, it) }
            _state.update { it.copy(leftSentenceIndex = 0, leftSentenceCount = 0) }
            // Reset expected TTS position for paragraph 0 of the new chapter, otherwise
            // every WebView callback would be rejected as "spurious" (TTS stall at chapter end)
            ttsExpectedSentenceIndex = 0
            ttsExpectedParagraphIndex = 0
            ttsExpectedRightParagraphIndex = if (rightBook != null) 0 else _state.value.rightParagraphIndex
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

    // --- Single-book mode: sentence-level TTS within paragraph ---------------------
    // The paragraph is the translation unit (ML Kit gets full paragraph for context).
    // TTS reads sentence by sentence: EN1 → ES1 → EN2 → ES2... (mode dependent),
    // using compromise.js sentence splitting from the WebView for accurate boundaries.
    // Translation is inserted inline below each paragraph as a sibling div.

    /**
     * Get English sentences for a paragraph using our own sentence splitter (extractSentences)
     * on the paragraph text. The same splitter runs on the ML Kit translation, so boundaries
     * are comparable (compromise.js over-splits).
     * Caller must ensure paragraph is highlighted.
     */
    private fun getSingleParagraphSentences(paraIdx: Int): List<String>? {
        val paraText = getSingleParagraphText(paraIdx, ::currentLeftChapterParagraphs) ?: return null

        // Use our own sentence splitter (regex-based) for correct boundaries
        val sentences = extractSentences(paraText).filter { it.trim().isNotBlank() }
        if (sentences.isEmpty()) return null
        Log.d("SingleBook", "Paragraph $paraIdx split into ${sentences.size} sentences (extractSentences)")
        return sentences
    }

    /** Paragraphs of the current left chapter parsed from its HTML (fallback when compromise.js has none). */
    private fun currentLeftChapterParagraphs(): List<String>? {
        val currentState = _state.value
        val chapter = currentState.leftBook?.chapters?.getOrNull(currentState.leftPosition.chapterIndex)
            ?: return null
        return extractParagraphs(chapter.htmlContent)
    }

    /**
     * Text of paragraph [paraIdx] from the compromise.js list (joined) or, failing that,
     * from [htmlParagraphs]. Null when the paragraph doesn't exist or is blank.
     */
    private fun getSingleParagraphText(paraIdx: Int, htmlParagraphs: () -> List<String>?): String? {
        if (paraIdx < 0) return null
        if (leftSentencesChapterIdx == _state.value.leftPosition.chapterIndex) {
            leftChapterSentencesByPara.getOrNull(paraIdx)
                ?.joinToString(" ")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return htmlParagraphs()?.getOrNull(paraIdx)?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Non-empty paragraphs around [paraIdx] (up to [TRANSLATION_CONTEXT_BEFORE] before, in
     * reading order, and [TRANSLATION_CONTEXT_AFTER] after) for the server translator.
     */
    private fun singleParagraphContext(paraIdx: Int): Pair<List<String>, List<String>> {
        val htmlParagraphs by lazy { currentLeftChapterParagraphs() }
        fun textAt(i: Int) = getSingleParagraphText(i) { htmlParagraphs }
        val before = (paraIdx - 1 downTo maxOf(0, paraIdx - TRANSLATION_CONTEXT_SCAN)).asSequence()
            .mapNotNull(::textAt)
            .take(TRANSLATION_CONTEXT_BEFORE)
            .toList()
            .reversed()
        val after = (paraIdx + 1..paraIdx + TRANSLATION_CONTEXT_SCAN).asSequence()
            .mapNotNull(::textAt)
            .take(TRANSLATION_CONTEXT_AFTER)
            .toList()
        return before to after
    }

    // Server translations of single-book paragraphs, in flight or finished, so the
    // prefetch of the next paragraph and its playback share one request
    private val serverTranslations = object : LinkedHashMap<String, Deferred<List<String>?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Deferred<List<String>?>>?) =
            size > SERVER_TRANSLATION_CACHE_SIZE
    }
    // After a failed request, use ML Kit for a while instead of retrying every paragraph
    private var serverTranslationRetryAt = 0L

    private fun serverTranslationKey(paraIdx: Int): String {
        val s = _state.value
        return "${s.leftBookUri}:${s.leftPosition.chapterIndex}:$paraIdx"
    }

    /** Server translation of [paraIdx] (started now or earlier), or null when the server can't be used. */
    private fun serverTranslationAsync(paraIdx: Int, enSentences: List<String>): Deferred<List<String>?>? {
        val key = serverTranslationKey(paraIdx)
        serverTranslations[key]?.let { return it }
        if (serverStatus.value == ServerStatus.OFFLINE || serverStatus.value == ServerStatus.UNAUTHORIZED) return null
        if (System.currentTimeMillis() < serverTranslationRetryAt) return null

        // Neighbouring paragraphs let the server pick the right grammatical gender.
        // Captured now: the chapter may change before the request runs.
        val (contextBefore, contextAfter) = singleParagraphContext(paraIdx)

        // Lazy so it's in the map before it runs: a failure removes its own entry
        val deferred = viewModelScope.async(start = CoroutineStart.LAZY) {
            val result = alignmentRepository.translateParagraph(enSentences, contextBefore, contextAfter)
                ?.map { it.replace("\n", " ").replace("\r", " ").trim() }
            if (result == null) {
                serverTranslations.remove(key)
                serverTranslationRetryAt = System.currentTimeMillis() + SERVER_TRANSLATION_RETRY_MS
            }
            _state.update { it.copy(isServerTranslationFailing = result == null) }
            result
        }
        serverTranslations[key] = deferred
        deferred.start()
        return deferred
    }

    /** Status button in single-book mode: check the server again and drop the ML Kit back-off. */
    fun retryServerTranslation() {
        viewModelScope.launch {
            _state.update { it.copy(isServerAligning = true) }
            if (serverStatus.value != ServerStatus.UNAUTHORIZED) alignmentRepository.checkHealth()
            serverTranslationRetryAt = 0L
            _state.update { it.copy(isServerAligning = false, isServerTranslationFailing = false) }
        }
    }

    /** Start (or resume after pause) the paragraph cycle on the current paragraph. */
    private suspend fun speakSingleParagraph() {
        if (!_state.value.isTtsPlaying) return

        // Wait for WebView to highlight a paragraph
        var waited = 0
        while (_state.value.isTtsPlaying && _state.value.leftParagraphIndex < 0 && waited < 25) {
            delay(100)
            waited++
        }
        if (!_state.value.isTtsPlaying) return

        val paraIdx = _state.value.leftParagraphIndex
        if (paraIdx < 0) {
            Log.w("SingleBook", "No paragraph highlighted, stopping TTS")
            stopTts()
            return
        }
        ttsExpectedParagraphIndex = paraIdx

        // Get English sentences for this paragraph using our own sentence splitter
        val enSentences = getSingleParagraphSentences(paraIdx)
        if (enSentences == null || enSentences.isEmpty()) {
            Log.d("SingleBook", "Paragraph $paraIdx has no sentences, advancing")
            advanceSingleParagraph()
            return
        }

        // Translate the full paragraph (better ML Kit context)
        val paraText = enSentences.joinToString(" ")
        lastSpokenEnglishText = paraText
        Log.d("SingleBook", "Reading paragraph $paraIdx (${enSentences.size} sentences, mode=${_state.value.ttsBilingualMode})")

        // Translate for ES modes and/or auto-translation display
        var esParagraph: String? = null
        val mode = _state.value.ttsBilingualMode
        val needsEsSpeech = mode != TtsBilingualMode.OFF
        val needsEsDisplay = _state.value.autoTranslationEnabled

        // Server translation first: better quality and exactly one ES sentence
        // per EN sentence. ML Kit below is the fallback when the server is unavailable.
        var serverSentences: List<String>? = null
        if (needsEsSpeech || needsEsDisplay) {
            serverSentences = serverTranslationAsync(paraIdx, enSentences)?.await()
            if (serverSentences != null) esParagraph = serverSentences.joinToString(" ")
            // Keep the next paragraph ready so playback doesn't wait on the network
            getSingleParagraphSentences(paraIdx + 1)?.let { serverTranslationAsync(paraIdx + 1, it) }
        }

        if ((needsEsSpeech || needsEsDisplay) && serverSentences == null) {
            esParagraph = translationManager.translate(paraText)
            if (esParagraph == paraText) {
                Log.w("SingleBook", "ML Kit translation unavailable for paragraph $paraIdx")
                esParagraph = null
            } else {
                // Normalize newlines for consistent matching
                esParagraph = esParagraph.replace("\n", " ").replace("\r", " ").trim()
            }
        }

        // Split Spanish translation into sentences
        var esSentences = serverSentences ?: esParagraph?.let { extractSentences(it) } ?: emptyList()

        // Sentence pairing is by position, so counts must match. When ML Kit merged or
        // split sentences, translate this paragraph sentence by sentence for SPEECH only
        // (less context, but EN[i] <-> ES[i] is guaranteed). The displayed translation
        // stays the full-paragraph one.
        if (needsEsSpeech && esParagraph != null && esSentences.size != enSentences.size) {
            Log.d("SingleBook", "Paragraph $paraIdx: ${enSentences.size} EN vs ${esSentences.size} ES sentences, translating per sentence")
            val perSentence = enSentences.map { translationManager.translate(it) }
            if (perSentence != enSentences) { // translate() returns the input unchanged on failure
                esSentences = perSentence.map { it.replace("\n", " ").replace("\r", " ").trim() }
            } else {
                Log.w("SingleBook", "Per-sentence translation failed for paragraph $paraIdx, keeping paragraph split")
            }
        }

        // Insert translation div at paragraph start (if enabled and available)
        if (needsEsDisplay && esParagraph != null) {
            pushInlineTranslation(paraIdx, esParagraph)
        }

        // Build the utterance sequence based on mode
        val utterances = buildSingleBookUtteranceSequence(
            enSentences = enSentences,
            esSentences = esSentences,
            mode = mode
        )
        singleBookUtterances = utterances
        singleBookUtteranceIndex = 0
        singleBookEnSentences = enSentences
        singleBookEsSentences = esSentences

        // Start the first utterance
        speakNextSingleBookUtterance()
    }

    /**
     * Build the sequence of utterances for single-book mode.
     * Each utterance is a pair: (language, sentenceIndex) where language: 0=EN, 1=ES
     */
    private fun buildSingleBookUtteranceSequence(
        enSentences: List<String>,
        esSentences: List<String>,
        mode: TtsBilingualMode
    ): List<Pair<Int, Int>> {
        val seq = mutableListOf<Pair<Int, Int>>()
        val maxIdx = maxOf(enSentences.size, esSentences.size)

        when (mode) {
            TtsBilingualMode.OFF -> {
                for (i in enSentences.indices) seq.add(0 to i)
            }
            TtsBilingualMode.EN_TO_ES -> {
                for (i in 0 until maxIdx) {
                    if (i < enSentences.size) seq.add(0 to i)
                    if (i < esSentences.size) seq.add(1 to i)
                }
            }
            TtsBilingualMode.ES_TO_EN -> {
                for (i in 0 until maxIdx) {
                    if (i < esSentences.size) seq.add(1 to i)
                    if (i < enSentences.size) seq.add(0 to i)
                }
            }
            TtsBilingualMode.EN_ES_EN -> {
                // Each sentence: EN -> ES -> EN (repeat)
                for (i in enSentences.indices) {
                    seq.add(0 to i)
                    if (i < esSentences.size) seq.add(1 to i)
                    seq.add(0 to i) // repeat EN
                }
            }
        }
        return seq
    }

    /** Speak the next utterance in the single-book sequence. */
    private fun speakNextSingleBookUtterance() {
        if (!_state.value.isTtsPlaying) return
        if (singleBookUtteranceIndex >= singleBookUtterances.size) {
            advanceSingleParagraph()
            return
        }

        val (lang, sentIdx) = singleBookUtterances[singleBookUtteranceIndex]

        if (lang == 0) {
            // Speak English sentence
            if (sentIdx < singleBookEnSentences.size) {
                val text = singleBookEnSentences[sentIdx]
                // Highlight in English paragraph using exact text match (not compromise.js index)
                _state.update { it.copy(highlightEnglishTrigger = it.highlightEnglishTrigger + 1) }
                _state.update { it.copy(highlightEnglishText = text) }
                // Keep leftSentenceIndex at -1 to avoid compromise.js highlighting
                _state.update { it.copy(leftSentenceIndex = -1) }
                ttsManager.speak(text, sentIdx, _state.value.ttsSpeed)
            } else {
                // Fallback: skip to next
                singleBookUtteranceIndex++
                speakNextSingleBookUtterance()
            }
        } else {
            // Speak Spanish sentence
            if (sentIdx < singleBookEsSentences.size) {
                val text = singleBookEsSentences[sentIdx]
                // Clear English highlight, highlight in translation div
                _state.update { it.copy(highlightEnglishText = "") }
                _state.update { it.copy(highlightTranslatedTrigger = it.highlightTranslatedTrigger + 1) }
                _state.update { it.copy(highlightTranslatedText = text) }
                ttsManager.speakSpanish(text, sentIdx)
            } else {
                // Fallback: skip to next
                singleBookUtteranceIndex++
                speakNextSingleBookUtterance()
            }
        }
    }

    /** Called when an utterance finishes in single-book mode. */
    private fun onSingleParagraphUtteranceComplete() {
        if (!_state.value.isTtsPlaying) return
        singleBookUtteranceIndex++
        speakNextSingleBookUtterance()
    }

    /** Ask the WebView to scroll to the next paragraph and keep the cycle running. */
    private fun advanceSingleParagraph() {
        if (!_state.value.isTtsPlaying) return
        val fromIdx = _state.value.leftParagraphIndex
        ttsExpectedParagraphIndex = fromIdx + 1
        _state.update { it.copy(ttsScrollToNextParagraphTrigger = it.ttsScrollToNextParagraphTrigger + 1) }

        viewModelScope.launch {
            var waited = 0
            while (_state.value.isTtsPlaying &&
                _state.value.leftParagraphIndex == fromIdx &&
                waited < 30
            ) {
                delay(100)
                waited++
            }
            if (!_state.value.isTtsPlaying) return@launch
            if (_state.value.leftParagraphIndex == fromIdx) {
                Log.w("SingleBook", "Paragraph did not advance, stopping TTS")
                stopTts()
                return@launch
            }
            // Reset sentence index and clear translation highlight
            _state.update { it.copy(leftSentenceIndex = -1, highlightTranslatedText = "", highlightEnglishText = "") }
            speakSingleParagraph()
        }
    }

    // Mutable state for single-book sentence-level flow
    private var singleBookUtterances: List<Pair<Int, Int>> = emptyList()
    private var singleBookUtteranceIndex = 0
    private var singleBookEnSentences: List<String> = emptyList()
    private var singleBookEsSentences: List<String> = emptyList()

    /**
     * Advance both books to the next chapter simultaneously.
     * Called from the divider "Next Chapter" button.
     */
    fun advanceBothBooksToNextChapter() {
        val leftBook = _state.value.leftBook ?: return
        val leftChapter = _state.value.leftPosition.chapterIndex
        val leftTotal = leftBook.totalChapters

        val rightBook = _state.value.rightBook
        val rightChapter = _state.value.rightPosition.chapterIndex
        val rightTotal = rightBook?.totalChapters ?: 0

        // Check if either book can advance
        val canAdvanceLeft = leftChapter < leftTotal - 1
        val canAdvanceRight = rightBook != null && rightChapter < rightTotal - 1

        if (canAdvanceLeft) {
            setChapter(true, leftChapter + 1)
            _state.update { it.copy(leftSentenceIndex = 0, leftSentenceCount = 0) }
            rightChapterFollowing(leftChapter + 1, step = 1)?.let { setChapter(false, it) }
        } else if (canAdvanceRight) {
            setChapter(false, rightChapter + 1)
        }

        // Refresh TTS if playing
        if (_state.value.isTtsPlaying) {
            viewModelScope.launch {
                delay(500)
                ttsRefreshTrigger++
            }
        }
    }

    /**
     * Go to previous chapter in both books simultaneously.
     * Called from the bottom bar "Previous Chapter" button (|<).
     */
    fun navigateToPreviousChapter() {
        stopTts()
        
        val leftBook = _state.value.leftBook ?: return
        val leftChapter = _state.value.leftPosition.chapterIndex
        
        val rightBook = _state.value.rightBook
        val rightChapter = _state.value.rightPosition.chapterIndex

        // Check if either book can go back
        val canGoBackLeft = leftChapter > 0
        val canGoBackRight = rightBook != null && rightChapter > 0

        if (canGoBackLeft) {
            setChapter(true, leftChapter - 1)
            _state.update { it.copy(leftSentenceIndex = 0, leftSentenceCount = 0) }
            rightChapterFollowing(leftChapter - 1, step = -1)?.let { setChapter(false, it) }
        } else if (canGoBackRight) {
            setChapter(false, rightChapter - 1)
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
        if (currentSessionId <= 0) {
            // Fallback to latest session if no specific session ID provided
            viewModelScope.launch {
                val session = bookRepository.getLatestSession() ?: return@launch
                currentSessionId = session.id
                _state.update { it.copy(isLoading = true) }

                val leftBook = bookRepository.loadBookFromUri(session.leftBookUri)
                val rightBook = session.rightBookUri?.let { bookRepository.loadBookFromUri(it) }
                chapterOverrides = decodeChapterOverrides(session.chapterOverrides)

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
                        isSingleBookMode = session.isSingleBookMode,
                        autoTranslationEnabled = session.autoTranslationEnabled,
                        ttsTimeLimitMinutes = session.ttsTimeLimitMinutes,
                        ttsBilingualMode = try {
                            TtsBilingualMode.valueOf(session.ttsBilingualMode)
                        } catch (_: Exception) {
                            TtsBilingualMode.OFF
                        },
                        ttsSpeed = session.ttsSpeed,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun saveCurrentSession() {
        viewModelScope.launch {
            val s = _state.value
            val leftUri = s.leftBookUri ?: return@launch
            val leftTitle = s.leftBook?.title ?: return@launch

            val session = ReadingSession(
                id = currentSessionId,
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
                syncOffset = s.syncOffset,
                isSingleBookMode = s.isSingleBookMode,
                autoTranslationEnabled = s.autoTranslationEnabled,
                ttsTimeLimitMinutes = s.ttsTimeLimitMinutes,
                ttsBilingualMode = s.ttsBilingualMode.name,
                ttsSpeed = s.ttsSpeed,
                chapterOverrides = encodeChapterOverrides(chapterOverrides)
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
