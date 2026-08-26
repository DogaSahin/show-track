package com.anarky.showtrack.core.network.di

import com.anarky.showtrack.core.network.auth.TokenPair
import com.anarky.showtrack.core.network.auth.TokenStore
import com.anarky.showtrack.core.network.dto.LoginRequest
import com.anarky.showtrack.core.network.dto.RefreshRequest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Tests of the MODULE, assembled by Dagger — not of a copy of it.
 *
 * Everything here defends an invariant whose violation is silent: a shared dispatcher deadlocks
 * only under five concurrent 401s, the wrong client for `AuthApi` deadlocks only when a token
 * expires, and a dropped `ignoreUnknownKeys` fails only on the first real refresh. None of them
 * shows up in a build, a lint run, or a test that hand-builds its own OkHttp clients.
 */
class NetworkModuleTest {
    private lateinit var server: MockWebServer
    private lateinit var component: TestNetworkComponent

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        component =
            DaggerTestNetworkComponent
                .factory()
                .create(StoredTokens, server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.close()
    }

    /**
     * The dispatcher caps concurrent calls per host at 5, and the five 401'd library calls are
     * still counted as running while the authenticator works — so a refresh issued on a client
     * sharing that dispatcher queues behind the very calls waiting on it, forever. The pool is
     * shared because sockets should be; the dispatcher must not be.
     */
    @Test
    fun `the two clients share a connection pool but not a dispatcher`() {
        val plain = component.plainClient()
        val authenticated = component.authenticatedClient()

        assertNotSame(
            "a shared dispatcher queues the refresh behind the calls waiting on it",
            plain.dispatcher,
            authenticated.dispatcher,
        )
        assertSame(plain.connectionPool, authenticated.connectionPool)
    }

    /**
     * `AuthApi` must be on the client with no interceptor and no authenticator. Served by the
     * authenticated one, a 401 from `/v1/auth/refresh` re-enters the authenticator on another
     * thread and blocks on the mutex the outer refresh holds.
     *
     * The observable difference is the header, which is why that is what is asserted — a direct
     * call to `NetworkModule.authApi(...)` would pass either way, because a `@Provides`
     * parameter's qualifier means nothing outside a graph.
     */
    @Test
    fun `the token endpoints are served by a client that attaches no credentials`() {
        server.enqueue(MockResponse.Builder().code(204).build())

        runBlocking { component.authApi().logout(RefreshRequest(StoredTokens.pair.refresh)) }

        val recorded = server.takeRequest()
        assertEquals("/v1/auth/logout", recorded.url.encodedPath)
        assertNull(
            "AuthApi must not be on the authenticated client",
            recorded.headers["Authorization"],
        )
    }

    /** The other half of the same invariant: the API surface DOES get the token. */
    @Test
    fun `the api endpoints are served by a client that attaches the access token`() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"items":[],"next_cursor":null}""")
                .build(),
        )

        runBlocking { component.showTrackApi().library(cursor = null, limit = 20) }

        assertEquals("Bearer ${StoredTokens.pair.access}", server.takeRequest().headers["Authorization"])
    }

    /**
     * The real server sends `token_type`, which `TokenPairDto` does not model. Delete
     * `ignoreUnknownKeys` from `NetworkModule.json()` and this is the test that fails — where
     * production would instead fail on the first refresh after an access token expired, and log
     * the user out with nothing in the logs.
     */
    @Test
    fun `the shared Json tolerates a field the DTOs do not model`() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"access_token":"a-2","refresh_token":"r-2","token_type":"bearer"}""")
                .build(),
        )

        val pair = runBlocking { component.authApi().refresh(RefreshRequest("r-1")) }

        assertEquals("a-2", pair.accessToken)
        assertEquals("r-2", pair.refreshToken)
    }

    /** Encoding goes through the same instance, so the request bodies are covered by it too. */
    @Test
    fun `the shared Json encodes request bodies as the server expects`() {
        server.enqueue(MockResponse.Builder().code(401).build())

        runCatching { runBlocking { component.authApi().login(LoginRequest("a@b.example", "p")) } }

        assertEquals(
            """{"email":"a@b.example","password":"p"}""",
            server.takeRequest().body?.utf8(),
        )
    }

    /**
     * The guard that makes it safe for a later task to hand this client to an image loader.
     * MockWebServer answers on `localhost`, so `127.0.0.1` is the same socket under a different
     * host — a third party as far as the credential rule is concerned.
     */
    @Test
    fun `the authenticated client attaches nothing to a host that is not ours`() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("{}")
                .build(),
        )
        val foreign =
            server
                .url("/v1/library")
                .newBuilder()
                .host("127.0.0.1")
                .build()

        component
            .authenticatedClient()
            .newCall(Request.Builder().url(foreign).build())
            .execute()
            .close()

        assertNull(
            "the access token must never leave our own host",
            server.takeRequest().headers["Authorization"],
        )
    }

    private object StoredTokens : TokenStore {
        val pair = TokenPair(access = "access-1", refresh = "refresh-1")

        override suspend fun tokens(): TokenPair = pair

        override suspend fun save(
            access: String,
            refresh: String,
        ) = Unit

        override suspend fun clear() = Unit
    }
}
