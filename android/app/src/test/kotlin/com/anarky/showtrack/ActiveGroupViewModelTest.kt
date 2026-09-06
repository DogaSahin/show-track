package com.anarky.showtrack

import com.anarky.showtrack.core.model.ActiveGroupState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * [ActiveGroupViewModel] exercised against fakes — `FeedViewModelTest`'s identical shape: plain
 * JUnit, no Robolectric (nothing here touches Android beyond `androidx.lifecycle.ViewModel`, which
 * needs only a `Dispatchers.Main` substitute for `viewModelScope` to run at all).
 *
 * The task brief's own three named tests, verbatim, are pinned below, plus the cases fix round 1
 * added: BLOCKING B3 (loading/error distinct from a genuinely empty list), BLOCKING B4 ([reset]),
 * and the two smaller items ([refresh]'s generation guard, the fallback write-back).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveGroupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The brief's own named test, verbatim. A "cold start" is simulated the way
     * [com.anarky.showtrack.core.data.group.ActiveGroupStoreTest] itself frames survival — a store
     * that already holds a value BEFORE this ViewModel is ever constructed, standing in for a
     * process that persisted a selection on a previous run and is now starting fresh. `refresh()`
     * is called explicitly (fix round 1, BLOCKING B4): this class no longer fetches from `init`.
     */
    @Test
    fun `the active group survives a cold start`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = BETA.id)
            val repository = FakeGroupRepository(groups = listOf(ALPHA, BETA))
            val viewModel = ActiveGroupViewModel(repository, store)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                ActiveGroupState.Success(groups = listOf(ALPHA, BETA), activeGroupId = BETA.id),
                viewModel.state.value,
            )
        }

    /** The brief's own named test, verbatim. */
    @Test
    fun `with nothing stored the first group from the server is active`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = null)
            // BETA first, deliberately: the resolved id must match the SERVER'S order, not
            // whatever order would result from sorting by name or id.
            val repository = FakeGroupRepository(groups = listOf(BETA, ALPHA))
            val viewModel = ActiveGroupViewModel(repository, store)

            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.state.value as ActiveGroupState.Success
            assertEquals(BETA.id, state.activeGroupId)
        }

    /** The brief's own named test, verbatim. */
    @Test
    fun `a stored id naming a group you are no longer in falls back to the first available`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = "group-departed")
            val repository = FakeGroupRepository(groups = listOf(ALPHA, BETA))
            val viewModel = ActiveGroupViewModel(repository, store)

            viewModel.refresh()
            advanceUntilIdle()

            val state = viewModel.state.value as ActiveGroupState.Success
            assertEquals(ALPHA.id, state.activeGroupId)
        }

    /**
     * Fix round 1, smaller item 1: the fallback above is not just RESOLVED, it is WRITTEN BACK to
     * the store — otherwise the stored value and the resolved one diverge permanently (this
     * class's own KDoc on `recompute`).
     */
    @Test
    fun `falling back to the first available group writes it back to the store`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = "group-departed")
            val repository = FakeGroupRepository(groups = listOf(ALPHA, BETA))
            val viewModel = ActiveGroupViewModel(repository, store)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(listOf(ALPHA.id), store.setCalls)
        }

    /** E-K's first empty state: no groups at all resolves to a genuinely empty Success, not a crash. */
    @Test
    fun `no groups resolves to an empty success, not an error`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = null)
            val repository = FakeGroupRepository(groups = emptyList())
            val viewModel = ActiveGroupViewModel(repository, store)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(ActiveGroupState.Success(groups = emptyList(), activeGroupId = null), viewModel.state.value)
        }

    /**
     * [ActiveGroupViewModel.selectGroup] writes through to the store, and the resolved
     * [ActiveGroupState.Success.activeGroupId] reacts to that write — proving the round trip this
     * class exists for: [com.anarky.showtrack.core.designsystem.component.GroupSwitcher]'s own
     * callback reaches persistence, and persistence reaches back into the resolved state, entirely
     * through the store's `Flow`, not a locally-mutated field.
     */
    @Test
    fun `selecting a group persists it and becomes the resolved active group`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = null)
            val repository = FakeGroupRepository(groups = listOf(ALPHA, BETA))
            val viewModel = ActiveGroupViewModel(repository, store)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(ALPHA.id, (viewModel.state.value as ActiveGroupState.Success).activeGroupId)

            viewModel.selectGroup(BETA.id)
            advanceUntilIdle()

            assertEquals(BETA.id, (viewModel.state.value as ActiveGroupState.Success).activeGroupId)
        }

    /**
     * BLOCKING B3: the VERY FIRST fetch failing must surface as [ActiveGroupState.Error], not a
     * [ActiveGroupState.Success] with an empty list — collapsing the two is exactly what made
     * Feed's create-or-join invitation (E-K) render for an account that has real groups but hit a
     * failed request, indistinguishable from one that has none.
     */
    @Test
    fun `a failed first load surfaces as Error, not an empty groups list`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = null)
            val repository = FakeGroupRepository(groups = emptyList())
            repository.groupsFailure = GroupFailure.Network
            val viewModel = ActiveGroupViewModel(repository, store)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(ActiveGroupState.Error(GroupFailure.Network), viewModel.state.value)
        }

    /**
     * BLOCKING B3's other half — the settled refresh shape: a RETRY failing after a successful
     * load must keep showing what already resolved, not replace a working switcher with an error.
     */
    @Test
    fun `a failed retry after a successful load keeps showing the loaded groups`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = null)
            val repository = FakeGroupRepository(groups = listOf(ALPHA, BETA))
            val viewModel = ActiveGroupViewModel(repository, store)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(
                ActiveGroupState.Success(groups = listOf(ALPHA, BETA), activeGroupId = ALPHA.id),
                viewModel.state.value,
            )

            repository.groupsFailure = GroupFailure.Network
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                ActiveGroupState.Success(groups = listOf(ALPHA, BETA), activeGroupId = ALPHA.id),
                viewModel.state.value,
            )
        }

    /**
     * Fix round 1, smaller item 2: `refresh()`'s generation guard. Two calls, the FIRST gated so it
     * resolves SECOND (after the newer one already landed) — the older, now-stale response must not
     * overwrite the newer group list, `FeedViewModel.reload`'s identical "myGeneration" shape
     * applied to a single-shot fetch.
     */
    @Test
    fun `a stale refresh response does not overwrite a newer one`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = null)
            val repository = FakeGroupRepository(groups = listOf(ALPHA))
            val viewModel = ActiveGroupViewModel(repository, store)

            val gate = CompletableDeferred<Unit>()
            repository.groupsGate = gate
            viewModel.refresh() // generation 1
            advanceUntilIdle() // runs generation 1's groups() up to the gate, where it suspends —
            // FakeGroupRepository.groups() has already captured ALPHA-only as ITS OWN result by now.

            repository.groupsGate = null
            repository.groups = listOf(ALPHA, BETA)
            viewModel.refresh() // generation 2 — no gate, resolves immediately
            advanceUntilIdle()
            assertEquals(
                ActiveGroupState.Success(groups = listOf(ALPHA, BETA), activeGroupId = ALPHA.id),
                viewModel.state.value,
            )

            // Release generation 1's gate — its stale, ALPHA-only result (captured before the
            // mutation above) must be DROPPED rather than overwriting generation 2's already-
            // rendered, two-group state.
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                ActiveGroupState.Success(groups = listOf(ALPHA, BETA), activeGroupId = ALPHA.id),
                viewModel.state.value,
            )
        }

    /**
     * BLOCKING B4: [ActiveGroupViewModel.reset] clears the in-memory groups/selection AND the
     * persisted store — the round trip a logout/login inside the same (Activity-scoped) process
     * needs, so the next account never inherits the previous one's group names or active selection.
     */
    @Test
    fun `reset clears the loaded groups, the resolved selection, and the store`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = null)
            val repository = FakeGroupRepository(groups = listOf(ALPHA, BETA))
            val viewModel = ActiveGroupViewModel(repository, store)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(
                ActiveGroupState.Success(groups = listOf(ALPHA, BETA), activeGroupId = ALPHA.id),
                viewModel.state.value,
            )

            viewModel.reset()
            advanceUntilIdle()

            assertEquals(ActiveGroupState.Loading, viewModel.state.value)
            assertNull(store.setCalls.last())
        }

    /**
     * BLOCKING 2's other half (whole-branch fix round). `hasRequestedGroups` is the load-once latch
     * `activeGroupActionFor` reads to decide whether an ordinary authenticated destination should
     * fetch. If [ActiveGroupViewModel.reset] did not clear it, account B signing in after account A
     * signed out would land on Library with the latch still `true`, no fetch would ever be issued,
     * and B's Feed would spin on `ActiveGroupState.Loading` for the whole session — the previous
     * account's sign-out having silently disabled the next account's only load trigger.
     */
    @Test
    fun `reset clears the load-once latch so the next account fetches again`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = null)
            val repository = FakeGroupRepository(groups = listOf(ALPHA, BETA))
            val viewModel = ActiveGroupViewModel(repository, store)
            viewModel.refresh()
            advanceUntilIdle()
            assertTrue(viewModel.hasRequestedGroups)

            viewModel.reset()
            advanceUntilIdle()

            assertFalse(viewModel.hasRequestedGroups)
        }

    /**
     * BLOCKING F1 (fix round 2): [ActiveGroupViewModel.reset] must invalidate an in-flight
     * [ActiveGroupViewModel.refresh] the SAME way a group switch does — `refresh()`'s own
     * generation guard exists for exactly this shape, and `reset()` was the one caller that did
     * not bump it. Scenario: account A's `refresh()` is still in flight (gated) when A signs out
     * and `reset()` runs; A's stale response must not land as a `Success` over the `Loading` reset
     * left behind, and must not write A's group id back into the store `reset()` just cleared.
     */
    @Test
    fun `reset invalidates an in-flight refresh so the previous account's response cannot land`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = null)
            val repository = FakeGroupRepository(groups = listOf(ALPHA, BETA))
            val viewModel = ActiveGroupViewModel(repository, store)

            val gate = CompletableDeferred<Unit>()
            repository.groupsGate = gate
            viewModel.refresh() // account A's refresh
            advanceUntilIdle() // runs A's groups() up to the gate, where it suspends

            viewModel.reset() // account A signs out
            advanceUntilIdle()
            assertEquals(ActiveGroupState.Loading, viewModel.state.value)
            val storeCallsAfterReset = store.setCalls.size

            // A's stale response finally lands, well after the reset.
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                "state after the previous account's response landed",
                ActiveGroupState.Loading,
                viewModel.state.value,
            )
            assertEquals(
                "the stale response must not write A's group id back into the store reset() cleared",
                storeCallsAfterReset,
                store.setCalls.size,
            )
        }

    /**
     * The other half of B4's fix: after [ActiveGroupViewModel.reset], a NEW account's `refresh()`
     * must resolve cleanly from that account's own [FakeGroupRepository.groups] — not resurrect the
     * previous account's list via a stale in-memory field `reset()` failed to clear.
     */
    @Test
    fun `a refresh after reset resolves the next account's own groups, not the previous one's`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = null)
            val repository = FakeGroupRepository(groups = listOf(ALPHA, BETA))
            val viewModel = ActiveGroupViewModel(repository, store)
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.reset()
            advanceUntilIdle()
            repository.groups = listOf(GAMMA)
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                ActiveGroupState.Success(groups = listOf(GAMMA), activeGroupId = GAMMA.id),
                viewModel.state.value,
            )
        }

    /**
     * BLOCKING B4's other consequence: a store-only emission arriving BEFORE the very first
     * `refresh()` has ever resolved must not write a premature `Success(emptyList(), null)` over
     * `Loading` — that would look exactly like "loaded, and genuinely empty" (B3's own distinction)
     * for an account that has not been asked yet.
     */
    @Test
    fun `a store emission before the first successful load does not pre-empt Loading`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = "group-x")
            val repository = FakeGroupRepository(groups = listOf(ALPHA))
            val viewModel = ActiveGroupViewModel(repository, store)
            advanceUntilIdle()

            assertEquals(ActiveGroupState.Loading, viewModel.state.value)
            assertTrue("no fetch was ever requested", repository.groupsCallCount == 0)
        }

    private companion object {
        val ALPHA =
            Group(id = "group-alpha", name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T10:15:30Z"))
        val BETA =
            Group(id = "group-beta", name = "Beta Watchers", createdAt = Instant.parse("2026-08-29T09:00:00Z"))
        val GAMMA =
            Group(id = "group-gamma", name = "Gamma Watchers", createdAt = Instant.parse("2026-08-30T09:00:00Z"))
    }
}
