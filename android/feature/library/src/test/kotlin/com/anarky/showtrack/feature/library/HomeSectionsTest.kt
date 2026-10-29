package com.anarky.showtrack.feature.library

import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.model.LibrarySection
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.feature.library.HomeFixtures.entries
import com.anarky.showtrack.feature.library.HomeFixtures.entry
import com.anarky.showtrack.feature.library.HomeFixtures.pages
import com.anarky.showtrack.feature.library.HomeFixtures.stats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * Home's sections, driven through [LibraryViewModel] (which owns the controller) against the
 * shared [FakeLibraryRepository]. `home` is a plain `StateFlow`, so `.value` is readable without a
 * collector.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSectionsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `sections follow home order and hide empty ones`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages =
                pages(
                    LibrarySection.DROPPED to listOf(entry("d1", LibrarySection.DROPPED)),
                    LibrarySection.PLANNED to listOf(entry("p1", LibrarySection.PLANNED)),
                    LibrarySection.WATCHING to listOf(entry("w1", LibrarySection.WATCHING)),
                    LibrarySection.PAUSED to emptyList(),
                )
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()

            assertEquals(
                listOf(LibrarySection.WATCHING, LibrarySection.PLANNED, LibrarySection.DROPPED),
                viewModel.ready().sections.map { it.section },
            )
        }

    @Test
    fun `caught up entries come after watching entries in the watching section`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val watching = entry("w1", LibrarySection.WATCHING)
            val caughtUp = entry("c1", LibrarySection.CAUGHT_UP)
            repository.homePages =
                pages(LibrarySection.CAUGHT_UP to listOf(caughtUp), LibrarySection.WATCHING to listOf(watching))
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()

            val sections = viewModel.ready().sections
            assertEquals("caught up has no section of its own", 1, sections.size)
            assertEquals(listOf(watching, caughtUp), sections.single().entries)
        }

    @Test
    fun `watching count adds caught up count`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages =
                mapOf(
                    LibrarySection.WATCHING to entries("w", LibrarySection.WATCHING, 12, cursor = "w-next"),
                    LibrarySection.CAUGHT_UP to Page(listOf(entry("c1", LibrarySection.CAUGHT_UP)), nextCursor = null),
                )
            repository.stats = stats(LibrarySection.WATCHING to 14, LibrarySection.CAUGHT_UP to 2)
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            assertNull("no count before the stats arrive", viewModel.section(LibrarySection.WATCHING).count)

            viewModel.refreshOverview()
            advanceUntilIdle()

            val watching = viewModel.section(LibrarySection.WATCHING)
            assertEquals(16, watching.count)
            assertTrue(watching.hasMore)
            assertEquals(12, watching.collapsedEntries.size)
        }

    @Test
    fun `cache shows as stale until the network answers`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val cached = entries("cached", LibrarySection.PLANNED, 15).items
            repository.homeCache.value = mapOf(LibrarySection.PLANNED to cached)
            val live = entry("live", LibrarySection.PLANNED)
            repository.homePages = pages(LibrarySection.PLANNED to listOf(live))
            val gate = CompletableDeferred<Unit>()
            repository.refreshHomeGate = gate
            val viewModel = LibraryViewModel(repository)
            runCurrent()

            val stale = viewModel.ready()
            assertTrue(stale.isStale)
            // Edits upsert into the cache, so it can hold more than a page: show at most twelve.
            assertEquals(cached.take(12), stale.sections.single().entries)

            gate.complete(Unit)
            advanceUntilIdle()

            val fresh = viewModel.ready()
            assertFalse(fresh.isStale)
            assertEquals(listOf(live), fresh.sections.single().entries)
        }

    @Test
    fun `failed refresh keeps cached sections marked stale`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val cached = entry("cached", LibrarySection.PAUSED)
            repository.homeCache.value = mapOf(LibrarySection.PAUSED to listOf(cached))
            repository.refreshHomeFailure = IOException("offline")
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()

            val state = viewModel.ready()
            assertTrue(state.isStale)
            assertEquals(listOf(cached), state.sections.single().entries)
        }

    @Test
    fun `failed refresh with nothing cached is an error`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val failure = IOException("offline")
            repository.refreshHomeFailure = failure
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()

            assertEquals(HomeUiState.Error(failure), viewModel.home.value)
        }

    @Test
    fun `show all loads remaining pages then expands`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val firstWatching = entries("w", LibrarySection.WATCHING, 12, cursor = "w-1")
            val firstCaughtUp = entries("c", LibrarySection.CAUGHT_UP, 2, cursor = "c-1")
            repository.homePages =
                mapOf(LibrarySection.WATCHING to firstWatching, LibrarySection.CAUGHT_UP to firstCaughtUp)
            val moreWatching1 = entry("w-more-1", LibrarySection.WATCHING)
            val moreWatching2 = entry("w-more-2", LibrarySection.WATCHING)
            val moreCaughtUp = entry("c-more", LibrarySection.CAUGHT_UP)
            repository.sectionPages =
                mapOf(
                    (LibrarySection.WATCHING to "w-1") to Page(listOf(moreWatching1), nextCursor = "w-2"),
                    (LibrarySection.WATCHING to "w-2") to Page(listOf(moreWatching2), nextCursor = null),
                    (LibrarySection.CAUGHT_UP to "c-1") to Page(listOf(moreCaughtUp), nextCursor = null),
                )
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            repository.sectionPageGate = gate

            viewModel.toggleSection(LibrarySection.WATCHING)
            runCurrent()
            val loading = viewModel.section(LibrarySection.WATCHING)
            assertTrue(loading.loadingMore)
            assertFalse("expands only once the pages are in", loading.expanded)

            gate.complete(Unit)
            advanceUntilIdle()

            val expanded = viewModel.section(LibrarySection.WATCHING)
            assertTrue(expanded.expanded)
            assertFalse(expanded.loadingMore)
            assertNull(expanded.nextCursor)
            assertEquals(
                firstWatching.items + moreWatching1 + moreWatching2 + firstCaughtUp.items + moreCaughtUp,
                expanded.entries,
            )
            assertEquals(
                listOf(
                    FakeLibraryRepository.SectionPageCall(LibrarySection.WATCHING, LibrarySort.TITLE, "w-1", 100),
                    FakeLibraryRepository.SectionPageCall(LibrarySection.WATCHING, LibrarySort.TITLE, "w-2", 100),
                    FakeLibraryRepository.SectionPageCall(LibrarySection.CAUGHT_UP, LibrarySort.TITLE, "c-1", 100),
                ),
                repository.sectionPageCalls,
            )
        }

    @Test
    fun `a failed page marks only its own section`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages =
                mapOf(
                    LibrarySection.PLANNED to entries("p", LibrarySection.PLANNED, 12, cursor = "p-1"),
                    LibrarySection.COMPLETED to entries("d", LibrarySection.COMPLETED, 12, cursor = "d-1"),
                )
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            val failure = IOException("offline")
            repository.sectionPageFailure = failure

            viewModel.toggleSection(LibrarySection.PLANNED)
            advanceUntilIdle()

            val planned = viewModel.section(LibrarySection.PLANNED)
            assertEquals(failure, planned.error)
            assertFalse(planned.expanded)
            assertFalse(planned.loadingMore)
            assertNull(viewModel.section(LibrarySection.COMPLETED).error)
        }

    @Test
    fun `show less collapses without refetching`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages =
                mapOf(LibrarySection.PLANNED to entries("p", LibrarySection.PLANNED, 12, cursor = "p-1"))
            val more = entry("p-more", LibrarySection.PLANNED)
            repository.sectionPages = mapOf((LibrarySection.PLANNED to "p-1") to Page(listOf(more), nextCursor = null))
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            viewModel.toggleSection(LibrarySection.PLANNED)
            advanceUntilIdle()

            viewModel.toggleSection(LibrarySection.PLANNED)
            advanceUntilIdle()
            val collapsed = viewModel.section(LibrarySection.PLANNED)
            assertFalse(collapsed.expanded)
            assertEquals("loaded entries are kept", 13, collapsed.entries.size)

            viewModel.toggleSection(LibrarySection.PLANNED)
            advanceUntilIdle()
            assertTrue(viewModel.section(LibrarySection.PLANNED).expanded)
            assertEquals("expanding again needs no fetch", 1, repository.sectionPageCalls.size)
        }

    @Test
    fun `sort change collapses and reloads without reusing old cursors`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages =
                mapOf(LibrarySection.PLANNED to entries("p", LibrarySection.PLANNED, 12, cursor = "title-1"))
            val late = Page(listOf(entry("late", LibrarySection.PLANNED)), nextCursor = null)
            repository.sectionPages = mapOf((LibrarySection.PLANNED to "title-1") to late)
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            repository.sectionPageGate = gate
            viewModel.toggleSection(LibrarySection.PLANNED)
            runCurrent() // the Title-sort expansion is in flight

            repository.homePages =
                mapOf(LibrarySection.PLANNED to entries("s", LibrarySection.PLANNED, 12, cursor = "score-1"))
            viewModel.selectSort(LibrarySort.SCORE)
            gate.complete(Unit) // the old expansion's page lands after the sort change
            advanceUntilIdle()

            assertEquals(listOf(LibrarySort.TITLE, LibrarySort.SCORE), repository.refreshHomeSorts)
            val afterSort = viewModel.section(LibrarySection.PLANNED)
            assertFalse(afterSort.expanded)
            assertFalse(afterSort.loadingMore)
            assertEquals("score-1", afterSort.nextCursor)
            assertEquals("the old-sort page is discarded", 12, afterSort.entries.size)

            repository.sectionPageCalls.clear()
            viewModel.toggleSection(LibrarySection.PLANNED)
            advanceUntilIdle()
            assertEquals(
                listOf(
                    FakeLibraryRepository.SectionPageCall(LibrarySection.PLANNED, LibrarySort.SCORE, "score-1", 100),
                ),
                repository.sectionPageCalls,
            )
        }

    @Test
    fun `a show all tapped during a reload expands against the new pages`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages =
                mapOf(LibrarySection.PLANNED to entries("p", LibrarySection.PLANNED, 12, cursor = "old"))
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            val reload = CompletableDeferred<Unit>()
            repository.refreshHomeGate = reload
            repository.homePages =
                mapOf(LibrarySection.PLANNED to entries("n", LibrarySection.PLANNED, 12, cursor = "new"))
            val more = entry("n-more", LibrarySection.PLANNED)
            repository.sectionPages = mapOf((LibrarySection.PLANNED to "new") to Page(listOf(more), nextCursor = null))
            viewModel.refreshOverview() // quiet reload, in flight
            runCurrent()

            viewModel.toggleSection(LibrarySection.PLANNED)
            runCurrent()
            assertTrue("waits on the reload", viewModel.section(LibrarySection.PLANNED).loadingMore)
            reload.complete(Unit)
            advanceUntilIdle()

            val planned = viewModel.section(LibrarySection.PLANNED)
            assertTrue(planned.expanded)
            assertFalse(planned.loadingMore)
            assertEquals(13, planned.entries.size)
            assertEquals(
                "never the superseded cursor",
                listOf(FakeLibraryRepository.SectionPageCall(LibrarySection.PLANNED, LibrarySort.TITLE, "new", 100)),
                repository.sectionPageCalls,
            )
        }

    @Test
    fun `a show all in flight survives a failed quiet reload`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages =
                mapOf(LibrarySection.PLANNED to entries("p", LibrarySection.PLANNED, 12, cursor = "p-1"))
            val more = entry("p-more", LibrarySection.PLANNED)
            repository.sectionPages = mapOf((LibrarySection.PLANNED to "p-1") to Page(listOf(more), nextCursor = null))
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            val page = CompletableDeferred<Unit>()
            repository.sectionPageGate = page
            viewModel.toggleSection(LibrarySection.PLANNED)
            runCurrent()

            repository.refreshHomeFailure = IOException("offline")
            viewModel.refreshOverview() // the resume: a quiet reload that fails
            page.complete(Unit)
            advanceUntilIdle()

            val planned = viewModel.section(LibrarySection.PLANNED)
            assertFalse("no spinner left behind", planned.loadingMore)
            assertTrue(planned.expanded)
            assertEquals(13, planned.entries.size)
        }

    @Test
    fun `sort change shows loading, not the cache, until the network answers`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homeCache.value =
                mapOf(LibrarySection.PLANNED to listOf(entry("cached", LibrarySection.PLANNED)))
            repository.homePages = pages(LibrarySection.PLANNED to listOf(entry("title", LibrarySection.PLANNED)))
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            repository.refreshHomeGate = gate
            val scored = entry("scored", LibrarySection.PLANNED)
            repository.homePages = pages(LibrarySection.PLANNED to listOf(scored))

            viewModel.selectSort(LibrarySort.SCORE)
            runCurrent()
            assertEquals(HomeUiState.Loading, viewModel.home.value)

            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(viewModel.ready().isStale)
            assertEquals(listOf(scored), viewModel.section(LibrarySection.PLANNED).entries)
        }

    @Test
    fun `a failed sort change falls back to the cache, marked stale`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val cached = entry("cached", LibrarySection.PLANNED)
            repository.homeCache.value = mapOf(LibrarySection.PLANNED to listOf(cached))
            repository.homePages = pages(LibrarySection.PLANNED to listOf(entry("title", LibrarySection.PLANNED)))
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()

            repository.refreshHomeFailure = IOException("offline")
            viewModel.selectSort(LibrarySort.SCORE)
            advanceUntilIdle()

            assertTrue(viewModel.ready().isStale)
            assertEquals(listOf(cached), viewModel.section(LibrarySection.PLANNED).entries)
        }

    @Test
    fun `a section expanded over the cache collapses when a retry falls back to the cache`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homeCache.value = mapOf(LibrarySection.PLANNED to entries("c", LibrarySection.PLANNED, 15).items)
            repository.refreshHomeFailure = IOException("offline")
            repository.sectionPages =
                mapOf((LibrarySection.PLANNED to null) to entries("live", LibrarySection.PLANNED, 15))
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            viewModel.toggleSection(LibrarySection.PLANNED) // the network answers this one
            advanceUntilIdle()
            assertTrue(viewModel.section(LibrarySection.PLANNED).expanded)

            viewModel.refresh() // still offline for the first pages
            advanceUntilIdle()

            val planned = viewModel.section(LibrarySection.PLANNED)
            assertFalse("twelve cached rows must not show as expanded", planned.expanded)
            assertEquals(12, planned.entries.size)
            assertFalse(planned.loadingMore)
        }

    @Test
    fun `entries with unknown section are left off home`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val known = entry("known", LibrarySection.PLANNED)
            repository.homeCache.value =
                mapOf(
                    LibrarySection.PLANNED to listOf(known),
                    LibrarySection.UNKNOWN to listOf(entry("mystery", LibrarySection.UNKNOWN)),
                )
            repository.refreshHomeFailure = IOException("offline")
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()

            assertEquals(listOf(known), viewModel.ready().sections.flatMap { it.entries })
        }

    @Test
    fun `the resume refresh reloads home so changes made elsewhere show up`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages = pages(LibrarySection.PLANNED to listOf(entry("p1", LibrarySection.PLANNED)))
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()

            val added = entry("added", LibrarySection.PLANNED)
            repository.homePages = pages(LibrarySection.PLANNED to listOf(entry("p1", LibrarySection.PLANNED), added))
            viewModel.refreshOverview()
            advanceUntilIdle()

            assertEquals(2, viewModel.section(LibrarySection.PLANNED).entries.size)
            assertFalse(viewModel.ready().isStale)
        }

    @Test
    fun `a failed resume refresh keeps the live sections`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages = pages(LibrarySection.PLANNED to listOf(entry("p1", LibrarySection.PLANNED)))
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            val before = viewModel.home.value

            repository.refreshHomeFailure = IOException("offline")
            viewModel.refreshOverview()
            advanceUntilIdle()

            assertEquals(before, viewModel.home.value)
        }

    @Test
    fun `a single status view never loads home`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            viewModel.selectStatus(UserMediaStatus.PLANNED)
            advanceUntilIdle()
            repository.refreshHomeSorts.clear()

            viewModel.refreshOverview()
            viewModel.selectSort(LibrarySort.SCORE)
            advanceUntilIdle()

            assertEquals(emptyList<LibrarySort>(), repository.refreshHomeSorts)
        }

    @Test
    fun `re-selecting the current sort mid sort change keeps loading, not the cache`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homeCache.value =
                mapOf(LibrarySection.PLANNED to listOf(entry("cached", LibrarySection.PLANNED)))
            repository.homePages = pages(LibrarySection.PLANNED to listOf(entry("title", LibrarySection.PLANNED)))
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            repository.refreshHomeGate = CompletableDeferred()
            viewModel.selectSort(LibrarySort.SCORE)
            runCurrent()
            repository.refreshHomeSorts.clear()

            viewModel.selectSort(LibrarySort.SCORE)
            runCurrent()

            assertEquals(HomeUiState.Loading, viewModel.home.value)
            assertEquals(emptyList<LibrarySort>(), repository.refreshHomeSorts)
        }

    @Test
    fun `re-selecting the current status does not reload`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages = pages(LibrarySection.PLANNED to listOf(entry("p1", LibrarySection.PLANNED)))
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            val before = viewModel.home.value
            repository.refreshHomeSorts.clear()

            viewModel.selectStatus(null)
            advanceUntilIdle()

            assertEquals(emptyList<LibrarySort>(), repository.refreshHomeSorts)
            assertEquals(before, viewModel.home.value)
        }
}

internal fun LibraryViewModel.ready(): HomeUiState.Ready = home.value as HomeUiState.Ready

internal fun LibraryViewModel.section(section: LibrarySection): HomeSection =
    ready().sections.single { it.section == section }
