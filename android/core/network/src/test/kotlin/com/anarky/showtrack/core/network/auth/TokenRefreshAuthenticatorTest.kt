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
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import retrofit2.HttpException
import java.io.IOException
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

    /**
     * Every failure this class defends against is a DEADLOCK, and a deadlock's natural expression
     * is a hang: a shared OkHttp dispatcher exhausts its five-per-host slots and no thread is left
     * to run the refresh, so nothing times out because no call ever starts. This class runs first
     * in the module, so without this rule an unfiltered `testDebugUnitTest` writes no result XML
     * at all and CI burns to its job limit — which reads as infrastructure flakiness, the one
     * failure people retry instead of investigate.
     *
     * 30s against a normal worst case of ~1s. JUnit runs each method on its own thread and fails
     * it on expiry, so the stuck OkHttp threads are left behind but the run continues.
     */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

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

        runBlocking { callLibrary() }

        val recorded = server.takeRequest()
        assertEquals("Bearer $FRESH_ACCESS", recorded.headers["Authorization"])
    }

    @Test
    fun `five concurrent 401s trigger exactly one refresh`() {
        buildApi()
        server.dispatcher = backend()

        runBlocking {
            (1..CONCURRENT_CALLS)
                .map { async(Dispatchers.IO) { callLibrary() } }
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
                val failure = assertThrows(HttpException::class.java) { runBlocking { callLibrary() } }
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

        val failure = assertThrows(HttpException::class.java) { runBlocking { callLibrary() } }

        assertEquals(401, failure.code())
        assertEquals("the refresh must not be attempted again for the replayed request", 1, refreshRequests.get())
        // The original plus exactly one replay. A third would mean the retry bound is gone.
        assertEquals(2, libraryRequests.get())
    }

    /**
     * The token read is a DISK read, and it happens on an OkHttp dispatcher thread. OkHttp's
     * `AsyncCall.run` rethrows a `Throwable` from the chain after `onFailure`, and on Android an
     * uncaught throwable on that executor kills the process — so a corrupt token file must log the
     * user out, not take the app down. Move `tokens.tokens()` back outside the `try` and this is
     * the test that notices.
     */
    @Test
    fun `a token store that fails to read logs the user out rather than escaping`() {
        buildApi()
        server.dispatcher = backend()
        // The interceptor's read succeeds and the request goes out authenticated; the
        // authenticator's read — the one inside the try — is the one that fails.
        store.failReadsAfter(1)

        runBlocking {
            events.events.test(timeout = 10.seconds) {
                // HttpException, not IOException: the failure was handled and the ORIGINAL 401 is
                // what surfaces. An IOException here would mean the throwable escaped.
                val failure = assertThrows(HttpException::class.java) { runBlocking { callLibrary() } }
                assertEquals(401, failure.code())
                assertEquals(AuthEvent.LoggedOut, awaitItem())
            }
        }

        assertEquals("an unreadable store has nothing to refresh with", 0, refreshRequests.get())
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
                assertThrows(HttpException::class.java) { runBlocking { callLibrary() } }
                assertEquals(AuthEvent.LoggedOut, awaitItem())
            }
        }

        assertNull("dead credentials must not be left in the store", runBlocking { store.tokens() })
    }

    /**
     * A weak signal or a proxy mid-redeploy says nothing about the refresh token, which is still
     * valid. Signing out here sends the user to the password screen for a network problem.
     */
    @Test
    fun `a dropped connection during refresh keeps the user signed in`() {
        buildApi()
        server.dispatcher =
            backend(refreshAnswer = { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() })

        // IOException, not HttpException: the refresh never produced a response. What matters is
        // that the session survives, so only that is asserted.
        runBlocking {
            events.events.test(timeout = 10.seconds) {
                runCatching { callLibrary() }
                expectNoEvents()
            }
        }
        assertEquals(TokenPair(EXPIRED_ACCESS, VALID_REFRESH), runBlocking { store.tokens() })
    }

    @Test
    fun `a 500 during refresh keeps the user signed in`() {
        buildApi()
        server.dispatcher = backend(refreshAnswer = status(500))
        assertStaysSignedIn()
    }

    @Test
    fun `a 502 during refresh keeps the user signed in`() {
        buildApi()
        server.dispatcher = backend(refreshAnswer = status(502))
        assertStaysSignedIn()
    }

    @Test
    fun `a 503 during refresh keeps the user signed in`() {
        buildApi()
        server.dispatcher = backend(refreshAnswer = status(503))
        assertStaysSignedIn()
    }

    @Test
    fun `a 408 during refresh keeps the user signed in`() {
        buildApi()
        server.dispatcher = backend(refreshAnswer = status(408))
        assertStaysSignedIn()
    }

    @Test
    fun `a 429 during refresh keeps the user signed in`() {
        buildApi()
        server.dispatcher = backend(refreshAnswer = status(429))
        assertStaysSignedIn()
    }

    /** A 2xx that is not a token pair: the server may have rotated, but the next refresh settles it. */
    @Test
    fun `an unreadable refresh response keeps the user signed in`() {
        buildApi()
        server.dispatcher =
            backend(
                refreshAnswer = {
                    MockResponse
                        .Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body("not json")
                        .build()
                },
            )
        assertStaysSignedIn()
    }

    @Test
    fun `a 400 from refresh signs the user out`() {
        buildApi()
        server.dispatcher = backend(refreshAnswer = status(400))
        assertSignedOut()
    }

    @Test
    fun `a 401 from refresh signs the user out`() {
        buildApi()
        server.dispatcher = backend(refreshAnswer = status(401))
        assertSignedOut()
    }

    @Test
    fun `a 403 from refresh signs the user out`() {
        buildApi()
        server.dispatcher = backend(refreshAnswer = status(403))
        assertSignedOut()
    }

    /**
     * Sign-out cleared the store while this refresh was on the wire. Writing the new pair back
     * would sign the user in again behind the login screen; the server already ended the session.
     */
    @Test
    fun `a refresh that finishes after sign-out writes nothing back`() {
        buildApi()
        server.dispatcher = backend(beforeRefreshAnswers = { runBlocking { store.clear() } })

        runBlocking {
            events.events.test(timeout = 10.seconds) {
                val failure = assertThrows(HttpException::class.java) { runBlocking { callLibrary() } }
                assertEquals(401, failure.code())
                expectNoEvents()
            }
        }
        assertNull("a lost compare-and-set must not resurrect the session", runBlocking { store.tokens() })
        assertEquals("no replay without a saved token", 1, libraryRequests.get())
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

    private fun status(code: Int) =
        {
            MockResponse
                .Builder()
                .code(code)
                .body("""{"detail":"x"}""")
                .build()
        }

    /** Expects the original 401 to surface, no LoggedOut, and the stored pair untouched. */
    private fun assertStaysSignedIn() {
        runBlocking {
            events.events.test(timeout = 10.seconds) {
                val failure = assertThrows(HttpException::class.java) { runBlocking { callLibrary() } }
                assertEquals(401, failure.code())
                expectNoEvents()
            }
        }
        assertEquals(TokenPair(EXPIRED_ACCESS, VALID_REFRESH), runBlocking { store.tokens() })
    }

    /** Expects the original 401 to surface with LoggedOut, and the store cleared. */
    private fun assertSignedOut() {
        runBlocking {
            events.events.test(timeout = 10.seconds) {
                val failure = assertThrows(HttpException::class.java) { runBlocking { callLibrary() } }
                assertEquals(401, failure.code())
                assertEquals(AuthEvent.LoggedOut, awaitItem())
            }
        }
        assertNull(runBlocking { store.tokens() })
    }

    /** Every filter left null: these tests exercise the auth stack, not the library filters. */
    private suspend fun callLibrary() =
        api.library(cursor = null, limit = 20, status = null, sort = null, mediaId = null, favorite = null, type = null)

    /**
     * A stateful dispatcher rather than a queue of canned responses: with five calls in flight
     * the arrival order is not deterministic, so an `enqueue` list would be asserting on thread
     * scheduling. This answers each request on its own merits — a library request is authorised
     * or it is not.
     */
    private fun backend(
        refreshSucceeds: Boolean = true,
        libraryAlwaysRejects: Boolean = false,
        refreshAnswer: (() -> MockResponse)? = null,
        beforeRefreshAnswers: () -> Unit = {},
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            when (request.url.encodedPath) {
                "/v1/auth/refresh" -> {
                    refreshRequests.incrementAndGet()
                    beforeRefreshAnswers()
                    refreshAnswer?.invoke() ?: if (refreshSucceeds) {
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

    @Volatile
    private var readsBeforeFailure: Int = Int.MAX_VALUE
    private val reads = AtomicInteger()

    /**
     * Reads succeed [count] times and throw after that.
     *
     * Counted rather than a simple on/off switch so a failure can be aimed at the AUTHENTICATOR's
     * read specifically. [AuthInterceptor] reads first, on its own thread and outside any
     * try/catch of ours; failing that read would test a different path and never reach the one
     * under test.
     */
    fun failReadsAfter(count: Int) {
        readsBeforeFailure = count
    }

    override suspend fun tokens(): TokenPair? {
        // IOException because that is what an unreadable DataStore file raises.
        if (reads.incrementAndGet() > readsBeforeFailure) throw IOException("token store unreadable")
        return pair
    }

    override suspend fun save(
        access: String,
        refresh: String,
    ) {
        pair = TokenPair(access, refresh)
    }

    override suspend fun replace(
        expectedRefresh: String,
        access: String,
        refresh: String,
    ): Boolean {
        if (pair?.refresh != expectedRefresh) return false
        pair = TokenPair(access, refresh)
        return true
    }

    override suspend fun clear() {
        pair = null
    }
}
