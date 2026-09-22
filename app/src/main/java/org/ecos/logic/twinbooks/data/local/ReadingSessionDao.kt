package org.ecos.logic.twinbooks.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ReadingSessionDao {

    @Query("SELECT * FROM reading_sessions ORDER BY lastOpenedTimestamp DESC LIMIT 1")
    suspend fun getLatestSession(): ReadingSessionEntity?

    @Query("SELECT * FROM reading_sessions ORDER BY lastOpenedTimestamp DESC")
    suspend fun getAllSessions(): List<ReadingSessionEntity>

    @Query("SELECT * FROM reading_sessions WHERE leftBookUri = :bookUri ORDER BY lastOpenedTimestamp DESC LIMIT 1")
    suspend fun getSessionByLeftBook(bookUri: String): ReadingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ReadingSessionEntity): Long

    @Update
    suspend fun updateSession(session: ReadingSessionEntity)

    @Query("DELETE FROM reading_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
}
