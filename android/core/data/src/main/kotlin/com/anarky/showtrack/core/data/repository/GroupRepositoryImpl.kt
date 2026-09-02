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

        override suspend fun joinGroup(inviteCode: String): GroupWithInvite =
            guarded { api.joinGroup(JoinGroupRequestDto(inviteCode = inviteCode)).toDomain() }

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
         * [GroupFailure.NoSuchTitle] on a 404 here — the ONE call site that overrides [guarded]'s
         * default [GroupFailure.NotAMember], because a 404 from this endpoint means the *media id*
         * was not found (`app/groups/routes.py`'s `responses={404: "no such title"}`), not that the
         * caller left the group between the membership check and this line.
         */
        override suspend fun proposeTitle(
            groupId: String,
            mediaId: String,
        ): WatchlistEntry =
            guarded(notFound = GroupFailure.NoSuchTitle) {
                api.proposeToWatchlist(groupId, ProposeTitleRequestDto(mediaId = mediaId)).toDomain()
            }

        override suspend fun removeFromWatchlist(
            groupId: String,
            entryId: String,
        ) = guarded { api.removeFromWatchlist(groupId, entryId) }

        override suspend fun progress(
            groupId: String,
            mediaId: String,
        ): List<MemberProgress> = guarded { api.groupProgress(groupId, mediaId).map { it.toDomain() } }

        override suspend fun createReview(
            mediaId: String,
            body: String,
            containsSpoilers: Boolean,
        ): Review =
            guarded {
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
         */
        override suspend fun updateReview(
            reviewId: String,
            body: String?,
            containsSpoilers: Boolean?,
        ): Review =
            guarded {
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
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun <T> guarded(
    notFound: GroupFailure = GroupFailure.NotAMember,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        throw GroupOperationException(mapFailure(failure, notFound))
    }

/**
 * 409 is checked unconditionally rather than gated behind a caller-supplied parameter the way 404
 * is: only [GroupRepositoryImpl.createReview] can ever receive one from the backend, so there is no
 * second meaning for [notFound] to disambiguate the way there is for 404 (NotAMember vs. NoSuchTitle).
 * [GroupFailure.AlreadyReviewed.existingReviewId] is always null — see that case's own KDoc.
 */
private fun mapFailure(
    failure: Throwable,
    notFound: GroupFailure,
): GroupFailure =
    when {
        failure is HttpException && failure.code() == HTTP_FORBIDDEN -> GroupFailure.NotPermitted
        failure is HttpException && failure.code() == HTTP_NOT_FOUND -> notFound
        failure is HttpException && failure.code() == HTTP_CONFLICT ->
            GroupFailure.AlreadyReviewed(existingReviewId = null)
        failure is IOException -> GroupFailure.Network
        else -> GroupFailure.Unknown(failure)
    }
