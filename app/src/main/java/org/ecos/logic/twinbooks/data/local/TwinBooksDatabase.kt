package org.ecos.logic.twinbooks.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ReadingSessionEntity::class],
    version = 3,
    exportSchema = false
)
abstract class TwinBooksDatabase : RoomDatabase() {
    abstract fun readingSessionDao(): ReadingSessionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN leftParagraphText TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN rightParagraphText TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN fontSize REAL NOT NULL DEFAULT 12.0")
            }
        }
    }
}
