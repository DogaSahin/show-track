package com.anarky.showtrack.feature.feed

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
 * [FeedViewModel]/[FeedScreen]'s own fixture — `:feature:groups`' `FakeGroupRepository`'s
 * identical technique, one module over: every real test here exercises its ViewModel against this
 * fake rather than `GroupRepositoryImpl`, which would need Retrofit (neither is on this module's
 * compile classpath — architecture rule 2).
 *
 * Only [feed] is functional — the single [GroupRepository] method this module ever calls. Every
 * other member `error(...)`s rather than silently no-op-ing: a ViewModel that accidentally reached
 * one of them fails LOUDLY, with that message, rather than an unexplained result —
 * `:feature:groups`' own `FakeGroupRepository`'s identical discrimination technique.
 *
 * [feedPages] defaults to a single, EMPTY, successful first page — the identical default
 * `:feature:groups`' `FakeGroupRepository.watchlistPages` uses, for the identical reason: a test
 * that never mentions the feed at all still needs `selectGroup`'s own fetch to resolve to
 * something rather than an `error(...)`.
 *
 * [feedFailure] is a [GroupFailure] directly, not a [Throwable], wrapped in
 * [GroupOperationException] here — mirroring exactly what `GroupRepositoryImpl`'s own `guarded`
 * does at the real boundary, so [FeedViewModel]'s `catch (failure: GroupOperationException)` is
 * exercised the same way it would be against production.
 *
 * [feedGate], when set, is what lets a test observe [FeedViewModel.state] WHILE `feed()` is still
 * suspended, rather than only before and after — `FakeLibraryRepository.refreshGate`'s identical
 * technique, needed to make a re-entrant `loadMore()` call's rejection actually observable.
 */
internal class FakeGroupRepository(
    var feedPages: MutableMap<String?, FeedPage> =
        mutableMapOf(null to FeedPage(items = emptyList(), nextCursor = null)),
    var feedFailure: GroupFailure? = null,
) : GroupRepository {
    var feedGate: CompletableDeferred<Unit>? = null

    // Every (groupId, cursor) pair `feed` was actually called with, in order — what a paging test
    // uses to prove EXACTLY one fetch per page, neither a duplicate first-page re-fetch nor a
    // skipped one (`FeedViewModelTest`'s "paging the feed appends without duplicates").
    val feedCalls = mutableListOf<Pair<String, String?>>()

    override suspend fun feed(
        groupId: String,
        cursor: String?,
    ): FeedPage {
        feedCalls += groupId to cursor
        feedGate?.await()
        feedFailure?.let { throw GroupOperationException(it) }
        return feedPages[cursor] ?: error("no feedPages entry configured for cursor=$cursor")
    }

    override suspend fun groups(): List<Group> = error("not exercised by FeedViewModel")

    override suspend fun createGroup(name: String): GroupWithInvite = error("not exercised by FeedViewModel")

    override suspend fun joinGroup(inviteCode: String): GroupWithInvite = error("not exercised by FeedViewModel")

    override suspend fun members(groupId: String): List<GroupMember> = error("not exercised by FeedViewModel")

    override suspend fun rotateInvite(groupId: String): GroupWithInvite = error("not exercised by FeedViewModel")

    override suspend fun removeMember(
        groupId: String,
        userId: String,
    ): Unit = error("not exercised by FeedViewModel")

    override suspend fun reviews(
        groupId: String,
        mediaId: String,
    ): List<Review> = error("not exercised by FeedViewModel")

    override suspend fun watchlist(
        groupId: String,
        cursor: String?,
    ): WatchlistPage = error("not exercised by FeedViewModel")

    override suspend fun proposeTitle(
        groupId: String,
        mediaId: String,
    ): WatchlistEntry = error("not exercised by FeedViewModel")

    override suspend fun removeFromWatchlist(
        groupId: String,
        entryId: String,
    ): Unit = error("not exercised by FeedViewModel")

    override suspend fun progress(
        groupId: String,
        mediaId: String,
    ): List<MemberProgress> = error("not exercised by FeedViewModel")

    override suspend fun createReview(
        mediaId: String,
        body: String,
        containsSpoilers: Boolean,
    ): Review = error("not exercised by FeedViewModel")

    override suspend fun updateReview(
        reviewId: String,
        body: String?,
        containsSpoilers: Boolean?,
    ): Review = error("not exercised by FeedViewModel")
}
