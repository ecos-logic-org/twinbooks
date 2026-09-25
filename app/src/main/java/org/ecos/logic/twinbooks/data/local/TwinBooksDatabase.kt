package org.ecos.logic.twinbooks.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ReadingSessionEntity::class, ChapterAlignmentEntity::class],
    version = 9,
    exportSchema = false
)
abstract class TwinBooksDatabase : RoomDatabase() {
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun chapterAlignmentDao(): ChapterAlignmentDao

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN isSynchronized INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN syncOffset INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN ttsTimeLimitMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN ttsBilingualMode TEXT NOT NULL DEFAULT 'OFF'")
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN ttsSpeed REAL NOT NULL DEFAULT 1.0")
            }
        }

        // v5->6: se añadió y quitó sentence_alignments (desarrollo)
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS sentence_alignments")
            }
        }

        // v6->7: limpieza final (desarrollo)
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS sentence_alignments")
            }
        }

        // v7->8: modo libro único (un solo libro con traducción automática ML Kit)
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN isSingleBookMode INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN autoTranslationEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        // v8->9: alineación local por capítulo + correcciones manuales del mapa de capítulos
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS chapter_alignments (" +
                        "leftBookUri TEXT NOT NULL, rightBookUri TEXT NOT NULL, " +
                        "leftChapterIndex INTEGER NOT NULL, rightChapterIndex INTEGER NOT NULL, " +
                        "leftCount INTEGER NOT NULL, rightCount INTEGER NOT NULL, " +
                        "pairs TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(leftBookUri, rightBookUri, leftChapterIndex, rightChapterIndex))"
                )
                db.execSQL("ALTER TABLE reading_sessions ADD COLUMN chapterOverrides TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
