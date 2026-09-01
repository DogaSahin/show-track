package com.anarky.showtrack.core.network.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
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

            // The fields the UI actually reads. `sources` is asserted because C-O's partial-
            // failure notice is drawn from it, and a decode that silently dropped an unknown
            // key would leave that notice permanently absent with no other symptom.
            assertEquals(2, response.items.size)
            assertTrue(response.sources.isNotEmpty())
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

            api.library(cursor = null, limit = 20, status = "watching", sort = "score", mediaId = "m-1")

            val url = server.takeRequest().url
            assertEquals("watching", url.queryParameter("status"))
            assertEquals("score", url.queryParameter("sort"))
            assertEquals("m-1", url.queryParameter("media_id"))
            // The point of passing null rather than "null": an absent filter must not appear.
            assertNull(url.queryParameter("cursor"))
        }
}
