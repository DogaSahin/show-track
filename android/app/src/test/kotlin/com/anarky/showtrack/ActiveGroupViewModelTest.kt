package com.anarky.showtrack

import com.anarky.showtrack.core.model.Group
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
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * [ActiveGroupViewModel] exercised against fakes — `FeedViewModelTest`'s identical shape: plain
 * JUnit, no Robolectric (nothing here touches Android beyond `androidx.lifecycle.ViewModel`, which
 * needs only a `Dispatchers.Main` substitute for `viewModelScope` to run at all).
 *
 * The task brief's own three named tests, verbatim, are pinned below, plus the two additional
 * cases E-K names that those three do not, on their own, discriminate: no groups at all, and
 * [selectGroup] actually persisting through to [ActiveGroupStore].
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
     * process that persisted a selection on a previous run and is now starting fresh.
     */
    @Test
    fun `the active group survives a cold start`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = BETA.id)
            val repository = FakeGroupRepository(groups = listOf(ALPHA, BETA))
            val viewModel = ActiveGroupViewModel(repository, store)

            advanceUntilIdle()

            assertEquals(BETA.id, viewModel.activeGroupId.value)
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

            advanceUntilIdle()

            assertEquals(BETA.id, viewModel.activeGroupId.value)
        }

    /** The brief's own named test, verbatim. */
    @Test
    fun `a stored id naming a group you are no longer in falls back to the first available`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = "group-departed")
            val repository = FakeGroupRepository(groups = listOf(ALPHA, BETA))
            val viewModel = ActiveGroupViewModel(repository, store)

            advanceUntilIdle()

            assertEquals(ALPHA.id, viewModel.activeGroupId.value)
        }

    /** E-K's first empty state: no groups at all resolves to no active group, not a crash. */
    @Test
    fun `no groups resolves to no active group`() =
        runTest(dispatcher) {
            val store = FakeActiveGroupStore(initial = null)
            val repository = FakeGroupRepository(groups = emptyList())
            val viewModel = ActiveGroupViewModel(repository, store)

            advanceUntilIdle()

            assertNull(viewModel.activeGroupId.value)
            assertEquals(emptyList<Group>(), viewModel.groups.value)
        }

    /**
     * [ActiveGroupViewModel.selectGroup] writes through to the store, and the resolved
     * [ActiveGroupViewModel.activeGroupId] reacts to that write — proving the round trip this
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
            advanceUntilIdle()
            assertEquals(ALPHA.id, viewModel.activeGroupId.value)

            viewModel.selectGroup(BETA.id)
            advanceUntilIdle()

            assertEquals(BETA.id, viewModel.activeGroupId.value)
            assertEquals(listOf(BETA.id), store.setCalls)
        }

    private companion object {
        val ALPHA =
            Group(id = "group-alpha", name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T10:15:30Z"))
        val BETA =
            Group(id = "group-beta", name = "Beta Watchers", createdAt = Instant.parse("2026-08-29T09:00:00Z"))
    }
}
