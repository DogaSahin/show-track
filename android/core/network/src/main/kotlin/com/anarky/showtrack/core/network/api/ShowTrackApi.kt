package com.anarky.showtrack.core.network.api

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
import com.anarky.showtrack.core.network.dto.ProgressEntryDto
import com.anarky.showtrack.core.network.dto.ProposeTitleRequestDto
import com.anarky.showtrack.core.network.dto.PushTargetDto
import com.anarky.showtrack.core.network.dto.RecommendationPageDto
import com.anarky.showtrack.core.network.dto.RegisterTargetRequest
import com.anarky.showtrack.core.network.dto.ReviewDto
import com.anarky.showtrack.core.network.dto.UserDto
import com.anarky.showtrack.core.network.dto.WatchlistItemDto
import com.anarky.showtrack.core.network.dto.WatchlistPageDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The authenticated surface of the backend. Served by the OkHttp client that carries
 * [com.anarky.showtrack.core.network.auth.AuthInterceptor] and
 * [com.anarky.showtrack.core.network.auth.TokenRefreshAuthenticator].
 *
 * `@Suppress("TooManyFunctions")`: this interface is the ONE Retrofit surface `:core:data` is
 * allowed to depend on (architecture rule 2's `implementation`-scoped edge exists precisely so
 * nothing else does) — splitting it by domain (library vs. media vs. push vs. groups vs. reviews)
 * would multiply Retrofit service interfaces for a distinction that means nothing to the one
 * caller that ever sees any of them. The cost, stated rather than omitted: a type-level suppression
 * turns detekt's ratchet off permanently for this file, the same trade-off [GroupRepository] and
 * `LibraryRepository` make for the identical reason one layer up.
 */
@Suppress("TooManyFunctions")
interface ShowTrackApi {
    /**
     * `GET /v1/library`. Cursor-paginated: pass the previous page's `next_cursor`, or null for
     * the first page. Retrofit omits a null @Query from the string rather than sending "null",
     * which is what makes every filter here optional without a second method.
     *
     * [mediaId] answers "is this title in my library?" in one request (decision C-C); the answer
     * is `items.firstOrNull()`, where null means "not in your library".
     *
     * [favorite] is task 9b.2's filter (decision D-F). The backend resolves it with `is not None`,
     * so `false` is a REAL filter meaning "non-favourites", not "unset" — passing `false` here is
     * never equivalent to omitting the parameter. Every parameter on this method is explicit with
     * no Kotlin default (this file's own long-standing convention), so every call site — including
     * every one that predates [favorite] — must now pass it, `null` unless it is genuinely filtering
     * on favourite status.
     *
     * Six parameters trips detekt's `LongParameterList` (threshold 6); suppressed rather than
     * bundling them into a request object, which would exist for this one method (plus its wire
     * tests) only, and would undo the "every parameter explicit, no Kotlin default" convention
     * this KDoc leans on to force every call site to consider a new filter rather than silently
     * default it away — `LibraryScreen`/`DiscoverScreen` carry the identical suppression for the
     * identical reason.
     */
    @Suppress("LongParameterList")
    @GET("v1/library")
    suspend fun library(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
        @Query("status") status: String?,
        @Query("sort") sort: String?,
        @Query("media_id") mediaId: String?,
        @Query("favorite") favorite: Boolean?,
    ): LibraryPageDto

    @POST("v1/library")
    suspend fun addLibraryEntry(
        @Body request: AddLibraryEntryRequest,
    ): LibraryEntryDto

    /**
     * `PATCH /v1/library/{id}`. The body is a [JsonObject] rather than a data class because
     * `score` is a tri-state field: absent means "leave it", `null` means "unrate", and a string
     * means "set it". A nullable Kotlin property collapses the first two. :core:data builds the
     * object, where the caller's intent is known.
     */
    @PATCH("v1/library/{id}")
    suspend fun updateLibraryEntry(
        @Path("id") id: String,
        @Body patch: JsonObject,
    ): LibraryEntryDto

