package com.anarky.showtrack.feature.search

import com.anarky.showtrack.core.data.di.DataModule
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces `:core:data`'s [DataModule] for every `@HiltAndroidTest` in this module's test source
 * set — `:feature:library`'s `TestDataModule` is the pattern (task 9c.0's brief). Deliberately
 * EMPTY: `SearchViewModel`'s constructor names `MediaRepository` and `LibraryRepository`, and
 * [SearchEntryHiltTest] supplies both via `@BindValue`, seeded with data before injection — see
 * [EntryFakeMediaRepository]/[EntryFakeLibraryRepository]'s own KDoc for why.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
object TestDataModule
