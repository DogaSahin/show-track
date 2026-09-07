package com.anarky.showtrack.feature.profile.push

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The only Hilt module a feature module owns, and it is here rather than in `:core:data` for a
 * reason worth stating: [DistributorSource] is not data access. It queries the device's installed
 * packages and talks to another app, which is UI-adjacent configuration, not a repository. Moving
 * it into `:core:data` would put the UnifiedPush connector on the classpath of every feature that
 * touches data, to serve one screen.
 *
 * `SingletonComponent` and not `ViewModelComponent`: the same choice must be visible to the
 * receiver's process as to the screen's, and the underlying state (which distributor is saved)
 * lives in the connector's own SharedPreferences either way.
 *
 * No `@Singleton` on the method — the scope sits on [UnifiedPushDistributorSource], for
 * `DataModule`'s reason: a scoped `@Binds` scopes only the interface, leaving a direct injection
 * of the concrete type unscoped.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PushModule {
    @Binds
    abstract fun distributorSource(impl: UnifiedPushDistributorSource): DistributorSource
}