    /**
     * `GET /v1/library/stats` (task 9b.5, decision D-F's stats half). Aggregates computed in SQL,
     * not by paging the library client-side — see the backend's `get_stats` KDoc.
     */
    @GET("v1/library/stats")
    suspend fun libraryStats(): LibraryStatsDto

    /**
     * `POST /v1/library/import/anilist` (task 9b.6, backend decision 4-H). Synchronous — a typical
     * list is a few hundred titles and one or two upstream GraphQL requests, so this suspends for
     * seconds rather than returning a task to poll.
     *
     * A non-2xx arrives as an `HttpException`: 404 means no PUBLIC AniList list for that
     * username — the server does not distinguish "no such user" from "list is private" (its own
     * `UserListNotAvailable` docstring), so neither does this call — 422 a malformed username,
     * and 502/504/429 an upstream failure. `:core:data` translates all of these into
     * [com.anarky.showtrack.core.model.ImportFailure] at the repository boundary (decision C-R).
     */
    @POST("v1/library/import/anilist")
    suspend fun importAniList(
        @Body request: ImportAniListRequest,
    ): ImportSummaryDto

    @GET("v1/media/search")
    suspend fun searchMedia(
        @Query("q") query: String,
        @Query("page") page: Int,
    ): MediaSearchResponseDto

    /** `GET /v1/media/{id}`, a MediaDetail — the same shape [LibraryEntryDto] embeds. */
    @GET("v1/media/{id}")
    suspend fun mediaDetail(
        @Path("id") id: String,
    ): MediaDto

    /**
     * `POST /v1/notifications/targets`. Registers this device for push.
     *
     * IDEMPOTENT for `unifiedpush` (backend decision A-O), which is why there is no
     * "have I registered before?" bookkeeping on this side beyond remembering the id to delete:
     * the distributor re-delivers the endpoint through `onNewEndpoint` on every app start, and
     * the server answers 201 the first time and 200 every time after, with the same body. Both
     * are 2xx, so Retrofit returns normally for both and the client does not have to care.
     */
    @POST("v1/notifications/targets")
    suspend fun registerPushTarget(
        @Body request: RegisterTargetRequest,
    ): PushTargetDto

    /**
     * `DELETE /v1/notifications/targets/{id}`, 204 on success.
     *
     * 404 when the id is unknown OR belongs to another account — the backend refuses to
     * distinguish those, so a non-2xx here arrives as an `HttpException` and means only
     * "not deletable by you".
     */
    @DELETE("v1/notifications/targets/{id}")
    suspend fun deletePushTarget(
        @Path("id") id: String,
    )

    /**
     * `GET /v1/recommendations`. Cursor-paginated (architecture rule 4): pass the previous page's
     * `next_cursor`, or null for the first page. No `score` query or field anywhere here — the
     * ordering of the returned `items` IS the ranking (backend decision 7-K).
     */
    @GET("v1/recommendations")
    suspend fun recommendations(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
    ): RecommendationPageDto

    /**
     * `GET /v1/users/me`. The authenticated caller's own identity (task 9c.2) — mounted under
     * `/v1` alongside every other route on this authenticated router (`main.py`), unlike
     * `AuthApi`'s login/refresh/logout/register, which are deliberately served by the
     * UNauthenticated client (see that interface's own KDoc). [UserDto] already exists for
     * `AuthApi.register`'s response, whose wire shape is identical to `GET /v1/users/me`'s
     * (`UserOut` on the backend, `app/users/schemas.py`) — reused rather than minting a second DTO.
     *
     * Added for [com.anarky.showtrack.core.data.repository.GroupRepository.currentUserId] — see
     * that method's own KDoc for why this call exists at all: deriving ownership for the group
     * detail screen's owner-only affordances (E-F) needs the signed-in user's id, and nothing else
     * in this client currently resolves one.
     */
    @GET("v1/users/me")
    suspend fun me(): UserDto

