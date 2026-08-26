package com.anarky.showtrack.core.network.di

import com.anarky.showtrack.core.network.BuildConfig
import com.anarky.showtrack.core.network.api.AuthApi
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.auth.AuthInterceptor
import com.anarky.showtrack.core.network.auth.DataStoreTokenStore
import com.anarky.showtrack.core.network.auth.KeystoreSecretKeySource
import com.anarky.showtrack.core.network.auth.SecretKeySource
import com.anarky.showtrack.core.network.auth.TokenRefreshAuthenticator
import com.anarky.showtrack.core.network.auth.TokenStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    /**
     * `ignoreUnknownKeys = true` and nothing else, on purpose. It makes the contract asymmetric
     * in the direction that matches how the backend evolves: a field ADDED server-side is
     * ignored, while a field REMOVED fails loudly at the first decode instead of silently
     * becoming null. `explicitNulls` is left at its default for the same reason — turning it off
     * would make a dropped field indistinguishable from a null one.
     */
    @Provides
    @Singleton
    fun json(): Json =
        Json {
            ignoreUnknownKeys = true
        }

    /**
     * BASIC, never BODY, and only in debug. Both the request headers and the auth response
     * bodies carry tokens, so a body/header log is a credential in logcat.
     */
    @Provides
    @Singleton
    fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

    /**
     * Shared between the two clients so one pool of sockets serves both. The DISPATCHERS are
     * deliberately NOT shared, which is why this is a separate binding instead of
     * `plain.newBuilder()`.
     *
     * `newBuilder()` copies the dispatcher by reference, and OkHttp's dispatcher caps concurrent
     * calls per host at 5. Five library requests that all 401 are still "running" while the
     * authenticator works — so a refresh issued on a client sharing that dispatcher would be
     * queued behind the very calls waiting on it, and the authenticator would block forever.
     * Two dispatchers, one connection pool.
     */
    @Provides
    @Singleton
    fun connectionPool(): ConnectionPool = ConnectionPool()

    @Provides
    @Singleton
    @PlainClient
    fun plainClient(
        pool: ConnectionPool,
        logging: HttpLoggingInterceptor,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectionPool(pool)
            .addInterceptor(logging)
            .build()

    @Provides
    @Singleton
    @AuthenticatedClient
    fun authenticatedClient(
        pool: ConnectionPool,
        logging: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor,
        authenticator: TokenRefreshAuthenticator,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectionPool(pool)
            .addInterceptor(logging)
            .addInterceptor(authInterceptor)
            .authenticator(authenticator)
            .build()

    /**
     * `@PlainClient`, and this is load-bearing rather than a preference: served by the
     * authenticated client, a 401 from `/v1/auth/refresh` would re-enter
     * [com.anarky.showtrack.core.network.auth.TokenRefreshAuthenticator] on another OkHttp
     * thread and block on the mutex the outer refresh still holds. NetworkModuleTest asserts
     * the login request carries no Authorization header, which is what that swap would change.
     */
    @Provides
    @Singleton
    fun authApi(
        @PlainClient client: OkHttpClient,
        json: Json,
        @BaseUrl baseUrl: String,
    ): AuthApi = retrofit(client, json, baseUrl).create(AuthApi::class.java)

    @Provides
    @Singleton
    fun showTrackApi(
        @AuthenticatedClient client: OkHttpClient,
        json: Json,
        @BaseUrl baseUrl: String,
    ): ShowTrackApi = retrofit(client, json, baseUrl).create(ShowTrackApi::class.java)

    private fun retrofit(
        client: OkHttpClient,
        json: Json,
        baseUrl: String,
    ): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
}

/**
 * The Android-backed half, kept separate from [NetworkModule] so the latter stays free of any
 * binding that needs a `Context` — which is what lets NetworkModuleTest assemble it on the JVM.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TokenStoreModule {
    @Binds
    @Singleton
    abstract fun tokenStore(impl: DataStoreTokenStore): TokenStore

    @Binds
    @Singleton
    abstract fun secretKeySource(impl: KeystoreSecretKeySource): SecretKeySource
}
