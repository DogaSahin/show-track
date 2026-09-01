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
            advanceUntilIdle()

            repository.applyFilterFailure = null
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(LibraryUiState.Success(entries = emptyList(), loadingMore = false), viewModel.state.value)
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
            val repository = FakeLibraryRepository()
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()

            viewModel.state.test {
                assertEquals(LibraryUiState.Success(entries = emptyList(), loadingMore = false), awaitItem())
                viewModel.loadMore()
                assertEquals(LibraryUiState.Success(entries = emptyList(), loadingMore = true), awaitItem())
                advanceUntilIdle()
                assertEquals(LibraryUiState.Success(entries = emptyList(), loadingMore = false), awaitItem())
                assertTrue(repository.loadMoreCalls == 1)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private class FakeLibraryRepository(
        var applyFilterFailure: Throwable? = null,
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
