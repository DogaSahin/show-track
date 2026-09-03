package com.anarky.showtrack.feature.groups

import com.anarky.showtrack.core.data.repository.FeedPage
import com.anarky.showtrack.core.data.repository.GroupOperationException
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.data.repository.WatchlistPage
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.MemberProgress
import com.anarky.showtrack.core.model.Review
import com.anarky.showtrack.core.model.WatchlistEntry
import kotlinx.coroutines.CompletableDeferred

/**
 * Shared by [GroupsViewModelTest]/[GroupsEntryHiltTest] and, as of task 9c.2,
 * [GroupDetailViewModelTest]/[GroupDetailScreenTest]/`GroupDetailEntryHiltTest` — every one of
 * them exercises its ViewModel against a fake rather than
 * [com.anarky.showtrack.core.data.repository.GroupRepositoryImpl], which would need Retrofit
 * (neither is on this module's compile classpath — architecture rule 2).
 *
 * [groups]/[createGroup]/[joinGroup] (the three [GroupsViewModel] calls), since task 9c.2,
 * [members]/[rotateInvite]/[removeMember], and, since task 9c.3, [watchlist]/[removeFromWatchlist]
 * (five of the seven [GroupDetailViewModel] calls — `currentUserId` moved to
 * `AuthRepository`/`FakeAuthRepository` in round 1 review) are functional. Every OTHER member
 * `error(...)`s rather than silently no-op-ing: a ViewModel that accidentally reached one of them
 * fails LOUDLY, with that message, rather than an unexplained `NotImplementedError` or a silently
 * wrong result — [FakeLibraryRepository]'s (`:feature:favorites`) identical discrimination technique.
 *
 * **[proposeTitle] reverted to `error(...)` in fix round 1**: task 9c.3's own propose UI was
 * removed from `:feature:groups` entirely (a raw-media-id text field was worse than not offering
 * the action — see `GroupDetailActionState`'s own KDoc), so nothing here calls it any more; a
 * still-functional fake for a method [GroupDetailViewModel] no longer reaches would silently mask
 * a regression that made it reachable again by mistake.
 *
 * [watchlistPages] defaults to a single, EMPTY, successful first page (`null to WatchlistPage(items
 * = emptyList(), nextCursor = null)`) — not an `error(...)`, unlike every other still-unimplemented
 * member — because [GroupDetailViewModel.refresh] (task 9c.3) now fetches the watchlist's first page
 * unconditionally once the member list has loaded, so EVERY existing test in this file that never
 * mentions the watchlist at all (every test written before this task) still exercises that fetch on
 * every `advanceUntilIdle()`. A default that threw would break every one of them; a default that
 * silently returns nothing keeps `GroupDetailUiState.Success`'s new fields at their own defaults,
 * which is exactly what those tests' existing `assertEquals(GroupDetailUiState.Success(members =
 * ...), ...)` calls (built with the watchlist fields left at THEIR defaults too) already expect.
 *
 * Failures are configured as a [GroupFailure] directly, not a [Throwable], and wrapped in
 * [GroupOperationException] here — mirroring exactly what
 * [com.anarky.showtrack.core.data.repository.GroupRepositoryImpl]'s own `guarded` does at the real
 * boundary, so a ViewModel's `catch (failure: GroupOperationException)` is exercised the same way
 * it would be against production.
 *
 * [groupsGate]/[createGate]/[joinGate]/[membersGate]/[removeMemberGate], when set, are what let a
 * test observe a ViewModel's state WHILE the corresponding suspend call is still suspended, rather
 * than only before and after — [FakeLibraryRepository.refreshGate]'s identical technique, needed
 * for the same reason: a fake that always resolves synchronously can never make a wrongly-shown
 * intermediate state (e.g. `Loading` on a resume, or a re-entrant second call) observable.
 *
 * `@Suppress("LongParameterList")`: this fake's constructor is one result/failure pair per
 * [GroupRepository] method it actually implements — `GroupRepositoryImplTest`'s own
 * `TooManyFunctions` suppression carries the identical seam-cohesion argument for why the
 * INTERFACE this mirrors is one type rather than several.
 */
