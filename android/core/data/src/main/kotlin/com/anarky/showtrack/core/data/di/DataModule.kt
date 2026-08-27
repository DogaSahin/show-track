package com.anarky.showtrack.core.data.di

import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.LibraryRepositoryImpl
import com.anarky.showtrack.core.database.LibraryDao
import com.anarky.showtrack.core.network.api.ShowTrackApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    /**
     * `@Singleton` is load-bearing, not a default: `LibraryRepositoryImpl` holds the paginator's
     * cursor and accumulated pages in memory, so an unscoped binding would hand each ViewModel a
     * repository that starts from page one and never sees what another already loaded.
     *
     * `@Provides` rather than `@Binds` + `@Inject constructor` so the implementation stays a
     * plain class with no DI annotations — it is constructed directly in its own test.
     */
    @Provides
    @Singleton
    fun libraryRepository(
        api: ShowTrackApi,
        dao: LibraryDao,
    ): LibraryRepository = LibraryRepositoryImpl(api, dao)
}
