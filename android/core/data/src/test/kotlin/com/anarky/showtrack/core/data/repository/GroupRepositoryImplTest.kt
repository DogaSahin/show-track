package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.GroupRole
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.AddLibraryEntryRequest
import com.anarky.showtrack.core.network.dto.CreateGroupRequestDto
import com.anarky.showtrack.core.network.dto.CreateReviewRequestDto
import com.anarky.showtrack.core.network.dto.FeedItemDto
import com.anarky.showtrack.core.network.dto.FeedPageDto
import com.anarky.showtrack.core.network.dto.GroupActorDto
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
import com.anarky.showtrack.core.network.dto.ReviewDto
import com.anarky.showtrack.core.network.dto.WatchlistItemDto
import com.anarky.showtrack.core.network.dto.WatchlistPageDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.Instant

/** Builds an `HttpException` the way Retrofit itself does — `LibraryRepositoryImplTest`'s own helper. */
private fun httpError(code: Int): HttpException = HttpException(Response.error<Any>(code, "".toResponseBody(null)))

private fun mediaDto(id: String = "media-1") =
    MediaDto(
        id = id,
        source = "anilist",
        externalId = "154587",
        type = "anime",
        title = "Frieren",
        year = 2023,
        genres = listOf("fantasy"),
        coverImageUrl = "https://example.com/cover.jpg",
        status = "airing",
        nextEpisodeSeason = 1,
        nextEpisodeNumber = 5,
        nextEpisodeDate = "2026-09-05T14:00:00Z",
        daysUntilNextEpisode = 3,
    )

private fun actorDto(id: String = "actor-1") = GroupActorDto(id = id, username = "someone")

private fun feedItemDto(
    kind: String = "added",
    media: MediaDto? = mediaDto(),
) = FeedItemDto(
    id = "feed-1",
    actor = actorDto(),
    kind = kind,
    media = media,
    payload = emptyMap(),
    createdAt = "2026-09-01T10:00:00Z",
)

class GroupRepositoryImplTest {
    private fun repository(api: ShowTrackApi): GroupRepository = GroupRepositoryImpl(api)

    /**
     * ActivityKind.IMPORTED arrives with `media: null` on the wire (decision S-A) — a mapper that
     * assumes media is present crashes on a real feed the first time anyone imports. This drives it
     * through the actual repository call, not the mapper function directly, so it also pins that
     * `feed()` does not itself reject or default a null media before the mapper even runs.
     */
    @Test
    fun `an imported feed entry maps with no media rather than throwing`() =
        runTest {
            val api = FakeApi()
            api.feedResponse =
                FeedPageDto(items = listOf(feedItemDto(kind = "imported", media = null)), nextCursor = null)

            val page = repository(api).feed(groupId = "group-1", cursor = null)

            assertEquals(ActivityKind.IMPORTED, page.items.single().kind)
            assertNull(page.items.single().media)
        }

    /** A future seventh kind must not crash a client built today. */
    @Test
    fun `an unrecognised activity kind maps to UNKNOWN rather than throwing`() =
        runTest {
            val api = FakeApi()
            api.feedResponse = FeedPageDto(items = listOf(feedItemDto(kind = "reposted")), nextCursor = null)

            val page = repository(api).feed(groupId = "group-1", cursor = null)

            assertEquals(ActivityKind.UNKNOWN, page.items.single().kind)
        }

    /**
     * Task 9c.7 turns this into "edit your existing review instead". A generic error would make
     * that impossible to distinguish from a network fault.
     */
    @Test
    fun `a 409 on createReview surfaces AlreadyReviewed, not a generic failure`() =
        runTest {
            val api = FakeApi()
            api.createReviewFailure = httpError(409)

            val failure =
                runCatching {
                    repository(api).createReview(mediaId = "media-1", body = "great show", containsSpoilers = false)
                }.exceptionOrNull()

            assertTrue(failure is GroupOperationException)
            val groupFailure = (failure as GroupOperationException).failure
            assertTrue(groupFailure is GroupFailure.AlreadyReviewed)
            // The backend's 409 body carries no id (a fixed detail string) — see that case's own KDoc.
            assertNull((groupFailure as GroupFailure.AlreadyReviewed).existingReviewId)
        }

    /**
     * The UI hides owner-only actions (E-F), so this should be unreachable — which is exactly why
     * it needs a test: if the hiding regresses, this is the behaviour the user gets.
     */
    @Test
    fun `a 403 on rotateInvite surfaces NotPermitted`() =
        runTest {
            val api = FakeApi()
            api.rotateFailure = httpError(403)

            val failure = runCatching { repository(api).rotateInvite("group-1") }.exceptionOrNull()

            assertTrue(failure is GroupOperationException)
            assertEquals(GroupFailure.NotPermitted, (failure as GroupOperationException).failure)
        }

