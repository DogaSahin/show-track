package com.anarky.showtrack.core.data.repository

import com.anarky.showtrack.core.data.mapper.toDomain
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.MemberProgress
import com.anarky.showtrack.core.model.Review
import com.anarky.showtrack.core.model.WatchlistEntry
import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.CreateGroupRequestDto
import com.anarky.showtrack.core.network.dto.CreateReviewRequestDto
import com.anarky.showtrack.core.network.dto.JoinGroupRequestDto
import com.anarky.showtrack.core.network.dto.ProposeTitleRequestDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val PAGE_SIZE = 20
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409

/**
 * `@Singleton` on the class, same reasoning `LibraryRepositoryImpl`'s own KDoc gives for
 * `@Binds` carrying no scope of its own — though this class holds no paginator state the way that
 * one does; a single instance is still the right default rather than a fresh one per injection
 * point, matching every other repository in this module.
 *
 * `@Suppress("TooManyFunctions")`: mirrors [GroupRepository]'s own suppression, for the identical
 * reason — see that interface's KDoc.
 */
@Suppress("TooManyFunctions")
@Singleton
class GroupRepositoryImpl
    @Inject
    constructor(
        private val api: ShowTrackApi,
    ) : GroupRepository {
        override suspend fun groups(): List<Group> = guarded { api.groups().map { it.toDomain() } }

        override suspend fun createGroup(name: String): GroupWithInvite =
            guarded { api.createGroup(CreateGroupRequestDto(name = name)).toDomain() }

        /**
         * [GroupFailure.BadRequest] on a 400 here (fix round 2): `POST /v1/groups/join` returns
         * exactly that status for a bad, unknown, or expired code (`routes.py`'s `_INVALID_CODE`)
         * — see [GroupFailure.BadRequest]'s own KDoc for why this needed a dedicated case rather
         * than folding into [GroupFailure.Unknown] the way round 1 originally left it.
         */
        override suspend fun joinGroup(inviteCode: String): GroupWithInvite =
            guarded(badRequest = GroupFailure.BadRequest) {
                api.joinGroup(JoinGroupRequestDto(inviteCode = inviteCode)).toDomain()
            }

        override suspend fun members(groupId: String): List<GroupMember> =
            guarded { api.groupMembers(groupId).map { it.toDomain() } }

        override suspend fun rotateInvite(groupId: String): GroupWithInvite =
            guarded { api.rotateGroupInvite(groupId).toDomain() }

        override suspend fun removeMember(
            groupId: String,
            userId: String,
        ) = guarded { api.removeGroupMember(groupId, userId) }

        override suspend fun feed(
            groupId: String,
            cursor: String?,
        ): FeedPage =
            guarded {
                val page = api.groupFeed(groupId = groupId, cursor = cursor, limit = PAGE_SIZE)
                FeedPage(items = page.items.map { it.toDomain() }, nextCursor = page.nextCursor)
            }

        override suspend fun reviews(
            groupId: String,
            mediaId: String,
        ): List<Review> = guarded { api.groupReviews(groupId, mediaId).map { it.toDomain() } }

        override suspend fun watchlist(
            groupId: String,
            cursor: String?,
        ): WatchlistPage =
            guarded {
                val page = api.groupWatchlist(groupId = groupId, cursor = cursor, limit = PAGE_SIZE)
                WatchlistPage(items = page.items.map { it.toDomain() }, nextCursor = page.nextCursor)
            }

        /**
         * [GroupFailure.NoSuchTitle] on a 404 here — one of four call sites that override [guarded]'s
         * default [GroupFailure.NotAMember] (round 1 fix added the other three:
         * [createReview] for the identical reason, [removeFromWatchlist]/[updateReview] for
         * [GroupFailure.NoSuchEntry]) — because a 404 from this endpoint means the *media id* was
         * not found (`app/groups/routes.py`'s `responses={404: "no such title"}`), not that the
         * caller left the group between the membership check and this line.
         */
        override suspend fun proposeTitle(
            groupId: String,
            mediaId: String,
        ): WatchlistEntry =
            guarded(notFound = GroupFailure.NoSuchTitle) {
                api.proposeToWatchlist(groupId, ProposeTitleRequestDto(mediaId = mediaId)).toDomain()
            }

        /**
         * [GroupFailure.NoSuchEntry] on a 404 here, not the default [GroupFailure.NotAMember]: any
         * member may remove any entry (design doc §5.3), so two members racing to delete the same
         * row is a real case — the loser must be told the ROW is gone, not that they left the group.
         */
        override suspend fun removeFromWatchlist(
            groupId: String,
            entryId: String,
        ) = guarded(notFound = GroupFailure.NoSuchEntry) { api.removeFromWatchlist(groupId, entryId) }

        override suspend fun progress(
            groupId: String,
            mediaId: String,
        ): List<MemberProgress> = guarded { api.groupProgress(groupId, mediaId).map { it.toDomain() } }

        /**
         * [GroupFailure.NoSuchTitle] on a 404 here, not the default [GroupFailure.NotAMember]:
         * `POST /v1/reviews` 404s when `media_id` names no known title
         * (`backend/app/library/routes.py`), the identical shape [proposeTitle] above already
         * overrides for. Telling the caller they are not a member of the group would be wrong — the
         * title is what is missing, and this endpoint is not even group-scoped.
         */
        override suspend fun createReview(
            mediaId: String,
            body: String,
            containsSpoilers: Boolean,
        ): Review =
            guarded(notFound = GroupFailure.NoSuchTitle) {
                api
                    .createReview(
                        CreateReviewRequestDto(mediaId = mediaId, body = body, containsSpoilers = containsSpoilers),
                    ).toDomain()
            }

        /**
         * The PATCH body is built by hand, matching `ShowTrackApi.updateLibraryEntry`'s own
         * `LibraryPatch.toJson()`: a null [body]/[containsSpoilers] here means "leave it alone", and
         * the backend REJECTS an explicit JSON null for either field
         * (`UpdateReviewRequest._reject_explicit_nulls`) — so "unchanged" has to be the field's
         * ABSENCE from the body, which only an object built field-by-field can express.
         *
         * [GroupFailure.NoSuchEntry] on a 404 (round 1 fix): "ownership failures answer 404 rather
         * than 403" (`app/library/routes.py`) for this endpoint, covering both "no such review" and
         * "not this account's review" — [NotAMember] would be actively wrong here, since the caller
         * can be a member of every group involved and still get this 404.
         */
        override suspend fun updateReview(
            reviewId: String,
            body: String?,
            containsSpoilers: Boolean?,
        ): Review =
            guarded(notFound = GroupFailure.NoSuchEntry) {
                val patch =
                    buildJsonObject {
                        body?.let { put("body", it) }
                        containsSpoilers?.let { put("contains_spoilers", it) }
                    }
                api.updateReview(reviewId, patch).toDomain()
            }
    }

