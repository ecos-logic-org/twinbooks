package org.ecos.logic.twinbooks.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sentence_alignments",
    foreignKeys = [
        ForeignKey(
            entity = ReadingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId", "leftChapterIndex", "rightChapterIndex"]),
        Index(value = ["sessionId", "leftChapterIndex", "leftSentenceIndex"]),
        Index(value = ["sessionId", "rightChapterIndex", "rightSentenceIndex"])
    ]
)
data class SentenceAlignmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val leftChapterIndex: Int,
    val rightChapterIndex: Int,
    val leftSentenceIndex: Int,
    val rightSentenceIndex: Int,
    val similarityScore: Float,
    val createdAt: Long = System.currentTimeMillis()
)