@Suppress("LongParameterList")
internal class FakeGroupRepository(
    var groupsResult: List<Group> = emptyList(),
    var groupsFailure: GroupFailure? = null,
    var createResult: GroupWithInvite? = null,
    var createFailure: GroupFailure? = null,
    var joinResult: GroupWithInvite? = null,
    var joinFailure: GroupFailure? = null,
    var membersResult: List<GroupMember> = emptyList(),
    var membersFailure: GroupFailure? = null,
    var rotateResult: GroupWithInvite? = null,
    var rotateFailure: GroupFailure? = null,
    var removeMemberFailure: GroupFailure? = null,
    var watchlistPages: MutableMap<String?, WatchlistPage> =
        mutableMapOf(null to WatchlistPage(items = emptyList(), nextCursor = null)),
    var watchlistFailure: GroupFailure? = null,
    var removeWatchlistEntryFailure: GroupFailure? = null,
) : GroupRepository {
    var groupsGate: CompletableDeferred<Unit>? = null
    var createGate: CompletableDeferred<Unit>? = null
    var joinGate: CompletableDeferred<Unit>? = null
    var membersGate: CompletableDeferred<Unit>? = null
    var removeMemberGate: CompletableDeferred<Unit>? = null
    var watchlistGate: CompletableDeferred<Unit>? = null
    var removeWatchlistEntryGate: CompletableDeferred<Unit>? = null

    var groupsCalls = 0
        private set

    var createCalls = 0
        private set

    var joinCalls = 0
        private set

    var rotateCalls = 0
        private set

    // Every (groupId, userId) pair removeMember was actually called with, in order — what lets a
    // test tell "leave (your own id)" apart from "remove (someone else's)" at the repository
    // boundary, since both are the SAME call with a different argument (design doc §1.1).
    val removeMemberCalls = mutableListOf<Pair<String, String>>()

    // Every cursor `watchlist` was actually called with, in order — what a paging test uses to
    // prove EXACTLY one fetch per page, neither a duplicate first-page re-fetch nor a skipped one
    // (`GroupDetailViewModelTest`'s "paging the watchlist appends without duplicates").
    val watchlistCalls = mutableListOf<String?>()

    val removeWatchlistEntryCalls = mutableListOf<Pair<String, String>>()

    override suspend fun groups(): List<Group> {
        groupsCalls++
        groupsGate?.await()
        groupsFailure?.let { throw GroupOperationException(it) }
        return groupsResult
    }

    override suspend fun createGroup(name: String): GroupWithInvite {
        createCalls++
        createGate?.await()
        createFailure?.let { throw GroupOperationException(it) }
        return createResult ?: error("createResult not set for this test")
    }

    override suspend fun joinGroup(inviteCode: String): GroupWithInvite {
        joinCalls++
        joinGate?.await()
        joinFailure?.let { throw GroupOperationException(it) }
        return joinResult ?: error("joinResult not set for this test")
    }

    override suspend fun members(groupId: String): List<GroupMember> {
        membersGate?.await()
        membersFailure?.let { throw GroupOperationException(it) }
        return membersResult
    }

    override suspend fun rotateInvite(groupId: String): GroupWithInvite {
        rotateCalls++
        rotateFailure?.let { throw GroupOperationException(it) }
        return rotateResult ?: error("rotateResult not set for this test")
    }

    override suspend fun removeMember(
        groupId: String,
        userId: String,
    ) {
        removeMemberCalls += groupId to userId
        removeMemberGate?.await()
        removeMemberFailure?.let { throw GroupOperationException(it) }
    }

    override suspend fun feed(
        groupId: String,
        cursor: String?,
    ): FeedPage = error("not exercised by GroupsViewModel")

    override suspend fun reviews(
        groupId: String,
        mediaId: String,
    ): List<Review> = error("not exercised by GroupsViewModel")

    override suspend fun watchlist(
        groupId: String,
        cursor: String?,
    ): WatchlistPage {
        watchlistCalls += cursor
        watchlistGate?.await()
        watchlistFailure?.let { throw GroupOperationException(it) }
        return watchlistPages[cursor] ?: error("no watchlistPages entry configured for cursor=$cursor")
    }

    override suspend fun proposeTitle(
        groupId: String,
        mediaId: String,
    ): WatchlistEntry = error("not exercised by GroupsViewModel")

    override suspend fun removeFromWatchlist(
        groupId: String,
        entryId: String,
    ) {
        removeWatchlistEntryCalls += groupId to entryId
        removeWatchlistEntryGate?.await()
        removeWatchlistEntryFailure?.let { throw GroupOperationException(it) }
    }

    override suspend fun progress(
        groupId: String,
        mediaId: String,
    ): List<MemberProgress> = error("not exercised by GroupsViewModel")

    override suspend fun createReview(
        mediaId: String,
        body: String,
        containsSpoilers: Boolean,
    ): Review = error("not exercised by GroupsViewModel")

    override suspend fun updateReview(
        reviewId: String,
        body: String?,
        containsSpoilers: Boolean?,
    ): Review = error("not exercised by GroupsViewModel")
}
