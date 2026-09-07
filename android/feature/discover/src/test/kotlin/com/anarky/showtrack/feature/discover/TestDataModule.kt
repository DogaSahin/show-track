package com.anarky.showtrack.feature.discover

import com.anarky.showtrack.core.data.di.DataModule
import com.anarky.showtrack.core.data.repository.LibraryRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces `:core:data`'s [DataModule] for every `@HiltAndroidTest` in this module's test source
 * set — `:feature:library`'s `TestDataModule` is the pattern (task 9c.0's brief).
 * `RecommendationRepository` is NOT bound here: [DiscoverEntryHiltTest] supplies it via
 * `@BindValue` instead, seeded with data before injection — see [EntryFakeRecommendationRepository]'s
 * own KDoc for why. `LibraryRepository` needs no per-test data, so a plain `@Provides` suffices.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
object TestDataModule {
    @Provides
    fun libraryRepository(): LibraryRepository = EntryFakeLibraryRepository()
}
