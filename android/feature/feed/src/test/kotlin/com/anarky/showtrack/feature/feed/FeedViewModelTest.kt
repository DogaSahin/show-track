package com.anarky.showtrack.feature.feed

import com.anarky.showtrack.core.data.repository.FeedPage
import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.FeedEntry
import com.anarky.showtrack.core.model.GroupActor
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * [FeedViewModel] exercised against a fake [com.anarky.showtrack.core.data.repository.GroupRepository]
 * — `GroupDetailViewModelTest`'s identical shape, one screen (and no `SavedStateHandle`) over.
 * Plain JUnit, no Robolectric: unlike `GroupDetailViewModel`, this class never calls
 * `SavedStateHandle.toRoute` — `LibraryViewModelTest`'s identical "nothing here knows Retrofit,
 * Room, or Android exists" note.
 *
 * **What this class deliberately does NOT pin:** which STRING renders for a given [FeedUiState],
 * or which rows are tappable — those are rendering decisions [FeedScreenTest] pins by driving the
 * stateless [FeedScreen] overload directly (Global Constraints: "if a behaviour is a rendering
 * decision, a ViewModel test cannot pin it").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {
    // viewModelScope is hard-wired to Dispatchers.Main, which has no implementation on a plain
    // JVM. Substituting a TestDispatcher is what makes the launch in `selectGroup`/`refresh`/
    // `loadMore` run at all — without it every test here fails with "Module with the Main
    // dispatcher is missing".
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state starts loading and then shows the fetched entries`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages = mutableMapOf(null to FeedPage(items = listOf(ADDED), nextCursor = null)),
                )
            val viewModel = FeedViewModel(repository)

            assertEquals(FeedUiState.Loading, viewModel.state.value)

            viewModel.selectGroup(GROUP_ID)
            assertEquals(FeedUiState.Loading, viewModel.state.value)

            advanceUntilIdle()

            assertEquals(FeedUiState.Success(entries = listOf(ADDED)), viewModel.state.value)
        }

    /**
     * `selectGroup` is a no-op before construction and nothing has selected a group yet — the
     * guard [FeedViewModel.refresh]/[FeedViewModel.loadMore] both share: there is no paginator to
     * fetch from, and no [FeedUiState.Error] this screen could have shown to make a retry button
     * reachable in the first place.
     */
    @Test
    fun `refresh with no group selected is a no-op`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository()
            val viewModel = FeedViewModel(repository)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(FeedUiState.Loading, viewModel.state.value)
            assertEquals(emptyList<Pair<String, String?>>(), repository.feedCalls)
        }

    @Test
    fun `a failing initial load surfaces as Error`() =
        runTest(dispatcher) {
            val repository = FakeGroupRepository(feedFailure = GroupFailure.Network)
            val viewModel = FeedViewModel(repository)

            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()

            assertEquals(FeedUiState.Error(GroupFailure.Network), viewModel.state.value)
        }

    /** The brief's own named test, verbatim. */
    @Test
    fun `paging the feed appends without duplicates`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages =
                        mutableMapOf(
                            null to FeedPage(items = listOf(ADDED), nextCursor = "cursor-2"),
                            "cursor-2" to FeedPage(items = listOf(RATED), nextCursor = null),
                        ),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()
            assertEquals(listOf(ADDED), (viewModel.state.value as FeedUiState.Success).entries)

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(listOf(ADDED, RATED), (viewModel.state.value as FeedUiState.Success).entries)
            assertEquals(listOf(GROUP_ID to null, GROUP_ID to "cursor-2"), repository.feedCalls)

            // The negative control for exhaustion: the list is now exhausted (nextCursor == null
            // on page two), so a THIRD call must be a no-op, not a re-fetch of page one appended a
            // second time — CursorPaginator's own `started && cursor == null` guard.
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to null, GROUP_ID to "cursor-2"), repository.feedCalls)
            assertEquals(listOf(ADDED, RATED), (viewModel.state.value as FeedUiState.Success).entries)
        }

    /**
     * [FeedViewModel.loadMore]'s own re-entrancy guard — `LibraryViewModel.loadMore`'s identical
     * shape. THREE pages, deliberately, not two: with only two, [CursorPaginator]'s own exhaustion
     * check (`started && cursor == null`) happens to absorb a re-entrant call for free the moment
     * the SECOND page exhausts the list, which made an earlier draft of this test pass even with
     * the guard deleted — a red this task's own mutation pass caught, not a red left unread (Global
     * Constraints). A THIRD page still exists after the first `loadMore()` resolves, so an
     * un-guarded second/third call queued behind [CursorPaginator]'s mutex would, once unblocked,
     * go on to fetch it for real — which is exactly what the guard exists to prevent.
     */
    @Test
    fun `loadMore is not fired again while one is already in flight`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages =
                        mutableMapOf(
                            null to FeedPage(items = listOf(ADDED), nextCursor = "cursor-2"),
                            "cursor-2" to FeedPage(items = listOf(RATED), nextCursor = "cursor-3"),
                            "cursor-3" to FeedPage(items = listOf(COMPLETED), nextCursor = null),
                        ),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()

            repository.feedGate = CompletableDeferred()
            viewModel.loadMore()
            viewModel.loadMore()
            viewModel.loadMore()
            advanceUntilIdle()

            // Only the ORIGINAL first-page fetch (from selectGroup) plus ONE loadMore call for
            // cursor-2 — the two re-entrant calls above must not have queued a second request.
            assertTrue((viewModel.state.value as FeedUiState.Success).loadingMore)
            repository.feedGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to null, GROUP_ID to "cursor-2"), repository.feedCalls)
            assertEquals(listOf(ADDED, RATED), (viewModel.state.value as FeedUiState.Success).entries)
        }

    /**
     * A page-fetch failure is [FeedUiState.Success.pageError] — a footer, never a promotion to
     * [FeedUiState.Error] (Global Constraints) — and [FeedUiState.Success.entries] survives
     * untouched. The SECOND half is the axis this phase kept missing (Global Constraints: "every
     * single blocking finding lived in a combination the tests did not construct"): a subsequent
     * SUCCESSFUL reload must clear [FeedUiState.Success.pageError] — it is a strictly newer,
     * authoritative read of the same section, `GroupDetailViewModel.reloadWatchlist`'s fix round 2
     * lesson (BLOCKING R1) for the identical bug.
     */
    @Test
    fun `a failed loadMore surfaces a page error, and a subsequent reload clears it`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages = mutableMapOf(null to FeedPage(items = listOf(ADDED), nextCursor = "cursor-2")),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()

            repository.feedFailure = GroupFailure.Network
            viewModel.loadMore()
            advanceUntilIdle()

            val afterFailure = viewModel.state.value as FeedUiState.Success
            assertEquals(GroupFailure.Network, afterFailure.pageError)
            assertEquals(listOf(ADDED), afterFailure.entries)
            assertTrue(!afterFailure.loadingMore)

            repository.feedFailure = null
            repository.feedPages[null] = FeedPage(items = listOf(ADDED, RATED), nextCursor = null)
            viewModel.refresh()
            advanceUntilIdle()

            val afterReload = viewModel.state.value as FeedUiState.Success
            assertNull(afterReload.pageError)
            assertEquals(listOf(ADDED, RATED), afterReload.entries)
        }

    /**
     * The settled refresh shape (Global Constraints): a reload failure over an already-populated
     * screen KEEPS the existing entries and marks them stale, never blanks or errors them away.
     */
    @Test
    fun `a failed refresh after a successful load marks the state stale, keeping entries on screen`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages = mutableMapOf(null to FeedPage(items = listOf(ADDED), nextCursor = null)),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()

            repository.feedFailure = GroupFailure.Network
            viewModel.refresh()
            advanceUntilIdle()

            val afterFailure = viewModel.state.value as FeedUiState.Success
            assertEquals(listOf(ADDED), afterFailure.entries)
            assertTrue(afterFailure.isStale)

            repository.feedFailure = null
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(false, (viewModel.state.value as FeedUiState.Success).isStale)
        }

    /**
     * The second axis Global Constraints calls out by name: switching to a DIFFERENT group must
     * not leave the OLD group's rows on screen under the new group's tab, and must fetch the new
     * group's OWN first page independently of the old one — `LibraryViewModel.selectStatus`'s
     * "a filter change forces a blank" reasoning, applied to a group switch instead of a filter.
     */
    @Test
    fun `switching to a different group blanks and loads that group's feed independently`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages =
                        mutableMapOf(
                            null to FeedPage(items = listOf(ADDED), nextCursor = null),
                        ),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()
            assertEquals(listOf(ADDED), (viewModel.state.value as FeedUiState.Success).entries)

            repository.feedPages[null] = FeedPage(items = listOf(RATED), nextCursor = null)
            viewModel.selectGroup(OTHER_GROUP_ID)

            // Blanked to Loading synchronously, BEFORE the new group's fetch has resolved — the
            // old group's ADDED row must not still be on screen for even one frame.
            assertEquals(FeedUiState.Loading, viewModel.state.value)

            advanceUntilIdle()

            assertEquals(listOf(RATED), (viewModel.state.value as FeedUiState.Success).entries)
            assertEquals(listOf(GROUP_ID to null, OTHER_GROUP_ID to null), repository.feedCalls)
        }

    /**
     * `selectGroup` called again with the SAME group (the ordinary tab-resume case) must NOT
     * re-blank an already-populated screen — the settled refresh shape, applied to the resume path
     * rather than only the retry button. Distinguishes this from the group-switch test above,
     * which DOES blank: the two share one function, and this is the negative control for it.
     */
    @Test
    fun `selecting the already-active group reloads in place rather than blanking`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages = mutableMapOf(null to FeedPage(items = listOf(ADDED), nextCursor = null)),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()

            repository.feedGate = CompletableDeferred()
            viewModel.selectGroup(GROUP_ID)

            // Still Success, not Loading — the existing row stays on screen while the resume's own
            // fetch is still in flight.
            assertEquals(listOf(ADDED), (viewModel.state.value as FeedUiState.Success).entries)

            repository.feedGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to null, GROUP_ID to null), repository.feedCalls)
        }

    private companion object {
        const val GROUP_ID = "group-1"
        const val OTHER_GROUP_ID = "group-2"
        val ACTOR = GroupActor(id = "user-1", username = "alex")
        val ADDED =
            FeedEntry(
                id = "entry-1",
                actor = ACTOR,
                kind = ActivityKind.ADDED,
                media = null,
                mediaId = null,
                payload = emptyMap(),
                createdAt = Instant.parse("2026-08-28T10:15:30Z"),
            )
        val RATED =
            FeedEntry(
                id = "entry-2",
                actor = ACTOR,
                kind = ActivityKind.RATED,
                media = null,
                mediaId = null,
                payload = mapOf("score" to "8.5"),
                createdAt = Instant.parse("2026-08-29T09:00:00Z"),
            )
        val COMPLETED =
            FeedEntry(
                id = "entry-3",
                actor = ACTOR,
                kind = ActivityKind.COMPLETED,
                media = null,
                mediaId = null,
                payload = mapOf("status" to "completed"),
                createdAt = Instant.parse("2026-08-30T09:00:00Z"),
            )
    }
}
