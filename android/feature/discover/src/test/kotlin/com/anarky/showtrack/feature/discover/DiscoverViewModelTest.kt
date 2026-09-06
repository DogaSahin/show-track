package com.anarky.showtrack.feature.discover

import app.cash.turbine.test
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.Recommendation
import com.anarky.showtrack.core.model.RecommendationReason
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
import java.io.IOException

/**
 * The ViewModel is exercised against FAKE `RecommendationRepository`/`LibraryRepository` — nothing
 * here knows Retrofit exists, mirroring `SearchViewModelTest`'s shape (the ViewModel this one is
 * closest to: a plain `MutableStateFlow`, no `SavedStateHandle`, no Room-backed upstream).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the feed loads once refresh is called`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            assertEquals(
                DiscoverUiState.Success(items = listOf(FRIEREN, BEBOP)),
                viewModel.state.value,
            )
        }

    /**
     * The negative half of the above (round 1 of this task — construction no longer loads
     * anything): removing `init { refresh() }` (see [DiscoverViewModel]'s own KDoc) means
     * construction alone must NOT call [RecommendationRepository.refresh] — `DiscoverScreen`'s
     * `LifecycleResumeEffect` is the only production caller, mirroring `FavoritesViewModel`'s
     * identical discipline (that class's own KDoc). A `Loading` state with zero repository calls is
     * what distinguishes "nobody has asked yet" from "a fetch is in flight" — a state-only
     * assertion could not tell those apart if `refresh()` were still called from `init`.
     */
    @Test
    fun `construction alone does not fetch the feed`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            advanceUntilIdle()

            assertEquals(DiscoverUiState.Loading, viewModel.state.value)
            assertEquals(0, recommendations.refreshCalls)
        }

    @Test
    fun `a failing initial load is captured instead of escaping the coroutine`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val recommendations = FakeRecommendationRepository(refreshFailure = failure)
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            assertEquals(DiscoverUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `retrying after a failed load shows loading immediately, not the stale error`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val recommendations = FakeRecommendationRepository(refreshFailure = failure)
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()
            assertEquals(DiscoverUiState.Error(failure), viewModel.state.value)

            recommendations.refreshFailure = null
            recommendations.refreshResult = listOf(FRIEREN)
            viewModel.state.test {
                assertEquals(DiscoverUiState.Error(failure), awaitItem())
                viewModel.refresh()
                assertEquals(DiscoverUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(DiscoverUiState.Success(items = listOf(FRIEREN)), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Decision, this task (E-M): a resume's refresh must not blank an already-populated feed to a
     * spinner — `FavoritesViewModel.refresh`/`ProfileViewModel.refreshStats`'s identical shape,
     * "the settled refresh shape" the Global Constraints name. `recommendations.refreshGate` is
     * what makes the mid-flight state actually observable — a fake that resolves synchronously
     * would never show a wrongly-blanked `Loading` even if the production code regressed.
     */
    @Test
    fun `a resume over an already-populated feed keeps the stale rows, not a spinner, mid-fetch`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()
            assertEquals(DiscoverUiState.Success(items = listOf(FRIEREN)), viewModel.state.value)

            recommendations.refreshResult = listOf(FRIEREN, BEBOP)
            recommendations.refreshGate = CompletableDeferred()
            viewModel.refresh()
            advanceUntilIdle()

            // Still the OLD items, and still Success — never DiscoverUiState.Loading — while the
            // network round trip this resume triggered is genuinely still in flight.
            assertEquals(DiscoverUiState.Success(items = listOf(FRIEREN)), viewModel.state.value)

            recommendations.refreshGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(DiscoverUiState.Success(items = listOf(FRIEREN, BEBOP)), viewModel.state.value)
        }

    /**
     * The failure half: a resume's background refetch failing must not destroy a feed the user is
     * already reading — `FavoritesViewModel.refresh`'s round-2 fix, applied here. `isStale = true`
     * is the marker; a bare [DiscoverUiState.Error] stays reachable only for the case nothing is
     * on screen yet.
     */
    @Test
    fun `a failed resume over an already-populated feed marks it stale instead of replacing it`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()
            assertEquals(DiscoverUiState.Success(items = listOf(FRIEREN)), viewModel.state.value)

            val failure = IOException("offline")
            recommendations.refreshGate = CompletableDeferred()
            recommendations.refreshFailure = failure
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(DiscoverUiState.Success(items = listOf(FRIEREN)), viewModel.state.value)

            recommendations.refreshGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                DiscoverUiState.Success(items = listOf(FRIEREN), isStale = true),
                viewModel.state.value,
            )
        }

    @Test
    fun `a successful resume clears a previous stale mark`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            recommendations.refreshFailure = IOException("offline")
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(
                DiscoverUiState.Success(items = listOf(FRIEREN), isStale = true),
                viewModel.state.value,
            )

            recommendations.refreshFailure = null
            recommendations.refreshResult = listOf(FRIEREN, BEBOP)
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                DiscoverUiState.Success(items = listOf(FRIEREN, BEBOP), isStale = false),
                viewModel.state.value,
            )
        }

    /**
     * The re-entrancy guard this task's brief calls out by name (step 2, extended here as the
     * "sibling" the brief's own lesson warns about: adding a resume trigger to [DiscoverViewModel.refresh]
     * reproduces the identical "manual retry races a resume fetch" shape `FavoritesViewModel.refresh`/
     * `ProfileViewModel.refreshStats` were fixed for — [DiscoverUiState.Success.isStale] now makes
     * `StaleDataBanner`'s retry reachable here exactly the way it is on those two screens. Asserts
     * the repository call COUNT, not the resulting state (Global Constraints): a count on a fake
     * cannot pass when the call never happens, which is what makes it discriminate a guard that is
     * silently missing from one that is present but happens not to matter for this particular
     * assertion.
     */
    @Test
    fun `a retry landing inside an in-flight resume fetch does not double-fetch`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()
            assertEquals(1, recommendations.refreshCalls)

            recommendations.refreshGate = CompletableDeferred()
            viewModel.refresh() // the resume-triggered fetch
            viewModel.refresh() // a manual retry landing while it is still in flight
            advanceUntilIdle()

            // Only the resume's own call — the retry that landed inside it must be dropped, not
            // queued behind it.
            assertEquals(2, recommendations.refreshCalls)

            recommendations.refreshGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, recommendations.refreshCalls)
        }

    @Test
    fun `loadMore is not fired again while one is in flight`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            viewModel.loadMore()
            viewModel.loadMore()
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(1, recommendations.loadMoreCalls)
        }

    @Test
    fun `a failed loadMore leaves items standing with a page error`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val recommendations =
                FakeRecommendationRepository(refreshResult = listOf(FRIEREN), loadMoreFailure = failure)
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                DiscoverUiState.Success(items = listOf(FRIEREN), loadingMore = false, pageError = failure),
                viewModel.state.value,
            )
        }

    @Test
    fun `a successful loadMore clears a previous page error`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val recommendations =
                FakeRecommendationRepository(
                    refreshResult = listOf(FRIEREN),
                    loadMoreFailure = failure,
                    loadMoreAppends = listOf(BEBOP),
                )
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(failure, (viewModel.state.value as DiscoverUiState.Success).pageError)

            recommendations.loadMoreFailure = null
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                DiscoverUiState.Success(items = listOf(FRIEREN, BEBOP), loadingMore = false, pageError = null),
                viewModel.state.value,
            )
        }

    /**
     * Review finding B2, round 1: a successful [DiscoverViewModel.refresh] used to rebuild
     * [DiscoverUiState.Success] field-by-field (`Success(items = ...)`), silently defaulting
     * [DiscoverUiState.Success.loadingMore] back to `false` even while a concurrent [DiscoverViewModel.loadMore]
     * was genuinely still in flight — freeing `EndOfListTrigger` to fire a SECOND, concurrent
     * `loadMore()`. `recommendations.loadMoreGate` is what makes "still in flight" observable:
     * `loadMore()` is called first and gated open, `refresh()` is driven to completion while it is
     * still suspended, and the assertion below is taken BEFORE the gate is ever released.
     */
    @Test
    fun `a successful refresh preserves loadingMore from a loadMore still genuinely in flight`() =
        runTest(dispatcher) {
            val recommendations =
                FakeRecommendationRepository(refreshResult = listOf(FRIEREN), loadMoreAppends = listOf(BEBOP))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh()
            advanceUntilIdle()

            recommendations.loadMoreGate = CompletableDeferred()
            viewModel.loadMore()
            advanceUntilIdle()
            assertTrue((viewModel.state.value as DiscoverUiState.Success).loadingMore)

            recommendations.refreshResult = listOf(FRIEREN)
            viewModel.refresh()
            advanceUntilIdle()

            // The resume's OWN refresh succeeded (recommendations.refreshCalls proves it ran), but
            // loadMore()'s own loadingMore flag must still read true — a rebuilt Success would have
            // silently reset it to false here, with the page fetch still genuinely suspended below.
            assertEquals(2, recommendations.refreshCalls)
            assertTrue(
                "a rebuilt Success would have silently cleared loadingMore here",
                (viewModel.state.value as DiscoverUiState.Success).loadingMore,
            )

            recommendations.loadMoreGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                DiscoverUiState.Success(items = listOf(FRIEREN, BEBOP), loadingMore = false, pageError = null),
                viewModel.state.value,
            )
        }

    /**
     * Review finding B3, round 1 (KDoc corrected round 2 — see [DiscoverViewModel.loadMore]'s own
     * KDoc for why the ORIGINAL duplicate-`media.id` justification for this guard was false):
     * before this task, [DiscoverViewModel.loadMore] and [DiscoverViewModel.refresh] were disjoint
     * by construction, so they could never run concurrently against [RecommendationRepository]. A
     * resume can now call [DiscoverViewModel.refresh] at any moment, including while a page fetch
     * is in flight — [DiscoverViewModel.loadMore] drops that call outright rather than let
     * [refresh]'s own page-1 truncation immediately discard the page it would have fetched.
     * Asserts the repository call COUNT: a count on a fake cannot pass when the call never
     * happens, which is what makes it discriminate. The follow-up — does the dropped call ever
     * actually run? — is the next test below, which pins the automatic re-fire rather than a
     * manual second call.
     */
    @Test
    fun `loadMore is dropped while a refresh is genuinely in flight`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh()
            advanceUntilIdle()

            recommendations.refreshGate = CompletableDeferred()
            viewModel.refresh() // a resume's refresh, held open
            viewModel.loadMore() // must be dropped — a refresh is known to be in flight
            advanceUntilIdle()

            assertEquals(0, recommendations.loadMoreCalls)

            recommendations.refreshGate?.complete(Unit)
            advanceUntilIdle()
        }

    /**
     * Review finding, round 2 ("B3 guard can stall paging"): `EndOfListTrigger` only emits on the
     * FALSE -> TRUE edge of its own `shouldTrigger` (that composable's own KDoc) — a `loadMore()`
     * dropped by [DiscoverViewModel.loadMore]'s `refreshInFlight` guard changes neither `itemCount`
     * nor scroll position, so nothing re-fires it on its own once the refresh lands. This test pins
     * [pendingLoadMoreAfterRefresh]'s repair: the dropped call is re-issued automatically by
     * [DiscoverViewModel.refresh]'s own `finally`, with NO second [DiscoverViewModel.loadMore] call
     * from this test at all — the previous test above still drives a manual second call too, to
     * pin that manual paging still works afterward, but this one isolates the automatic re-fire.
     */
    @Test
    fun `a loadMore dropped by an in-flight refresh is re-issued automatically once the refresh lands`() =
        runTest(dispatcher) {
            val recommendations =
                FakeRecommendationRepository(refreshResult = listOf(FRIEREN), loadMoreAppends = listOf(BEBOP))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh()
            advanceUntilIdle()

            recommendations.refreshGate = CompletableDeferred()
            viewModel.refresh() // a resume's refresh, held open
            viewModel.loadMore() // dropped — sets pendingLoadMoreAfterRefresh
            advanceUntilIdle()
            assertEquals(0, recommendations.loadMoreCalls)

            recommendations.refreshGate?.complete(Unit)
            advanceUntilIdle()

            // No second viewModel.loadMore() call here — refresh()'s own finally is what fired it.
            assertEquals(1, recommendations.loadMoreCalls)
            assertEquals(
                DiscoverUiState.Success(items = listOf(FRIEREN, BEBOP), loadingMore = false, pageError = null),
                viewModel.state.value,
            )
        }

    /**
     * Review finding, round 2 (BLOCKING): [DiscoverViewModel.refresh] and [DiscoverViewModel.add]
     * are the pair that actually duplicates a `media.id` — round 1's [loadMore]/`refreshInFlight`
     * guard defended the wrong pair (see [loadMore]'s own KDoc, corrected round 2). Tap Add, POST
     * in flight, a resume fires [DiscoverViewModel.refresh] before the server commits the add — the
     * repository fake's `refreshResult` still names the row, so without this guard the refresh
     * would republish it and a subsequent failed `restore()` would insert a SECOND copy. Asserts
     * the repository call COUNT: a count on a fake cannot pass when the call never happens.
     */
    @Test
    fun `add is dropped while a refresh is genuinely in flight`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val library = FakeLibraryRepository()
            val viewModel = DiscoverViewModel(recommendations, library)
            viewModel.refresh()
            advanceUntilIdle()

            recommendations.refreshGate = CompletableDeferred()
            viewModel.refresh() // a resume's refresh, held open
            viewModel.add(FRIEREN) // must be dropped — a refresh is known to be in flight
            advanceUntilIdle()

            assertEquals(0, library.addCalls.size)
            assertEquals(listOf(FRIEREN, BEBOP), (viewModel.state.value as DiscoverUiState.Success).items)

            recommendations.refreshGate?.complete(Unit)
            advanceUntilIdle()

            // Once the refresh has actually landed, add() is reachable again.
            viewModel.add(FRIEREN)
            advanceUntilIdle()
            assertEquals(1, library.addCalls.size)
        }

    /**
     * The reverse half of the same guard: a resume's [DiscoverViewModel.refresh] must not land
     * WHILE an optimistic [DiscoverViewModel.add] is still in flight either — the failure path
     * (`restore()` re-inserting a row a same-tick refresh already republished) is the crash the
     * review's probe actually reproduced against round 1's code.
     */
    @Test
    fun `refresh is dropped while an add is genuinely in flight`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val addGate = CompletableDeferred<Unit>()
            val library = FakeLibraryRepository(addGate = addGate)
            val viewModel = DiscoverViewModel(recommendations, library)
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.add(FRIEREN) // held open on addGate
            advanceUntilIdle()
            assertEquals(listOf(BEBOP), (viewModel.state.value as DiscoverUiState.Success).items)

            viewModel.refresh() // must be dropped — an add is known to be in flight
            advanceUntilIdle()

            assertEquals(1, recommendations.refreshCalls) // only the very first call above
            assertEquals(listOf(BEBOP), (viewModel.state.value as DiscoverUiState.Success).items)

            addGate.complete(Unit)
            advanceUntilIdle()

            // Once the add has actually landed, refresh() is reachable again.
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(2, recommendations.refreshCalls)
        }

    /**
     * Task 9c.8 round 2 (review finding): [DiscoverViewModel.loadMore]'s `finally` reset, proven
     * with a genuine [CancellationException] as the vehicle — this class's own `catch` never
     * absorbs that type, so a plain [IOException] could never discriminate a missing `finally`
     * here (the generic `catch (failure: Exception)` already resets `loadingMore` for every OTHER
     * failure). Round 1's [DiscoverViewModel.refresh] fix (B2) removed the ACCIDENTAL recovery this
     * relied on before: the old field-by-field rebuild used to silently reset a stuck `loadingMore`
     * on the next successful refresh; the new `copy()` faithfully preserves it forever instead.
     */
    @Test
    fun `loadMore resets loadingMore even when the fetch is cancelled, not merely failed`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh()
            advanceUntilIdle()

            recommendations.loadMoreFailure = CancellationException("simulated cancellation mid-fetch")
            viewModel.loadMore()
            advanceUntilIdle()

            val success = viewModel.state.value as DiscoverUiState.Success
            assertFalse("loadingMore must not stay stuck true", success.loadingMore)
        }

    @Test
    fun `adding a row removes it from the feed immediately, before the network call resolves`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val addGate = CompletableDeferred<Unit>()
            val library = FakeLibraryRepository(addGate = addGate)
            val viewModel = DiscoverViewModel(recommendations, library)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            viewModel.add(FRIEREN)
            // `add()`'s own removal write is synchronous (before `viewModelScope.launch`), but the
            // launched coroutine itself needs the StandardTestDispatcher pumped before it reaches
            // (and suspends on) addGate.await() — advanceUntilIdle() does exactly that and no more,
            // since an uncompleted CompletableDeferred leaves the coroutine genuinely suspended
            // rather than idle-with-nothing-left-to-run.
            advanceUntilIdle()

            // The row is gone from the SCREEN state, and libraryRepository.add() has genuinely
            // been CALLED and is now suspended on addGate — not merely "resolved fast" — proving
            // the removal did not wait for the network round trip to finish.
            assertEquals(listOf(BEBOP), (viewModel.state.value as DiscoverUiState.Success).items)
            assertEquals(1, library.addCalls.size)
            assertEquals(MediaSource.ANILIST to "154587", library.addCalls.single())
            addGate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `a successful add does not restore the row and reports no error`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            viewModel.add(FRIEREN)
            advanceUntilIdle()

            assertEquals(
                DiscoverUiState.Success(items = listOf(BEBOP)),
                viewModel.state.value,
            )
            assertEquals(listOf("media-frieren"), recommendations.removedIds)
        }

    /**
     * The restore-on-failure test — decision D-I's whole point, and the one the task brief calls
     * out as "the one that matters". [FRIEREN] sits at index 0 of a three-row feed; the add fails;
     * the row must come back at index 0, not appended to the end, and not merely present somewhere.
     *
     * The restore itself now goes through [RecommendationRepository.restore] (fix round 1: a
     * review found the original UI-only restore did not survive a later `loadMore()` — see the
     * composing test directly below, which is the one that would have caught it).
     */
    @Test
    fun `a failed add restores the row at its original index and reports beside it`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP, DANDADAN))
            val failure = IOException("offline")
            val library = FakeLibraryRepository(addFailure = failure)
            val viewModel = DiscoverViewModel(recommendations, library)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            viewModel.add(BEBOP)
            advanceUntilIdle()

            val success = viewModel.state.value as DiscoverUiState.Success
            assertEquals(listOf(FRIEREN, BEBOP, DANDADAN), success.items)
            assertEquals(AddFailure(BEBOP.media.id, failure), success.addError)
        }

    /**
     * The composing test fix round 1 asked for, written to fail first: a middle row's add fails
     * (restoring it via [RecommendationRepository.restore]), then a SUCCESSFUL `loadMore()` runs.
     * `loadMore`'s success path re-publishes `items` wholesale from
     * [RecommendationRepository.feed] — if the restore had only patched this ViewModel's own copy
     * of the list (the original implementation), that wholesale re-read would silently discard the
     * restored row the moment the user scrolled far enough to trigger a page fetch: it would vanish
     * with no user action, and [DiscoverUiState.Success.addError] would be left naming a `mediaId`
     * no longer present in `items` at all — which also strands the row's own retry affordance,
     * since that renders inside the (now-gone) row's own list item.
     *
     * Confirmed to fail against the pre-fix implementation before this fix: with the restore
     * applied to local UI state only, the assertion below failed with
     * `expected:<[Frieren, Bebop, Dandadan, SpyFamily]> but was:<[Frieren, Dandadan, SpyFamily]>` —
     * Bebop silently dropped, exactly the reported bug.
     */
    @Test
    fun `a restored row survives a loadMore that runs after the failed add`() =
        runTest(dispatcher) {
            val recommendations =
                FakeRecommendationRepository(
                    refreshResult = listOf(FRIEREN, BEBOP, DANDADAN),
                    loadMoreAppends = listOf(SPY_FAMILY),
                )
            val failure = IOException("offline")
            val library = FakeLibraryRepository(addFailure = failure)
            val viewModel = DiscoverViewModel(recommendations, library)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            viewModel.add(BEBOP)
            advanceUntilIdle()
            // Sanity check: the restore already landed before loadMore ever runs.
            assertEquals(listOf(FRIEREN, BEBOP, DANDADAN), (viewModel.state.value as DiscoverUiState.Success).items)

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                listOf(FRIEREN, BEBOP, DANDADAN, SPY_FAMILY),
                (viewModel.state.value as DiscoverUiState.Success).items,
            )
        }

    @Test
    fun `a second add is ignored while one is already in flight`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val addGate = CompletableDeferred<Unit>()
            val library = FakeLibraryRepository(addGate = addGate)
            val viewModel = DiscoverViewModel(recommendations, library)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            viewModel.add(FRIEREN)
            viewModel.add(BEBOP)
            addGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, library.addCalls.size)
        }

    @Test
    fun `a successful add does not clear a standing page error`() =
        runTest(dispatcher) {
            // The inverse of task 9a.8's shipped bug: loadMore's pageError and add's addError must
            // stay independent channels — a successful add clearing an unrelated pageError would be
            // exactly that bug in a new screen.
            val failure = IOException("offline")
            val recommendations =
                FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP), loadMoreFailure = failure)
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(failure, (viewModel.state.value as DiscoverUiState.Success).pageError)

            viewModel.add(FRIEREN)
            advanceUntilIdle()

            assertEquals(failure, (viewModel.state.value as DiscoverUiState.Success).pageError)
        }

    @Test
    fun `a failed add does not clear a standing page error`() =
        runTest(dispatcher) {
            val loadMoreFailure = IOException("offline - loadMore")
            val addFailure = IOException("offline - add")
            val recommendations =
                FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP), loadMoreFailure = loadMoreFailure)
            val library = FakeLibraryRepository(addFailure = addFailure)
            val viewModel = DiscoverViewModel(recommendations, library)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()

            viewModel.add(FRIEREN)
            advanceUntilIdle()

            val success = viewModel.state.value as DiscoverUiState.Success
            assertEquals(loadMoreFailure, success.pageError)
            assertEquals(AddFailure(FRIEREN.media.id, addFailure), success.addError)
        }

    @Test
    fun `refresh never calls remove — the optimistic add is the only source of a removal`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()

            assertTrue(recommendations.removedIds.isEmpty())
            assertNull((viewModel.state.value as DiscoverUiState.Success).addError)
        }

    // FakeRecommendationRepository/FakeLibraryRepository moved to their own files (task 9c.8,
    // E-M) — see FakeRecommendationRepository's own KDoc for why: DiscoverResumeTest needs the
    // exact same fakes, and a nested `private class` is invisible outside this class.

    private companion object {
        fun media(
            id: String,
            title: String,
            externalId: String,
        ) = Media(
            id = id,
            source = MediaSource.ANILIST,
            externalId = externalId,
            type = MediaType.ANIME,
            title = title,
            year = 2023,
            genres = listOf("fantasy"),
            coverImageUrl = null,
            status = MediaStatus.NOT_YET_AIRED,
            nextEpisodeSeason = null,
            nextEpisodeNumber = null,
            nextEpisodeDate = null,
            daysUntilNextEpisode = null,
        )

        val FRIEREN =
            Recommendation(
                media = media(id = "media-frieren", title = "Frieren", externalId = "154587"),
                reason =
                    RecommendationReason(
                        seedMediaId = "seed-1",
                        seedTitle = "Made in Abyss",
                        matchedGenres = listOf("fantasy"),
                    ),
            )

        val BEBOP =
            Recommendation(
                media = media(id = "media-bebop", title = "Cowboy Bebop", externalId = "1"),
                reason =
                    RecommendationReason(
                        seedMediaId = "seed-2",
                        seedTitle = "Trigun",
                        matchedGenres = listOf("action"),
                    ),
            )

        val DANDADAN =
            Recommendation(
                media = media(id = "media-dandadan", title = "Dandadan", externalId = "2"),
                reason =
                    RecommendationReason(
                        seedMediaId = "seed-3",
                        seedTitle = "Mob Psycho 100",
                        matchedGenres = listOf("comedy"),
                    ),
            )

        val SPY_FAMILY =
            Recommendation(
                media = media(id = "media-spy-family", title = "Spy x Family", externalId = "3"),
                reason =
                    RecommendationReason(
                        seedMediaId = "seed-4",
                        seedTitle = "Great Pretender",
                        matchedGenres = listOf("comedy"),
                    ),
            )
    }
}
