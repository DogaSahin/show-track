package com.anarky.showtrack.core.network.di

import com.anarky.showtrack.core.network.api.AuthApi
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.auth.AuthEventBus
import com.anarky.showtrack.core.network.auth.TokenRefreshAuthenticator
import com.anarky.showtrack.core.network.auth.TokenStore
import dagger.BindsInstance
import dagger.Component
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * A real Dagger graph over the real [NetworkModule].
 *
 * The point is the qualifiers. `NetworkModule.authApi(@PlainClient client, ...)` called as a
 * plain Kotlin function ignores its own annotations entirely — a test that does that will pass
 * with `@AuthenticatedClient` on that parameter, which is a production deadlock. Only a graph
 * resolves the qualifier, so only a graph can defend it.
 *
 * Everything Android-dependent is supplied from outside: [TokenStore] as a fake, and the base URL
 * as a `MockWebServer` address ([NetworkConfigModule] is deliberately not in the module list, and
 * exists so that this is possible).
 */
@Singleton
@Component(modules = [NetworkModule::class])
internal interface TestNetworkComponent {
    fun authApi(): AuthApi

    fun events(): AuthEventBus

    fun showTrackApi(): ShowTrackApi

    fun json(): Json

    fun authenticator(): TokenRefreshAuthenticator

    @PlainClient
    fun plainClient(): OkHttpClient

    @AuthenticatedClient
    fun authenticatedClient(): OkHttpClient

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance tokens: TokenStore,
            @BindsInstance @BaseUrl baseUrl: String,
        ): TestNetworkComponent
    }
}
