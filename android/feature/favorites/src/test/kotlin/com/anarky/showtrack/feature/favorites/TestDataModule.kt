package com.anarky.showtrack.feature.favorites

import com.anarky.showtrack.core.data.di.DataModule
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces `:core:data`'s [DataModule] for every `@HiltAndroidTest` in this module's test source
 * set — `:feature:library`'s `TestDataModule` is the pattern (task 9c.0's brief). Deliberately
 * EMPTY: `FavoritesViewModel`'s constructor names only `LibraryRepository`, and
 * [FavoritesEntryHiltTest] supplies that directly via `@BindValue` on the existing
 * [FakeLibraryRepository] rather than through a `@Provides` method here — `@BindValue` needs
 * [DataModule] out of the way, not a replacement binding of its own.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
object TestDataModule
