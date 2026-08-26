package com.anarky.showtrack.core.network.auth

import app.cash.turbine.test
import com.anarky.showtrack.core.model.AuthEvent
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.di.DaggerTestNetworkComponent
import com.anarky.showtrack.core.network.di.TestNetworkComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the real OkHttp call stack against a real (mock) server rather than calling
 * `authenticate()` directly: the two properties under test — that OkHttp collapses parallel 401s
 * into one refresh, and that it stops retrying — are properties of the interaction with OkHttp,
 * and a direct unit call would assert neither.
 *
 * `runBlocking`, not `runTest`: these tests are about genuine parallelism across OkHttp's own
 * worker threads. `runTest`'s virtual clock would auto-advance past the deliberate server-side
 * delays that make the single-flight race observable, and its scheduler would serialise anything
 * left on the test dispatcher — which is exactly the failure mode that hides the bug.
 */
class TokenRefreshAuthenticatorTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FakeTokenStore
    private lateinit var component: TestNetworkComponent
    private lateinit var events: AuthEventBus
    private lateinit var api: ShowTrackApi

    private val refreshRequests = AtomicInteger()
    private val libraryRequests = AtomicInteger()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = FakeTokenStore(TokenPair(access = EXPIRED_ACCESS, refresh = VALID_REFRESH))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the access token is attached to every request`() {
        store = FakeTokenStore(TokenPair(access = FRESH_ACCESS, refresh = VALID_REFRESH))
        buildApi()
        server.dispatcher = backend()

        runBlocking { api.library(cursor = null, limit = 20) }

        val recorded = server.takeRequest()
        assertEquals("Bearer $FRESH_ACCESS", recorded.headers["Authorization"])
    }

    @Test
    fun `five concurrent 401s trigger exactly one refresh`() {
        buildApi()
        server.dispatcher = backend()

        runBlocking {
            (1..CONCURRENT_CALLS)
                .map { async(Dispatchers.IO) { api.library(cursor = null, limit = 20) } }
                .awaitAll()
        }

        assertEquals("parallel 401s must collapse into ONE refresh", 1, refreshRequests.get())
        // Five rejected, five replayed: proof the other four callers reused the winner's token
        // rather than giving up.
        assertEquals(CONCURRENT_CALLS * 2, libraryRequests.get())
        assertEquals(TokenPair(FRESH_ACCESS, ROTATED_REFRESH), runBlocking { store.tokens() })
    }

    @Test
    fun `a failed refresh clears tokens and emits LoggedOut`() {
        buildApi()
        server.dispatcher = backend(refreshSucceeds = false)

        runBlocking {
            events.events.test(timeout = 10.seconds) {
                val failure = assertThrows(HttpException::class.java) { runBlocking { api.library(null, 20) } }
                assertEquals(401, failure.code())
                assertEquals(AuthEvent.LoggedOut, awaitItem())
            }
        }

        assertNull(runBlocking { store.tokens() })
        assertEquals(1, refreshRequests.get())
    }

    @Test
    fun `a 401 on the retried request does not loop`() {
        buildApi()
        server.dispatcher = backend(libraryAlwaysRejects = true)

        val failure = assertThrows(HttpException::class.java) { runBlocking { api.library(null, 20) } }

        assertEquals(401, failure.code())
        assertEquals("the refresh must not be attempted again for the replayed request", 1, refreshRequests.get())
        // The original plus exactly one replay. A third would mean the retry bound is gone.
        assertEquals(2, libraryRequests.get())
    }

    /**
     * Stopping is not enough. A freshly minted token that is itself rejected means the
     * credentials are dead, and leaving them in the store makes every LATER call repeat the
     * whole dance — refresh (rotating the refresh token server-side), 401, give up — with
     * nothing ever telling the app to show a login screen.
     */
    @Test
    fun `a 401 on the retried request also logs the user out`() {
        buildApi()
        server.dispatcher = backend(libraryAlwaysRejects = true)

        runBlocking {
            events.events.test(timeout = 10.seconds) {
                assertThrows(HttpException::class.java) { runBlocking { api.library(null, 20) } }
                assertEquals(AuthEvent.LoggedOut, awaitItem())
            }
        }

        assertNull("dead credentials must not be left in the store", runBlocking { store.tokens() })
    }

    /**
     * The PRODUCTION wiring, assembled by Dagger from the real [NetworkModule] — not a
     * hand-built copy of it.
     *
     * This distinction is the whole reason these tests are worth anything. A local
     * `OkHttpClient.Builder()` here would mirror the module's shape rather than exercise it, and
     * every regression that matters (a shared dispatcher, AuthApi on the wrong client, a dropped
     * `ignoreUnknownKeys`) would leave the suite green.
     */
    private fun buildApi() {
        component =
            DaggerTestNetworkComponent
                .factory()
                .create(store, server.url("/").toString())
        events = component.events()
        api = component.showTrackApi()
    }

    /**
     * A stateful dispatcher rather than a queue of canned responses: with five calls in flight
     * the arrival order is not deterministic, so an `enqueue` list would be asserting on thread
     * scheduling. This answers each request on its own merits — a library request is authorised
     * or it is not.
     */
    private fun backend(
        refreshSucceeds: Boolean = true,
        libraryAlwaysRejects: Boolean = false,
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            when (request.url.encodedPath) {
                "/v1/auth/refresh" -> {
                    refreshRequests.incrementAndGet()
                    if (refreshSucceeds) {
                        MockResponse
                            .Builder()
                            .code(200)
                            .setHeader("Content-Type", "application/json")
                            // Held open long enough that every concurrent caller is certainly
                            // inside authenticate() before the winner finishes. Without this the
                            // single-flight race is real but narrow, and a test that only
                            // sometimes observes it is not a test.
                            .headersDelay(REFRESH_DELAY_MS, TimeUnit.MILLISECONDS)
                            .body(ROTATED_TOKEN_PAIR_JSON)
                            .build()
                    } else {
                        MockResponse
                            .Builder()
                            .code(401)
                            .body("""{"detail":"invalid refresh token"}""")
                            .build()
                    }
                }

                "/v1/library" -> {
                    libraryRequests.incrementAndGet()
                    val authorised =
                        !libraryAlwaysRejects && request.headers["Authorization"] == "Bearer $FRESH_ACCESS"
                    if (authorised) {
                        MockResponse
                            .Builder()
                            .code(200)
                            .setHeader("Content-Type", "application/json")
                            .body("""{"items":[],"next_cursor":null}""")
                            .build()
                    } else {
                        MockResponse
                            .Builder()
                            .code(401)
                            // Staggers the 401s no more than a few ms apart, so all five callers
                            // reach the mutex together.
                            .headersDelay(UNAUTHORIZED_DELAY_MS, TimeUnit.MILLISECONDS)
                            .body("""{"detail":"Not authenticated"}""")
                            .build()
                    }
                }

                else -> MockResponse.Builder().code(404).build()
            }
    }

    private companion object {
        const val EXPIRED_ACCESS = "expired-access-token"
        const val FRESH_ACCESS = "fresh-access-token"
        const val VALID_REFRESH = "valid-refresh-token"
        const val ROTATED_REFRESH = "rotated-refresh-token"
        const val CONCURRENT_CALLS = 5

        // Includes token_type, which TokenPairDto does not model — so the refresh path is
        // exercised against the same unknown key the real server sends.
        const val ROTATED_TOKEN_PAIR_JSON =
            """{"access_token":"$FRESH_ACCESS",""" +
                """"refresh_token":"$ROTATED_REFRESH","token_type":"bearer"}"""
        const val REFRESH_DELAY_MS = 400L
        const val UNAUTHORIZED_DELAY_MS = 100L
    }
}

/**
 * `@Volatile` rather than a lock: the point of these tests is that the ONLY serialisation is the
 * authenticator's own mutex. A synchronised store would provide some of that guarantee itself and
 * quietly weaken the single-flight assertion.
 */
private class FakeTokenStore(
    initial: TokenPair?,
) : TokenStore {
    @Volatile
    private var pair: TokenPair? = initial

    override suspend fun tokens(): TokenPair? = pair

    override suspend fun save(
        access: String,
        refresh: String,
    ) {
        pair = TokenPair(access, refresh)
    }

    override suspend fun clear() {
        pair = null
    }
}
