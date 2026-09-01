package com.anarky.showtrack.feature.library

import com.anarky.showtrack.core.data.di.DataModule
import com.anarky.showtrack.core.data.repository.LibraryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/**
 * Replaces `:core:data`'s [DataModule] for every `@HiltAndroidTest` in this module's test source
 * set. `@TestInstallIn(replaces = [DataModule::class])` rather than `@UninstallModules` plus a
 * second `@Module`: the effect is the same (the real module is dropped from the graph and this
 * one stands in), but `@TestInstallIn` needs no matching annotation on the test class itself.
 *
 * Only [LibraryRepository] is bound, not the other five interfaces [DataModule] provides
 * (`AuthRepository`, `MediaRepository`, `PushRepository`, `PushRegistrationStore`,
 * `AuthEventSource`). That is deliberate, not an oversight: Dagger only has to satisfy a binding
 * that is actually REQUESTED somewhere in the compiled graph, and nothing [LibraryEntryHiltTest]
 * composes asks for any of the other five — `LibraryViewModel`'s constructor names only
 * `LibraryRepository`. Binding the rest here would buy nothing and would have to invent fakes for
 * types this test never touches.
 *
 * This is also why the real `NetworkModule`/`DatabaseModule`/`TokenStoreModule` (reachable from
 * this module at runtime, even though architecture rule 2 keeps them off its compile classpath)
 * are harmless left installed: nothing requests `TokenStore`, `AuthApi`, or `ShowTrackDatabase`
 * either, so Dagger never constructs `KeystoreSecretKeySource` — which has no Robolectric shadow
 * for `AndroidKeyStore` and would fail if it were ever actually built under this test.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
abstract class TestDataModule {
    // `internal`, matching FakeLibraryRepository's own visibility: a `public` signature naming an
    // `internal` parameter type does not compile ("exposes its 'internal' parameter type"), and
    // Dagger's KSP-generated component is compiled into this same test source set, so `internal`
    // is visible to it regardless.
    @Binds
    internal abstract fun libraryRepository(impl: FakeLibraryRepository): LibraryRepository
}
