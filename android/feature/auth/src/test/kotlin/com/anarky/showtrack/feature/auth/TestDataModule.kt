package com.anarky.showtrack.feature.auth

import com.anarky.showtrack.core.data.di.DataModule
import com.anarky.showtrack.core.data.repository.AuthRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces `:core:data`'s [DataModule] for every `@HiltAndroidTest` in this module's test source
 * set — `:feature:library`'s `TestDataModule` is the pattern (task 9c.0's brief). Only
 * [AuthRepository] is bound: `AuthViewModel`'s constructor names only that one interface, so
 * nothing [AuthEntryHiltTest] composes ever asks the graph for anything else [DataModule] provides.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
abstract class TestDataModule {
    @Binds
    internal abstract fun authRepository(impl: EntryFakeAuthRepository): AuthRepository
}