    /**
     * The one call site that must override the default 404 mapping: `POST .../watchlist`'s 404
     * means the media id was not found, never that the caller fell out of the group mid-call.
     */
    @Test
    fun `a 404 on proposeTitle surfaces NoSuchTitle, not NotAMember`() =
        runTest {
            val api = FakeApi()
            api.proposeFailure = httpError(404)

            val failure = runCatching { repository(api).proposeTitle("group-1", "media-1") }.exceptionOrNull()

            assertEquals(GroupFailure.NoSuchTitle, (failure as GroupOperationException).failure)
        }

    /** The DEFAULT 404 mapping every other call site relies on. */
    @Test
    fun `a 404 on removeMember surfaces NotAMember`() =
        runTest {
            val api = FakeApi()
            api.removeMemberFailure = httpError(404)

            val failure = runCatching { repository(api).removeMember("group-1", "user-1") }.exceptionOrNull()

            assertEquals(GroupFailure.NotAMember, (failure as GroupOperationException).failure)
        }

    @Test
    fun `a request that never reaches the server surfaces Network`() =
        runTest {
            val api = FakeApi()
            api.groupsFailure = IOException("offline")

            val failure = runCatching { repository(api).groups() }.exceptionOrNull()

            assertEquals(GroupFailure.Network, (failure as GroupOperationException).failure)
        }

    @Test
    fun `an entry proposed by a deleted account maps with a null proposer`() =
        runTest {
            val api = FakeApi()
            api.watchlistResponse =
                WatchlistPageDto(
                    items =
                        listOf(
                            WatchlistItemDto(
                                id = "w-1",
                                media = mediaDto(),
                                proposedBy = null,
                                createdAt = "2026-09-01T10:00:00Z",
                            ),
                        ),
                    nextCursor = null,
                )

            val page = repository(api).watchlist("group-1", cursor = null)

            assertNull(page.items.single().proposedBy)
        }

    @Test
    fun `feed and watchlist pass the cursor through and read back the next one`() =
        runTest {
            val api = FakeApi()
            api.feedResponse = FeedPageDto(items = emptyList(), nextCursor = "next-cursor")

            val page = repository(api).feed("group-1", cursor = "prev-cursor")

            assertEquals("prev-cursor", api.lastFeedCursor)
            assertEquals("next-cursor", page.nextCursor)
        }

    @Test
    fun `createGroup and joinGroup map the invite fields, including the expiry instant`() =
        runTest {
            val api = FakeApi()
            api.groupWithInviteResponse =
                GroupWithInviteDto(
                    id = "group-1",
                    name = "The Watch Party",
                    createdAt = "2026-09-01T10:00:00Z",
                    inviteCode = "ABCD-1234",
                    inviteCodeExpiresAt = "2026-09-08T10:00:00Z",
                )

            val created = repository(api).createGroup("The Watch Party")
            val joined = repository(api).joinGroup("ABCD-1234")

            assertEquals("ABCD-1234", created.inviteCode)
            assertEquals(Instant.parse("2026-09-08T10:00:00Z"), created.expiresAt)
            assertEquals("The Watch Party", joined.group.name)
        }

    @Test
    fun `members maps GroupRole strictly`() =
        runTest {
            val api = FakeApi()
            api.membersResponse =
                listOf(
                    MemberDto(
                        userId = "u-1",
                        username = "owner-user",
                        role = "owner",
                        joinedAt = "2026-09-01T10:00:00Z",
                    ),
                    MemberDto(
                        userId = "u-2",
                        username = "member-user",
                        role = "member",
                        joinedAt = "2026-09-01T10:00:00Z",
                    ),
                )

            val members = repository(api).members("group-1")

            assertEquals(listOf(GroupRole.OWNER, GroupRole.MEMBER), members.map { it.role })
        }

    @Test
    fun `progress maps UserMediaStatus strictly`() =
        runTest {
            val api = FakeApi()
            api.progressResponse = listOf(ProgressEntryDto(member = actorDto(), status = "watching", progress = 4))

            val progress = repository(api).progress("group-1", "media-1")

            assertEquals(UserMediaStatus.WATCHING, progress.single().status)
        }

    @Test
    fun `updateReview sends only the fields it names`() =
        runTest {
            val api = FakeApi()
            api.reviewResponse =
                ReviewDto(
                    id = "review-1",
                    author = actorDto(),
                    mediaId = "media-1",
                    body = "updated",
                    containsSpoilers = false,
                    createdAt = "2026-09-01T10:00:00Z",
                    updatedAt = "2026-09-01T11:00:00Z",
                )

            repository(api).updateReview(reviewId = "review-1", body = "updated", containsSpoilers = null)

            val (id, patch) = api.lastUpdateReviewRequest!!
            assertEquals("review-1", id)
            assertTrue(patch.containsKey("body"))
            assertTrue("containsSpoilers must be omitted, not sent as null", !patch.containsKey("contains_spoilers"))
        }