    // -- Groups (task 9c.0). Twelve endpoints under /v1/groups, plus two review writes that live
    // under /v1/reviews (design doc §1) — every shape read from `app/groups/schemas.py`,
    // `app/groups/routes.py` and `app/library/schemas.py` rather than the task breakdown.

    @POST("v1/groups")
    suspend fun createGroup(
        @Body request: CreateGroupRequestDto,
    ): GroupWithInviteDto

    /** `GET /v1/groups`. A plain list, not `{items, next_cursor}` — decision G-H: unbounded growth. */
    @GET("v1/groups")
    suspend fun groups(): List<GroupDto>

    @POST("v1/groups/join")
    suspend fun joinGroup(
        @Body request: JoinGroupRequestDto,
    ): GroupWithInviteDto

    @GET("v1/groups/{id}/members")
    suspend fun groupMembers(
        @Path("id") groupId: String,
    ): List<MemberDto>

    /** `POST /v1/groups/{id}/invite/rotate`. Owner only — a non-owner gets a 403. */
    @POST("v1/groups/{id}/invite/rotate")
    suspend fun rotateGroupInvite(
        @Path("id") groupId: String,
    ): GroupWithInviteDto

    /**
     * `DELETE /v1/groups/{id}/members/{userId}`, 204 on success. Any member may remove
     * themselves; only the owner may remove anyone else — the same endpoint answers "leave group"
     * and "remove member" depending on whose id is passed (design doc §1.1).
     */
    @DELETE("v1/groups/{id}/members/{userId}")
    suspend fun removeGroupMember(
        @Path("id") groupId: String,
        @Path("userId") userId: String,
    )

    @GET("v1/groups/{id}/feed")
    suspend fun groupFeed(
        @Path("id") groupId: String,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
    ): FeedPageDto

    /** `GET /v1/groups/{id}/media/{mediaId}/reviews`. A plain list — bounded by group membership. */
    @GET("v1/groups/{id}/media/{mediaId}/reviews")
    suspend fun groupReviews(
        @Path("id") groupId: String,
        @Path("mediaId") mediaId: String,
    ): List<ReviewDto>

    @GET("v1/groups/{id}/watchlist")
    suspend fun groupWatchlist(
        @Path("id") groupId: String,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
    ): WatchlistPageDto

    /**
     * `POST /v1/groups/{id}/watchlist`. 200, not 201: idempotent, the same shape as
     * `POST /v1/library` for a title already tracked. 404 when [ProposeTitleRequestDto.mediaId]
     * names no known title.
     */
    @POST("v1/groups/{id}/watchlist")
    suspend fun proposeToWatchlist(
        @Path("id") groupId: String,
        @Body request: ProposeTitleRequestDto,
    ): WatchlistItemDto

    /** `DELETE /v1/groups/{id}/watchlist/{entryId}`, 204 on success. Any member may remove any entry. */
    @DELETE("v1/groups/{id}/watchlist/{entryId}")
    suspend fun removeFromWatchlist(
        @Path("id") groupId: String,
        @Path("entryId") entryId: String,
    )

    /** `GET /v1/groups/{id}/media/{mediaId}/progress`. A plain list — bounded by group membership. */
    @GET("v1/groups/{id}/media/{mediaId}/progress")
    suspend fun groupProgress(
        @Path("id") groupId: String,
        @Path("mediaId") mediaId: String,
    ): List<ProgressEntryDto>

    /** `POST /v1/reviews`, 201. 409 when this account already reviewed [CreateReviewRequestDto.mediaId]. */
    @POST("v1/reviews")
    suspend fun createReview(
        @Body request: CreateReviewRequestDto,
    ): ReviewDto

    /**
     * `PATCH /v1/reviews/{id}`. [patch] is a [JsonObject], not a data class — see `ReviewDtos.kt`'s
     * own note: the backend rejects an explicit null for either field, so "leave it alone" has to
     * be the field's ABSENCE from the body, which only a hand-built object can express.
     */
    @PATCH("v1/reviews/{id}")
    suspend fun updateReview(
        @Path("id") id: String,
        @Body patch: JsonObject,
    ): ReviewDto
}
