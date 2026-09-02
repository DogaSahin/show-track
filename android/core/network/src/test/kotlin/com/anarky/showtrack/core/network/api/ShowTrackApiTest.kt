package com.anarky.showtrack.core.network.api

import com.anarky.showtrack.core.network.dto.CreateGroupRequestDto
import com.anarky.showtrack.core.network.dto.ImportAniListRequest
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * MockWebServer over the real interface, the same shape as [AuthApiTest] — plus fixture loading
 * off the classpath, the same shape as `dto.WireContractTest`. `media_search.json` was captured
 * from the real `/v1/media/search` route with a stub provider registry (never from a hand-typed
 * body), so a decode failure here means the DTOs drifted from what the server actually sends.
 */
class ShowTrackApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ShowTrackApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true }
        api =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ShowTrackApi::class.java)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "missing fixture $name"
        }.use { it.readBytes().decodeToString() }

    @Test
    fun `a search response decodes, including the per-provider sources map`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(fixture("wire/media_search.json"))
                    .build(),
            )

            val response = api.searchMedia(query = "frieren", page = 1)

            // The fields the UI actually reads. `sources["tmdb"]` is pinned to the exact
            // degraded value the fixture was captured with — `isNotEmpty()` would still pass on
            // a fixture where every provider came back "ok", which defeats the point of
            // capturing the fixture with a failing provider in the first place (decision C-O).
            assertEquals(2, response.items.size)
            assertEquals("timeout", response.sources["tmdb"])
        }

    @Test
    fun `the library call sends every filter as a query parameter`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"items":[],"next_cursor":null}""")
                    .build(),
            )

            api.library(
                cursor = null,
                limit = 20,
                status = "watching",
                sort = "score",
                mediaId = "m-1",
                favorite = true,
            )

            val url = server.takeRequest().url
            assertEquals("watching", url.queryParameter("status"))
            assertEquals("score", url.queryParameter("sort"))
            assertEquals("m-1", url.queryParameter("media_id"))
            assertEquals("true", url.queryParameter("favorite"))
            // The point of passing null rather than "null": an absent filter must not appear.
            assertNull(url.queryParameter("cursor"))
        }

    /**
     * The half the test above cannot cover: [ShowTrackApi.library]'s KDoc warns that the backend
     * resolves `favorite` with `is not None`, so `false` is a REAL filter ("non-favourites"), not
     * "unset" — it must reach the wire as the literal string `"false"`, never be dropped the way a
     * null parameter is.
     */
    @Test
    fun `favorite = false is sent as a real filter, not omitted`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"items":[],"next_cursor":null}""")
                    .build(),
            )

            api.library(cursor = null, limit = 20, status = null, sort = null, mediaId = null, favorite = false)

            assertEquals("false", server.takeRequest().url.queryParameter("favorite"))
        }

    /**
     * Task 9b.6. The body key must be `username`, matching `backend/app/library/schemas.py`'s
     * `ImportRequest` exactly — a `@SerialName` typo here would 422 every real import silently
     * disguised as a passing test, since the fixture response below decodes regardless of what
     * was actually sent.
     */
    @Test
    fun `the import call sends the username as the request body`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"imported":3,"skipped":1,"failed":0,"truncated":false}""")
                    .build(),
            )

            val summary = api.importAniList(ImportAniListRequest(username = "someone"))

            assertEquals("""{"username":"someone"}""", server.takeRequest().body?.utf8())
            assertEquals(3, summary.imported)
            assertEquals(1, summary.skipped)
            assertEquals(0, summary.failed)
            assertFalse(summary.truncated)
        }

    /**
     * `POST /v1/groups` — the request body's key must be `name`, matching
     * `backend/app/groups/schemas.py`'s `CreateGroupRequest` exactly, and the response must
     * decode `invite_code`/`invite_code_expires_at`, the fields `GET /v1/groups`'s plain
     * `GroupRead` does NOT carry (design decision, §1.1 "the invite code is a credential").
     */
    @Test
    fun `createGroup sends the name and decodes the invite fields`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(201)
                    .body(
                        """{"id":"g-1","name":"Watch Party","created_at":"2026-09-01T10:00:00Z",""" +
                            """"invite_code":"ABCD-1234","invite_code_expires_at":"2026-09-08T10:00:00Z"}""",
                    ).build(),
            )

            val response = api.createGroup(CreateGroupRequestDto(name = "Watch Party"))

            assertEquals("""{"name":"Watch Party"}""", server.takeRequest().body?.utf8())
            assertEquals("ABCD-1234", response.inviteCode)
            assertEquals("2026-09-08T10:00:00Z", response.inviteCodeExpiresAt)
        }

    /**
     * `GET /v1/groups/{id}/feed` — the wire shape a real `imported` row actually has: `media: null`
     * (decision S-A). A DTO that made `media` non-nullable would fail this decode outright, not
     * merely map it wrong.
     */
    @Test
    fun `a feed response decodes an imported row with no media`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """{"items":[{"id":"f-1","actor":{"id":"u-1","username":"alex"},"kind":"imported",""" +
                            """"media":null,"payload":{"count":42},"created_at":"2026-09-01T10:00:00Z"}],""" +
                            """"next_cursor":null}""",
                    ).build(),
            )

            val page = api.groupFeed(groupId = "group-1", cursor = null, limit = 20)

            val item = page.items.single()
            assertEquals("imported", item.kind)
            assertNull(item.media)
            assertTrue(item.payload.isNotEmpty())
        }
}
