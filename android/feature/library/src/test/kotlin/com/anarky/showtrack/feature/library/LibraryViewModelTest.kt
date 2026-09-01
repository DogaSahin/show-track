package com.anarky.showtrack.feature.library

import app.cash.turbine.test
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun `entries starts empty and then mirrors the repository`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val viewModel = LibraryViewModel(repository)

            viewModel.entries.test {
                // stateIn's initialValue: the screen renders before the first upstream emission
                // rather than waiting on it.
                assertEquals(emptyList<LibraryEntry>(), awaitItem())
                repository.entries.value = listOf(ENTRY)
                assertEquals(listOf(ENTRY), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a failing refresh is captured instead of escaping the coroutine`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(refreshFailure = failure)
            val viewModel = LibraryViewModel(repository)

            viewModel.refresh()
            advanceUntilIdle()

            // The alternative this asserts against is not "no error shown" but "process killed":
            // an exception thrown inside viewModelScope.launch reaches the thread's uncaught
            // handler, and on Android that is a crash.
            assertSame(failure, viewModel.lastError.value)
        }

    @Test
    fun `a later success clears the previous failure`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshFailure = IOException("offline"))
            val viewModel = LibraryViewModel(repository)

            viewModel.refresh()
            advanceUntilIdle()
            repository.refreshFailure = null
            viewModel.refresh()
            advanceUntilIdle()

            assertNull(viewModel.lastError.value)
        }

    private class FakeLibraryRepository(
        var refreshFailure: Throwable? = null,
    ) : LibraryRepository {
        val entries = MutableStateFlow(emptyList<LibraryEntry>())

        override fun observeLibrary(): Flow<List<LibraryEntry>> = entries

        override suspend fun refresh() {
            refreshFailure?.let { throw it }
        }

        override suspend fun loadMore() = Unit

        // Filters and writes are task 9a.8/9a.9's to exercise, once the library and detail
        // screens actually call them. `error(...)` rather than a silent no-op: this ViewModel
        // does not call these yet, so a test that reached one would be testing something that
        // does not exist.
        override suspend fun applyFilter(filter: LibraryFilter): Unit = error("not exercised by LibraryViewModel yet")

        override suspend fun add(
            source: MediaSource,
            externalId: String,
        ): LibraryEntry = error("not exercised by LibraryViewModel yet")

        override suspend fun update(
            entryId: String,
            patch: LibraryPatch,
        ): LibraryEntry = error("not exercised by LibraryViewModel yet")

        override suspend fun entryForMedia(mediaId: String): LibraryEntry? =
            error("not exercised by LibraryViewModel yet")
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
