package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.AddLibraryEntryRequest
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import com.anarky.showtrack.core.network.dto.LibraryPageDto
import com.anarky.showtrack.core.network.dto.MediaDto
import com.anarky.showtrack.core.network.dto.MediaSearchResponseDto
import com.anarky.showtrack.core.network.dto.MediaSummaryDto
import com.anarky.showtrack.core.network.dto.PushTargetDto
import com.anarky.showtrack.core.network.dto.RegisterTargetRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MediaRepositoryTest {
    @Test
    fun `a provider that did not answer is reported as degraded`() =
        runTest {
            // C-O: has_more is false because the provider that ANSWERED has no more. Without
            // reading `sources`, this is indistinguishable from a complete result set.
            val api = FakeApi(response(sources = mapOf("anilist" to "ok", "tmdb" to "timeout")))
            val repository = MediaRepositoryImpl(api)

            repository.search("frieren")

            val results = repository.searchResults.value
            assertEquals(listOf(MediaSource.TMDB), results.degraded)
            assertTrue(results.isDegraded)
        }

    @Test
    fun `every provider answering ok is not degraded`() =
        runTest {
            val api = FakeApi(response(sources = mapOf("anilist" to "ok", "tmdb" to "ok")))
            val repository = MediaRepositoryImpl(api)

            repository.search("frieren")

            assertFalse(repository.searchResults.value.isDegraded)
        }

    @Test
    fun `an unrecognised provider or status does not crash the search`() =
        runTest {
            // The backend may add a provider or a status before this client knows about it.
            // Failing the whole search over an unknown map entry would break search on a server
            // upgrade — so an unknown SOURCE is ignored and an unknown STATUS counts as degraded.
            val api = FakeApi(response(sources = mapOf("anilist" to "ok", "kitsu" to "error", "tmdb" to "wobbly")))
            val repository = MediaRepositoryImpl(api)

            repository.search("frieren")

            assertEquals(listOf(MediaSource.TMDB), repository.searchResults.value.degraded)
        }

    @Test
    fun `a new query starts over rather than appending to the previous results`() =
        runTest {
            val api = FakeApi(response(titles = listOf("Frieren")))
            val repository = MediaRepositoryImpl(api)
            repository.search("frieren")

            api.next = response(titles = listOf("Bebop"))
            repository.search("bebop")

            assertEquals(
                listOf("Bebop"),
                repository.searchResults.value.items
                    .map { it.title },
            )
            // The half the previous version of this test never pinned: that the new query was
            // actually the one sent to the API, not merely that the displayed items changed.
            assertEquals("bebop", api.lastQuery)
            assertEquals(1, api.lastPage)
        }

    /**
     * FINDING 2's regression test. `PagePaginator.restart()` mutates nothing when its fetch
     * throws, so after a failed `search("bebop")` the paginator's contents still belong to
     * "frieren". If `MediaRepositoryImpl` left `query` on "bebop" anyway, a later
     * `loadMoreResults()` would fetch bebop's page 2 and APPEND it onto frieren's page 1 — a
     * result set silently mixing two different queries.
     */
    @Test
    fun `a failed search does not leave the next page appending to the previous query's results`() =
        runTest {
            val api = FakeApi(response(titles = listOf("Frieren"), hasMore = true))
            val repository = MediaRepositoryImpl(api)
            repository.search("frieren")

            api.nextFailure = IOException("simulated network failure")
            runCatching { repository.search("bebop") }

            api.nextFailure = null
            api.next = response(titles = listOf("Frieren 2"))
            repository.loadMoreResults()

            assertEquals(
                listOf("Frieren", "Frieren 2"),
                repository.searchResults.value.items
                    .map { it.title },
            )
            // The page fetched by loadMoreResults() must have been requested as a continuation
            // of "frieren", not "bebop" — the failed search must not have won the race to name
            // the paginator's query.
            assertEquals("frieren", api.lastQuery)
            assertEquals(2, api.lastPage)
        }

    /** Coverage `loadMoreResults()` had none of before this fix round. */
    @Test
    fun `loadMoreResults accumulates items and takes hasMore from the newest page`() =
        runTest {
            val api = FakeApi(response(titles = listOf("Frieren"), hasMore = true))
            val repository = MediaRepositoryImpl(api)
            repository.search("frieren")

            api.next = response(titles = listOf("Bebop"), hasMore = false)
            repository.loadMoreResults()

            val results = repository.searchResults.value
            assertEquals(listOf("Frieren", "Bebop"), results.items.map { it.title })
            assertFalse(results.hasMore)
            assertEquals("frieren", api.lastQuery)
            assertEquals(2, api.lastPage)
        }

    private fun response(
        titles: List<String> = listOf("Frieren"),
        sources: Map<String, String> = mapOf("anilist" to "ok", "tmdb" to "ok"),
        hasMore: Boolean = false,
    ) = MediaSearchResponseDto(
        items =
            titles.map { title ->
                MediaSummaryDto(
                    source = "anilist",
                    externalId = "1",
                    type = "anime",
                    title = title,
                    year = 2023,
                    genres = listOf("fantasy"),
                    coverImageUrl = null,
                )
            },
        page = 1,
        hasMore = hasMore,
        sources = sources,
    )

    /**
     * Every method but `searchMedia`/`mediaDetail` is unused by [MediaRepositoryImpl] and would
     * signal a repository that has started reaching outside its own concern if it were ever hit.
     */
    private class FakeApi(
        var next: MediaSearchResponseDto,
    ) : ShowTrackApi {
        var lastQuery: String? = null
        var lastPage: Int? = null
        var nextFailure: Throwable? = null

        override suspend fun library(
            cursor: String?,
            limit: Int,
            status: String?,
            sort: String?,
            mediaId: String?,
        ): LibraryPageDto = TODO("not used")

        override suspend fun addLibraryEntry(request: AddLibraryEntryRequest): LibraryEntryDto = TODO("not used")

        override suspend fun updateLibraryEntry(
            id: String,
            patch: JsonObject,
        ): LibraryEntryDto = TODO("not used")

        override suspend fun searchMedia(
            query: String,
            page: Int,
        ): MediaSearchResponseDto {
            lastQuery = query
            lastPage = page
            nextFailure?.let { throw it }
            return next
        }

        override suspend fun mediaDetail(id: String): MediaDto = TODO("not used")

        override suspend fun registerPushTarget(request: RegisterTargetRequest): PushTargetDto = TODO("not used")

        override suspend fun deletePushTarget(id: String): Unit = TODO("not used")
    }
}
