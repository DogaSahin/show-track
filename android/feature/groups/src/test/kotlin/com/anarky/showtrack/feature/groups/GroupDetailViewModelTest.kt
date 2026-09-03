package com.anarky.showtrack.feature.groups

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.data.repository.WatchlistPage
import com.anarky.showtrack.core.model.AuthFailure
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.GroupRole
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.WatchlistEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

/**
 * [GroupDetailViewModel] exercised against fake [com.anarky.showtrack.core.data.repository.GroupRepository]
 * and [com.anarky.showtrack.core.data.repository.AuthRepository] — [GroupsViewModelTest]'s
 * identical shape, one screen (and one more dependency) over.
 *
 * Robolectric, `DetailViewModelTest`'s identical reasoning (that class's own KDoc, quoted here
 * since this is the same failure mode): [GroupDetailViewModel]'s constructor calls
 * `SavedStateHandle.toRoute<GroupDetailRoute>()`, which builds an intermediate `android.os.Bundle`
 * internally — unmocked, and therefore a crash, on a bare JVM. `sdk=35` is pinned module-wide in
 * `src/test/resources/robolectric.properties` (Robolectric ships no shadow jar for 36);
 * `application = Application::class` avoids standing up `ShowTrackApplication`'s `@HiltAndroidApp`
 * component, which this test needs neither DataStore nor the Keystore from.
 *
 * `GroupDetailViewModel`'s `init { refresh() }` — which itself calls `loadCurrentUserId()`, per
 * that class's own KDoc — means every test below observes the CONSTRUCTOR's own load of BOTH
 * members and identity — `viewModel(...)` alone already schedules both, so `advanceUntilIdle()`
 * right after construction is what stands in for the resume `GroupsViewModelTest` calls
 * explicitly, mirroring how `DetailViewModelTest` (this class's own closer analogue) handles its
 * identical `init { load() }`.
 *
 * **Round 1 review's own instruction, followed here:** several tests below build a state the
 * screen can genuinely reach but round 0's suite never did — most importantly `currentUserIdFailure`/
 * `membersFailure` in independent COMBINATIONS, and [GroupDetailViewModel.leaveGroup]/
 * [GroupDetailViewModel.rotateInvite] invoked from [GroupDetailUiState.Error]/[GroupDetailUiState.Loading]
 * rather than only from an already-populated [GroupDetailUiState.Success]. BLOCKING 2 and BLOCKING 3
 * both lived in exactly that gap.
 *
 * **What this class deliberately does NOT pin:** owner-only rendering (E-F) and "leave is offered to
 * everyone, remove never for yourself" are RENDERING decisions — `GroupDetailScreenTest` pins those,
 * driving the stateless `GroupDetailScreen` overload directly (Global Constraints: "if a behaviour is
 * a rendering decision, a ViewModel test cannot pin it").
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GroupDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        groupRepository: FakeGroupRepository = FakeGroupRepository(),
        authRepository: FakeAuthRepository = FakeAuthRepository(),
    ): GroupDetailViewModel =
        GroupDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("groupId" to GROUP_ID)),
            groupRepository = groupRepository,
            authRepository = authRepository,
        )

    @Test
    fun `refresh loads the members, and identity resolves independently`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER, MEMBER))
            val authRepository = FakeAuthRepository(currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(groupRepository, authRepository)
            advanceUntilIdle()

            assertEquals(
                GroupDetailUiState.Success(members = listOf(OWNER, MEMBER)),
                viewModel.state.value,
            )
            assertEquals(OWNER.userId, viewModel.currentUserId.value)
        }

    @Test
    fun `a failed initial load with nothing on screen produces Error`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersFailure = GroupFailure.Network)
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            assertEquals(GroupDetailUiState.Error(GroupFailure.Network), viewModel.state.value)
        }

    /**
     * BLOCKING 2's own scenario, at the identity layer: a failed member-list load must not stop
     * identity from resolving — they are now two independent coroutines, launched from `init`
     * separately (`GroupDetailViewModel`'s own KDoc). Round 0 fetched both inside the SAME `try`,
     * so a `membersFailure` here would ALSO have meant no `currentUserId` at all.
     */
    @Test
    fun `identity resolves even when the member list fails to load`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersFailure = GroupFailure.Network)
            val authRepository = FakeAuthRepository(currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(groupRepository, authRepository)
            advanceUntilIdle()

            assertEquals(GroupDetailUiState.Error(GroupFailure.Network), viewModel.state.value)
            assertEquals(OWNER.userId, viewModel.currentUserId.value)
        }

    /**
     * The settled refresh shape (Global Constraints), `GroupsViewModelTest`'s identical test one
     * screen over: a background reload must not blank an already-populated screen to a spinner
     * while it is still in flight.
     */
    @Test
    fun `a reload over an already-loaded screen keeps the members, not a spinner, mid-fetch`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER))
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()
            assertEquals(GroupDetailUiState.Success(members = listOf(OWNER)), viewModel.state.value)

            groupRepository.membersResult = listOf(OWNER, MEMBER)
            groupRepository.membersGate = CompletableDeferred()
            viewModel.refresh()
            advanceUntilIdle()

            // Still the OLD member list, and still Success — never Loading — while the network
            // round trip this refresh triggered is genuinely still in flight.
            assertEquals(GroupDetailUiState.Success(members = listOf(OWNER)), viewModel.state.value)

            groupRepository.membersGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(GroupDetailUiState.Success(members = listOf(OWNER, MEMBER)), viewModel.state.value)
        }

    @Test
    fun `a failed reload over an already-loaded screen marks it stale instead of replacing it`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER))
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            groupRepository.membersFailure = GroupFailure.Network
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                GroupDetailUiState.Success(members = listOf(OWNER), isStale = true),
                viewModel.state.value,
            )
        }

    /** [refresh] retries a previously-failed identity resolution too, not only the member list. */
    @Test
    fun `refresh retries a previously failed identity resolution`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER))
            val authRepository = FakeAuthRepository(currentUserIdFailure = AuthFailure.Offline(IOException("offline")))
            val viewModel = viewModel(groupRepository, authRepository)
            advanceUntilIdle()
            assertNull(viewModel.currentUserId.value)

            authRepository.currentUserIdFailure = null
            authRepository.currentUserIdResult = OWNER.userId
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(OWNER.userId, viewModel.currentUserId.value)
        }

    /** The negative control for the test above: once resolved, a refresh must NOT re-ask. */
    @Test
    fun `refresh does not re-resolve identity once it has already succeeded`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER))
            val authRepository = FakeAuthRepository(currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(groupRepository, authRepository)
            advanceUntilIdle()
            assertEquals(1, authRepository.currentUserIdCalls)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(1, authRepository.currentUserIdCalls)
        }

    /** The brief's fourth named test, verbatim. */
    @Test
    fun `rotating replaces the displayed code`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER))
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            groupRepository.rotateResult = INVITE
            viewModel.rotateInvite()
            advanceUntilIdle()

            assertEquals(INVITE, (viewModel.state.value as GroupDetailUiState.Success).rotatedInvite)
            assertEquals(GroupDetailActionState(), viewModel.actionState.value)
        }

    /**
     * The negative control: a failed rotate must leave [GroupDetailUiState.Success.rotatedInvite]
     * unset (never a stale or partial code) and surface the failure on
     * [GroupDetailActionState.rotateError] instead — [GroupsViewModelTest]'s identical shape for
     * `createGroup`.
     */
    @Test
    fun `a failed rotate surfaces the error and leaves no code on screen`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER))
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            groupRepository.rotateFailure = GroupFailure.NotPermitted
            viewModel.rotateInvite()
            advanceUntilIdle()

            assertNull((viewModel.state.value as GroupDetailUiState.Success).rotatedInvite)
            assertEquals(GroupFailure.NotPermitted, viewModel.actionState.value.rotateError)
        }

    @Test
    fun `rotate is not fired again while one is already in flight`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER), rotateResult = INVITE)
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            viewModel.rotateInvite()
            viewModel.rotateInvite()
            viewModel.rotateInvite()
            advanceUntilIdle()

            assertEquals(1, groupRepository.rotateCalls)
        }

    /**
     * BLOCKING 3 (round 1 review): round 0 let `rotateInvite()` reach the repository from a
     * non-`Success` state, genuinely rotating the server-side code and then discarding the
     * response — an irreversible consequence for a call `GroupDetailScreen` never offers outside
     * `Success` in the first place. The guard now makes that a real no-op.
     */
    @Test
    fun `rotateInvite does nothing when the member list failed to load`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersFailure = GroupFailure.Network, rotateResult = INVITE)
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()
            assertEquals(GroupDetailUiState.Error(GroupFailure.Network), viewModel.state.value)

            viewModel.rotateInvite()
            advanceUntilIdle()

            assertEquals(0, groupRepository.rotateCalls)
            assertEquals(GroupDetailActionState(), viewModel.actionState.value)
        }

    /** [rotateInvite]'s guard, isolated from the "loaded but not yet resolved" [Loading] case too. */
    @Test
    fun `rotateInvite does nothing while the member list is still loading`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(rotateResult = INVITE)
            groupRepository.membersGate = CompletableDeferred()
            val viewModel = viewModel(groupRepository)
            assertEquals(GroupDetailUiState.Loading, viewModel.state.value)

            viewModel.rotateInvite()
            advanceUntilIdle()

            assertEquals(0, groupRepository.rotateCalls)
        }

    @Test
    fun `leaving calls removeMember with the signed-in user's own id and sets left`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER, MEMBER))
            val authRepository = FakeAuthRepository(currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(groupRepository, authRepository)
            advanceUntilIdle()

            viewModel.leaveGroup()
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to OWNER.userId), groupRepository.removeMemberCalls)
            assertTrue(viewModel.left.value)
        }

    /**
     * BLOCKING 2, the flagship regression test (round 1 review's own instruction: "include at
     * least one that builds a state the screen can reach but no existing test creates"). Round 0's
     * `leaveGroup()` read `(state as? Success)?.currentUserId ?: return` — the literal shape
     * `GroupsActionState`'s own fix rounds existed to remove — so from [GroupDetailUiState.Error]
     * this returned silently: no call, no error, `left` stayed false, and the affordance was not
     * even on screen (`GroupDetailSuccessContent` is where round 0 kept the Leave button). Fixed by
     * moving identity off [state] entirely; this is the direct proof it no longer depends on it.
     */
    @Test
    fun `leaving succeeds even when the member list failed to load`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersFailure = GroupFailure.Network)
            val authRepository = FakeAuthRepository(currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(groupRepository, authRepository)
            advanceUntilIdle()
            assertEquals(GroupDetailUiState.Error(GroupFailure.Network), viewModel.state.value)

            viewModel.leaveGroup()
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to OWNER.userId), groupRepository.removeMemberCalls)
            assertTrue(viewModel.left.value)
        }

    /** [leaveGroup]'s mirror of the test above, for the OTHER state a guard on [state] would have blocked. */
    @Test
    fun `leaving succeeds while the member list is still loading`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository()
            groupRepository.membersGate = CompletableDeferred()
            val authRepository = FakeAuthRepository(currentUserIdResult = OWNER.userId)
            val viewModel = viewModel(groupRepository, authRepository)
            advanceUntilIdle()
            assertEquals(GroupDetailUiState.Loading, viewModel.state.value)

            viewModel.leaveGroup()
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to OWNER.userId), groupRepository.removeMemberCalls)
            assertTrue(viewModel.left.value)
        }

    @Test
    fun `a failed leave surfaces the error and does not set left`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER))
            groupRepository.removeMemberFailure = GroupFailure.Network
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            viewModel.leaveGroup()
            advanceUntilIdle()

            assertEquals(GroupFailure.Network, viewModel.actionState.value.leaveError)
            assertTrue("a failed leave must not navigate away", !viewModel.left.value)
        }

    /**
     * `leaveGroup`'s own KDoc: it re-asks [com.anarky.showtrack.core.data.repository.AuthRepository]
     * directly rather than trusting the cached [GroupDetailViewModel.currentUserId] field, so a
     * failure THERE also surfaces as a (generic) leave failure rather than an unexplained no-op.
     */
    @Test
    fun `a failed identity resolution surfaces as a leave failure too`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER))
            val authRepository = FakeAuthRepository(currentUserIdFailure = AuthFailure.Offline(IOException("offline")))
            val viewModel = viewModel(groupRepository, authRepository)
            advanceUntilIdle()
            assertNull(viewModel.currentUserId.value)

            viewModel.leaveGroup()
            advanceUntilIdle()

            assertTrue(viewModel.actionState.value.leaveError is GroupFailure.Unknown)
            assertTrue("a failed leave must not navigate away", !viewModel.left.value)
        }

    @Test
    fun `leave is not fired again while one is already in flight`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER))
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            viewModel.leaveGroup()
            viewModel.leaveGroup()
            viewModel.leaveGroup()
            advanceUntilIdle()

            assertEquals(1, groupRepository.removeMemberCalls.size)
        }

    /**
     * Mutation-critical negative control: a `removeMember` that always targeted the CALLER's own
     * id (or the first member in the list) rather than the argument it was given would pass every
     * OTHER test in this file, since [MEMBER] is not [OWNER]. Two members in the fixture, removing
     * the SECOND one, is what actually discriminates "reads the argument" from "reads something
     * fixed" — `GroupsEntryHiltTest`'s own KDoc names this exact failure shape.
     */
    @Test
    fun `removing a member calls removeMember with that member's id, not the caller's own`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER, MEMBER))
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            viewModel.removeMember(MEMBER.userId)
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to MEMBER.userId), groupRepository.removeMemberCalls)
        }

    /**
     * `GroupDetailViewModel.removeMember`'s own KDoc: [GroupDetailActionState.removingUserId] stays
     * set through the WHOLE round trip, including the reload, not just the delete call — a fast
     * double-tap in that window must still be dropped. [membersGate] is what makes the in-flight
     * reload observable rather than resolving synchronously and hiding the window entirely.
     */
    @Test
    fun `removingUserId stays set through the reload, not just the delete call`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER, MEMBER))
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            groupRepository.membersGate = CompletableDeferred()
            groupRepository.membersResult = listOf(OWNER)
            viewModel.removeMember(MEMBER.userId)
            advanceUntilIdle()

            // The DELETE itself has completed (it needs no gate to observe), but the follow-up
            // GET /members this reload issues is still suspended — removingUserId must still be set.
            assertEquals(MEMBER.userId, viewModel.actionState.value.removingUserId)

            // A second remove call for a DIFFERENT member must still be dropped while the first
            // one's reload is in flight.
            viewModel.removeMember(OWNER.userId)
            advanceUntilIdle()
            assertEquals(1, groupRepository.removeMemberCalls.size)

            groupRepository.membersGate?.complete(Unit)
            advanceUntilIdle()

            assertNull(viewModel.actionState.value.removingUserId)
            assertEquals(listOf(OWNER), (viewModel.state.value as GroupDetailUiState.Success).members)
        }

    @Test
    fun `a failed remove surfaces the error, clears removingUserId, and leaves the member list untouched`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER, MEMBER))
            groupRepository.removeMemberFailure = GroupFailure.NotPermitted
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            viewModel.removeMember(MEMBER.userId)
            advanceUntilIdle()

            assertEquals(GroupFailure.NotPermitted, viewModel.actionState.value.removeError)
            assertNull(viewModel.actionState.value.removingUserId)
            assertEquals(listOf(OWNER, MEMBER), (viewModel.state.value as GroupDetailUiState.Success).members)
        }

    @Test
    fun `remove is not fired again while one is already in flight`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER, MEMBER))
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            viewModel.removeMember(MEMBER.userId)
            viewModel.removeMember(MEMBER.userId)
            viewModel.removeMember(MEMBER.userId)
            advanceUntilIdle()

            assertEquals(1, groupRepository.removeMemberCalls.size)
        }

    /**
     * Round 1 review, minor 5: a rotated code that is still on screen must SURVIVE a remove's own
     * reload — round 0's single reload path always dropped it, so an owner rotating and then
     * removing a different member lost the just-rotated (and now server-side invalidated) code
     * with no way back except rotating again.
     */
    @Test
    fun `removing a member preserves a rotated code already on screen`() =
        runTest(dispatcher) {
            val groupRepository =
                FakeGroupRepository(membersResult = listOf(OWNER, MEMBER), rotateResult = INVITE)
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()
            viewModel.rotateInvite()
            advanceUntilIdle()
            assertEquals(INVITE, (viewModel.state.value as GroupDetailUiState.Success).rotatedInvite)

            groupRepository.membersResult = listOf(OWNER)
            viewModel.removeMember(MEMBER.userId)
            advanceUntilIdle()

            assertEquals(INVITE, (viewModel.state.value as GroupDetailUiState.Success).rotatedInvite)
        }

    /** The negative control for the test above: an ORDINARY [refresh] still drops it (E-I, unchanged). */
    @Test
    fun `an ordinary refresh still drops a rotated code`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER), rotateResult = INVITE)
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()
            viewModel.rotateInvite()
            advanceUntilIdle()
            assertEquals(INVITE, (viewModel.state.value as GroupDetailUiState.Success).rotatedInvite)

            viewModel.refresh()
            advanceUntilIdle()

            assertNull((viewModel.state.value as GroupDetailUiState.Success).rotatedInvite)
        }

    /**
     * Fix-round-2 lesson (`GroupsViewModel.clearCreateError`'s own KDoc), applied proactively:
     * three SEPARATE channels (decision C-S), so clearing one must never touch the other two.
     */
    @Test
    fun `clearRotateError, clearRemoveError and clearLeaveError each clear only their own channel`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER, MEMBER))
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            groupRepository.rotateFailure = GroupFailure.NotPermitted
            viewModel.rotateInvite()
            advanceUntilIdle()
            groupRepository.removeMemberFailure = GroupFailure.NotPermitted
            viewModel.removeMember(MEMBER.userId)
            advanceUntilIdle()
            groupRepository.removeMemberFailure = GroupFailure.Network
            viewModel.leaveGroup()
            advanceUntilIdle()

            assertEquals(GroupFailure.NotPermitted, viewModel.actionState.value.rotateError)
            assertEquals(GroupFailure.NotPermitted, viewModel.actionState.value.removeError)
            assertEquals(GroupFailure.Network, viewModel.actionState.value.leaveError)

            viewModel.clearRotateError()
            assertNull(viewModel.actionState.value.rotateError)
            assertEquals(GroupFailure.NotPermitted, viewModel.actionState.value.removeError)
            assertEquals(GroupFailure.Network, viewModel.actionState.value.leaveError)

            viewModel.clearRemoveError()
            assertNull(viewModel.actionState.value.removeError)
            assertEquals(GroupFailure.Network, viewModel.actionState.value.leaveError)

            viewModel.clearLeaveError()
            assertNull(viewModel.actionState.value.leaveError)
        }

    @Test
    fun `dismissRotatedInvite clears the rotated code and nothing else`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER), rotateResult = INVITE)
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()
            viewModel.rotateInvite()
            advanceUntilIdle()
            assertEquals(INVITE, (viewModel.state.value as GroupDetailUiState.Success).rotatedInvite)

            viewModel.dismissRotatedInvite()

            val result = viewModel.state.value as GroupDetailUiState.Success
            assertNull(result.rotatedInvite)
            assertEquals(listOf(OWNER), result.members)
        }

    /**
     * The brief's second named test (task 9c.3), verbatim. `WatchlistPage` is `{items, next_cursor}`
     * over the composite `(sort_value, id)` cursor (architecture rule 4) — [ENTRY_1]/[ENTRY_2] are
     * DISTINCT entries so a bug that re-fetched or re-appended page one would be visible as a
     * duplicate, not merely a wrong count. [watchlistCalls] pins the cursor sequence itself: a
     * mutant that fetched the first page twice, or skipped straight to page two without a first
     * fetch, would still leave [state] looking plausible but would fail that assertion.
     */
    @Test
    fun `paging the watchlist appends without duplicates`() =
        runTest(dispatcher) {
            val groupRepository =
                FakeGroupRepository(
                    membersResult = listOf(OWNER),
                    watchlistPages =
                        mutableMapOf(
                            null to WatchlistPage(items = listOf(ENTRY_1), nextCursor = "cursor-2"),
                            "cursor-2" to WatchlistPage(items = listOf(ENTRY_2), nextCursor = null),
                        ),
                )
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()
            assertEquals(listOf(ENTRY_1), (viewModel.state.value as GroupDetailUiState.Success).watchlist)

            viewModel.loadMoreWatchlist()
            advanceUntilIdle()

            assertEquals(
                listOf(ENTRY_1, ENTRY_2),
                (viewModel.state.value as GroupDetailUiState.Success).watchlist,
            )
            assertEquals(listOf(null, "cursor-2"), groupRepository.watchlistCalls)

            // The negative control for exhaustion: the list is now exhausted (nextCursor == null
            // on page two), so a THIRD call must be a no-op, not a re-fetch of page one appended a
            // second time.
            viewModel.loadMoreWatchlist()
            advanceUntilIdle()

            assertEquals(listOf(null, "cursor-2"), groupRepository.watchlistCalls)
            assertEquals(
                listOf(ENTRY_1, ENTRY_2),
                (viewModel.state.value as GroupDetailUiState.Success).watchlist,
            )
        }

    /** [loadMoreWatchlist]'s own re-entrancy guard — `LibraryViewModel.loadMore`'s identical shape. */
    @Test
    fun `loadMoreWatchlist is not fired again while one is already in flight`() =
        runTest(dispatcher) {
            val groupRepository =
                FakeGroupRepository(
                    membersResult = listOf(OWNER),
                    watchlistPages =
                        mutableMapOf(
                            null to WatchlistPage(items = listOf(ENTRY_1), nextCursor = "cursor-2"),
                            "cursor-2" to WatchlistPage(items = listOf(ENTRY_2), nextCursor = null),
                        ),
                )
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            groupRepository.watchlistGate = CompletableDeferred()
            viewModel.loadMoreWatchlist()
            viewModel.loadMoreWatchlist()
            viewModel.loadMoreWatchlist()
            advanceUntilIdle()

            // Only the ORIGINAL first-page fetch (from refresh()) plus ONE loadMore call for
            // cursor-2 — the two re-entrant calls above must not have queued a second request.
            assertEquals(listOf(null, "cursor-2"), groupRepository.watchlistCalls)

            groupRepository.watchlistGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(ENTRY_1, ENTRY_2), (viewModel.state.value as GroupDetailUiState.Success).watchlist)
        }

    /**
     * The reachable-state test round 1 review's own instruction (Global Constraints) asks for: a
     * failed WATCHLIST fetch, over a member list that loaded fine, is a combination no OTHER test
     * in this file builds. [GroupDetailUiState.Success.watchlistPageError] must carry the failure
     * and [GroupDetailUiState.Success.members] must be UNTOUCHED — not [GroupDetailUiState.Error],
     * which would take the correctly-loaded member list off screen for a failure that has nothing
     * to do with it (this task's own "can the user still act on what they can see" question,
     * answered for the member list's side: yes, because the watchlist failing never demotes it).
     */
    @Test
    fun `a failed watchlist fetch leaves the members on screen with an inline error, not a full-screen one`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER, MEMBER))
            groupRepository.watchlistFailure = GroupFailure.Network
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            val result = viewModel.state.value as GroupDetailUiState.Success
            assertEquals(listOf(OWNER, MEMBER), result.members)
            assertEquals(emptyList<WatchlistEntry>(), result.watchlist)
            assertEquals(GroupFailure.Network, result.watchlistPageError)
        }

    /** The brief's third named test (task 9c.3), verbatim. */
    @Test
    fun `proposing a title the server does not know shows the unknown-title error`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER))
            groupRepository.proposeFailure = GroupFailure.NoSuchTitle
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            viewModel.proposeTitle("no-such-media-id")
            advanceUntilIdle()

            assertEquals(GroupFailure.NoSuchTitle, viewModel.actionState.value.proposeError)
            assertEquals(emptyList<WatchlistEntry>(), (viewModel.state.value as GroupDetailUiState.Success).watchlist)
        }

    /**
     * The positive control for the test above: without it, a [proposeTitle] that ALWAYS set
     * [GroupDetailActionState.proposeError] would trivially pass it. Also pins that
     * [proposeGate]/[proposeCalls] is the right ARGUMENT (`mediaId`), not merely that some call
     * happened.
     */
    @Test
    fun `proposing a known title reloads the watchlist and clears the propose state`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER))
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()
            // Starts empty (the fake's default first page) — proves what follows is a genuine
            // reload, not a no-op propose that happens to leave an already-populated list alone.
            assertEquals(emptyList<WatchlistEntry>(), (viewModel.state.value as GroupDetailUiState.Success).watchlist)

            groupRepository.proposeResult = ENTRY_1
            groupRepository.watchlistPages =
                mutableMapOf(null to WatchlistPage(items = listOf(ENTRY_1), nextCursor = null))
            viewModel.proposeTitle(ENTRY_1.mediaId)
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to ENTRY_1.mediaId), groupRepository.proposeCalls)
            assertEquals(listOf(ENTRY_1), (viewModel.state.value as GroupDetailUiState.Success).watchlist)
            assertEquals(GroupDetailActionState(), viewModel.actionState.value)
        }

    @Test
    fun `propose is not fired again while one is already in flight`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersResult = listOf(OWNER), proposeResult = ENTRY_1)
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            viewModel.proposeTitle(ENTRY_1.mediaId)
            viewModel.proposeTitle(ENTRY_1.mediaId)
            viewModel.proposeTitle(ENTRY_1.mediaId)
            advanceUntilIdle()

            assertEquals(1, groupRepository.proposeCalls.size)
        }

    /**
     * [proposeTitle]'s own BLOCKING-3-shaped guard, mirroring [rotateInvite]'s test of the same
     * name one section up: this screen offers exactly one propose form, reachable only from a
     * loaded [GroupDetailUiState.Success], so a call reaching the repository from
     * [GroupDetailUiState.Error] would be an irreversible server-side create with nowhere to fold
     * the result into.
     */
    @Test
    fun `proposeTitle does nothing when the member list failed to load`() =
        runTest(dispatcher) {
            val groupRepository = FakeGroupRepository(membersFailure = GroupFailure.Network, proposeResult = ENTRY_1)
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()
            assertEquals(GroupDetailUiState.Error(GroupFailure.Network), viewModel.state.value)

            viewModel.proposeTitle(ENTRY_1.mediaId)
            advanceUntilIdle()

            assertEquals(0, groupRepository.proposeCalls.size)
            assertEquals(GroupDetailActionState(), viewModel.actionState.value)
        }

    /**
     * Mutation-critical negative control, `removing a member calls removeMember with that member's
     * id, not the caller's own`'s identical shape one resource over: a [removeFromWatchlist] that
     * always targeted the FIRST entry would pass every other test in this file, since [ENTRY_1] is
     * not [ENTRY_2]. Two entries in the fixture, removing the SECOND, is what discriminates "reads
     * the argument" from "reads something fixed".
     */
    @Test
    fun `removing a watchlist entry calls removeFromWatchlist with that entry's id, not another entry's`() =
        runTest(dispatcher) {
            val groupRepository =
                FakeGroupRepository(
                    membersResult = listOf(OWNER),
                    watchlistPages =
                        mutableMapOf(null to WatchlistPage(items = listOf(ENTRY_1, ENTRY_2), nextCursor = null)),
                )
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            viewModel.removeFromWatchlist(ENTRY_2.id)
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to ENTRY_2.id), groupRepository.removeWatchlistEntryCalls)
        }

    @Test
    fun `a failed remove-from-watchlist surfaces the error and leaves the watchlist untouched`() =
        runTest(dispatcher) {
            val groupRepository =
                FakeGroupRepository(
                    membersResult = listOf(OWNER),
                    watchlistPages =
                        mutableMapOf(null to WatchlistPage(items = listOf(ENTRY_1, ENTRY_2), nextCursor = null)),
                )
            groupRepository.removeWatchlistEntryFailure = GroupFailure.NoSuchEntry
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            viewModel.removeFromWatchlist(ENTRY_1.id)
            advanceUntilIdle()

            assertEquals(GroupFailure.NoSuchEntry, viewModel.actionState.value.removeEntryError)
            assertNull(viewModel.actionState.value.removingEntryId)
            assertEquals(listOf(ENTRY_1, ENTRY_2), (viewModel.state.value as GroupDetailUiState.Success).watchlist)
        }

    @Test
    fun `remove entry is not fired again while one is already in flight`() =
        runTest(dispatcher) {
            val groupRepository =
                FakeGroupRepository(
                    membersResult = listOf(OWNER),
                    watchlistPages =
                        mutableMapOf(null to WatchlistPage(items = listOf(ENTRY_1, ENTRY_2), nextCursor = null)),
                )
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            viewModel.removeFromWatchlist(ENTRY_1.id)
            viewModel.removeFromWatchlist(ENTRY_2.id)
            viewModel.removeFromWatchlist(ENTRY_1.id)
            advanceUntilIdle()

            assertEquals(1, groupRepository.removeWatchlistEntryCalls.size)
        }

    /**
     * Fix-round-2 lesson (`GroupsViewModel.clearCreateError`'s own KDoc), applied proactively to
     * the two channels this task adds — decision C-S: clearing one must never touch another.
     */
    @Test
    fun `clearProposeError and clearRemoveEntryError each clear only their own channel`() =
        runTest(dispatcher) {
            val groupRepository =
                FakeGroupRepository(
                    membersResult = listOf(OWNER),
                    watchlistPages =
                        mutableMapOf(null to WatchlistPage(items = listOf(ENTRY_1), nextCursor = null)),
                )
            val viewModel = viewModel(groupRepository)
            advanceUntilIdle()

            groupRepository.proposeFailure = GroupFailure.NoSuchTitle
            viewModel.proposeTitle(ENTRY_1.mediaId)
            advanceUntilIdle()
            groupRepository.removeWatchlistEntryFailure = GroupFailure.NoSuchEntry
            viewModel.removeFromWatchlist(ENTRY_1.id)
            advanceUntilIdle()

            assertEquals(GroupFailure.NoSuchTitle, viewModel.actionState.value.proposeError)
            assertEquals(GroupFailure.NoSuchEntry, viewModel.actionState.value.removeEntryError)

            viewModel.clearProposeError()
            assertNull(viewModel.actionState.value.proposeError)
            assertEquals(GroupFailure.NoSuchEntry, viewModel.actionState.value.removeEntryError)

            viewModel.clearRemoveEntryError()
            assertNull(viewModel.actionState.value.removeEntryError)
        }

    private companion object {
        const val GROUP_ID = "group-1"

        fun member(
            id: String,
            username: String,
            role: GroupRole,
        ) = GroupMember(userId = id, username = username, role = role, joinedAt = Instant.parse("2026-08-28T10:15:30Z"))

        val OWNER = member(id = "user-owner", username = "alex", role = GroupRole.OWNER)
        val MEMBER = member(id = "user-member", username = "sam", role = GroupRole.MEMBER)
        val INVITE =
            GroupWithInvite(
                group = Group(id = GROUP_ID, name = "Watch Party", createdAt = Instant.parse("2026-08-28T10:15:30Z")),
                inviteCode = "NEWCODE0000000000000",
                expiresAt = Instant.parse("2026-09-10T00:00:00Z"),
            )

        fun summary(title: String) =
            MediaSummary(
                source = MediaSource.ANILIST,
                externalId = title,
                type = MediaType.ANIME,
                title = title,
                year = 2024,
                genres = emptyList(),
                coverImageUrl = null,
            )

        fun entry(
            id: String,
            title: String,
            mediaId: String,
            proposedBy: String?,
        ) = WatchlistEntry(
            id = id,
            media = summary(title),
            mediaId = mediaId,
            proposedBy = proposedBy,
            createdAt = Instant.parse("2026-08-28T10:15:30Z"),
        )

        val ENTRY_1 = entry(id = "entry-1", title = "Frieren", mediaId = "media-1", proposedBy = OWNER.userId)
        val ENTRY_2 = entry(id = "entry-2", title = "Mushishi", mediaId = "media-2", proposedBy = MEMBER.userId)
    }
}
