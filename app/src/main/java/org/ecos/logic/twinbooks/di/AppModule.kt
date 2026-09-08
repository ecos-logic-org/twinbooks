package org.ecos.logic.twinbooks.di

import android.content.Context
import androidx.room.Room
import org.ecos.logic.twinbooks.data.local.ReadingSessionDao
import org.ecos.logic.twinbooks.data.local.TwinBooksDatabase
import org.ecos.logic.twinbooks.data.repository.BookRepositoryImpl
import org.ecos.logic.twinbooks.domain.model.BookRepository
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
        ).addMigrations(TwinBooksDatabase.MIGRATION_1_2).build()
    }

    @Provides
    fun provideReadingSessionDao(database: TwinBooksDatabase): ReadingSessionDao {
        return database.readingSessionDao()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBookRepository(impl: BookRepositoryImpl): BookRepository
}
