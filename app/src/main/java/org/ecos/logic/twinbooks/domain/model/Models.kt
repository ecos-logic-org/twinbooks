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

enum class TtsSpeed(val value: Float, val label: String) {
    SPEED_0_25(0.25f, "0.25x"),
    SPEED_0_5(0.5f, "0.5x"),
    SPEED_1(1f, "1x"),
    SPEED_1_25(1.25f, "1.25x"),
    SPEED_1_5(1.5f, "1.5x")
}

enum class TtsPlayState { IDLE, PLAYING, PAUSED }

data class TtsState(
    val playState: TtsPlayState = TtsPlayState.IDLE,
    val speed: TtsSpeed = TtsSpeed.SPEED_1,
    val currentSentenceIndex: Int = 0,
    val totalSentences: Int = 0,
    val timerMinutes: Int = 0,
    val timerRemainingSec: Int = 0,
    val isTimerRunning: Boolean = false
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
    val ttsState: TtsState = TtsState()
)
