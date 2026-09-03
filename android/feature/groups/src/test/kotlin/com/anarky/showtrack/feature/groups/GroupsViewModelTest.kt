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
 * for that first resume.
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
 * `GroupsScreen`, not by this ViewModel or [GroupsUiState] — this ViewModel has no field for a
 * "typed code" to keep or clear. What THIS class can and does pin is the half it actually owns:
 * `` `joining with a bad code surfaces the error and leaves the loaded groups standing` `` below.
 * `GroupsScreenTest`'s `` `a failed join keeps the typed code in the field` `` pins the rendering
 * half.
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

            assertEquals(
                GroupsUiState.Success(groups = listOf(ALPHA), joinError = failure),
                viewModel.state.value,
            )
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
            assertEquals(failure, (viewModel.state.value as GroupsUiState.Success).createError)

            repository.createFailure = null
            repository.createGate = CompletableDeferred()
            repository.createResult = invite(group = GAMMA)
            viewModel.createGroup("Gamma Watchers")

            // The gate has not been completed yet — the retry's own network round trip is still
            // suspended — but the error must already be gone and `creating` already true.
            val midFlight = viewModel.state.value as GroupsUiState.Success
            assertEquals(null, midFlight.createError)
            assertEquals(true, midFlight.creating)

            repository.createGate?.complete(Unit)
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
