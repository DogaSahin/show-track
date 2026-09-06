package com.anarky.showtrack.feature.groups

import com.anarky.showtrack.core.data.di.DataModule
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces `:core:data`'s [DataModule] for every `@HiltAndroidTest` in this module's test source
 * set — `:feature:favorites`'s `TestDataModule` is the pattern (task 9c.0's brief). Deliberately
 * EMPTY, including for `AuthRepository` (round 1 review tried a module-wide `@Provides` default
 * here first — see git history — and it does not compose with `@BindValue`: Dagger reports
 * `[Dagger/DuplicateBindings]` the moment ANY test in the module also `@BindValue`s the same type,
 * since `@BindValue`'s own generated binding does not OVERRIDE a `@Provides` for the identical
 * type, it competes with it). [GroupsEntryHiltTest]/[GroupDetailEntryHiltTest] each supply every
 * binding they need directly via `@BindValue` instead — `@BindValue` needs [DataModule] out of the
 * way, not a replacement binding of its own, for EITHER type this module's `@HiltViewModel`s need.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
object TestDataModule
