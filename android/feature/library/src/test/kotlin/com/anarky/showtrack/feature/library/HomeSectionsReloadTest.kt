package com.anarky.showtrack.feature.library

import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.model.LibrarySection
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.feature.library.HomeFixtures.entries
import com.anarky.showtrack.feature.library.HomeFixtures.entry
import com.anarky.showtrack.feature.library.HomeFixtures.pages
import com.anarky.showtrack.feature.library.HomeFixtures.stats
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
 * Home across reloads: how expanded sections come back (one bounded request each), how a failed
 * extra-rows fetch stays inside its own section, and the retry and old-server paths. Same setup as
 * [HomeSectionsTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSectionsReloadTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `expanded section stays expanded across a resume and picks up new rows`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages =
                mapOf(
                    LibrarySection.WATCHING to entries("w", LibrarySection.WATCHING, 12, cursor = "w-1"),
                    LibrarySection.CAUGHT_UP to entries("c", LibrarySection.CAUGHT_UP, 2),
                    LibrarySection.PLANNED to entries("p", LibrarySection.PLANNED, 12, cursor = "p-1"),
                )
            repository.sectionPages =
                mapOf(
                    (LibrarySection.WATCHING to "w-1") to Page(listOf(entry("w13", LibrarySection.WATCHING)), null),
                    (LibrarySection.PLANNED to "p-1") to entries("p-more", LibrarySection.PLANNED, 3),
                )
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            viewModel.toggleSection(LibrarySection.WATCHING)
            viewModel.toggleSection(LibrarySection.PLANNED)
            advanceUntilIdle()
            assertEquals(15, viewModel.section(LibrarySection.PLANNED).entries.size)

            // Server side meanwhile: one more Planned show.
            repository.sectionPages =
                mapOf(
                    (LibrarySection.WATCHING to null) to entries("w", LibrarySection.WATCHING, 13),
                    (LibrarySection.CAUGHT_UP to null) to entries("c", LibrarySection.CAUGHT_UP, 2),
                    (LibrarySection.PLANNED to null) to entries("p", LibrarySection.PLANNED, 16),
                )
            repository.sectionPageCalls.clear()
            viewModel.refreshOverview()
            advanceUntilIdle()

            val planned = viewModel.section(LibrarySection.PLANNED)
            assertTrue(planned.expanded)
            assertEquals(16, planned.entries.size)
            assertTrue(planned.entries.any { it.id == "p16" })
            assertFalse(planned.hasMore)
            assertTrue(viewModel.section(LibrarySection.WATCHING).expanded)
            assertEquals(15, viewModel.section(LibrarySection.WATCHING).entries.size)
            assertEquals(
                "one request per expanded raw section, sized to what it had loaded",
                setOf(
                    FakeLibraryRepository.SectionPageCall(LibrarySection.WATCHING, LibrarySort.TITLE, null, 13),
                    FakeLibraryRepository.SectionPageCall(LibrarySection.CAUGHT_UP, LibrarySort.TITLE, null, 12),
                    FakeLibraryRepository.SectionPageCall(LibrarySection.PLANNED, LibrarySort.TITLE, null, 15),
                ),
                repository.sectionPageCalls.toSet(),
            )
            assertEquals(3, repository.sectionPageCalls.size)
        }

    @Test
    fun `expanded section beyond 100 rows comes back with Show all after a resume`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages =
                mapOf(LibrarySection.PLANNED to entries("p", LibrarySection.PLANNED, 12, cursor = "p-1"))
            repository.sectionPages =
                mapOf(
                    (LibrarySection.PLANNED to "p-1") to entries("a", LibrarySection.PLANNED, 100, cursor = "p-2"),
                    (LibrarySection.PLANNED to "p-2") to entries("b", LibrarySection.PLANNED, 10),
                )
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            viewModel.toggleSection(LibrarySection.PLANNED)
            advanceUntilIdle()
            assertEquals(122, viewModel.section(LibrarySection.PLANNED).entries.size)

            repository.sectionPages =
                repository.sectionPages +
                mapOf(
                    (LibrarySection.PLANNED to null) to entries("p", LibrarySection.PLANNED, 100, cursor = "r-1"),
                    (LibrarySection.PLANNED to "r-1") to entries("r", LibrarySection.PLANNED, 22),
                )
            repository.sectionPageCalls.clear()
            viewModel.refreshOverview()
            advanceUntilIdle()

            val resumed = viewModel.section(LibrarySection.PLANNED)
            assertTrue(resumed.expanded)
            assertEquals(100, resumed.entries.size)
            assertTrue("the rest is one tap away", resumed.hasMore)
            assertEquals(
                listOf(FakeLibraryRepository.SectionPageCall(LibrarySection.PLANNED, LibrarySort.TITLE, null, 100)),
                repository.sectionPageCalls,
            )

            viewModel.toggleSection(LibrarySection.PLANNED)
            advanceUntilIdle()
            val all = viewModel.section(LibrarySection.PLANNED)
            assertTrue(all.expanded)
            assertEquals(122, all.entries.size)
            assertFalse(all.hasMore)
        }

    @Test
    fun `a failing rest fetch during a reload keeps the other sections fresh`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages =
                mapOf(
                    LibrarySection.PLANNED to entries("p", LibrarySection.PLANNED, 12, cursor = "p-1"),
                    LibrarySection.COMPLETED to entries("d", LibrarySection.COMPLETED, 12, cursor = "d-1"),
                    LibrarySection.PAUSED to entries("h", LibrarySection.PAUSED, 1),
                )
            repository.sectionPages =
                mapOf(
                    (LibrarySection.PLANNED to "p-1") to entries("p-more", LibrarySection.PLANNED, 2),
                    (LibrarySection.COMPLETED to "d-1") to entries("d-more", LibrarySection.COMPLETED, 2),
                )
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            viewModel.toggleSection(LibrarySection.PLANNED)
            viewModel.toggleSection(LibrarySection.COMPLETED)
            advanceUntilIdle()

            val newPlanned = entries("np", LibrarySection.PLANNED, 12, cursor = "np-1")
            repository.homePages =
                mapOf(
                    LibrarySection.PLANNED to newPlanned,
                    LibrarySection.COMPLETED to entries("nd", LibrarySection.COMPLETED, 12, cursor = "nd-1"),
                    LibrarySection.PAUSED to entries("nh", LibrarySection.PAUSED, 2),
                )
            repository.sectionPages =
                mapOf((LibrarySection.COMPLETED to null) to entries("nd", LibrarySection.COMPLETED, 15))
            val failure = IOException("timeout")
            repository.sectionPageFailures = mapOf(LibrarySection.PLANNED to failure)
            viewModel.refreshOverview()
            advanceUntilIdle()

            val state = viewModel.ready()
            assertFalse(state.isStale)
            val planned = viewModel.section(LibrarySection.PLANNED)
            assertEquals(failure, planned.error)
            assertFalse(planned.expanded)
            assertFalse(planned.loadingMore)
            assertEquals(newPlanned.items, planned.entries)
            val completed = viewModel.section(LibrarySection.COMPLETED)
            assertTrue(completed.expanded)
            assertEquals(15, completed.entries.size)
            assertNull(completed.error)
            assertEquals(2, viewModel.section(LibrarySection.PAUSED).entries.size)
        }

    @Test
    fun `a section expanded over the cache comes back expanded with one bounded fetch after a retry`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homeCache.value = mapOf(LibrarySection.PLANNED to entries("c", LibrarySection.PLANNED, 15).items)
            repository.refreshHomeFailure = IOException("offline")
            repository.sectionPages =
                mapOf((LibrarySection.PLANNED to null) to entries("live", LibrarySection.PLANNED, 20))
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            viewModel.toggleSection(LibrarySection.PLANNED)
            advanceUntilIdle()
            assertTrue(viewModel.section(LibrarySection.PLANNED).expanded)

            repository.refreshHomeFailure = null
            repository.homePages =
                mapOf(LibrarySection.PLANNED to entries("live", LibrarySection.PLANNED, 12, cursor = "l-1"))
            repository.sectionPageCalls.clear()
            viewModel.refresh()
            advanceUntilIdle()

            val planned = viewModel.section(LibrarySection.PLANNED)
            assertFalse(viewModel.ready().isStale)
            assertTrue(planned.expanded)
            assertEquals(20, planned.entries.size)
            assertEquals(
                listOf(FakeLibraryRepository.SectionPageCall(LibrarySection.PLANNED, LibrarySort.TITLE, null, 20)),
                repository.sectionPageCalls,
            )
        }

    @Test
    fun `a full live page with a cursor offers Show all even without stats`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages =
                mapOf(LibrarySection.PLANNED to entries("p", LibrarySection.PLANNED, 12, cursor = "p-1"))
            repository.stats = stats(LibrarySection.PLANNED to 12) // stale: one was added since
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            viewModel.refreshOverview()
            advanceUntilIdle()

            assertTrue(viewModel.section(LibrarySection.PLANNED).hasMore)
        }

    @Test
    fun `entries from another section are left out when the server ignores the section filter`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val planned = entry("p1", LibrarySection.PLANNED)
            val dropped = entry("d1", LibrarySection.DROPPED)
            val wholeLibrary = Page(listOf(planned, dropped), nextCursor = null)
            repository.homePages =
                LibrarySection.homeOrder.associateWith { wholeLibrary } + (LibrarySection.CAUGHT_UP to wholeLibrary)
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()

            assertEquals(
                listOf(LibrarySection.PLANNED, LibrarySection.DROPPED),
                viewModel.ready().sections.map { it.section },
            )
            assertEquals(listOf(planned), viewModel.section(LibrarySection.PLANNED).entries)
            assertEquals(listOf(dropped), viewModel.section(LibrarySection.DROPPED).entries)
        }

    @Test
    fun `refresh on home also reloads the counts and airing soon`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            repository.homePages = pages(LibrarySection.PLANNED to listOf(entry("p1", LibrarySection.PLANNED)))
            repository.stats = stats(LibrarySection.PLANNED to 1)
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            val statsBefore = repository.statsCalls
            val upcomingBefore = repository.upcomingCalls
            repository.refreshHomeSorts.clear()

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(listOf(LibrarySort.TITLE), repository.refreshHomeSorts)
            assertEquals(statsBefore + 1, repository.statsCalls)
            assertEquals(upcomingBefore + 1, repository.upcomingCalls)
        }
}
