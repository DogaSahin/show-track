package com.anarky.showtrack

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
 * Only [groups] is functional — the single [GroupRepository] method [ActiveGroupViewModel] calls
 * — `:feature:feed`/`:feature:groups`' own `FakeGroupRepository` discrimination technique: every
 * other member `error(...)`s so a caller that accidentally reached one fails loudly instead of
 * silently. Shared by [ActiveGroupViewModelTest] and [GroupSwitchNavHostTest] — `internal`, not
 * `private`, for the identical reason [FakeActiveGroupStore] is (that class's own KDoc).
 *
 * [groupsFailure] and [groupsGate] (fix round 1) are `FeedViewModelTest`'s `FakeGroupRepository`'s
 * identical technique: a configurable failure lets B3's error-path tests exercise
 * [GroupOperationException] the same way `GroupRepositoryImpl`'s own `guarded` would produce it;
 * the gate lets a test observe [ActiveGroupViewModel.state] WHILE `groups()` is still suspended,
 * which the generation-guard tests (smaller item 2) need to construct an out-of-order pair of calls.
 */
internal class FakeGroupRepository(
    var groups: List<Group>,
) : GroupRepository {
    var groupsFailure: GroupFailure? = null
    var groupsGate: CompletableDeferred<Unit>? = null
    val groupsCallCount get() = callCount

    private var callCount = 0

    override suspend fun groups(): List<Group> {
        callCount++
        // Captured BEFORE the gate suspends, not after — so a later call that mutates [groups]
        // while this one is still gated does not retroactively change what THIS call returns.
        // Without this, the generation-guard test could never construct a distinguishable stale
        // response: both calls would return whatever `groups` happens to hold by the time either
        // one actually resumes.
        val result = groups
        groupsGate?.await()
        groupsFailure?.let { throw GroupOperationException(it) }
        return result
    }

    override suspend fun createGroup(name: String): GroupWithInvite = error("not exercised by this fake")

    override suspend fun joinGroup(inviteCode: String): GroupWithInvite = error("not exercised by this fake")

    override suspend fun members(groupId: String): List<GroupMember> = error("not exercised by this fake")

    override suspend fun rotateInvite(groupId: String): GroupWithInvite = error("not exercised by this fake")

    override suspend fun removeMember(
        groupId: String,
        userId: String,
    ): Unit = error("not exercised by this fake")

    override suspend fun feed(
        groupId: String,
        cursor: String?,
    ): FeedPage = error("not exercised by this fake")

    override suspend fun reviews(
        groupId: String,
        mediaId: String,
    ): List<Review> = error("not exercised by this fake")

    override suspend fun watchlist(
        groupId: String,
        cursor: String?,
    ): WatchlistPage = error("not exercised by this fake")

    override suspend fun proposeTitle(
        groupId: String,
        mediaId: String,
    ): WatchlistEntry = error("not exercised by this fake")

    override suspend fun removeFromWatchlist(
        groupId: String,
        entryId: String,
    ): Unit = error("not exercised by this fake")

    override suspend fun progress(
        groupId: String,
        mediaId: String,
    ): List<MemberProgress> = error("not exercised by this fake")

    override suspend fun createReview(
        mediaId: String,
        body: String,
        containsSpoilers: Boolean,
    ): Review = error("not exercised by this fake")

    override suspend fun updateReview(
        reviewId: String,
        body: String?,
        containsSpoilers: Boolean?,
    ): Review = error("not exercised by this fake")
}
