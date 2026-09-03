package com.anarky.showtrack.feature.groups

import com.anarky.showtrack.core.data.di.DataModule
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces `:core:data`'s [DataModule] for every `@HiltAndroidTest` in this module's test source
 * set — `:feature:favorites`'s `TestDataModule` is the pattern (task 9c.0's brief). Deliberately
 * EMPTY: [GroupsViewModel]'s constructor names only `GroupRepository`, and [GroupsEntryHiltTest]
 * supplies that directly via `@BindValue` on [FakeGroupRepository] rather than through a
 * `@Provides` method here — `@BindValue` needs [DataModule] out of the way, not a replacement
 * binding of its own.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
object TestDataModule
