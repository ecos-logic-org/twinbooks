package org.ecos.logic.twinbooks.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_sessions")
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val leftBookUri: String,
    val rightBookUri: String?,
    val leftTitle: String,
    val rightTitle: String?,
    val leftChapterIndex: Int = 0,
    val rightChapterIndex: Int = 0,
    val leftScrollOffset: Int = 0,
    val rightScrollOffset: Int = 0,
    val leftProgressPercent: Float = 0f,
    val rightProgressPercent: Float = 0f,
    val leftParagraphText: String = "",
    val rightParagraphText: String = "",
    val fontSize: Float = 12f,
    val isSynchronized: Boolean = false,
    val syncOffset: Int = 0,
    val lastOpenedTimestamp: Long = System.currentTimeMillis()
)
