package com.anarky.showtrack.core.database.di

import android.content.Context
import androidx.room.Room
import com.anarky.showtrack.core.database.LibraryDao
import com.anarky.showtrack.core.database.ShowTrackDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private const val DATABASE_NAME = "showtrack.db"

    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): ShowTrackDatabase =
        Room
            .databaseBuilder(context, ShowTrackDatabase::class.java, DATABASE_NAME)
            // Destructive, not a migration: every row is fully reconstructible from the next
            // successful `replaceAll`, so a version bump has nothing worth preserving across it —
            // see ShowTrackDatabase's `exportSchema = false` for the other half of that call.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    fun libraryDao(database: ShowTrackDatabase): LibraryDao = database.libraryDao()
}
