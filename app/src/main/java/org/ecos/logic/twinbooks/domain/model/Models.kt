package org.ecos.logic.twinbooks.domain.model

data class BookContent(
    val title: String,
    val chapters: List<Chapter>,
    val totalChapters: Int,
    val coverImage: String? = null // Base64 data URI of cover image
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
    // Trigger to request chapter sentences from WebView (compromise.js)
    val requestChapterSentencesTrigger: Int = 0,
    val isBottomBarVisible: Boolean = false,
    val ttsBilingualMode: TtsBilingualMode = TtsBilingualMode.OFF,
    val ttsSpeed: Float = 1.0f,
    // Server alignment progress indicator
    val isServerAligning: Boolean = false,
    // Single-book mode: one book full screen, Spanish comes from on-device ML Kit translation
    val isSingleBookMode: Boolean = false,
    val autoTranslationEnabled: Boolean = true,
    // Inline translation push into the WebView (bump trigger to insert a translation)
    val inlineTranslationTrigger: Int = 0,
    val inlineTranslationSentenceIdx: Int = -1,
    val inlineTranslationText: String = "",
    // Highlight a sentence INSIDE the inserted translation block (bump trigger)
    val highlightTranslatedTrigger: Int = 0,
    val highlightTranslatedText: String = "",
    // Highlight a sentence in the ENGLISH paragraph (single-book mode, uses exact text match)
    val highlightEnglishTrigger: Int = 0,
    val highlightEnglishText: String = "",
    // Sync anchor points: set when user manually scrolls right book
    // anchorRight = right paragraph index at the moment of manual scroll
    // anchorLeft = left paragraph index at the moment of manual scroll
    // Future sync: rightIndex = anchorRight + (leftIndex - anchorLeft)
    val syncAnchorLeftIndex: Int = -1,
    val syncAnchorRightIndex: Int = -1
)

/**
 * Bilingual TTS reading modes.
 * Each mode defines the order in which languages are spoken.
 */
enum class TtsBilingualMode(val label: String) {
    OFF("Off"),
    EN_TO_ES("EN → ES"),
    ES_TO_EN("ES → EN"),
    EN_ES_EN("EN → ES → EN");

    companion object {
        fun next(current: TtsBilingualMode): TtsBilingualMode {
            val values = entries
            val nextIndex = (values.indexOf(current) + 1) % values.size
            return values[nextIndex]
        }
    }
}
