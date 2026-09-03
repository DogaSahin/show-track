package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.model.FeedEntry
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.MemberProgress
import com.anarky.showtrack.core.model.Review
import com.anarky.showtrack.core.model.WatchlistEntry
import java.time.Instant

/** The cursor-paginated envelope `GET /v1/groups/{id}/feed` returns (architecture rule 4). */
data class FeedPage(
    val items: List<FeedEntry>,
    val nextCursor: String?,
)

/** The cursor-paginated envelope `GET /v1/groups/{id}/watchlist` returns (architecture rule 4). */
data class WatchlistPage(
    val items: List<WatchlistEntry>,
    val nextCursor: String?,
)

/**
 * `GroupRead` plus the invite code — returned only to a member, on create, join and rotate. `GET
 * /v1/groups` (the plain list) returns [Group] alone; the invite code is a credential and is never
 * part of the plain group representation (design decision, §1.1).
 */
data class GroupWithInvite(
    val group: Group,
    val inviteCode: String,
    val expiresAt: Instant,
)

/**
 * The single exception type [GroupRepositoryImpl] throws for every failure below. [GroupFailure]
 * itself is a plain sealed interface, not a sealed class extending `Exception` — see its own KDoc
 * for why — so it needs a carrier to cross an actual `throw`/`catch` boundary. Callers catch this
 * and switch on [failure]; they never match on this wrapper's own type.
 *
 * `message`/`cause` are set from [failure] (round 1 fix) rather than left at `Exception()`'s own
 * defaults: five of [GroupFailure]'s six cases carried no `Throwable` at all before this, so a log
 * of an uncaught [GroupOperationException] gave a bare stack with no indication of what actually
 * failed. `(failure as? GroupFailure.Unknown)?.cause` is the only case that HAS an original
 * throwable to chain; every other case's `message` still names which [GroupFailure] it was.
 */
class GroupOperationException(
    val failure: GroupFailure,
) : Exception(failure.toString(), (failure as? GroupFailure.Unknown)?.cause)

/**
 * The only data-layer type any `:feature:*` module sees for groups, the shared feed, the shared
 * watchlist, progress comparison and reviews — one domain's single door into `:core:data`, the same
 * seam-cohesion argument [LibraryRepository] settled on for its own `TooManyFunctions` suppression
 * (design decision E-E).
 *
 * `@Suppress("TooManyFunctions")` at fifteen methods. The outcome — one interface, not split by
 * sub-concern (groups vs. feed vs. watchlist vs. reviews) — is right for the same reason
 * [LibraryRepository] gives: a `:feature:*` module is meant to see ONE group-domain interface
 * (architecture rule 2's whole point), and splitting by sub-concern would make "which interface do
 * I inject" a fact about internals a caller has no business knowing.
 *
 * The cost this suppression carries, worth stating rather than omitting (the same corrected lesson
 * [LibraryRepository]'s KDoc records): it sits on the TYPE, so the ratchet it disables is off
 * PERMANENTLY, not just for the methods that exist today. [currentUserId] (task 9c.2) is exactly
 * the fifteenth method this KDoc's own previous revision predicted — and, judged by hand as
 * predicted, it is arguably not a groups/feed/watchlist/reviews concern at all, but a users one;
 * see its own KDoc for why it lives here anyway.
 */
@Suppress("TooManyFunctions")
interface GroupRepository {
    /**
     * `GET /v1/users/me`, answering the signed-in user's own id (task 9c.2). Not a "users" concern
     * split onto its own repository: its ONLY caller today is `GroupDetailViewModel`, which derives
     * owner-only rendering (E-F) by comparing this id against `GroupMember.role` on the live
     * [members] response — "not from anything cached or inferred" is E-F's own stated mitigation,
     * and nothing else in this client currently resolves a signed-in identity at all (`AuthRepository`
     * knows only opaque tokens; `TokenStore` never decodes them — see that interface's own KDoc).
     * Keeping this on [GroupRepository] rather than `AuthRepository` means `GroupDetailViewModel`'s
     * existing `catch (failure: GroupOperationException)` covers this call the identical way it
     * already covers [members]/[rotateInvite]/[removeMember], rather than that ViewModel reconciling
     * two unrelated failure-domain types for one screen. See `ShowTrackApi.me`'s own KDoc for why
     * this is NOT served by `AuthApi` (its client carries no `AuthInterceptor`, so an unauthenticated
     * call to a route that requires one would always 401).
     */
    suspend fun currentUserId(): String

    /** `GET /v1/groups`. A plain list, not `{items, next_cursor}` (decision G-H): unbounded growth. */
    suspend fun groups(): List<Group>

    suspend fun createGroup(name: String): GroupWithInvite

    /**
     * `POST /v1/groups/join`. A bad or expired code is a 400 the backend deliberately does not
     * distinguish (`_INVALID_CODE`, `app/groups/routes.py`), so it surfaces as
     * [GroupOperationException] wrapping [GroupFailure.Unknown] rather than a dedicated case.
     */
    suspend fun joinGroup(inviteCode: String): GroupWithInvite

    suspend fun members(groupId: String): List<GroupMember>

    /** `POST /v1/groups/{id}/invite/rotate`. Owner only — [GroupFailure.NotPermitted] otherwise. */
    suspend fun rotateInvite(groupId: String): GroupWithInvite

    /**
     * `DELETE /v1/groups/{id}/members/{userId}`. Any member may remove themselves; only the owner
     * may remove anyone else — [GroupFailure.NotPermitted] on that second case for a non-owner.
     */
    suspend fun removeMember(
        groupId: String,
        userId: String,
    )

    suspend fun feed(
        groupId: String,
        cursor: String?,
    ): FeedPage

    /** `GET /v1/groups/{id}/media/{mediaId}/reviews`. A plain list — bounded by group membership. */
    suspend fun reviews(
        groupId: String,
        mediaId: String,
    ): List<Review>

    suspend fun watchlist(
        groupId: String,
        cursor: String?,
    ): WatchlistPage

    /** `POST /v1/groups/{id}/watchlist`. [GroupFailure.NoSuchTitle] when [mediaId] names no known title. */
    suspend fun proposeTitle(
        groupId: String,
        mediaId: String,
    ): WatchlistEntry

    /** [GroupFailure.NoSuchEntry] when [entryId] is already gone — any member may remove any entry. */
    suspend fun removeFromWatchlist(
        groupId: String,
        entryId: String,
    )

    /** `GET /v1/groups/{id}/media/{mediaId}/progress`. A plain list — bounded by group membership. */
    suspend fun progress(
        groupId: String,
        mediaId: String,
    ): List<MemberProgress>

    /**
     * `POST /v1/reviews`. [GroupFailure.AlreadyReviewed] when this account already reviewed
     * [mediaId]; [GroupFailure.NoSuchTitle] when [mediaId] names no known title.
     */
    suspend fun createReview(
        mediaId: String,
        body: String,
        containsSpoilers: Boolean,
    ): Review

    /**
     * `PATCH /v1/reviews/{id}`. A null [body]/[containsSpoilers] means "leave it unchanged", never
     * "clear it". [GroupFailure.NoSuchEntry] when [reviewId] does not exist or is not this
     * account's own review.
     */
    suspend fun updateReview(
        reviewId: String,
        body: String?,
        containsSpoilers: Boolean?,
    ): Review
}
