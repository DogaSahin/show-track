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
 * Shared by [GroupsViewModelTest] and [GroupsEntryHiltTest] — both exercise [GroupsViewModel]
 * against a fake rather than [com.anarky.showtrack.core.data.repository.GroupRepositoryImpl],
 * which would need Retrofit (neither is on this module's compile classpath — architecture rule 2).
 *
 * Only [groups]/[createGroup]/[joinGroup] are functional — the only three [GroupsViewModel] ever
 * calls. Every other member `error(...)`s rather than silently no-op-ing: a [GroupsViewModel] that
 * accidentally reached one of them fails LOUDLY, with that message, rather than an unexplained
 * `NotImplementedError` or a silently wrong result — [FakeLibraryRepository]'s (`:feature:favorites`)
 * identical discrimination technique.
 *
 * Failures are configured as a [GroupFailure] directly, not a [Throwable], and wrapped in
 * [GroupOperationException] here — mirroring exactly what
 * [com.anarky.showtrack.core.data.repository.GroupRepositoryImpl]'s own `guarded` does at the real
 * boundary, so [GroupsViewModel]'s `catch (failure: GroupOperationException)` is exercised the
 * same way it would be against production.
 *
 * [groupsGate]/[createGate]/[joinGate], when set, are what let a test observe [GroupsViewModel.state]
 * WHILE the corresponding suspend call is still suspended, rather than only before and after —
 * [FakeLibraryRepository.refreshGate]'s identical technique, needed for the same reason: a fake that
 * always resolves synchronously can never make a wrongly-shown intermediate state (e.g. `Loading` on
 * a resume, or a re-entrant second call) observable.
 */
internal class FakeGroupRepository(
    var groupsResult: List<Group> = emptyList(),
    var groupsFailure: GroupFailure? = null,
    var createResult: GroupWithInvite? = null,
    var createFailure: GroupFailure? = null,
    var joinResult: GroupWithInvite? = null,
    var joinFailure: GroupFailure? = null,
) : GroupRepository {
    var groupsGate: CompletableDeferred<Unit>? = null
    var createGate: CompletableDeferred<Unit>? = null
    var joinGate: CompletableDeferred<Unit>? = null

    var groupsCalls = 0
        private set

    var createCalls = 0
        private set

    var joinCalls = 0
        private set

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

    override suspend fun members(groupId: String): List<GroupMember> = error("not exercised by GroupsViewModel")

    override suspend fun rotateInvite(groupId: String): GroupWithInvite = error("not exercised by GroupsViewModel")

    override suspend fun removeMember(
        groupId: String,
        userId: String,
    ): Unit = error("not exercised by GroupsViewModel")

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
    ): WatchlistPage = error("not exercised by GroupsViewModel")

    override suspend fun proposeTitle(
        groupId: String,
        mediaId: String,
    ): WatchlistEntry = error("not exercised by GroupsViewModel")

    override suspend fun removeFromWatchlist(
        groupId: String,
        entryId: String,
    ): Unit = error("not exercised by GroupsViewModel")

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
