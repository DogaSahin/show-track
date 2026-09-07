package com.anarky.showtrack.feature.feed

import com.anarky.showtrack.core.data.repository.FeedPage
import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.FeedEntry
import com.anarky.showtrack.core.model.GroupActor
import com.anarky.showtrack.core.model.GroupFailure
import kotlinx.coroutines.CancellationException
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
                    feedPages = mutableMapOf((GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = null)),
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
                            (GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = "cursor-2"),
                            (GROUP_ID to "cursor-2") to FeedPage(items = listOf(RATED), nextCursor = null),
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
                            (GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = "cursor-2"),
                            (GROUP_ID to "cursor-2") to FeedPage(items = listOf(RATED), nextCursor = "cursor-3"),
                            (GROUP_ID to "cursor-3") to FeedPage(items = listOf(COMPLETED), nextCursor = null),
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
     * Task 9c.8 round 1 (review finding B1): [FeedViewModel.loadMore]'s `finally` reset, proven
     * with a genuine [CancellationException] as the vehicle rather than a [GroupOperationException]
     * — `FeedViewModel`'s own `catch` already handles that type, so a wrapped failure could never
     * discriminate a missing `finally`. A round-0 attempt at this test used
     * `FakeGroupRepository.feed`'s own `error("no feedPages entry configured for ...")`
     * ([IllegalStateException]) as the vehicle instead — that exception is genuinely UNCAUGHT, so
     * `kotlinx-coroutines-test` reported it and failed this test UNCONDITIONALLY, with or without
     * the `finally` present, discriminating nothing. [CancellationException] completes the
     * coroutine as CANCELLED, not FAILED, so `kotlinx-coroutines-test` never reports it as an
     * uncaught exception — only the state assertion below is the signal.
     */
    @Test
    fun `loadMore resets loadingMore even when the fetch is cancelled, not merely failed`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages =
                        mutableMapOf(
                            (GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = "cursor-2"),
                        ),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()
            assertEquals(listOf(ADDED), (viewModel.state.value as FeedUiState.Success).entries)

            repository.feedThrows = CancellationException("simulated cancellation mid-fetch")
            viewModel.loadMore()
            advanceUntilIdle()

            val success = viewModel.state.value as FeedUiState.Success
            assertFalse("loadingMore must not stay stuck true", success.loadingMore)
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
                    feedPages =
                        mutableMapOf((GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = "cursor-2")),
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
            repository.feedPages[GROUP_ID to null] = FeedPage(items = listOf(ADDED, RATED), nextCursor = null)
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
                    feedPages = mutableMapOf((GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = null)),
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
                            (GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = null),
                        ),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()
            assertEquals(listOf(ADDED), (viewModel.state.value as FeedUiState.Success).entries)

            repository.feedPages[OTHER_GROUP_ID to null] = FeedPage(items = listOf(RATED), nextCursor = null)
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
                    feedPages = mutableMapOf((GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = null)),
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

    /**
     * **Round 1, BLOCKING.** Reviewer-reproduced: `loadMore()` for group A queued behind
     * [CursorPaginator]'s mutex must not, once unblocked, write group A's late page over group B's
     * already-rendered feed after [selectGroup] switches subjects mid-fetch. [FeedViewModel] is
     * the first ViewModel in this codebase to outlive the subject it pages — see that class's own
     * KDoc — so rebuilding [paginator] per group (which stops a stale CURSOR) was never enough on
     * its own; this pins the [generation] check that stops a stale CONTINUATION too.
     */
    @Test
    fun `a group switch does not let a stale in-flight page overwrite the new group's feed`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages =
                        mutableMapOf(
                            (GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = "cursor-2"),
                            (GROUP_ID to "cursor-2") to FeedPage(items = listOf(RATED), nextCursor = null),
                            (OTHER_GROUP_ID to null) to FeedPage(items = listOf(COMPLETED), nextCursor = null),
                        ),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()
            assertEquals(listOf(ADDED), (viewModel.state.value as FeedUiState.Success).entries)

            // Gate group A's page-2 fetch so it stays in flight while the switch below happens.
            repository.feedGates[GROUP_ID to "cursor-2"] = CompletableDeferred()
            viewModel.loadMore()

            viewModel.selectGroup(OTHER_GROUP_ID)
            advanceUntilIdle()

            // Group B's own page has already landed and rendered — this is what a real screen
            // would be showing at this point, well before A's late page ever arrives.
            assertEquals(listOf(COMPLETED), (viewModel.state.value as FeedUiState.Success).entries)

            // A's page-2 fetch finally resolves.
            repository.feedGates.getValue(GROUP_ID to "cursor-2").complete(Unit)
            advanceUntilIdle()

            // Must still be showing group B's own feed — the reviewer-reproduced repro this fix
            // closes: `FEED CALLS: [(group-A, null), (group-A, cursor-2), (group-B, null)]` must
            // NOT land as `Success(entries=[a1, a2])`.
            assertEquals(listOf(COMPLETED), (viewModel.state.value as FeedUiState.Success).entries)
            assertEquals(
                listOf(GROUP_ID to null, GROUP_ID to "cursor-2", OTHER_GROUP_ID to null),
                repository.feedCalls,
            )
        }

    /**
     * **Round 1, BLOCKING — the [reload] failure-path half of the same bug.** A retry for group A
     * that is still in flight when [selectGroup] switches to group B must not, on failing, mark
     * group B's already-rendered [FeedUiState.Success] stale — B's data is fresh; A's failure is
     * not B's problem, and belongs to a generation nobody is looking at any more.
     */
    @Test
    fun `a stale reload failure from an old group does not mark the new group's fresh success stale`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages =
                        mutableMapOf(
                            (GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = null),
                            (OTHER_GROUP_ID to null) to FeedPage(items = listOf(COMPLETED), nextCursor = null),
                        ),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()
            assertEquals(listOf(ADDED), (viewModel.state.value as FeedUiState.Success).entries)

            // A retry for group A that will FAIL once it lands — gated so it stays in flight, and
            // its failure configured PER KEY so it cannot leak onto group B's unrelated fetch below.
            repository.feedGates[GROUP_ID to null] = CompletableDeferred()
            repository.feedFailures[GROUP_ID to null] = GroupFailure.Network
            viewModel.refresh()

            viewModel.selectGroup(OTHER_GROUP_ID)
            advanceUntilIdle()

            assertEquals(FeedUiState.Success(entries = listOf(COMPLETED)), viewModel.state.value)

            // A's retry finally resolves — as a failure.
            repository.feedGates.getValue(GROUP_ID to null).complete(Unit)
            advanceUntilIdle()

            // B's screen must still be a clean, non-stale Success.
            assertEquals(FeedUiState.Success(entries = listOf(COMPLETED)), viewModel.state.value)
        }

    /**
     * **Round 1, small item 1.** Double-tapping Retry, or a resume racing a retry still in flight,
     * must not launch a second `restart()` — `FavoritesViewModel.refresh`'s identical unfixed gap,
     * closed here because [loadingGeneration] already exists for the BLOCKING fix above and this is
     * one extra comparison to also guard [refresh] itself. Not a correctness fix
     * ([CursorPaginator]'s own `Mutex` already keeps state consistent either way — this class's own
     * KDoc) — only a wasted-round-trip fix.
     */
    @Test
    fun `refresh does not launch a second reload while one is already in flight`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages = mutableMapOf((GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = null)),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()
            assertEquals(listOf(GROUP_ID to null), repository.feedCalls)

            repository.feedGates[GROUP_ID to null] = CompletableDeferred()
            viewModel.refresh()
            viewModel.refresh()
            viewModel.refresh()
            advanceUntilIdle()

            // Only ONE additional fetch queued behind the gate — the two re-entrant calls above
            // must not have launched a second/third restart() while the first was still in flight.
            assertEquals(listOf(GROUP_ID to null, GROUP_ID to null), repository.feedCalls)

            repository.feedGates.getValue(GROUP_ID to null).complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to null, GROUP_ID to null), repository.feedCalls)
            assertEquals(listOf(ADDED), (viewModel.state.value as FeedUiState.Success).entries)
        }

    /**
     * **Round 2 — pins [reload]'s SUCCESS-path generation check independently.** Round 1's own
     * cross-group tests happened to only exercise [reload]'s FAILURE path and [loadMore]'s SUCCESS
     * path; removing this specific check alone left all 22 round-1 tests green. A retry for group A
     * that will SUCCEED is gated in flight while the screen switches to group B; A's late (and, to
     * make a silent overwrite detectable, DIFFERENT) page must never land on B's screen.
     */
    @Test
    fun `a stale successful reload from an old group does not overwrite the new group's fresh success`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages =
                        mutableMapOf(
                            (GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = null),
                            (OTHER_GROUP_ID to null) to FeedPage(items = listOf(COMPLETED), nextCursor = null),
                        ),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()
            assertEquals(listOf(ADDED), (viewModel.state.value as FeedUiState.Success).entries)

            // A retry for group A that will SUCCEED once it lands — gated so it stays in flight,
            // and reconfigured to return a DIFFERENT page so a silent overwrite is detectable.
            repository.feedGates[GROUP_ID to null] = CompletableDeferred()
            repository.feedPages[GROUP_ID to null] = FeedPage(items = listOf(ADDED, RATED), nextCursor = null)
            viewModel.refresh()

            viewModel.selectGroup(OTHER_GROUP_ID)
            advanceUntilIdle()

            assertEquals(FeedUiState.Success(entries = listOf(COMPLETED)), viewModel.state.value)

            // A's retry finally resolves — as a SUCCESS, with a page B never asked for.
            repository.feedGates.getValue(GROUP_ID to null).complete(Unit)
            advanceUntilIdle()

            // B's screen must still show B's own feed, not A's late page.
            assertEquals(FeedUiState.Success(entries = listOf(COMPLETED)), viewModel.state.value)
        }

    /**
     * **Round 2 — pins [loadMore]'s FAILURE-path generation check independently.** Round 1's
     * `` `a group switch does not let a stale in-flight page overwrite the new group's feed` ``
     * only exercised [loadMore]'s SUCCESS path; removing the FAILURE-path check alone left all 22
     * round-1 tests green. Concrete consequence this guards against: group A's page-2 fetch is in
     * flight and about to fail, the user switches to group B (which renders a clean
     * [FeedUiState.Success]), and A's failure then lands and would otherwise write
     * [FeedUiState.Success.pageError] onto B's state — B shows a page-error retry footer for a page
     * fetch it never made.
     */
    @Test
    fun `a stale loadMore failure from an old group does not surface a page error on the new group's feed`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages =
                        mutableMapOf(
                            (GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = "cursor-2"),
                            (OTHER_GROUP_ID to null) to FeedPage(items = listOf(COMPLETED), nextCursor = null),
                        ),
                )
            val viewModel = FeedViewModel(repository)
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()
            assertEquals(listOf(ADDED), (viewModel.state.value as FeedUiState.Success).entries)

            // group A's page-2 fetch will FAIL once it lands — gated so it stays in flight, and its
            // failure configured PER KEY so it cannot leak onto group B's unrelated fetch below.
            repository.feedGates[GROUP_ID to "cursor-2"] = CompletableDeferred()
            repository.feedFailures[GROUP_ID to "cursor-2"] = GroupFailure.Network
            viewModel.loadMore()

            viewModel.selectGroup(OTHER_GROUP_ID)
            advanceUntilIdle()

            assertEquals(FeedUiState.Success(entries = listOf(COMPLETED)), viewModel.state.value)

            // A's page-2 fetch finally resolves — as a failure.
            repository.feedGates.getValue(GROUP_ID to "cursor-2").complete(Unit)
            advanceUntilIdle()

            // B's screen must still be a clean Success with no page error — A's failed page-2 fetch
            // is not B's problem.
            assertEquals(FeedUiState.Success(entries = listOf(COMPLETED)), viewModel.state.value)
        }

    /**
     * **Round 2 — pins the `finally` qualifier `if (loadingGeneration == myGeneration)
     * loadingGeneration = null` independently.** An unconditional clear lets a stale (OLD)
     * generation's `finally` erase a NEWER generation's still-genuinely-in-flight guard, letting a
     * third caller slip a redundant fetch in behind it. Needs TWO generations and a THIRD caller,
     * not two gated calls on one generation (round 1's own miss) — shape: gate A's first load,
     * `selectGroup(A)` (gen 1, left in flight); gate B's first load, `selectGroup(B)` (gen 2, left
     * in flight); release A's gate so its now-irrelevant `finally` unwinds; call [FeedViewModel.refresh]
     * as the third caller while B's own reload is STILL in flight; only then release B's gate. The
     * redundant third fetch is queued behind [CursorPaginator]'s own mutex (which B's own in-flight
     * reload still holds), so it is invisible in [FakeGroupRepository.feedCalls] until B's gate is
     * released — asserting before that release would pass against the mutant, the exact trap round
     * 1's own attempt at this fell into.
     */
    @Test
    fun `a stale generation's finally does not clear the guard for a newer generation still in flight`() =
        runTest(dispatcher) {
            val repository =
                FakeGroupRepository(
                    feedPages =
                        mutableMapOf(
                            (GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = null),
                            (OTHER_GROUP_ID to null) to FeedPage(items = listOf(COMPLETED), nextCursor = null),
                        ),
                )
            val viewModel = FeedViewModel(repository)

            repository.feedGates[GROUP_ID to null] = CompletableDeferred()
            viewModel.selectGroup(GROUP_ID)
            advanceUntilIdle()

            repository.feedGates[OTHER_GROUP_ID to null] = CompletableDeferred()
            viewModel.selectGroup(OTHER_GROUP_ID)
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to null, OTHER_GROUP_ID to null), repository.feedCalls)

            // Release group A's gate — its own (generation-1) reload resolves, drops its write (the
            // success-path check above), and its `finally` runs for a generation nobody is looking
            // at any more.
            repository.feedGates.getValue(GROUP_ID to null).complete(Unit)
            advanceUntilIdle()

            // A third caller — a resume, a retry — while group B's own reload (generation 2) is
            // STILL genuinely in flight.
            viewModel.refresh()

            // Release group B's gate. B's own reload resolves here; if the guard failed to hold,
            // this is also where the redundant third fetch — queued behind the mutex until now —
            // finally executes and shows up in feedCalls.
            repository.feedGates.getValue(OTHER_GROUP_ID to null).complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID to null, OTHER_GROUP_ID to null), repository.feedCalls)
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
