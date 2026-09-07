package com.anarky.showtrack.core.network.di

import com.anarky.showtrack.core.network.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The one binding in this package that is a function of the BUILD rather than of its inputs.
 *
 * Split out of [NetworkModule] deliberately: with the base URL arriving as a binding, every
 * provider in [NetworkModule] is a pure function of its arguments, so a test can assemble the
 * real graph against a `MockWebServer` by supplying this one value itself. Fold it back in and
 * the module becomes untestable — which is how it came to have no coverage at all.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkConfigModule {
    @Provides
    @Singleton
    @BaseUrl
    fun baseUrl(): String = BuildConfig.API_BASE_URL
}
