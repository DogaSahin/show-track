package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.AddLibraryEntryRequest
import com.anarky.showtrack.core.network.dto.CreateGroupRequestDto
import com.anarky.showtrack.core.network.dto.CreateReviewRequestDto
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
import com.anarky.showtrack.core.network.dto.PersistedMediaDto
import com.anarky.showtrack.core.network.dto.ProgressEntryDto
import com.anarky.showtrack.core.network.dto.ProposeTitleRequestDto
import com.anarky.showtrack.core.network.dto.PushTargetDto
import com.anarky.showtrack.core.network.dto.RecommendationDto
import com.anarky.showtrack.core.network.dto.RecommendationPageDto
import com.anarky.showtrack.core.network.dto.RecommendationReasonDto
import com.anarky.showtrack.core.network.dto.RegisterTargetRequest
import com.anarky.showtrack.core.network.dto.ReviewDto
import com.anarky.showtrack.core.network.dto.WatchlistItemDto
import com.anarky.showtrack.core.network.dto.WatchlistPageDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RecommendationRepositoryTest {
    @Test
    fun `refresh publishes the first page`() =
        runTest {
            val api = FakeApi(mapOf(null to page(titles = listOf("Frieren", "Bebop"), nextCursor = "c1")))
            val repository = RecommendationRepositoryImpl(api)

            repository.refresh()

            assertEquals(listOf("Frieren", "Bebop"), repository.feed.value.map { it.media.title })
        }

    @Test
    fun `loadMore appends the next page`() =
        runTest {
            val api =
                FakeApi(
                    mapOf(
                        null to page(titles = listOf("Frieren"), nextCursor = "c1"),
                        "c1" to page(titles = listOf("Bebop"), nextCursor = null),
                    ),
                )
            val repository = RecommendationRepositoryImpl(api)
            repository.refresh()

            repository.loadMore()

            assertEquals(listOf("Frieren", "Bebop"), repository.feed.value.map { it.media.title })
            assertEquals(listOf(null, "c1"), api.requestedCursors)
        }

    /**
     * `loadMore()` on an exhausted feed must be a safe no-op (CursorPaginator's own `started &&
     * cursor == null` guard), which is what lets `DiscoverList`'s `EndOfListTrigger` call it
     * unconditionally — same shape `LibraryList` relies on.
     */
    @Test
    fun `loadMore does nothing once the feed is exhausted`() =
        runTest {
            val api = FakeApi(mapOf(null to page(titles = listOf("Frieren"), nextCursor = null)))
            val repository = RecommendationRepositoryImpl(api)
            repository.refresh()

            repository.loadMore()

            assertEquals(listOf("Frieren"), repository.feed.value.map { it.media.title })
            assertEquals(listOf(null), api.requestedCursors)
        }

    /**
     * `CursorPaginator.restart()` mutates nothing when its fetch throws — the task brief's own
     * carried-forward lesson, "this shape has produced two bugs in this project already"
     * (`MediaRepositoryImpl.search`, `LibraryRepositoryImpl.applyFilter`). `mutableFeed` here is
     * only ever written from the fetch's own RETURNED result, never re-derived from
     * `paginator.items.value` after the fact, which is what makes a failed refresh leave the feed
     * exactly as it was rather than blanking it.
     */
    @Test
    fun `a failed refresh leaves the feed standing`() =
        runTest {
            val api = FakeApi(mapOf(null to page(titles = listOf("Frieren"), nextCursor = null)))
            val repository = RecommendationRepositoryImpl(api)
            repository.refresh()

            api.nextFailure = IOException("simulated network failure")
            runCatching { repository.refresh() }

            assertEquals(listOf("Frieren"), repository.feed.value.map { it.media.title })
        }

    /** The same property, for `loadMore()`'s own fetch-before-mutate discipline. */
    @Test
    fun `a failed loadMore leaves the feed standing`() =
        runTest {
            val api =
                FakeApi(
                    mapOf(
                        null to page(titles = listOf("Frieren"), nextCursor = "c1"),
                        "c1" to page(titles = listOf("Bebop"), nextCursor = null),
                    ),
                )
            val repository = RecommendationRepositoryImpl(api)
            repository.refresh()

            api.nextFailure = IOException("simulated network failure")
            runCatching { repository.loadMore() }

            assertEquals(listOf("Frieren"), repository.feed.value.map { it.media.title })
        }

    @Test
    fun `remove takes a row out of the feed`() =
        runTest {
            val api = FakeApi(mapOf(null to page(titles = listOf("Frieren", "Bebop"), nextCursor = null)))
            val repository = RecommendationRepositoryImpl(api)
            repository.refresh()
            val mediaId =
                repository.feed.value
                    .first { it.media.title == "Frieren" }
                    .media.id

            repository.remove(mediaId)

            assertEquals(listOf("Bebop"), repository.feed.value.map { it.media.title })
        }

    /**
     * The property `RecommendationRepositoryImpl`'s KDoc calls out by name: `feed` must never be
     * re-derived wholesale from `paginator.items.value`, because that list never learns a row was
     * removed. Without the `lastFetchedPage`-delta design this repository actually uses, a
     * `loadMore()` AFTER a `remove()` would silently resurrect the removed row — the exact bug this
     * test exists to catch.
     */
    @Test
    fun `a removed row does not reappear when loadMore fetches the next page`() =
        runTest {
            val api =
                FakeApi(
                    mapOf(
                        null to page(titles = listOf("Frieren", "Bebop"), nextCursor = "c1"),
                        "c1" to page(titles = listOf("Dandadan"), nextCursor = null),
                    ),
                )
            val repository = RecommendationRepositoryImpl(api)
            repository.refresh()
            val mediaId =
                repository.feed.value
                    .first { it.media.title == "Frieren" }
                    .media.id
            repository.remove(mediaId)

            repository.loadMore()

            assertEquals(listOf("Bebop", "Dandadan"), repository.feed.value.map { it.media.title })
        }

    @Test
    fun `restore re-inserts a removed row at the given index`() =
        runTest {
            val api = FakeApi(mapOf(null to page(titles = listOf("Frieren", "Bebop", "Dandadan"), nextCursor = null)))
            val repository = RecommendationRepositoryImpl(api)
            repository.refresh()
            val bebop = repository.feed.value.first { it.media.title == "Bebop" }
            repository.remove(bebop.media.id)

            repository.restore(1, bebop)

            assertEquals(listOf("Frieren", "Bebop", "Dandadan"), repository.feed.value.map { it.media.title })
        }

    /**
     * Fix round 1, finding 1: the composing test for the axis a review caught was missing. `remove`
     * surviving a later `loadMore` (above) and `restore` re-inserting at the right spot (above) were
     * each pinned in isolation; neither test alone would catch a `restore` that only patched a
     * CALLER's copy of the list instead of `mutableFeed` itself — `loadMore`'s success path
     * re-publishes from `mutableFeed` (see its own KDoc), so a restore that bypassed it would be
     * silently overwritten the next time a page loads. `restore` must land in the SAME published
     * list `loadMore` appends onto, which is what this proves by actually running both in sequence.
     */
    @Test
    fun `a restored row survives a later loadMore and keeps its position`() =
        runTest {
            val api =
                FakeApi(
                    mapOf(
                        null to page(titles = listOf("Frieren", "Bebop", "Dandadan"), nextCursor = "c1"),
                        "c1" to page(titles = listOf("Trigun"), nextCursor = null),
                    ),
                )
            val repository = RecommendationRepositoryImpl(api)
            repository.refresh()
            val bebop = repository.feed.value.first { it.media.title == "Bebop" }
            repository.remove(bebop.media.id)
            repository.restore(1, bebop)

            repository.loadMore()

            assertEquals(
                listOf("Frieren", "Bebop", "Dandadan", "Trigun"),
                repository.feed.value.map { it.media.title },
            )
        }

    @Test
    fun `it asks for the documented page size`() =
        runTest {
            val api = FakeApi(mapOf(null to page(titles = listOf("Frieren"), nextCursor = null)))
            val repository = RecommendationRepositoryImpl(api)

            repository.refresh()

            assertEquals(listOf(PAGE_SIZE), api.requestedLimits)
        }

    @Test
    fun `every seed's matched genres and title map through untouched`() =
        runTest {
            val api =
                FakeApi(
                    mapOf(
                        null to
                            RecommendationPageDto(
                                items =
                                    listOf(
                                        RecommendationDto(
                                            media = mediaDto(title = "Frieren"),
                                            reason =
                                                RecommendationReasonDto(
                                                    seedMediaId = "seed-1",
                                                    seedTitle = "Made in Abyss",
                                                    matchedGenres = listOf("fantasy", "adventure"),
                                                ),
                                        ),
                                    ),
                                nextCursor = null,
                            ),
                    ),
                )
            val repository = RecommendationRepositoryImpl(api)

            repository.refresh()

            val reason =
                repository.feed.value
                    .single()
                    .reason
            assertEquals("seed-1", reason.seedMediaId)
            assertEquals("Made in Abyss", reason.seedTitle)
            assertEquals(listOf("fantasy", "adventure"), reason.matchedGenres)
            assertTrue(reason.matchedGenres.isNotEmpty())
        }

    private fun page(
        titles: List<String>,
        nextCursor: String?,
    ) = RecommendationPageDto(
        items =
            titles.mapIndexed { index, title ->
                RecommendationDto(
                    media = mediaDto(id = "media-$index-$title", title = title),
                    reason =
                        RecommendationReasonDto(
                            seedMediaId = "seed-$index",
                            seedTitle = "Seed $index",
                            matchedGenres = listOf("fantasy"),
                        ),
                )
            },
        nextCursor = nextCursor,
    )

    private fun mediaDto(
        id: String = "media-1",
        title: String,
    ) = PersistedMediaDto(
        id = id,
        source = "anilist",
        externalId = "21",
        type = "anime",
        title = title,
        year = 2023,
        genres = listOf("fantasy"),
        coverImageUrl = null,
    )

    private companion object {
        const val PAGE_SIZE = 20
    }

    /** Hand-written rather than MockWebServer — see `LibraryRepositoryImplTest`'s own fake for why. */
    private class FakeApi(
        private val pages: Map<String?, RecommendationPageDto>,
    ) : ShowTrackApi {
        val requestedCursors = mutableListOf<String?>()
        val requestedLimits = mutableListOf<Int>()
        var nextFailure: Throwable? = null

        override suspend fun recommendations(
            cursor: String?,
            limit: Int,
        ): RecommendationPageDto {
            requestedCursors += cursor
            requestedLimits += limit
            nextFailure?.let {
                nextFailure = null
                throw it
            }
            return pages.getValue(cursor)
        }

        override suspend fun library(
            cursor: String?,
            limit: Int,
            status: String?,
            sort: String?,
            mediaId: String?,
            favorite: Boolean?,
        ): LibraryPageDto = error("this fake only serves refresh/loadMore/remove")

        override suspend fun addLibraryEntry(request: AddLibraryEntryRequest): LibraryEntryDto =
            error("this fake only serves refresh/loadMore/remove")

        override suspend fun libraryStats(): LibraryStatsDto = error("this fake only serves refresh/loadMore/remove")

        override suspend fun importAniList(request: ImportAniListRequest): ImportSummaryDto =
            error("this fake only serves refresh/loadMore/remove")

        override suspend fun updateLibraryEntry(
            id: String,
            patch: JsonObject,
        ): LibraryEntryDto = error("this fake only serves refresh/loadMore/remove")

        override suspend fun searchMedia(
            query: String,
            page: Int,
        ): MediaSearchResponseDto = error("this fake only serves refresh/loadMore/remove")

        override suspend fun mediaDetail(id: String): MediaDto = error("this fake only serves refresh/loadMore/remove")

        override suspend fun registerPushTarget(request: RegisterTargetRequest): PushTargetDto =
            error("this fake only serves refresh/loadMore/remove")

        override suspend fun deletePushTarget(id: String): Unit = error("this fake only serves refresh/loadMore/remove")

        override suspend fun createGroup(request: CreateGroupRequestDto): GroupWithInviteDto =
            error("this fake only serves refresh/loadMore/remove")

        override suspend fun groups(): List<GroupDto> = error("this fake only serves refresh/loadMore/remove")

        override suspend fun joinGroup(request: JoinGroupRequestDto): GroupWithInviteDto =
            error("this fake only serves refresh/loadMore/remove")

        override suspend fun groupMembers(groupId: String): List<MemberDto> =
            error("this fake only serves refresh/loadMore/remove")

        override suspend fun rotateGroupInvite(groupId: String): GroupWithInviteDto =
            error("this fake only serves refresh/loadMore/remove")

        override suspend fun removeGroupMember(
            groupId: String,
            userId: String,
        ): Unit = error("this fake only serves refresh/loadMore/remove")

        override suspend fun groupFeed(
            groupId: String,
            cursor: String?,
            limit: Int,
        ): FeedPageDto = error("this fake only serves refresh/loadMore/remove")

        override suspend fun groupReviews(
            groupId: String,
            mediaId: String,
        ): List<ReviewDto> = error("this fake only serves refresh/loadMore/remove")

        override suspend fun groupWatchlist(
            groupId: String,
            cursor: String?,
            limit: Int,
        ): WatchlistPageDto = error("this fake only serves refresh/loadMore/remove")

        override suspend fun proposeToWatchlist(
            groupId: String,
            request: ProposeTitleRequestDto,
        ): WatchlistItemDto = error("this fake only serves refresh/loadMore/remove")

        override suspend fun removeFromWatchlist(
            groupId: String,
            entryId: String,
        ): Unit = error("this fake only serves refresh/loadMore/remove")

        override suspend fun groupProgress(
            groupId: String,
            mediaId: String,
        ): List<ProgressEntryDto> = error("this fake only serves refresh/loadMore/remove")

        override suspend fun createReview(request: CreateReviewRequestDto): ReviewDto =
            error("this fake only serves refresh/loadMore/remove")

        override suspend fun updateReview(
            id: String,
            patch: JsonObject,
        ): ReviewDto = error("this fake only serves refresh/loadMore/remove")
    }
}
