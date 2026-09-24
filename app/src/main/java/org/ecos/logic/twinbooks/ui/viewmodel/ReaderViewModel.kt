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
import kotlin.math.roundToInt
import org.ecos.logic.twinbooks.alignment.AlignmentManager
import org.ecos.logic.twinbooks.alignment.model.ChapterAlignResponse
import org.ecos.logic.twinbooks.alignment.model.AlignmentResultWrapper
import org.ecos.logic.twinbooks.alignment.repository.AlignmentRepository
import org.ecos.logic.twinbooks.domain.model.BookContent
import org.ecos.logic.twinbooks.domain.model.BookRepository
import org.ecos.logic.twinbooks.domain.model.Chapter
import org.ecos.logic.twinbooks.domain.model.ReadingPosition
import org.ecos.logic.twinbooks.domain.model.ReadingSession
import org.ecos.logic.twinbooks.domain.model.ReadingState
import org.ecos.logic.twinbooks.domain.model.TtsBilingualMode
import org.ecos.logic.twinbooks.embedding.EmbeddingManager
import org.ecos.logic.twinbooks.translation.TranslationManager
import org.ecos.logic.twinbooks.tts.TtsManager
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val ttsManager: TtsManager,
    private val translationManager: TranslationManager,
    private val embeddingManager: EmbeddingManager,
    private val alignmentManager: AlignmentManager,
    private val alignmentRepository: AlignmentRepository
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
    // Callback to highlight sentence in right book
    var onHighlightRightSentence: ((Int) -> Unit)? = null
    private var currentSessionId: Long = -1

    fun loadSession(sessionId: Long) {
        currentSessionId = sessionId
        restoreSession(sessionId)
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
            } else {
                _state.update {
                    it.copy(isLoading = false, errorMessage = "Session not found")
                }
            }
        }
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
                            ttsTimeLimitMinutes = existingSession?.ttsTimeLimitMinutes ?: it.ttsTimeLimitMinutes,
                            ttsBilingualMode = try {
                                TtsBilingualMode.valueOf(existingSession?.ttsBilingualMode ?: "OFF")
                            } catch (_: Exception) { it.ttsBilingualMode },
                            ttsSpeed = existingSession?.ttsSpeed ?: it.ttsSpeed,
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
                syncAnchorRightIndex = -1
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
        val currentState = _state.value
        if (!currentState.isSynchronized) return
        
        val leftBook = currentState.leftBook
        val rightBook = currentState.rightBook
        val leftChapterIdx = currentState.leftPosition.chapterIndex
        val rightChapterIdx = currentState.rightPosition.chapterIndex
        
        if (leftBook == null || rightBook == null) return
        if (leftChapterIdx < 0 || leftChapterIdx >= leftBook.chapters.size) return
        if (rightChapterIdx < 0 || rightChapterIdx >= rightBook.chapters.size) return
        
        val leftChapter = leftBook.chapters[leftChapterIdx]
        val rightChapter = rightBook.chapters[rightChapterIdx]
        
        viewModelScope.launch {
            _state.update { it.copy(isServerAligning = true) }
            
            // Force a fresh server alignment (bypasses the per-chapter cache)
            val response = getChapterAlignment(leftChapter, rightChapter, forceRefresh = true)
            
            _state.update { it.copy(isServerAligning = false) }
            
            if (response != null) {
                Log.d("AlignmentRetry", "Server retry succeeded: ${response.alignment.size} pairs")
            } else {
                Log.w("AlignmentRetry", "Server retry failed (server down or chapter sentences not ready yet)")
            }
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
        val leftParagraphs = extractParagraphs(leftChapter.htmlContent)
        val rightParagraphs = extractParagraphs(rightChapter.htmlContent)
        
        if (rightParagraphs.isEmpty()) {
            Log.d("SyncTranslation", "No paragraphs found in right chapter")
            return
        }

        val leftTotal = leftParagraphs.size
        val rightTotal = rightParagraphs.size
        
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
        return Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /**
     * Extract sentences from text (simple regex).
     * Cold fallback only: used when the WebView (compromise.js) sentence list isn't ready yet.
     */
    private fun extractSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /**
     * Find the best matching Spanish sentence from the synchronized right paragraph
     * Uses server-side alignment first (highest quality, async), then falls back to local DP, embeddings, translation
     * @param englishSentence The English sentence being spoken
     * @param rightParaIndex Override for right paragraph index (use during paragraph transitions)
     * @return Pair of (spanishSentence, sentenceIndexInParagraph), or null if not found
     */
    private suspend fun findMatchingSpanishSentence(
        englishSentence: String,
        rightParaIndex: Int? = null
    ): Pair<String, Int>? {
        val currentState = _state.value
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
        if (spanishSentences.size == 1) return Pair(spanishSentences[0], 0)

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

            // STRATEGY 1: Server-side alignment (cached per chapter pair, one HTTP call)
            try {
                val response = getChapterAlignment(leftChapter, rightChapter)
                response?.let { r ->
                    val pair = r.alignment.firstOrNull { it.leftIdx == leftGlobalIdx }
                    if (pair != null) {
                        val localRightIdx = pair.rightIdx - rightSentencesBeforePara
                        if (localRightIdx >= 0 && localRightIdx < spanishSentences.size) {
                            Log.d("TtsBilingual", "Server Alignment match: leftGlobal=$leftGlobalIdx → rightGlobal=${pair.rightIdx} (local=$localRightIdx, score=${pair.score})")
                            return Pair(spanishSentences[localRightIdx], localRightIdx)
                        } else {
                            Log.w("TtsBilingual", "Server alignment pair outside paragraph: local=$localRightIdx, paraSentences=${spanishSentences.size}, rightBeforePara=$rightSentencesBeforePara")
                        }
                    } else {
                        Log.w("TtsBilingual", "Server alignment has no pair for leftGlobal=$leftGlobalIdx")
                    }
                }
            } catch (e: Exception) {
                Log.w("TtsBilingual", "Server alignment exception, falling back to local", e)
            }

            // STRATEGY 2: Pre-computed DP Alignment (instant fallback, same global index space)
            if (currentSentenceAlignment.isNotEmpty() && currentAlignmentKey == alignmentCacheKey(leftChapter, rightChapter)) {
                val alignedRightIdx = alignmentManager.getRightSentenceIndex(currentSentenceAlignment, leftGlobalIdx)
                if (alignedRightIdx != null) {
                    val localRightIdx = alignedRightIdx - rightSentencesBeforePara

                    if (localRightIdx >= 0 && localRightIdx < spanishSentences.size) {
                        Log.d("TtsBilingual", "DP Alignment fallback: leftGlobal=$leftGlobalIdx → rightGlobal=$alignedRightIdx (local=$localRightIdx)")
                        return Pair(spanishSentences[localRightIdx], localRightIdx)
                    } else {
                        Log.w("TtsBilingual", "DP Alignment local index out of bounds: local=$localRightIdx, paraSentences=${spanishSentences.size}, rightSentencesBeforePara=$rightSentencesBeforePara, alignedRightIdx=$alignedRightIdx")
                    }
                }
            }
        }
        
        // STRATEGY 3: Semantic Embeddings for sentence matching (fallback)
        if (embeddingManager.isReady.value) {
            val (bestIndex, score) = embeddingManager.findBestSentenceMatch(
                sourceSentence = englishSentence,
                candidateSentences = spanishSentences
            )
            Log.d("TtsBilingual", "Embedding fallback: index=$bestIndex, score=${(score * 100).roundToInt()}%")
            if (score >= EmbeddingManager.MIN_SEMANTIC_SCORE && bestIndex >= 0 && bestIndex < spanishSentences.size) {
                return Pair(spanishSentences[bestIndex], bestIndex)
            }
            Log.w("TtsBilingual", "Low semantic score ($score), falling back to translation")
        }
        
        // STRATEGY 4: Translation + Word Overlap fallback
        val bestIndex = translationManager.findBestMatch(
            sourceText = englishSentence,
            candidates = spanishSentences,
            startIndex = 0,
            range = spanishSentences.size
        )
        return if (bestIndex >= 0 && bestIndex < spanishSentences.size) {
            Pair(spanishSentences[bestIndex], bestIndex)
        } else {
            Pair(spanishSentences[0], 0) // fallback to first sentence
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
            similarityThreshold = EmbeddingManager.MIN_SEMANTIC_SCORE,
        )
        if (response != null) {
            chapterAlignmentCache = response
            chapterAlignmentCacheKey = key
            currentSentenceAlignment = response.alignment.map { Pair(it.leftIdx, it.rightIdx) }
            currentAlignmentKey = key
            Log.d("AlignmentCache", "Chapter aligned & cached: ${response.alignment.size} pairs (key=$key)")
        }
        return response
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
        ttsExpectedSentenceIndex = _state.value.leftSentenceIndex
        ttsExpectedParagraphIndex = _state.value.leftParagraphIndex
        ttsExpectedRightParagraphIndex = _state.value.rightParagraphIndex
        ttsRefreshTrigger++
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
                        val result = findMatchingSpanishSentence(text, rightParaIndex)
                        val (spanishSentence, sentenceIndex) = result ?: run {
                            Log.w("TtsBilingual", "No Spanish sentence found, falling back to translation")
                            Pair(translationManager.translate(text), 0)
                        }
                        Log.d("TtsBilingual", "ES→EN: Speaking Spanish from right book: '$spanishSentence' (index: $sentenceIndex, rightPara: $rightParaIndex)")
                        onHighlightRightSentence?.invoke(sentenceIndex)
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
                                val result = findMatchingSpanishSentence(englishSentence, rightParaIndex)
                                val (spanishSentence, sentenceIndex) = result ?: run {
                                    Log.w("TtsBilingual", "No Spanish sentence found, falling back to translation")
                                    Pair(translationManager.translate(englishSentence), 0)
                                }
                                Log.d("TtsBilingual", "EN→ES: Spanish sentence: '$spanishSentence' (index: $sentenceIndex, rightPara: $rightParaIndex)")
                                onHighlightRightSentence?.invoke(sentenceIndex)
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
                                val result = findMatchingSpanishSentence(englishSentence, rightParaIndex)
                                val (spanishSentence, sentenceIndex) = result ?: run {
                                    Log.w("TtsBilingual", "No Spanish sentence found, falling back to translation")
                                    Pair(translationManager.translate(englishSentence), 0)
                                }
                                Log.d("TtsBilingual", "ES→EN: Spanish sentence: '$spanishSentence' (index: $sentenceIndex, rightPara: $rightParaIndex)")
                                onHighlightRightSentence?.invoke(sentenceIndex)
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
                                val result = findMatchingSpanishSentence(englishSentence, rightParaIndex)
                                val (spanishSentence, sentenceIndex) = result ?: run {
                                    Log.w("TtsBilingual", "No Spanish sentence found, falling back to translation")
                                    Pair(translationManager.translate(englishSentence), 0)
                                }
                                Log.d("TtsBilingual", "EN↔ES: Spanish sentence: '$spanishSentence' (index: $sentenceIndex, rightPara: $rightParaIndex)")
                                onHighlightRightSentence?.invoke(sentenceIndex)
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
        val expectedRightPara = if (currentState.syncAnchorLeftIndex >= 0 && currentState.syncAnchorRightIndex >= 0) {
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
            navigateToChapter(true, currentChapter + 1)
            // Also advance right book if it's loaded
            val rightBook = _state.value.rightBook
            if (rightBook != null) {
                val rightChapter = _state.value.rightPosition.chapterIndex
                if (rightChapter < rightBook.totalChapters - 1) {
                    navigateToChapter(false, rightChapter + 1)
                }
            }
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
            navigateToChapter(true, leftChapter + 1)
            _state.update { it.copy(leftSentenceIndex = 0, leftSentenceCount = 0) }
        }
        if (canAdvanceRight) {
            navigateToChapter(false, rightChapter + 1)
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
            navigateToChapter(true, leftChapter - 1)
            _state.update { it.copy(leftSentenceIndex = 0, leftSentenceCount = 0) }
        }
        if (canGoBackRight) {
            navigateToChapter(false, rightChapter - 1)
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
                ttsTimeLimitMinutes = s.ttsTimeLimitMinutes,
                ttsBilingualMode = s.ttsBilingualMode.name,
                ttsSpeed = s.ttsSpeed
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
