package com.anarky.showtrack.feature.library

import app.cash.turbine.test
import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibrarySection
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
            val viewModel = pagedViewModel(repository)

            viewModel.state.test {
                // stateIn's initialValue: the screen renders a spinner before the first fetch
                // (triggered by the status switch) has resolved, rather than a bare, misleading empty list.
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
            val viewModel = pagedViewModel(repository)
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
            val viewModel = pagedViewModel(repository)
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
            val viewModel = pagedViewModel(repository)

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
            advanceUntilIdle()
            assertNull("All shows is Home; it never runs the paginator", repository.appliedFilter)

            viewModel.selectStatus(UserMediaStatus.PLANNED)
            advanceUntilIdle()

            assertEquals(UserMediaStatus.PLANNED, viewModel.filter.value.status)
            assertEquals(LibraryFilter(status = UserMediaStatus.PLANNED), repository.appliedFilter)
        }

    @Test
    fun `selecting a sort applies the filter`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val viewModel = pagedViewModel(repository)

            viewModel.selectSort(LibrarySort.SCORE)
            advanceUntilIdle()

            assertEquals(LibrarySort.SCORE, viewModel.filter.value.sort)
            assertEquals(
                LibraryFilter(status = UserMediaStatus.PLANNED, sort = LibrarySort.SCORE),
                repository.appliedFilter,
            )
        }

    @Test
    fun `refreshOnReturn does nothing on All shows`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle()
            viewModel.selectStatus(null)
            advanceUntilIdle()

            viewModel.refreshOnReturn()
            advanceUntilIdle()

            assertEquals("only the PLANNED switch ran the paginator", 1, repository.applyFilterCalls)
        }

    @Test
    fun `a failed filter change surfaces an error and does not strand the selection`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val repository = FakeLibraryRepository()
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // let the first load settle before the filter under test fails

            repository.applyFilterFailure = failure
            viewModel.selectStatus(UserMediaStatus.WATCHING)
            advanceUntilIdle()

            // The tab the user tapped stays selected — a ViewModel that reverted `filter` here
            // would snap the tab row back to "All" underneath an error message that never
            // mentions the tab moved, which reads as the tap being silently ignored.
            assertEquals(UserMediaStatus.WATCHING, viewModel.filter.value.status)
            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `loadMore is not fired again while one is in flight`() =
        runTest(dispatcher) {
            // A LazyColumn fires its end-reached callback on every frame near the bottom. Without
            // a guard that is a request per frame; CursorPaginator's mutex makes them safe but
            // not free, and a queue of them blocks the next legitimate page.
            val repository = FakeLibraryRepository()
            val viewModel = pagedViewModel(repository)
            advanceUntilIdle() // let the status switch settle

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
            val viewModel = pagedViewModel(repository)
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
            val viewModel = pagedViewModel(repository)

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
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // let the status switch settle into Success

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
            val viewModel = pagedViewModel(repository)
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
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // the status switch fails -> Error
            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)

            viewModel.loadMore() // unaffected by applyFilterFailure; succeeds
            advanceUntilIdle()

            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `a failed reload AFTER a successful load shows the error, not stale rows`() =
        runTest(dispatcher) {
            // Once a load has genuinely succeeded, a later failure must not silently keep showing
            // the (now possibly outdated) rows as if nothing had gone wrong.
            val failure = IOException("offline")
            val repository = FakeLibraryRepository()
            repository.entries.value = listOf(ENTRY)
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // the first load succeeds

            repository.applyFilterFailure = failure
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `a failed filter switch never renders the previous filter's rows`() =
        runTest(dispatcher) {
            // PLANNED loads successfully, then applyFilter(WATCHING) throws. The fake's `entries`
            // (standing in for what LibraryRepositoryImpl.observeLibrary() would still be emitting -
            // CursorPaginator.restart() mutates nothing on a throw) still holds PLANNED's rows,
            // which must not render under the WATCHING tab.
            val failure = IOException("offline")
            val repository = FakeLibraryRepository()
            repository.entries.value = listOf(ENTRY)
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle() // PLANNED loads successfully

            repository.applyFilterFailure = failure
            viewModel.selectStatus(UserMediaStatus.WATCHING)
            advanceUntilIdle()

            assertEquals(LibraryUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `refreshOnReturn does nothing before the first load has finished`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.refreshOnReturn() // the status switch has not run yet
            advanceUntilIdle()

            assertEquals("only the status switch's own load", 1, repository.applyFilterCalls)
        }

    @Test
    fun `refreshOnReturn reloads once and never leaves the list`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.entries.value = listOf(ENTRY)
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            repository.applyFilterGate = gate
            viewModel.refreshOnReturn()
            advanceUntilIdle()
            assertTrue("the list must stay on screen while reloading", viewModel.state.value is LibraryUiState.Success)

            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(viewModel.state.value is LibraryUiState.Success)
            assertEquals(2, repository.applyFilterCalls)
        }

    @Test
    fun `a failed refreshOnReturn keeps the list`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.entries.value = listOf(ENTRY)
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle()

            repository.applyFilterFailure = IOException("offline")
            viewModel.refreshOnReturn()
            advanceUntilIdle()

            assertEquals(
                "an offline return must not replace the list with an error",
                LibraryUiState.Success(entries = listOf(ENTRY), loadingMore = false, pageError = null),
                viewModel.state.value,
            )
        }

    @Test
    fun `refreshOnReturn leaves a list paged past its first page alone`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.entries.value = listOf(ENTRY)
            repository.nextPage = listOf(SECOND_ENTRY)
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()

            viewModel.refreshOnReturn()
            advanceUntilIdle()

            assertEquals("reloading page 1 would drop the page the user scrolled to", 1, repository.applyFilterCalls)
            assertEquals(listOf(ENTRY, SECOND_ENTRY), (viewModel.state.value as LibraryUiState.Success).entries)
        }

    @Test
    fun `refreshOnReturn reloads again after a full refresh`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.entries.value = listOf(ENTRY)
            repository.nextPage = listOf(SECOND_ENTRY)
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.refreshOnReturn()
            advanceUntilIdle()

            assertEquals("switch, refresh, then the quiet reload", 3, repository.applyFilterCalls)
        }

    @Test
    fun `a loadMore that adds nothing still lets refreshOnReturn reload`() =
        runTest(dispatcher) {
            // A short list fits on screen, so its end trigger fires a loadMore on first layout
            // that finds the list exhausted: that must not count as paging past page 1.
            val repository = FakeLibraryRepository()
            repository.entries.value = listOf(ENTRY)
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()

            viewModel.refreshOnReturn()
            advanceUntilIdle()

            assertEquals(2, repository.applyFilterCalls)
        }

    @Test
    fun `a loadMore failing during refreshOnReturn keeps its page error`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.entries.value = listOf(ENTRY)
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }
            advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            repository.applyFilterGate = gate
            viewModel.refreshOnReturn()
            advanceUntilIdle()
            val failure = IOException("page 2 offline")
            repository.loadMoreFailure = failure
            viewModel.loadMore()
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(failure, (viewModel.state.value as LibraryUiState.Success).pageError)
        }

    private class FakeLibraryRepository(
        var applyFilterFailure: Throwable? = null,
        var loadMoreFailure: Throwable? = null,
        // Stands in for a hung connection (airplane mode: the request never completes, rather
        // than failing fast) — awaited BEFORE the failure/success check below, so a test can
        // suspend `applyFilter` mid-flight and inspect `LibraryViewModel.state` for exactly the
        // window a real round trip spends in flight, not just its eventual, settled result.
        // `null` (the default) behaves exactly as before this field existed: no suspension at all.
        var applyFilterGate: CompletableDeferred<Unit>? = null,
    ) : LibraryRepository {
        val entries = MutableStateFlow(emptyList<LibraryEntry>())

        var appliedFilter: LibraryFilter? = null
            private set

        var loadMoreCalls = 0
            private set

        var applyFilterCalls = 0

        override fun observeLibrary(): Flow<List<LibraryEntry>> = entries

        // Never exercised directly by LibraryViewModel: every single-status load — the first one and
        // every tab/sort change — goes through applyFilter, so the ViewModel's own idea of "what
        // filter is selected" and what the repository was actually asked for can never drift
        // apart the way LibraryRepositoryImpl's own KDoc warns applyFilter/refresh can (task
        // 9a.5's carried-forward note).
        override suspend fun refresh(): Unit = error("not exercised by LibraryViewModel; it always calls applyFilter")

        // Home (HomeSectionsTest covers it) loads on construction; an empty library keeps it quiet.
        override fun observeHomeCache(): Flow<Map<LibrarySection, List<LibraryEntry>>> = flowOf(emptyMap())

        override suspend fun refreshHome(
            sort: LibrarySort,
            perSection: Int,
        ): Map<LibrarySection, Page<LibraryEntry>> = emptyMap()

        override suspend fun sectionPage(
            section: LibrarySection,
            sort: LibrarySort,
            cursor: String?,
            limit: Int,
        ): Page<LibraryEntry> = Page(emptyList(), nextCursor = null)

        // Appended to [entries] by a successful loadMore; empty stands for an exhausted list.
        var nextPage: List<LibraryEntry> = emptyList()

        override suspend fun loadMore() {
            loadMoreFailure?.let { throw it }
            loadMoreCalls++
            entries.value += nextPage
        }

        override suspend fun applyFilter(filter: LibraryFilter) {
            applyFilterCalls++
            applyFilterGate?.await()
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

        override suspend fun remove(entryId: String): Unit = error("not used here")

        override suspend fun watchedEpisodes(entryId: String): Set<String> = error("not used here")

        override suspend fun setWatched(
            entryId: String,
            episodeIds: Collection<String>,
            watched: Boolean,
        ): LibraryEntry = error("not used here")

        override suspend fun update(
            entryId: String,
            patch: LibraryPatch,
        ): LibraryEntry = error("not exercised by LibraryViewModel")

        override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("not exercised by LibraryViewModel")

        override suspend fun favoritesPage(
            type: MediaType?,
            sort: LibrarySort,
            cursor: String?,
            limit: Int,
        ): Page<LibraryEntry> = Page(emptyList(), null)

        var stats: LibraryStats? = null
        var upcoming: List<LibraryEntry> = emptyList()

        override suspend fun libraryStats(): LibraryStats = stats ?: throw IOException("stats offline")

        override suspend fun upcomingWatching(limit: Int): List<LibraryEntry> = upcoming

        override suspend fun allWatching(): List<LibraryEntry> = emptyList()

        override suspend fun importAniList(username: String) = error("not exercised by LibraryViewModel")
    }

    @Test
    fun `refreshOverview loads the counts and keeps only titles with a next episode`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val noNextEpisode =
                ENTRY.copy(id = "entry-2", media = ENTRY.media.copy(id = "media-2", nextEpisodeDate = null))
            repository.stats = STATS
            repository.upcoming = listOf(ENTRY, noNextEpisode)
            val viewModel = pagedViewModel(repository)

            viewModel.refreshOverview()
            advanceUntilIdle()

            assertEquals(STATS, viewModel.stats.value)
            assertEquals(listOf(ENTRY), viewModel.airingSoon.value)
        }

    @Test
    fun `a failed overview leaves no count and no airing row, and the list unaffected`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val viewModel = pagedViewModel(repository)
            backgroundScope.launch { viewModel.state.collect {} }

            viewModel.refreshOverview()
            advanceUntilIdle()

            assertNull(viewModel.stats.value)
            assertEquals(emptyList<LibraryEntry>(), viewModel.airingSoon.value)
            assertTrue(viewModel.state.value is LibraryUiState.Success)
        }

    /**
     * A ViewModel already switched to one status: "All shows" is Home (see HomeSectionsTest), so
     * the paged list under test only runs once a status is picked. That switch is the list's first
     * `applyFilter` call.
     */
    private fun pagedViewModel(repository: LibraryRepository) =
        LibraryViewModel(repository).also { it.selectStatus(UserMediaStatus.PLANNED) }

    private companion object {
        val STATS =
            LibraryStats(
                total = 9,
                byStatus = mapOf(UserMediaStatus.WATCHING to 9),
                averageScore = null,
                ratedCount = 0,
                episodesWatched = 0,
                topGenres = emptyList(),
                addedThisMonth = 0,
                favorites = 0,
            )

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

        val SECOND_ENTRY = ENTRY.copy(id = "entry-2", media = ENTRY.media.copy(id = "media-2"))
    }
}