/**
 * Runs [block], translating any failure into [GroupOperationException] wrapping the [GroupFailure]
 * [mapFailure] derives (decision C-R). [notFound] is decision C-S's "sink is a parameter of the
 * guard helper chosen by the caller" made concrete: every call site above passes the [GroupFailure]
 * a 404 means FOR THAT ENDPOINT, rather than this function branching on `isProposeTitle` internally.
 *
 * [badRequest] (fix round 2) is the identical shape for 400, defaulting to `null` — a 400 with no
 * override still falls through to [GroupFailure.Unknown] via [mapFailure]'s `else` branch, exactly
 * round 1's behaviour, for every endpoint that has no more specific story for it. Only
 * [GroupRepositoryImpl.joinGroup] overrides it today.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun <T> guarded(
    notFound: GroupFailure = GroupFailure.NotAMember,
    badRequest: GroupFailure? = null,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        throw GroupOperationException(mapFailure(failure, notFound, badRequest))
    }

/**
 * 409 is checked unconditionally rather than gated behind a caller-supplied parameter the way 404
 * is: only [GroupRepositoryImpl.createReview] can ever receive one from the backend, so there is no
 * second meaning for [notFound] to disambiguate the way there is for 404 (NotAMember vs. NoSuchTitle).
 * [GroupFailure.AlreadyReviewed.existingReviewId] is always null — see that case's own KDoc.
 *
 * The 400 branch is gated on `badRequest != null` (fix round 2), unlike 403/404/409 above: those
 * three are unconditional because EVERY call site that can receive them wants a [GroupFailure] for
 * them (403 always means [GroupFailure.NotPermitted]; every 404 call site passes its own
 * [notFound]). 400 is different — most endpoints here have no distinct story for it and are
 * correctly served by falling through to the generic [GroupFailure.Unknown] `else` branch, so this
 * only fires the caller-chosen [badRequest] value when one was actually supplied.
 */
private fun mapFailure(
    failure: Throwable,
    notFound: GroupFailure,
    badRequest: GroupFailure?,
): GroupFailure =
    when {
        failure is HttpException && failure.code() == HTTP_FORBIDDEN -> GroupFailure.NotPermitted
        failure is HttpException && failure.code() == HTTP_NOT_FOUND -> notFound
        failure is HttpException && failure.code() == HTTP_CONFLICT ->
            GroupFailure.AlreadyReviewed(existingReviewId = null)
        failure is HttpException && failure.code() == HTTP_BAD_REQUEST && badRequest != null -> badRequest
        failure is IOException -> GroupFailure.Network
        else -> GroupFailure.Unknown(failure)
    }
