package com.anarky.showtrack.feature.library

import app.cash.turbine.test
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

/**
 * The ViewModel is exercised against a FAKE `LibraryRepository`, which is the point of the
 * interface: nothing here knows Retrofit or Room exists, and this test needs no graph, no
 * Robolectric and no device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    // viewModelScope is hard-wired to Dispatchers.Main, which has no implementation on a plain
    // JVM. Substituting a TestDispatcher is what makes stateIn and the launch in `guard` run at
    // all — without it every test here fails with "Module with the Main dispatcher is missing".
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `state starts loading and then mirrors the repository`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val viewModel = LibraryViewModel(repository)

            viewModel.state.test {
                // stateIn's initialValue: the screen renders a spinner before the first fetch
                // (triggered by init) has resolved, rather than a bare, misleading empty list.
                assertEquals(LibraryUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(LibraryUiState.Success(entries = emptyList(), loadingMore = false), awaitItem())
                repository.entries.value = listOf(ENTRY)
                assertEquals(LibraryUiState.Success(entries = listOf(ENTRY), loadingMore = false), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a failing load is captured instead of escaping the coroutine`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(applyFilterFailure = failure)
            val viewModel = LibraryViewModel(repository)
            // `state` is WhileSubscribed(5s): a bare `.value` read never advances past the seeded
            // `initialValue` unless something is actively collecting. `backgroundScope` (a TestScope
            // facility) keeps a collector alive for the rest of this test without blocking it.
            backgroundScope.launch { viewModel.state.collect {} }

            advanceUntilIdle()

            // The alternative this asserts against is not "no error shown" but "process killed":
            // an exception thrown inside viewModelScope.launch reaches the thread's uncaught
            // handler, and on Android that is a crash.
            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `a later success clears the previous failure`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(applyFilterFailure = IOException("offline"))
            val viewModel = LibraryViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle()

            repository.applyFilterFailure = null
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(LibraryUiState.Success(entries = emptyList(), loadingMore = false), viewModel.state.value)
        }

    @Test
    fun `retrying after a failure shows loading immediately, not the stale error`() =
        runTest(dispatcher) {
            // applyCurrentFilter() must clear mutableError BEFORE launching the retry's fetch —
            // clearing it only on success (the previous version of this ViewModel) left the OLD
            // error on screen, unchanged, for the entire round trip: `error != null` outranks
            // `loading` in `state`'s `when`, so a lingering stale error blocks `Loading` from ever
            // showing until the retry resolves.
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(applyFilterFailure = failure)
            val viewModel = LibraryViewModel(repository)

            viewModel.state.test {
                assertEquals(LibraryUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(LibraryUiState.Error(failure), awaitItem())

                repository.applyFilterFailure = null
                viewModel.refresh()
                assertEquals(LibraryUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(LibraryUiState.Success(entries = emptyList(), loadingMore = false), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `selecting a tab applies the filter and keeps the selection while loading`() =
        runTest(dispatcher) {
            // If the selection lived inside Success, the tab row would snap back to "All" on
            // every filter change — the list reloads, so Success is briefly gone.
            val repository = FakeLibraryRepository()
            val viewModel = LibraryViewModel(repository)

            viewModel.selectStatus(UserMediaStatus.PLANNED)
            advanceUntilIdle()

            assertEquals(UserMediaStatus.PLANNED, viewModel.filter.value.status)
            assertEquals(LibraryFilter(status = UserMediaStatus.PLANNED), repository.appliedFilter)
        }

    @Test
    fun `selecting a sort applies the filter`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val viewModel = LibraryViewModel(repository)

            viewModel.selectSort(LibrarySort.SCORE)
            advanceUntilIdle()

            assertEquals(LibrarySort.SCORE, viewModel.filter.value.sort)
            assertEquals(LibraryFilter(sort = LibrarySort.SCORE), repository.appliedFilter)
        }

    @Test
    fun `a failed filter change surfaces an error and does not strand the selection`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val repository = FakeLibraryRepository()
            val viewModel = LibraryViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // let init's load settle before the filter under test fails

            repository.applyFilterFailure = failure
            viewModel.selectStatus(UserMediaStatus.PLANNED)
            advanceUntilIdle()

            // The tab the user tapped stays selected — a ViewModel that reverted `filter` here
            // would snap the tab row back to "All" underneath an error message that never
            // mentions the tab moved, which reads as the tap being silently ignored.
            assertEquals(UserMediaStatus.PLANNED, viewModel.filter.value.status)
            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `loadMore is not fired again while one is in flight`() =
        runTest(dispatcher) {
            // A LazyColumn fires its end-reached callback on every frame near the bottom. Without
            // a guard that is a request per frame; CursorPaginator's mutex makes them safe but
            // not free, and a queue of them blocks the next legitimate page.
            val repository = FakeLibraryRepository()
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle() // let init's load settle

            viewModel.loadMore()
            viewModel.loadMore()
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(1, repository.loadMoreCalls)
        }

    @Test
    fun `loadMore is allowed again once the previous call finished`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(2, repository.loadMoreCalls)
        }

    @Test
    fun `loadingMore reflects an in-flight page fetch`() =
        runTest(dispatcher) {
            // Subscribe via `.test { }` BEFORE calling `advanceUntilIdle()`, not after: `state` is
            // WhileSubscribed(5s), so advancing time with no collector yet does not start the
            // upstream combine at all, and a `.test { }` opened afterwards would see the seeded
            // `Loading` as its first item regardless of how settled the ViewModel already is.
            val repository = FakeLibraryRepository()
            val viewModel = LibraryViewModel(repository)

            viewModel.state.test {
                assertEquals(LibraryUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(LibraryUiState.Success(entries = emptyList(), loadingMore = false), awaitItem())
                viewModel.loadMore()
                assertEquals(LibraryUiState.Success(entries = emptyList(), loadingMore = true), awaitItem())
                advanceUntilIdle()
                assertEquals(LibraryUiState.Success(entries = emptyList(), loadingMore = false), awaitItem())
                assertTrue(repository.loadMoreCalls == 1)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a failed loadMore leaves Success standing with a page error`() =
        runTest(dispatcher) {
            // The entries a failed page-2 fetch left behind are still valid — LibraryRepository
            // leaves `paginator.items` untouched on a throw — so this must never promote `state`
            // to a full-screen Error, which would discard a fully-populated list over one failed
            // next page.
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(loadMoreFailure = failure)
            val viewModel = LibraryViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // let init's load settle into Success

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                LibraryUiState.Success(entries = emptyList(), loadingMore = false, pageError = failure),
                viewModel.state.value,
            )
        }

    @Test
    fun `a successful loadMore clears a previous page error`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(loadMoreFailure = failure)
            val viewModel = LibraryViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()
            repository.loadMoreFailure = null
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                LibraryUiState.Success(entries = emptyList(), loadingMore = false, pageError = null),
                viewModel.state.value,
            )
        }

    @Test
    fun `a successful loadMore does not clear a filter-load error`() =
        runTest(dispatcher) {
            // The inverse of the bug above: a single error slot shared by both operations meant
            // ANY success — even a loadMore's, which has nothing to do with the failed filter
            // load — silently cleared the full-screen Error underneath it.
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(applyFilterFailure = failure)
            val viewModel = LibraryViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // init's load fails -> Error
            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)

            viewModel.loadMore() // unaffected by applyFilterFailure; succeeds
            advanceUntilIdle()

            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `an offline cold start on the default filter shows cached entries, not a full-screen error`() =
        runTest(dispatcher) {
            // hasLoaded == false (nothing has ever succeeded yet), filter == default, refresh
            // throws -> Success(entries = cached, isStale = true), NOT Error. `repository.entries`
            // stands in for the Room cache LibraryRepositoryImpl.observeLibrary() would already be
            // emitting from a previous session, seeded before the ViewModel (and its `init { refresh() }`)
            // ever runs.
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(applyFilterFailure = failure)
            repository.entries.value = listOf(ENTRY)
            val viewModel = LibraryViewModel(repository)

            viewModel.state.test {
                assertEquals(LibraryUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(
                    LibraryUiState.Success(entries = listOf(ENTRY), loadingMore = false, isStale = true),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a failed reload AFTER a successful load shows the error, not stale rows`() =
        runTest(dispatcher) {
            // hasLoaded == true -> Error wins. This is the "don't lie about freshness" half: once
            // a load has genuinely succeeded, a later failure must not silently keep showing the
            // (now possibly outdated) rows as if nothing had gone wrong.
            val failure = IOException("offline")
            val repository = FakeLibraryRepository()
            repository.entries.value = listOf(ENTRY)
            val viewModel = LibraryViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // init's load succeeds -> hasLoaded flips true

            repository.applyFilterFailure = failure
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `a failed filter switch never renders the previous filter's rows`() =
        runTest(dispatcher) {
            // Default filter loads successfully, then applyFilter(PLANNED) throws. The fake's
            // `entries` (standing in for what LibraryRepositoryImpl.observeLibrary() would still
            // be emitting - CursorPaginator.restart() mutates nothing on a throw) still holds the
            // default filter's rows here. `hasLoaded` (session-scoped: true from the earlier
            // success and never reset per-selection) is what blocks this now, on its own — see
            // `a failed switch away from the default filter before any load has ever succeeded...`
            // below for the sequence where `isDefault` is the ONLY guard left standing.
            val failure = IOException("offline")
            val repository = FakeLibraryRepository()
            repository.entries.value = listOf(ENTRY)
            val viewModel = LibraryViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // default filter loads successfully

            repository.applyFilterFailure = failure
            viewModel.selectStatus(UserMediaStatus.PLANNED)
            advanceUntilIdle()

            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `a failed switch away from the default filter before any load succeeds shows the error, not the cache`() =
        runTest(dispatcher) {
            // hasLoaded is false throughout this whole test - nothing has EVER succeeded this
            // session - so it cannot block the cache fallback here on its own; `isDefault` is the
            // ONLY guard standing between this and rendering the cache (still holding the DEFAULT
            // filter's rows, from the cold-start failure below) under the PLANNED tab. This is
            // what makes `isDefault` load-bearing by itself, not merely redundant with
            // `hasLoaded` - see the mutation check that deletes it.
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(applyFilterFailure = failure)
            repository.entries.value = listOf(ENTRY) // a pre-existing Room cache
            val viewModel = LibraryViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // cold start fails -> Success(isStale = true) from the cache; hasLoaded stays false

            viewModel.selectStatus(UserMediaStatus.PLANNED)
            advanceUntilIdle()

            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `a failed switch back to default after a successful non-default switch does not resurrect those rows`() =
        runTest(dispatcher) {
            // Finding 2's regression (review round 1): a `hasLoaded` reset on EVERY selection
            // change - "a freshly picked filter hasn't loaded anything yet" - looked reasonable
            // but is wrong. LibraryRepositoryImpl.applyFilter reverts its OWN internal filter on a
            // throw, so when this last step's fetch fails, the repository's filter reverts to
            // PLANNED (the last filter that actually succeeded) while THIS ViewModel's `filter`
            // stays at the default tab the user just tapped. `entries` here stands in for what
            // observeLibrary() would then still be emitting: PLANNED's LIVE rows, not the cache -
            // a per-selection reset would clear `hasLoaded` for this switch and let those rows
            // render as `Success(isStale = true)`, mislabelled "saved", under the default tab.
            // Session-scoped `hasLoaded` (never reset after the first success) is what keeps this
            // blocked, entirely independent of `isDefault`.
            val failure = IOException("offline")
            val repository = FakeLibraryRepository()
            repository.entries.value = listOf(ENTRY)
            val viewModel = LibraryViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // default filter loads successfully -> hasLoaded = true

            viewModel.selectStatus(UserMediaStatus.PLANNED)
            advanceUntilIdle() // PLANNED also loads successfully

            repository.applyFilterFailure = failure
            viewModel.selectStatus(null) // switch back to the default tab; this fetch fails
            advanceUntilIdle()

            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `a successful loadMore after a stale render clears the lie about freshness`() =
        runTest(dispatcher) {
            // Finding 3, path 1 (review round 2): `loadMore()` calls `guard` with `trackLoading`
            // defaulted false, so a successful loadMore used to write CursorPaginator's `_items`
            // (LibraryRepository.loadMore -> CursorPaginator.loadMore, which mutates `_items` on
            // success just like `restart()` does) WITHOUT ever flipping `hasLoaded`. A stale
            // render's `LibraryList` auto-fires `loadMore()` the moment the last cached row is
            // visible (`LaunchedEffect(shouldLoadMore)`), so a transient offline failure followed
            // by the network coming back for that ONE call kept the cache-fallback condition true
            // forever, mislabelling live rows as "saved". `guard` now flips `hasLoaded` on ANY
            // successful repository call, not just a `trackLoading` one, closing this path.
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(applyFilterFailure = failure)
            repository.entries.value = listOf(ENTRY)
            val viewModel = LibraryViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // cold start fails -> Success(isStale = true) from the cache
            assertEquals(
                LibraryUiState.Success(entries = listOf(ENTRY), loadingMore = false, isStale = true),
                viewModel.state.value,
            )

            viewModel.loadMore() // the network answers THIS call, even though the reload never retried
            advanceUntilIdle()

            // The ORIGINAL reload's failure is still on record in `mutableError` (loadMore's
            // success only clears `mutableLoadMoreError`) and now wins outright instead of being
            // mislabelled as fresh - a failure after any success shows Error elsewhere in this
            // suite for the same reason (see "a failed reload AFTER a successful load...").
            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `a failed loadMore while stale surfaces pageError instead of being silently swallowed`() =
        runTest(dispatcher) {
            // Finding 4 (review round 2, folded in beside path 1): the showCacheInstead branch
            // built `Success` without carrying `pageError` through, so a loadMore failure fired
            // automatically by the stale list's `LaunchedEffect(shouldLoadMore)` vanished
            // entirely - no footer, no retry affordance, no signal anything went wrong.
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(applyFilterFailure = failure, loadMoreFailure = failure)
            repository.entries.value = listOf(ENTRY)
            val viewModel = LibraryViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // cold start fails -> Success(isStale = true)

            viewModel.loadMore() // also fails - the network is still down
            advanceUntilIdle()

            assertEquals(
                LibraryUiState.Success(
                    entries = listOf(ENTRY),
                    loadingMore = false,
                    pageError = failure,
                    isStale = true,
                ),
                viewModel.state.value,
            )
        }

    private class FakeLibraryRepository(
        var applyFilterFailure: Throwable? = null,
        var loadMoreFailure: Throwable? = null,
    ) : LibraryRepository {
        val entries = MutableStateFlow(emptyList<LibraryEntry>())

        var appliedFilter: LibraryFilter? = null
            private set

        var loadMoreCalls = 0
            private set

        override fun observeLibrary(): Flow<List<LibraryEntry>> = entries

        // Never exercised directly by LibraryViewModel any more: every load — the initial one and
        // every tab/sort change — goes through applyFilter, so the ViewModel's own idea of "what
        // filter is selected" and what the repository was actually asked for can never drift
        // apart the way LibraryRepositoryImpl's own KDoc warns applyFilter/refresh can (task
        // 9a.5's carried-forward note).
        override suspend fun refresh(): Unit = error("not exercised by LibraryViewModel; it always calls applyFilter")

        override suspend fun loadMore() {
            loadMoreFailure?.let { throw it }
            loadMoreCalls++
        }

        override suspend fun applyFilter(filter: LibraryFilter) {
            applyFilterFailure?.let { throw it }
            appliedFilter = filter
        }

        // Writes are task 9a.9's to exercise, once the detail screen actually calls them.
        // `error(...)` rather than a silent no-op: this ViewModel does not call these, so a test
        // that reached one would be testing something that does not exist.
        override suspend fun add(
            source: MediaSource,
            externalId: String,
        ): LibraryEntry = error("not exercised by LibraryViewModel")

        override suspend fun update(
            entryId: String,
            patch: LibraryPatch,
        ): LibraryEntry = error("not exercised by LibraryViewModel")

        override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("not exercised by LibraryViewModel")

        override val favoriteEntries: StateFlow<List<LibraryEntry>> = MutableStateFlow(emptyList())

        override suspend fun refreshFavorites(): Unit = error("not exercised by LibraryViewModel")

        override suspend fun loadMoreFavorites(): Unit = error("not exercised by LibraryViewModel")

        override suspend fun libraryStats() = error("not exercised by LibraryViewModel")

        override suspend fun importAniList(username: String) = error("not exercised by LibraryViewModel")
    }

    private companion object {
        val ENTRY =
            LibraryEntry(
                id = "entry-1",
                status = UserMediaStatus.WATCHING,
                score = BigDecimal("8.5"),
                progress = 3,
                favorite = false,
                updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
                media =
                    Media(
                        id = "media-1",
                        source = MediaSource.ANILIST,
                        externalId = "21",
                        type = MediaType.ANIME,
                        title = "One Piece",
                        year = 1999,
                        genres = listOf("Action"),
                        coverImageUrl = null,
                        status = MediaStatus.AIRING,
                        nextEpisodeSeason = null,
                        nextEpisodeNumber = 1100,
                        nextEpisodeDate = Instant.parse("2026-09-01T00:00:00Z"),
                        daysUntilNextEpisode = 4,
                    ),
            )
    }
}
