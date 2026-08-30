package com.anarky.showtrack

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.anarky.showtrack.core.data.push.PushSessionObserver
import com.anarky.showtrack.core.network.di.PlainClient
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * The root of the dependency graph. `@HiltAndroidApp` is what makes Hilt generate the singleton
 * component at compile time by aggregating every `@InstallIn(SingletonComponent::class)` module
 * on this module's runtime classpath — `NetworkModule`, `NetworkConfigModule`, `TokenStoreModule`,
 * `DatabaseModule` and `DataModule` all arrive here transitively, none of them named by hand.
 * That aggregation is why an unsatisfied binding anywhere in the app surfaces as a `:app` compile
 * error rather than at runtime.
 */
@HiltAndroidApp
class ShowTrackApplication :
    Application(),
    SingletonImageLoader.Factory {
    /**
     * `@PlainClient`, and this is a security constraint rather than a preference.
     *
     * The `@AuthenticatedClient` OkHttp instance attaches the user's bearer token via
     * `AuthInterceptor` to every request it is asked to make. Handing it to Coil would send that
     * access token to every poster CDN the app loads art from — AniList's and TMDB's image hosts,
     * i.e. third parties with no business holding a ShowTrack credential. `AuthInterceptor` and
     * `TokenRefreshAuthenticator` both carry an `ApiHost` check that would stop the header going
     * out, but a backstop is not a licence: images use the unauthenticated client.
     *
     * `dagger.Lazy` because `newImageLoader` is called on the first image, not at startup, and
     * there is no reason for `Application.onCreate` to stand up OkHttp for a screen that may
     * never show a poster.
     */
    @Inject
    @PlainClient
    lateinit var plainClient: Lazy<OkHttpClient>

    /**
     * Started here rather than from a screen, and it is not arbitrary: `AuthEventBus` has
     * `replay = 0`, so a subscriber that arrives after the event misses it entirely. `onCreate`
     * is the only place guaranteed to be earlier than any request that could 401.
     *
     * NOT `Lazy`, unlike [plainClient] above: the whole point is to be subscribed before the
     * event, so deferring construction to first use would defeat it.
     */
    @Inject
    lateinit var pushSessionObserver: PushSessionObserver

    /**
     * Process-lifetime, never cancelled — which is correct for exactly one subscriber that must
     * outlive every screen, and would be a leak for anything else. `SupervisorJob` so a failure
     * in the collector cannot take a sibling down with it; `Dispatchers.IO` because the work it
     * triggers is an HTTP DELETE and a DataStore write.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        pushSessionObserver.start(applicationScope)
    }

    /**
     * Reusing the app's OkHttp rather than letting Coil build its own means ONE connection pool
     * and one dispatcher thread pool for the process. Coil's default would create a second of
     * each, which for an image-heavy list is a real duplication rather than a tidiness argument.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { plainClient.get() }))
            }.build()
}
