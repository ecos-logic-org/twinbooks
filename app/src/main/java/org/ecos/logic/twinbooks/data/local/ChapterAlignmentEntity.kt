package org.ecos.logic.twinbooks.data.local

import androidx.room.Entity

/**
 * Persisted sentence alignment for one chapter pair (on-device translation + lexical DP).
 * [pairs] is compact text "left:right;left:right;..." with chapter-global sentence indices
 * (compromise.js split). [leftCount]/[rightCount] detect a different split → recompute.
 */
@Entity(
    tableName = "chapter_alignments",
    primaryKeys = ["leftBookUri", "rightBookUri", "leftChapterIndex", "rightChapterIndex"]
)
data class ChapterAlignmentEntity(
    val leftBookUri: String,
    val rightBookUri: String,
    val leftChapterIndex: Int,
    val rightChapterIndex: Int,
    val leftCount: Int,
    val rightCount: Int,
    val pairs: String,
    val createdAt: Long = System.currentTimeMillis()
)
