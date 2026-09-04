package com.anarky.showtrack.feature.detail

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
 * [DetailViewModel]'s own fixture for [GroupRepository] — `:feature:feed`'s `FakeGroupRepository`
 * technique, one module over: only [progress], [reviews] and [proposeTitle] are functional, the
 * three methods [DetailViewModel] actually calls; every other member `error(...)`s rather than
 * silently no-op-ing, so a ViewModel that accidentally reached one of them fails LOUDLY.
 *
 * [progressResults]/[reviewsResults]/[progressFailures]/[reviewsFailures] are keyed by
 * `(groupId, mediaId)`, not `mediaId` alone — `FeedViewModel`'s `feedPages` keyed by
 * `(groupId, cursor)` for the identical reason: the group-switch regression tests drive TWO groups
 * through the SAME fake for the SAME title, and group A's response/failure and group B's are
 * genuinely different.
 *
 * [progressGates]/[reviewsGates] are [FakeGroupRepository]'s (`:feature:feed`) `feedGates`'
 * identical per-key technique: a test suspends ONE specific group's fetch while a LATER group's
 * fetch, for the same title, resolves immediately — a single shared gate could not express that,
 * since it would suspend every call regardless of which group it named.
 */
internal class FakeGroupRepository(
    var progressResults: MutableMap<Pair<String, String>, List<MemberProgress>> = mutableMapOf(),
    var reviewsResults: MutableMap<Pair<String, String>, List<Review>> = mutableMapOf(),
    var proposeResult: WatchlistEntry? = null,
    var proposeFailure: GroupFailure? = null,
) : GroupRepository {
    var progressFailures: MutableMap<Pair<String, String>, GroupFailure> = mutableMapOf()
    var reviewsFailures: MutableMap<Pair<String, String>, GroupFailure> = mutableMapOf()
    var progressGates: MutableMap<Pair<String, String>, CompletableDeferred<Unit>> = mutableMapOf()
    var reviewsGates: MutableMap<Pair<String, String>, CompletableDeferred<Unit>> = mutableMapOf()

    val progressCalls = mutableListOf<Pair<String, String>>()
    val reviewsCalls = mutableListOf<Pair<String, String>>()

    var lastProposeGroupId: String? = null
        private set
    var lastProposeMediaId: String? = null
        private set
    var proposeCalls = 0
        private set

    override suspend fun progress(
        groupId: String,
        mediaId: String,
    ): List<MemberProgress> {
        val key = groupId to mediaId
        progressCalls += key
        progressGates[key]?.await()
        progressFailures[key]?.let { throw GroupOperationException(it) }
        return progressResults[key] ?: emptyList()
    }

    override suspend fun reviews(
        groupId: String,
        mediaId: String,
    ): List<Review> {
        val key = groupId to mediaId
        reviewsCalls += key
        reviewsGates[key]?.await()
        reviewsFailures[key]?.let { throw GroupOperationException(it) }
        return reviewsResults[key] ?: emptyList()
    }

    override suspend fun proposeTitle(
        groupId: String,
        mediaId: String,
    ): WatchlistEntry {
        lastProposeGroupId = groupId
        lastProposeMediaId = mediaId
        proposeCalls++
        proposeFailure?.let { throw GroupOperationException(it) }
        return proposeResult ?: error("no proposeResult configured")
    }

    override suspend fun groups(): List<Group> = error("not exercised by DetailViewModel")

    override suspend fun createGroup(name: String): GroupWithInvite = error("not exercised by DetailViewModel")

    override suspend fun joinGroup(inviteCode: String): GroupWithInvite = error("not exercised by DetailViewModel")

    override suspend fun members(groupId: String): List<GroupMember> = error("not exercised by DetailViewModel")

    override suspend fun rotateInvite(groupId: String): GroupWithInvite = error("not exercised by DetailViewModel")

    override suspend fun removeMember(
        groupId: String,
        userId: String,
    ): Unit = error("not exercised by DetailViewModel")

    override suspend fun feed(
        groupId: String,
        cursor: String?,
    ): FeedPage = error("not exercised by DetailViewModel")

    override suspend fun watchlist(
        groupId: String,
        cursor: String?,
    ): WatchlistPage = error("not exercised by DetailViewModel")

    override suspend fun removeFromWatchlist(
        groupId: String,
        entryId: String,
    ): Unit = error("not exercised by DetailViewModel")

    override suspend fun createReview(
        mediaId: String,
        body: String,
        containsSpoilers: Boolean,
    ): Review = error("not exercised by DetailViewModel")

    override suspend fun updateReview(
        reviewId: String,
        body: String?,
        containsSpoilers: Boolean?,
    ): Review = error("not exercised by DetailViewModel")
}
