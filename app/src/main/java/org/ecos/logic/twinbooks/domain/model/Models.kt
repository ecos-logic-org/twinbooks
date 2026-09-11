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
    val syncActive: Boolean = false,
    val syncLeftParagraphIndex: Int = -1,
    val syncRightParagraphIndex: Int = -1,
    val syncTotalLeftParagraphs: Int = 0,
    val syncTotalRightParagraphs: Int = 0
)
