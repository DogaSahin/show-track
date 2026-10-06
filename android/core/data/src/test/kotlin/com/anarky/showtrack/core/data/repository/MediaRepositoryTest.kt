package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.AddLibraryEntryRequest
import com.anarky.showtrack.core.network.dto.CreateGroupRequestDto
import com.anarky.showtrack.core.network.dto.CreateReviewRequestDto
import com.anarky.showtrack.core.network.dto.EpisodeDto
import com.anarky.showtrack.core.network.dto.EpisodeListDto
import com.anarky.showtrack.core.network.dto.FeedPageDto
import com.anarky.showtrack.core.network.dto.GroupDto
import com.anarky.showtrack.core.network.dto.GroupWithInviteDto
import com.anarky.showtrack.core.network.dto.ImportAniListRequest
import com.anarky.showtrack.core.network.dto.ImportSummaryDto
import com.anarky.showtrack.core.network.dto.JoinGroupRequestDto
import com.anarky.showtrack.core.network.dto.LibraryEntryDto
import com.anarky.showtrack.core.network.dto.LibraryPageDto
import com.anarky.showtrack.core.network.dto.LibraryStatsDto
import com.anarky.showtrack.core.network.dto.MediaDto
import com.anarky.showtrack.core.network.dto.MediaSearchResponseDto
import com.anarky.showtrack.core.network.dto.MemberDto
import com.anarky.showtrack.core.network.dto.ProgressEntryDto
import com.anarky.showtrack.core.network.dto.ProposeTitleRequestDto
import com.anarky.showtrack.core.network.dto.PushTargetDto
import com.anarky.showtrack.core.network.dto.RecommendationPageDto
import com.anarky.showtrack.core.network.dto.RegisterTargetRequest
import com.anarky.showtrack.core.network.dto.ResolveMediaRequestDto
import com.anarky.showtrack.core.network.dto.ReviewDto
import com.anarky.showtrack.core.network.dto.SearchItemDto
import com.anarky.showtrack.core.network.dto.SeasonDto
import com.anarky.showtrack.core.network.dto.SetWatchedRequestDto
import com.anarky.showtrack.core.network.dto.UserDto
import com.anarky.showtrack.core.network.dto.WatchedEpisodesDto
import com.anarky.showtrack.core.network.dto.WatchlistItemDto
import com.anarky.showtrack.core.network.dto.WatchlistPageDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

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
                    .map { it.media.title },
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
                    .map { it.media.title },
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
            assertEquals(listOf("Frieren", "Bebop"), results.items.map { it.media.title })
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
                SearchItemDto(
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

    @Test
    fun `a failing older search does not roll back a newer one`() =
        runTest {
            val api = FakeApi(response(hasMore = true))
            val olderGate = CompletableDeferred<Unit>()
            api.gates = mapOf("y" to olderGate)
            api.failingQueries = setOf("y")
            val repository = MediaRepositoryImpl(api)

            val older = launch { runCatching { repository.search("y") } }
            runCurrent()
            val newer = launch { repository.search("x") }
            runCurrent()
            olderGate.complete(Unit)
            older.join()
            newer.join()

            // Page 2 must belong to the query on screen, not to whatever preceded the failed one.
            repository.loadMoreResults()
            assertEquals("x", api.lastQuery)
        }

    @Test
    fun `an episode list maps seasons, dates and an unfetched list`() =
        runTest {
            val api = FakeApi(response())
            api.episodesAnswer =
                EpisodeListDto(
                    syncedAt = "2026-10-01T08:00:00Z",
                    totalEpisodes = 2,
                    seasons =
                        listOf(
                            SeasonDto(
                                number = 1,
                                episodeCount = 2,
                                episodes =
                                    listOf(
                                        EpisodeDto(
                                            id = "e1",
                                            number = 1,
                                            title = "Pilot",
                                            airDate = "2022-02-18",
                                            aired = true,
                                        ),
                                        EpisodeDto(id = "e2", number = 2, title = null, airDate = null, aired = false),
                                    ),
                            ),
                        ),
                )
            val repository = MediaRepositoryImpl(api)

            val list = repository.episodes("m-1")

            assertTrue(list.isAvailable)
            assertEquals(2, list.totalEpisodes)
            assertEquals(
                LocalDate.of(2022, 2, 18),
                list.seasons
                    .single()
                    .episodes
                    .first()
                    .airDate,
            )
            assertEquals(
                null,
                list.seasons
                    .single()
                    .episodes
                    .last()
                    .airDate,
            )

            api.episodesAnswer = EpisodeListDto(syncedAt = null, totalEpisodes = null, seasons = emptyList())
            assertFalse(repository.episodes("m-1").isAvailable)
        }

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

        // Per-query hooks for interleaving two searches.
        var gates: Map<String, CompletableDeferred<Unit>> = emptyMap()
        var failingQueries: Set<String> = emptySet()

        override suspend fun library(
            cursor: String?,
            limit: Int,
            status: String?,
            sort: String?,
            mediaId: String?,
            favorite: Boolean?,
            type: String?,
        ): LibraryPageDto = TODO("not used")

        override suspend fun addLibraryEntry(request: AddLibraryEntryRequest): LibraryEntryDto = TODO("not used")

        override suspend fun libraryStats(): LibraryStatsDto = TODO("not used")

        override suspend fun importAniList(request: ImportAniListRequest): ImportSummaryDto = TODO("not used")

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
            gates[query]?.await()
            if (query in failingQueries) throw IOException("offline")
            nextFailure?.let { throw it }
            return next
        }

        override suspend fun mediaDetail(id: String): MediaDto = TODO("not used")

        var lastResolve: ResolveMediaRequestDto? = null
        var resolveAnswer: MediaDto? = null

        var episodesAnswer: EpisodeListDto? = null

        override suspend fun mediaEpisodes(id: String): EpisodeListDto =
            checkNotNull(episodesAnswer) { "set episodesAnswer first" }

        override suspend fun deleteLibraryEntry(id: String): Unit = error("not used here")

        override suspend fun watchedEpisodes(id: String): WatchedEpisodesDto = TODO("not used")

        override suspend fun setWatchedEpisodes(
            id: String,
            request: SetWatchedRequestDto,
        ): LibraryEntryDto = TODO("not used")

        override suspend fun resolveMedia(request: ResolveMediaRequestDto): MediaDto {
            lastResolve = request
            return checkNotNull(resolveAnswer) { "set resolveAnswer first" }
        }

        override suspend fun me(): UserDto = TODO("not used")

        override suspend fun registerPushTarget(request: RegisterTargetRequest): PushTargetDto = TODO("not used")

        override suspend fun deletePushTarget(id: String): Unit = TODO("not used")

        override suspend fun recommendations(
            cursor: String?,
            limit: Int,
        ): RecommendationPageDto = TODO("not used")

        override suspend fun createGroup(request: CreateGroupRequestDto): GroupWithInviteDto = TODO("not used")

        override suspend fun groups(): List<GroupDto> = TODO("not used")

        override suspend fun joinGroup(request: JoinGroupRequestDto): GroupWithInviteDto = TODO("not used")

        override suspend fun groupMembers(groupId: String): List<MemberDto> = TODO("not used")

        override suspend fun groupInvite(groupId: String): GroupWithInviteDto = error("not used")

        override suspend fun rotateGroupInvite(groupId: String): GroupWithInviteDto = TODO("not used")

        override suspend fun removeGroupMember(
            groupId: String,
            userId: String,
        ): Unit = TODO("not used")

        override suspend fun groupFeed(
            groupId: String,
            cursor: String?,
            limit: Int,
        ): FeedPageDto = TODO("not used")

        override suspend fun groupReviews(
            groupId: String,
            mediaId: String,
        ): List<ReviewDto> = TODO("not used")

        override suspend fun groupWatchlist(
            groupId: String,
            cursor: String?,
            limit: Int,
        ): WatchlistPageDto = TODO("not used")

        override suspend fun proposeToWatchlist(
            groupId: String,
            request: ProposeTitleRequestDto,
        ): WatchlistItemDto = TODO("not used")

        override suspend fun removeFromWatchlist(
            groupId: String,
            entryId: String,
        ): Unit = TODO("not used")

        override suspend fun groupProgress(
            groupId: String,
            mediaId: String,
        ): List<ProgressEntryDto> = TODO("not used")

        override suspend fun createReview(request: CreateReviewRequestDto): ReviewDto = TODO("not used")

        override suspend fun updateReview(
            id: String,
            patch: JsonObject,
        ): ReviewDto = TODO("not used")
    }

    @Test
    fun `resolving sends the wire source and returns the stored title`() =
        runTest {
            val api = FakeApi(response())
            api.resolveAnswer =
                MediaDto(
                    id = "m-1",
                    source = "tmdb",
                    externalId = "95396",
                    type = "tv",
                    title = "Severance",
                    year = 2022,
                    genres = emptyList(),
                    coverImageUrl = null,
                    status = "airing",
                    nextEpisodeSeason = null,
                    nextEpisodeNumber = null,
                    nextEpisodeDate = null,
                    daysUntilNextEpisode = null,
                )

            val media = MediaRepositoryImpl(api).resolve(MediaSource.TMDB, "95396")

            assertEquals(ResolveMediaRequestDto(source = "tmdb", externalId = "95396"), api.lastResolve)
            assertEquals("m-1", media.id)
        }
}
