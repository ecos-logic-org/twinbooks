package org.ecos.logic.twinbooks.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChapterAlignmentDao {

    @Query(
        "SELECT * FROM chapter_alignments WHERE leftBookUri = :leftUri AND rightBookUri = :rightUri " +
            "AND leftChapterIndex = :leftChapter AND rightChapterIndex = :rightChapter LIMIT 1"
    )
    suspend fun get(leftUri: String, rightUri: String, leftChapter: Int, rightChapter: Int): ChapterAlignmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChapterAlignmentEntity)
}
