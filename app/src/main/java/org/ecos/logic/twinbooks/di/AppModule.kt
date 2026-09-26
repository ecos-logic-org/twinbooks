package org.ecos.logic.twinbooks.di

import android.content.Context
import androidx.room.Room
import org.ecos.logic.twinbooks.alignment.repository.AlignmentRepository
import org.ecos.logic.twinbooks.data.local.ChapterAlignmentDao
import org.ecos.logic.twinbooks.data.local.ReadingSessionDao
import org.ecos.logic.twinbooks.data.local.TwinBooksDatabase
import org.ecos.logic.twinbooks.data.repository.BookRepositoryImpl
import org.ecos.logic.twinbooks.domain.model.BookRepository
import org.ecos.logic.twinbooks.translation.MlKitTranslator
import org.ecos.logic.twinbooks.translation.TranslationManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TwinBooksDatabase {
        return Room.databaseBuilder(
            context,
            TwinBooksDatabase::class.java,
            "twinbooks_database"
        ).addMigrations(
            TwinBooksDatabase.MIGRATION_1_2,
            TwinBooksDatabase.MIGRATION_2_3,
            TwinBooksDatabase.MIGRATION_3_4,
            TwinBooksDatabase.MIGRATION_4_5,
            TwinBooksDatabase.MIGRATION_5_6,
            TwinBooksDatabase.MIGRATION_6_7,
            TwinBooksDatabase.MIGRATION_7_8,
            TwinBooksDatabase.MIGRATION_8_9
        ).build()
    }

    @Provides
    fun provideReadingSessionDao(database: TwinBooksDatabase): ReadingSessionDao {
        return database.readingSessionDao()
    }

    @Provides
    fun provideChapterAlignmentDao(database: TwinBooksDatabase): ChapterAlignmentDao {
        return database.chapterAlignmentDao()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ManagerModule {

    @Provides
    @Singleton
    fun provideTranslationManager(): TranslationManager {
        return TranslationManager(MlKitTranslator())
    }

    @Provides
    @Singleton
    fun provideAlignmentRepository(@ApplicationContext context: Context): AlignmentRepository {
        return AlignmentRepository(context)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBookRepository(impl: BookRepositoryImpl): BookRepository
}