    /** Hand-written rather than MockWebServer — see `LibraryRepositoryImplTest`'s own fake for why. */
    private class FakeApi : ShowTrackApi {
        var feedResponse = FeedPageDto(items = emptyList(), nextCursor = null)
        var watchlistResponse = WatchlistPageDto(items = emptyList(), nextCursor = null)
        var groupWithInviteResponse =
            GroupWithInviteDto(
                id = "group-1",
                name = "group",
                createdAt = "2026-09-01T10:00:00Z",
                inviteCode = "CODE",
                inviteCodeExpiresAt = "2026-09-08T10:00:00Z",
            )
        var membersResponse: List<MemberDto> = emptyList()
        var progressResponse: List<ProgressEntryDto> = emptyList()
        var reviewResponse: ReviewDto? = null
        var groupsFailure: Throwable? = null
        var rotateFailure: Throwable? = null
        var proposeFailure: Throwable? = null
        var removeMemberFailure: Throwable? = null
        var createReviewFailure: Throwable? = null

        var lastFeedCursor: String? = null
            private set
        var lastUpdateReviewRequest: Pair<String, JsonObject>? = null
            private set

        override suspend fun groups(): List<GroupDto> {
            groupsFailure?.let { throw it }
            return emptyList()
        }

        override suspend fun createGroup(request: CreateGroupRequestDto): GroupWithInviteDto = groupWithInviteResponse

        override suspend fun joinGroup(request: JoinGroupRequestDto): GroupWithInviteDto = groupWithInviteResponse

        override suspend fun groupMembers(groupId: String): List<MemberDto> = membersResponse

        override suspend fun rotateGroupInvite(groupId: String): GroupWithInviteDto {
            rotateFailure?.let { throw it }
            return groupWithInviteResponse
        }

        override suspend fun removeGroupMember(
            groupId: String,
            userId: String,
        ) {
            removeMemberFailure?.let { throw it }
        }

        override suspend fun groupFeed(
            groupId: String,
            cursor: String?,
            limit: Int,
        ): FeedPageDto {
            lastFeedCursor = cursor
            return feedResponse
        }

        override suspend fun groupReviews(
            groupId: String,
            mediaId: String,
        ): List<ReviewDto> = error("not exercised by this test")

        override suspend fun groupWatchlist(
            groupId: String,
            cursor: String?,
            limit: Int,
        ): WatchlistPageDto = watchlistResponse

        override suspend fun proposeToWatchlist(
            groupId: String,
            request: ProposeTitleRequestDto,
        ): WatchlistItemDto {
            proposeFailure?.let { throw it }
            return WatchlistItemDto(
                id = "w-1",
                media = mediaDto(),
                proposedBy = "user-1",
                createdAt = "2026-09-01T10:00:00Z",
            )
        }

        override suspend fun removeFromWatchlist(
            groupId: String,
            entryId: String,
        ) = Unit

        override suspend fun groupProgress(
            groupId: String,
            mediaId: String,
        ): List<ProgressEntryDto> = progressResponse

        override suspend fun createReview(request: CreateReviewRequestDto): ReviewDto {
            createReviewFailure?.let { throw it }
            return checkNotNull(reviewResponse) { "reviewResponse must be set for a successful call" }
        }

        override suspend fun updateReview(
            id: String,
            patch: JsonObject,
        ): ReviewDto {
            lastUpdateReviewRequest = id to patch
            return checkNotNull(reviewResponse) { "reviewResponse must be set for a successful call" }
        }

        // The rest of the interface — group tests never exercise these.
        override suspend fun library(
            cursor: String?,
            limit: Int,
            status: String?,
            sort: String?,
            mediaId: String?,
            favorite: Boolean?,
        ): LibraryPageDto = error("not used")

        override suspend fun addLibraryEntry(request: AddLibraryEntryRequest): LibraryEntryDto = error("not used")

        override suspend fun libraryStats(): LibraryStatsDto = error("not used")

        override suspend fun importAniList(request: ImportAniListRequest): ImportSummaryDto = error("not used")

        override suspend fun updateLibraryEntry(
            id: String,
            patch: JsonObject,
        ): LibraryEntryDto = error("not used")

        override suspend fun searchMedia(
            query: String,
            page: Int,
        ): MediaSearchResponseDto = error("not used")

        override suspend fun mediaDetail(id: String): MediaDto = error("not used")

        override suspend fun registerPushTarget(request: RegisterTargetRequest): PushTargetDto = error("not used")

        override suspend fun deletePushTarget(id: String): Unit = error("not used")

        override suspend fun recommendations(
            cursor: String?,
            limit: Int,
        ): RecommendationPageDto = error("not used")
    }
}
