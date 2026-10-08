package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.data.alerts.AlertSettingsStore
import com.anarky.showtrack.core.data.di.DataModule
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.LibraryRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces `:core:data`'s [DataModule] for every `@HiltAndroidTest` in this module's test source
 * set — `:feature:library`'s `TestDataModule` is the pattern (task 9c.0's brief). Binds the SAME
 * two fakes [ProfileViewModelTest]/[ProfileResumeTest] already use ([FakeAuthRepository],
 * [FakeLibraryRepository]), plus an [AlertSettingsStore] for the real `AlertsModule`'s
 * `AlertScheduler` (the ViewModel's alerts switch). That scheduler reaches WorkManager only through
 * `dagger.Lazy`, so nothing in these tests initialises it.
 *
 * `@Provides`, not `@Binds` + `@Inject constructor()`: both fakes predate this task and take every
 * constructor parameter with a Kotlin default rather than an `@Inject`-annotated no-arg
 * constructor — changing either class (shared by the existing ViewModel tests) is more than this
 * harness needs, so a plain factory method is the smaller change.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
object TestDataModule {
    // PUBLIC, unlike :feature:library's `internal abstract fun` `@Binds` methods: those bind an
    // INTERNAL implementation type as a parameter, which forces `internal` to avoid "exposes its
    // 'internal' parameter type" — a `@Provides` method here returns the PUBLIC interface type
    // with no internal type in its signature, and `internal` on it triggers a Dagger/KSP codegen
    // name-mangling bug instead (measured: the generated factory referenced a method name that did
    // not exist, `cannot find symbol ...authRepository$profile_debugUnitTest()`).
    @Provides
    fun authRepository(): AuthRepository = FakeAuthRepository()

    @Provides
    fun libraryRepository(): LibraryRepository = FakeLibraryRepository()

    // AlertScheduler (the real one, from AlertsModule) needs it; nothing here touches WorkManager.
    @Provides
    fun alertSettingsStore(): AlertSettingsStore = FakeAlertSettingsStore()
}
