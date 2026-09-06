package com.anarky.showtrack.feature.feed

import com.anarky.showtrack.core.data.di.DataModule
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces `:core:data`'s [DataModule] for every `@HiltAndroidTest` in this module's test source
 * set — `:feature:groups`' `TestDataModule` is the pattern (task 9c.0's brief). Deliberately EMPTY:
 * [FeedEntryHiltTest] supplies every binding it needs directly via `@BindValue` instead —
 * `@BindValue` needs [DataModule] out of the way, not a replacement binding of its own, for
 * `GroupRepository` — see `:feature:groups`' `TestDataModule` for why a module-wide `@Provides`
 * default here does not compose with `@BindValue` (`[Dagger/DuplicateBindings]`).
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
object TestDataModule
