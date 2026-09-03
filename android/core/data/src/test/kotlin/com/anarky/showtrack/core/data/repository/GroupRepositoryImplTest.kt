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
import com.anarky.showtrack.core.network.dto.UserDto
import com.anarky.showtrack.core.network.dto.WatchlistItemDto
import com.anarky.showtrack.core.network.dto.WatchlistPageDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    payload: Map<String, JsonElement> = emptyMap(),
) = FeedItemDto(
    id = "feed-1",
    actor = actorDto(),
    kind = kind,
    media = media,
    payload = payload,
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
            // Ruling 1 (round 1 fix): mediaId is null exactly when media is null — a mapper that
            // defaulted this to some other id would silently make an imported row look tappable.
            assertNull(page.items.single().mediaId)
        }

    /**
     * Ruling 1 (round 1 fix): `MediaSummary` deliberately carries no `id` (decision C-N), so
     * `FeedEntry.mediaId` has to be read off the wire DTO's `media.id` BEFORE `toSummary()` drops
     * it — otherwise a feed row has no id to open `DetailRoute` with. `"media-1"`, not the feed
     * row's OWN id (`"feed-1"`, set by `feedItemDto()`'s default), pins that the right id survived.
     */
    @Test
    fun `a feed entry's mediaId comes from the wire media's own id`() =
        runTest {
            val api = FakeApi()
            api.feedResponse =
                FeedPageDto(items = listOf(feedItemDto(media = mediaDto(id = "media-9"))), nextCursor = null)

            val page = repository(api).feed(groupId = "group-1", cursor = null)

            assertEquals("media-9", page.items.single().mediaId)
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

    /**
     * Round 1 fix (blocking finding B2): the round-0 submission left `createReview` on the
     * default `guarded {}`, so a 404 here — `POST /v1/reviews` 404s when `media_id` names no known
     * title (`backend/app/library/routes.py`) — surfaced as `NotAMember`. Task 9c.7 would then tell
     * a user they are not a member of the group when the TITLE is the thing that is gone.
     */
    @Test
    fun `a 404 on createReview surfaces NoSuchTitle, not NotAMember`() =
        runTest {
            val api = FakeApi()
            api.createReviewFailure = httpError(404)

            val failure =
                runCatching {
                    repository(api).createReview(mediaId = "media-1", body = "great show", containsSpoilers = false)
                }.exceptionOrNull()

            assertEquals(GroupFailure.NoSuchTitle, (failure as GroupOperationException).failure)
        }

    /**
     * Round 1 fix (Ruling 2): any member may remove any watchlist entry (design doc §5.3), so two
     * members racing to delete the same row is real — the loser's 404 must say the ROW is gone, not
     * that they left the group.
     */
    @Test
    fun `a 404 on removeFromWatchlist surfaces NoSuchEntry, not NotAMember`() =
        runTest {
            val api = FakeApi()
            api.removeFromWatchlistFailure = httpError(404)

            val failure = runCatching { repository(api).removeFromWatchlist("group-1", "entry-1") }.exceptionOrNull()

            assertEquals(GroupFailure.NoSuchEntry, (failure as GroupOperationException).failure)
        }

    /**
     * Round 1 fix (Ruling 2): `PATCH /v1/reviews/{id}` 404s for "no such review" OR "not this
     * account's review" (`app/library/routes.py`) — `NotAMember` would be actively wrong, since the
     * caller can be a member of every group involved and still get this 404.
     */
    @Test
    fun `a 404 on updateReview surfaces NoSuchEntry, not NotAMember`() =
        runTest {
            val api = FakeApi()
            api.updateReviewFailure = httpError(404)

            val failure =
                runCatching {
                    repository(api).updateReview(reviewId = "review-1", body = "edited", containsSpoilers = null)
                }.exceptionOrNull()

            assertEquals(GroupFailure.NoSuchEntry, (failure as GroupOperationException).failure)
        }

    @Test
    fun `a request that never reaches the server surfaces Network`() =
        runTest {
            val api = FakeApi()
            api.groupsFailure = IOException("offline")

            val failure = runCatching { repository(api).groups() }.exceptionOrNull()

            assertEquals(GroupFailure.Network, (failure as GroupOperationException).failure)
        }

    /**
     * Round 1 fix (item 4): `GroupOperationException` used to carry no `message` and no `cause` at
     * all (`Exception()`, the zero-arg constructor) — five of six `GroupFailure` cases lost the
     * original throwable entirely, so any log of an uncaught instance gave a bare stack with no
     * indication of what actually failed. `Unknown` is the one case that HAS an original throwable
     * to chain as `cause`; every other case still gets a non-blank `message` naming the failure.
     */
    @Test
    fun `an unmapped failure surfaces as Unknown, chaining the original throwable as the exception's cause`() =
        runTest {
            val api = FakeApi()
            val original = RuntimeException("boom")
            api.groupsFailure = original

            val failure = runCatching { repository(api).groups() }.exceptionOrNull() as GroupOperationException

            assertEquals(GroupFailure.Unknown(original), failure.failure)
            assertEquals(original, failure.cause)
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

    /**
     * Ruling 1 (round 1 fix): same reasoning as the feed entry's own `mediaId` test — `media.id`
     * has to survive [WatchlistEntry.toSummary]'s id-dropping, non-null here since a watchlist
     * entry's `media` is never absent on the wire (unlike a feed row).
     */
    @Test
    fun `a watchlist entry's mediaId comes from the wire media's own id`() =
        runTest {
            val api = FakeApi()
            api.watchlistResponse =
                WatchlistPageDto(
                    items =
                        listOf(
                            WatchlistItemDto(
                                id = "w-1",
                                media = mediaDto(id = "media-9"),
                                proposedBy = "user-1",
                                createdAt = "2026-09-01T10:00:00Z",
                            ),
                        ),
                    nextCursor = null,
                )

            val page = repository(api).watchlist("group-1", cursor = null)

            assertEquals("media-9", page.items.single().mediaId)
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

    /**
     * Fix round 2: a bad, unknown, or expired invite code is a 400
     * (`backend/app/groups/routes.py`'s `_INVALID_CODE`), and `joinGroup` is the one call site
     * that opts `guarded` into distinguishing it from every other unmapped failure — see
     * [GroupFailure.InvalidInviteCode]'s own KDoc for why round 1's `Unknown`-for-everything shape
     * was wrong (it also caught a 500 or an expired session, see the sibling test below) and fix
     * round 3's for why the case is named `InvalidInviteCode`, not the status code it came from.
     */
    @Test
    fun `a 400 from joinGroup surfaces as InvalidInviteCode`() =
        runTest {
            val api = FakeApi()
            api.joinFailure = httpError(400)

            val failure = runCatching { repository(api).joinGroup("BADCODE0000000000000") }.exceptionOrNull()

            assertEquals(GroupFailure.InvalidInviteCode, (failure as GroupOperationException).failure)
        }

    /**
     * The negative control for the test above: `badRequest` in `guarded`/`mapFailure` is gated
     * on the STATUS CODE actually being 400, not on "joinGroup failed at all" — a 500 or any other
     * unmapped status from the same endpoint must still fall through to the generic
     * [GroupFailure.Unknown], never [GroupFailure.InvalidInviteCode].
     */
    @Test
    fun `a 500 from joinGroup still surfaces as Unknown, not InvalidInviteCode`() =
        runTest {
            val api = FakeApi()
            api.joinFailure = httpError(500)

            val failure = runCatching { repository(api).joinGroup("BADCODE0000000000000") }.exceptionOrNull()

            assertTrue((failure as GroupOperationException).failure is GroupFailure.Unknown)
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

    /**
     * `groups()` had NO coverage at all before this (round 1 fix): `FakeApi.groups()` always
     * returned `emptyList()`, so `GroupDto.toDomain()` — id, name, and the `createdAt` parse —
     * was never actually exercised through the repository. Mutation-confirmed below.
     */
    @Test
    fun `groups maps every GroupDto field`() =
        runTest {
            val api = FakeApi()
            api.groupsResponse =
                listOf(GroupDto(id = "group-1", name = "The Watch Party", createdAt = "2026-09-01T10:00:00Z"))

            val groups = repository(api).groups()

            val group = groups.single()
            assertEquals("group-1", group.id)
            assertEquals("The Watch Party", group.name)
            assertEquals(Instant.parse("2026-09-01T10:00:00Z"), group.createdAt)
        }

    /**
     * `reviews()` had NO coverage at all before this (round 1 fix): `FakeApi.groupReviews()` was
     * `error("not exercised")`. `createdAt`/`updatedAt` are asserted as DISTINCT values on purpose —
     * a mapper that swapped the two (or dropped one and reused the other) would still pass a test
     * that only checked one of them, or checked them for equality with each other.
     */
    @Test
    fun `reviews maps every ReviewDto field, including createdAt and updatedAt distinctly`() =
        runTest {
            val api = FakeApi()
            api.reviewsResponse =
                listOf(
                    ReviewDto(
                        id = "review-1",
                        author = actorDto(),
                        mediaId = "media-1",
                        body = "a great show",
                        containsSpoilers = true,
                        createdAt = "2026-09-01T10:00:00Z",
                        updatedAt = "2026-09-02T11:30:00Z",
                    ),
                )

            val reviews = repository(api).reviews(groupId = "group-1", mediaId = "media-1")

            val review = reviews.single()
            assertEquals("review-1", review.id)
            assertEquals("actor-1", review.author.id)
            assertEquals("media-1", review.mediaId)
            assertEquals("a great show", review.body)
            assertTrue(review.containsSpoilers)
            assertEquals(Instant.parse("2026-09-01T10:00:00Z"), review.createdAt)
            assertEquals(Instant.parse("2026-09-02T11:30:00Z"), review.updatedAt)
        }

    /**
     * Every OTHER feed test fixture uses `payload = emptyMap()`, and the one wire-level test
     * (`ShowTrackApiTest`) asserts only `isNotEmpty()` — neither pins WHAT a value stringifies to
     * (round 1 fix). A `JsonPrimitive` must stringify to its bare content (no surrounding quotes,
     * even for a JSON string) — 9c.2's "progressed to episode 3" copy would otherwise render the
     * quoted `"3"` literally. A nested value falls back to its raw JSON text.
     */
    @Test
    fun `payload stringifies JSON primitives without quotes, and falls back to raw JSON for nested values`() =
        runTest {
            val api = FakeApi()
            api.feedResponse =
                FeedPageDto(
                    items =
                        listOf(
                            feedItemDto(
                                payload =
                                    mapOf(
                                        "episode" to JsonPrimitive(3),
                                        "note" to JsonPrimitive("x"),
                                        "extra" to buildJsonObject { put("nested", true) },
                                    ),
                            ),
                        ),
                    nextCursor = null,
                )

            val page = repository(api).feed(groupId = "group-1", cursor = null)

            val payload = page.items.single().payload
            assertEquals("3", payload.getValue("episode"))
            assertEquals("x", payload.getValue("note"))
            assertEquals("{\"nested\":true}", payload.getValue("extra"))
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
        var joinFailure: Throwable? = null
        var rotateFailure: Throwable? = null
        var proposeFailure: Throwable? = null
        var removeMemberFailure: Throwable? = null
        var createReviewFailure: Throwable? = null

        var lastFeedCursor: String? = null
            private set
        var lastUpdateReviewRequest: Pair<String, JsonObject>? = null
            private set

        var groupsResponse: List<GroupDto> = emptyList()

        override suspend fun groups(): List<GroupDto> {
            groupsFailure?.let { throw it }
            return groupsResponse
        }

        override suspend fun createGroup(request: CreateGroupRequestDto): GroupWithInviteDto = groupWithInviteResponse

        override suspend fun joinGroup(request: JoinGroupRequestDto): GroupWithInviteDto {
            joinFailure?.let { throw it }
            return groupWithInviteResponse
        }

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

        var reviewsResponse: List<ReviewDto> = emptyList()

        override suspend fun groupReviews(
            groupId: String,
            mediaId: String,
        ): List<ReviewDto> = reviewsResponse

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

        var removeFromWatchlistFailure: Throwable? = null

        override suspend fun removeFromWatchlist(
            groupId: String,
            entryId: String,
        ) {
            removeFromWatchlistFailure?.let { throw it }
        }

        override suspend fun groupProgress(
            groupId: String,
            mediaId: String,
        ): List<ProgressEntryDto> = progressResponse

        override suspend fun createReview(request: CreateReviewRequestDto): ReviewDto {
            createReviewFailure?.let { throw it }
            return checkNotNull(reviewResponse) { "reviewResponse must be set for a successful call" }
        }

        var updateReviewFailure: Throwable? = null

        override suspend fun updateReview(
            id: String,
            patch: JsonObject,
        ): ReviewDto {
            updateReviewFailure?.let { throw it }
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

        // GroupRepositoryImpl no longer calls api.me() (round 1 review moved currentUserId() to
        // AuthRepositoryImpl — see GroupRepository.kt's own KDoc) — kept as a loud failure, not a
        // stub answer, so a regression that reintroduces the call here is caught immediately.
        override suspend fun me(): UserDto = error("not used")

        override suspend fun registerPushTarget(request: RegisterTargetRequest): PushTargetDto = error("not used")

        override suspend fun deletePushTarget(id: String): Unit = error("not used")

        override suspend fun recommendations(
            cursor: String?,
            limit: Int,
        ): RecommendationPageDto = error("not used")
    }
}
