package com.anarky.showtrack.feature.groups

import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure
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
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The ViewModel is exercised against a FAKE [com.anarky.showtrack.core.data.repository.GroupRepository]
 * — nothing here knows Retrofit exists, mirroring `FavoritesViewModelTest`'s shape.
 *
 * [GroupsViewModel] has no `init { refresh() }` (that class's own KDoc): `GroupsScreen`'s
 * `LifecycleResumeEffect` is the only thing that ever calls [GroupsViewModel.refresh] in
 * production. Every test below calls it explicitly, once, right after construction — standing in
 * for that first resume — UNLESS the test is specifically about create/join working WITHOUT a
 * successful `refresh()` ever having landed (fix round 1's BLOCKING 1 tests), which deliberately
 * skip it or leave it failed.
 *
 * The brief's `` `the invite code is not shown for a group that came from the list` `` is
 * deliberately NOT reproduced here: [Group] carries no invite-code field at all (the server
 * withholds it from `GET /v1/groups` — the whole point of decision E-I), so there is no ViewModel
 * STATE that could ever hold one for a list-sourced group in the first place — a ViewModel test
 * asserting that fact would pin nothing a mutation could break. Whether the SCREEN accidentally
 * renders one for a list row anyway is a rendering decision only a composed test can see;
 * `GroupsScreenTest`'s `` `a group loaded from the list shows no invite code` `` is that test.
 *
 * The brief's `` `joining with a bad code shows an error and keeps the typed code` `` is split the
 * identical way: the invite-code text field is `JoinGroupDialog`'s own `remember`ed draft, owned by
 * `GroupsScreen`, not by this ViewModel or [GroupsUiState]/[GroupsActionState] — this ViewModel has
 * no field for a "typed code" to keep or clear. What THIS class can and does pin is the half it
 * actually owns: `` `joining with a bad code surfaces the error and leaves the loaded groups
 * standing` `` below. `GroupsScreenTest`'s `` `a failed join keeps the typed code in the field` ``
 * pins the rendering half.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `refresh shows the loaded groups`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA, BETA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            assertEquals(GroupsUiState.Success(groups = listOf(ALPHA, BETA)), viewModel.state.value)
        }

    /**
     * The brief's first named test, verbatim: `POST /v1/groups` is one of only three moments the
     * client ever sees an invite code, and the ONLY one right after a create.
     */
    @Test
    fun `creating a group shows its invite code once`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            val created = invite(group = GAMMA)
            repository.createResult = created
            viewModel.createGroup("Gamma Watchers")
            advanceUntilIdle()

            assertEquals(
                GroupsUiState.Success(groups = listOf(ALPHA, GAMMA), justCreated = created),
                viewModel.state.value,
            )
            assertEquals(GroupsActionState(), viewModel.actionState.value)
        }

    /**
     * The other half of decision E-I: a code shown after a create must not survive the round trip
     * back to this screen (tap the new group -> Detail -> Back), which fires [GroupsViewModel.refresh]
     * again exactly the way a first resume does. Proves [GroupsUiState.Success.justCreated] is
     * dropped by [GroupsViewModel.refresh] regardless of what it held before the call, not merely
     * left at its initial `null`.
     */
    @Test
    fun `a refresh after creating clears the invite code`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            val created = invite(group = GAMMA)
            repository.createResult = created
            viewModel.createGroup("Gamma Watchers")
            advanceUntilIdle()
            assertEquals(created, (viewModel.state.value as GroupsUiState.Success).justCreated)

            repository.groupsResult = listOf(ALPHA, GAMMA)
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                GroupsUiState.Success(groups = listOf(ALPHA, GAMMA), justCreated = null),
                viewModel.state.value,
            )
        }

    /**
     * The ViewModel half of the brief's third named test — see this class's own KDoc for why the
     * "keeps the typed code" half belongs in `GroupsScreenTest` instead. `joinError` is a SEPARATE
     * channel from `createError` (decision C-S), and the already-loaded [ALPHA] must stand: a failed
     * join is not a failed refresh and must not touch [GroupsUiState.Success.groups] at all.
     */
    @Test
    fun `joining with a bad code surfaces the error and leaves the loaded groups standing`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            val failure = GroupFailure.Unknown(IllegalStateException("bad or expired code"))
            repository.joinFailure = failure
            viewModel.joinGroup("BADCODE0000000000000")
            advanceUntilIdle()

            assertEquals(GroupsUiState.Success(groups = listOf(ALPHA)), viewModel.state.value)
            assertEquals(GroupsActionState(joinError = failure), viewModel.actionState.value)
        }

    /**
     * Decision C-S: the error is cleared THE MOMENT a retry launches, not only on success.
     * [FakeGroupRepository.createGate] is what makes this observable — without it, a synchronously
     * resolving fake would clear the error and land the new result in the same instant, and this
     * test could not tell "cleared before the call resolves" apart from "cleared as a side effect
     * of the call resolving".
     */
    @Test
    fun `retrying create after a failure clears the previous error before the new attempt lands`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            val failure = GroupFailure.Network
            repository.createFailure = failure
            viewModel.createGroup("Gamma Watchers")
            advanceUntilIdle()
            assertEquals(failure, viewModel.actionState.value.createError)

            repository.createFailure = null
            repository.createGate = CompletableDeferred()
            repository.createResult = invite(group = GAMMA)
            viewModel.createGroup("Gamma Watchers")

            // The gate has not been completed yet — the retry's own network round trip is still
            // suspended — but the error must already be gone and `creating` already true.
            val midFlight = viewModel.actionState.value
            assertEquals(null, midFlight.createError)
            assertEquals(true, midFlight.creating)

            repository.createGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(GAMMA, (viewModel.state.value as GroupsUiState.Success).justCreated?.group)
        }

    /** [joinGroup]'s mirror of the create retry test above — SEPARATE channel, SEPARATE guard. */
    @Test
    fun `retrying join after a failure clears the previous error before the new attempt lands`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            val failure = GroupFailure.Unknown(IllegalStateException("bad or expired code"))
            repository.joinFailure = failure
            viewModel.joinGroup("BADCODE0000000000000")
            advanceUntilIdle()
            assertEquals(failure, viewModel.actionState.value.joinError)

            repository.joinFailure = null
            repository.joinGate = CompletableDeferred()
            repository.joinResult = invite(group = GAMMA)
            viewModel.joinGroup("GOODCODE00000000000")

            val midFlight = viewModel.actionState.value
            assertEquals(null, midFlight.joinError)
            assertEquals(true, midFlight.joining)

            repository.joinGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(GAMMA, (viewModel.state.value as GroupsUiState.Success).justCreated?.group)
        }

    @Test
    fun `a failed initial load with nothing on screen produces Error`() =
        runTest(dispatcher) {
            val failure = GroupFailure.Network
            val repository = FakeGroupRepository(groupsFailure = failure)
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(GroupsUiState.Error(failure), viewModel.state.value)
        }

    /**
     * The settled refresh shape (Global Constraints): a resume's fetch must not blank an
     * already-populated screen to a spinner while it is still in flight. `FavoritesViewModelTest`'s
     * identical test, applied here.
     */
    @Test
    fun `a resume over an already-loaded screen keeps the list, not a spinner, mid-fetch`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(GroupsUiState.Success(groups = listOf(ALPHA)), viewModel.state.value)

            repository.groupsResult = listOf(ALPHA, BETA)
            repository.groupsGate = CompletableDeferred()
            viewModel.refresh()
            advanceUntilIdle()

            // Still the OLD groups, and still Success — never Loading — while the network round
            // trip this resume triggered is genuinely still in flight.
            assertEquals(GroupsUiState.Success(groups = listOf(ALPHA)), viewModel.state.value)

            repository.groupsGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(GroupsUiState.Success(groups = listOf(ALPHA, BETA)), viewModel.state.value)
        }

    @Test
    fun `a failed resume over an already-loaded screen marks it stale instead of replacing it`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(GroupsUiState.Success(groups = listOf(ALPHA)), viewModel.state.value)

            repository.groupsFailure = GroupFailure.Network
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                GroupsUiState.Success(groups = listOf(ALPHA), isStale = true),
                viewModel.state.value,
            )
        }

    /**
     * A double-tap on the create dialog's submit button must not race two `POST /v1/groups` calls
     * and create the group twice — `FavoritesViewModelTest`'s `` `loadMore is not fired again while
     * one is in flight` `` applied to a form submit instead of a scroll trigger. No gate needed:
     * `createGroup`'s `creating = true` write happens SYNCHRONOUSLY before the coroutine it
     * launches is even scheduled, so all three calls below run back-to-back on the test thread
     * before `advanceUntilIdle` lets any of them proceed.
     */
    @Test
    fun `create is not fired again while one is already in flight`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA), createResult = invite(group = GAMMA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.createGroup("Gamma Watchers")
            viewModel.createGroup("Gamma Watchers")
            viewModel.createGroup("Gamma Watchers")
            advanceUntilIdle()

            assertEquals(1, repository.createCalls)
        }

    /** [joinGroup]'s mirror of the create re-entrancy test above — a SEPARATE flag, SEPARATE guard. */
    @Test
    fun `join is not fired again while one is already in flight`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA), joinResult = invite(group = GAMMA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.joinGroup("GOODCODE00000000000")
            viewModel.joinGroup("GOODCODE00000000000")
            viewModel.joinGroup("GOODCODE00000000000")
            advanceUntilIdle()

            assertEquals(1, repository.joinCalls)
        }

    /**
     * BLOCKING 1 (fix round 1 review): before this round, `createGroup`/`joinGroup` opened with
     * `mutableState.value as? GroupsUiState.Success ?: return` — a guard that read as re-entrancy
     * but was ALSO an undocumented "the list must have loaded first" precondition nothing asked
     * for. [GroupsUiState.Error] is not transient (unlike `Loading`): a cold start with no
     * connectivity leaves the screen there indefinitely, and a user holding a valid invite code
     * has every reason to redeem it from exactly this screen. Before the fix, this exact sequence
     * left `joinCalls == 0` forever — the dialog accepted input and the button was enabled, but
     * tapping it did nothing.
     */
    @Test
    fun `joining a group succeeds even when the initial load failed`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsFailure = GroupFailure.Network)
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(GroupsUiState.Error(GroupFailure.Network), viewModel.state.value)

            val joined = invite(group = GAMMA)
            repository.joinResult = joined
            viewModel.joinGroup("GOODCODE00000000000")
            advanceUntilIdle()

            assertEquals(1, repository.joinCalls)
            // isStale = true (fix round 2, BLOCKING 3): the ViewModel knows it never successfully
            // loaded the list before this — this one group is real, but incomplete — so the banner
            // and its Retry are the correct affordance, not a screen silently claiming completeness.
            assertEquals(
                GroupsUiState.Success(groups = listOf(GAMMA), justCreated = joined, isStale = true),
                viewModel.state.value,
            )
        }

    /** [createGroup]'s mirror of the BLOCKING 1 join test above. */
    @Test
    fun `creating a group succeeds even when the initial load failed`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsFailure = GroupFailure.Network)
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(GroupsUiState.Error(GroupFailure.Network), viewModel.state.value)

            val created = invite(group = GAMMA)
            repository.createResult = created
            viewModel.createGroup("Gamma Watchers")
            advanceUntilIdle()

            assertEquals(1, repository.createCalls)
            // isStale = true — see the join test above's identical note.
            assertEquals(
                GroupsUiState.Success(groups = listOf(GAMMA), justCreated = created, isStale = true),
                viewModel.state.value,
            )
        }

    /**
     * BLOCKING 1's other half: the initial load's own fetch has not even ANSWERED yet
     * ([GroupsUiState.Loading], not [GroupsUiState.Error]) when the user submits a join. The old
     * `Success`-cast guard rejected this too.
     */
    @Test
    fun `joining a group succeeds while the initial load is still in flight`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository()
            repository.groupsGate = CompletableDeferred()
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            // Nothing has advanced the dispatcher yet — refresh()'s own coroutine has not even
            // started running, so this is genuinely still Loading, not merely "Loading and about
            // to resolve".
            assertEquals(GroupsUiState.Loading, viewModel.state.value)

            val joined = invite(group = GAMMA)
            repository.joinResult = joined
            viewModel.joinGroup("GOODCODE00000000000")
            advanceUntilIdle()

            assertEquals(1, repository.joinCalls)
            val result = viewModel.state.value as GroupsUiState.Success
            assertEquals(GAMMA, result.justCreated?.group)
            // isStale = true here too — Loading never became a successful Success before this.
            assertEquals(true, result.isStale)
        }

    /**
     * Small item 1 (fix round 1 review): a resume's OWN `refresh()` landing while a create is
     * still in flight must not silently clear `creating`/let a second tap through. Before the
     * fix, `refresh()`'s success wrote a brand new `Success` object — which is where `creating`
     * used to live — discarding it mid-flight. [actionState] now lives outside [GroupsUiState]
     * entirely, so `refresh()` structurally cannot reach it.
     */
    @Test
    fun `a resume mid-create does not clear the in-flight flag or admit a duplicate submit`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            repository.createGate = CompletableDeferred()
            repository.createResult = invite(group = GAMMA)
            viewModel.createGroup("Gamma Watchers")
            assertEquals(true, viewModel.actionState.value.creating)

            // The app was backgrounded and resumed while the create above is still suspended; the
            // resume's OWN refresh() lands successfully before the create does.
            repository.groupsResult = listOf(ALPHA)
            viewModel.refresh()
            advanceUntilIdle()

            // Still creating — the resume's refresh() must not have touched actionState.
            assertEquals(true, viewModel.actionState.value.creating)

            // A second tap while still (correctly) reported as creating must still be dropped.
            viewModel.createGroup("Gamma Watchers")
            advanceUntilIdle()
            assertEquals(1, repository.createCalls)

            repository.createGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(false, viewModel.actionState.value.creating)
            assertEquals(GAMMA, (viewModel.state.value as GroupsUiState.Success).justCreated?.group)
        }

    /**
     * BLOCKING 1 (fix round 2 review): `GroupRepository.joinGroup` is deliberately idempotent
     * (decision G-I, `backend/app/groups/service.py`'s `join_by_code`) — redeeming a code for a
     * group you already belong to returns 200 with that SAME group, not an error. Appending
     * unconditionally duplicated the id, and `GroupsScreen.kt`'s `LazyColumn` — keyed by
     * `Group::id` — crashed composition the moment that state was rendered
     * (`IllegalArgumentException: Key "…" was already used`). A rejoin must REPLACE the existing
     * row, not duplicate it.
     *
     * **Fix round 3 (review finding):** this also pins the row's POSITION, not just its count —
     * round 2's own fix was a remove-then-append, which stopped the crash but silently moved a
     * rejoined group to the bottom of the list (a visible reorder round 2's KDoc and report both
     * mis-described as an in-place replace). `applyGroupChange` now does a real `map`, so ALPHA
     * stays exactly where it already was; only a genuinely new id gets appended at the end, which
     * matches the server's own `GET /v1/groups` ordering (`created_at ASC`).
     */
    @Test
    fun `joining a group already in the list replaces it in place, without moving it`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA, BETA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            // The backend's idempotent rejoin returns the SAME group — same id — the caller
            // already belongs to.
            val rejoined = invite(group = ALPHA)
            repository.joinResult = rejoined
            viewModel.joinGroup("SAMECODE000000000000")
            advanceUntilIdle()

            val result = viewModel.state.value as GroupsUiState.Success
            // ALPHA stays FIRST — an in-place replace, not a move-to-the-end.
            assertEquals(listOf(ALPHA, BETA), result.groups)
            assertEquals(1, result.groups.count { it.id == ALPHA.id })
            assertEquals(rejoined, result.justCreated)
        }

    /**
     * BLOCKING 2 (fix round 2 review): [GroupsViewModel.applyGroupChange] used to construct a
     * brand-new `Success(...)` rather than carry the existing one's `isStale` forward, so ANY
     * successful create/join silently cleared a stale mark an UNRELATED failed background resume
     * had set — the banner and its Retry affordance vanished for a reason that had nothing to do
     * with the list actually being current again.
     */
    @Test
    fun `creating a group while the list is marked stale keeps it stale`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            repository.groupsFailure = GroupFailure.Network
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(true, (viewModel.state.value as GroupsUiState.Success).isStale)

            repository.createResult = invite(group = GAMMA)
            viewModel.createGroup("Gamma Watchers")
            advanceUntilIdle()

            assertEquals(true, (viewModel.state.value as GroupsUiState.Success).isStale)
        }

    /**
     * Fix round 2, small item 3: reopening the create dialog after a failed attempt must not show
     * that attempt's error before the user has done anything new.
     *
     * **Fix round 3 (review finding):** the "only" in this test's own name was unpinned — setting
     * ONE channel then asserting the whole [GroupsActionState] equals the all-null default cannot
     * tell "cleared just this channel" apart from "cleared everything", so mutating
     * [GroupsViewModel.clearCreateError]/[GroupsViewModel.clearJoinError] to clear BOTH fields left
     * this suite green (measured). Both errors are set here — a real, reachable sequence: create
     * fails, the user opens Join instead, that fails too — before clearing only one, so the
     * surviving field is what's actually asserted, not merely absent from an equality check that
     * happened to pass.
     */
    @Test
    fun `clearCreateError clears only the create channel, leaving a live join error untouched`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            repository.createFailure = GroupFailure.Network
            viewModel.createGroup("Gamma Watchers")
            advanceUntilIdle()
            repository.joinFailure = GroupFailure.InvalidInviteCode
            viewModel.joinGroup("BADCODE0000000000000")
            advanceUntilIdle()
            assertEquals(GroupFailure.Network, viewModel.actionState.value.createError)
            assertEquals(GroupFailure.InvalidInviteCode, viewModel.actionState.value.joinError)

            viewModel.clearCreateError()

            assertEquals(null, viewModel.actionState.value.createError)
            assertEquals(GroupFailure.InvalidInviteCode, viewModel.actionState.value.joinError)
        }

    /** [clearCreateError]'s mirror — a SEPARATE channel, cleared separately (fix round 3's identical evidence). */
    @Test
    fun `clearJoinError clears only the join channel, leaving a live create error untouched`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(groupsResult = listOf(ALPHA))
            val viewModel = GroupsViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            repository.createFailure = GroupFailure.Network
            viewModel.createGroup("Gamma Watchers")
            advanceUntilIdle()
            repository.joinFailure = GroupFailure.InvalidInviteCode
            viewModel.joinGroup("BADCODE0000000000000")
            advanceUntilIdle()
            assertEquals(GroupFailure.Network, viewModel.actionState.value.createError)
            assertEquals(GroupFailure.InvalidInviteCode, viewModel.actionState.value.joinError)

            viewModel.clearJoinError()

            assertEquals(GroupFailure.Network, viewModel.actionState.value.createError)
            assertEquals(null, viewModel.actionState.value.joinError)
        }

    private companion object {
        fun group(
            id: String,
            name: String,
        ) = Group(id = id, name = name, createdAt = Instant.parse("2026-08-28T10:15:30Z"))

        fun invite(
            group: Group,
            code: String = "ABCDEFGHIJ1234567890",
        ) = GroupWithInvite(group = group, inviteCode = code, expiresAt = Instant.parse("2026-09-10T00:00:00Z"))

        val ALPHA = group(id = "group-alpha", name = "Alpha Watchers")
        val BETA = group(id = "group-beta", name = "Beta Watchers")
        val GAMMA = group(id = "group-gamma", name = "Gamma Watchers")
    }
}
