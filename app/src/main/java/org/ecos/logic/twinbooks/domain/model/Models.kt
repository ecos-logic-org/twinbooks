package org.ecos.logic.twinbooks.domain.model

data class BookContent(
    val title: String,
    val chapters: List<Chapter>,
    val totalChapters: Int
)

data class Chapter(
    val index: Int,
    val title: String,
    val htmlContent: String,
    val resourceHref: String
)

data class ReadingPosition(
    val chapterIndex: Int = 0,
    val scrollOffset: Int = 0,
    val progressPercent: Float = 0f,
    val paragraphText: String = ""
)

data class ReadingState(
    val leftBook: BookContent? = null,
    val rightBook: BookContent? = null,
    val leftPosition: ReadingPosition = ReadingPosition(),
    val rightPosition: ReadingPosition = ReadingPosition(),
    val leftBookUri: String? = null,
    val rightBookUri: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val fontSize: Float = 12f,
    val isSynchronized: Boolean = false,
    val syncOffset: Int = 0,
    val leftParagraphIndex: Int = -1,
    val rightParagraphIndex: Int = -1,
    val leftSentenceIndex: Int = -1,
    val leftSentenceCount: Int = 0,
    val isTtsPlaying: Boolean = false,
    val ttsTimeLimitMinutes: Int = 0,
    val ttsRemainingSeconds: Long = 0L,
    val ttsScrollToNextParagraphTrigger: Int = 0,
    val isBottomBarVisible: Boolean = false,
    // Sync anchor points: set when user manually scrolls right book
    // anchorRight = right paragraph index at the moment of manual scroll
    // anchorLeft = left paragraph index at the moment of manual scroll
    // Future sync: rightIndex = anchorRight + (leftIndex - anchorLeft)
    val syncAnchorLeftIndex: Int = -1,
    val syncAnchorRightIndex: Int = -1
)
