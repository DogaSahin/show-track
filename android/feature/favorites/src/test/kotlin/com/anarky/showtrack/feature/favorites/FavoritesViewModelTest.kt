package com.anarky.showtrack.feature.favorites

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * The ViewModel is exercised against a FAKE `LibraryRepository` — nothing here knows Retrofit
 * exists, mirroring `DiscoverViewModelTest`'s shape (the ViewModel this one is closest to: a
 * plain `MutableStateFlow`, no Room-backed upstream, one-shot suspend calls the ViewModel drives
 * itself).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The fake's `observeLibrary()`/`refresh()` — the GENERAL-purpose surface — both `error(...)`.
     * A [FavoritesViewModel] that accidentally called through the general library view instead of
     * [LibraryRepository.favoriteEntries]/[LibraryRepository.refreshFavorites] would therefore fail
     * this test LOUDLY, with that error message, rather than silently returning the wrong list —
     * the "fake that ignores its argument makes the test vacuous" trap the brief warns about,
     * adapted to this repository's shape: `favorite = true` is baked into
     * [LibraryRepositoryImplTest]'s own dedicated paginator (asserted there, at the layer that
     * actually issues the query), so what THIS test must pin is that the ViewModel reaches that
     * paginator's surface at all, and never the general one.
     */
    @Test
    fun `the feed requests only favourites`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val viewModel = FavoritesViewModel(repository)
            advanceUntilIdle()

            assertEquals(
                FavoritesUiState.Success(entries = listOf(FRIEREN, BEBOP)),
                viewModel.state.value,
            )
        }

    /**
     * The acceptance criterion (brief, task 9b.4): favouriting/unfavouriting happens on Detail or
     * Library, never on this screen, so this screen's only way to learn about it is a fresh
     * [FavoritesViewModel.refresh] re-reading `favorite=true` from the server. A title dropped
     * from that response must disappear from [FavoritesUiState.Success.entries].
     */
    @Test
    fun `unfavouriting elsewhere removes the entry from this view on refresh`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val viewModel = FavoritesViewModel(repository)
            advanceUntilIdle()
            assertEquals(listOf(FRIEREN, BEBOP), (viewModel.state.value as FavoritesUiState.Success).entries)

            // FRIEREN was unfavourited from Detail/Library in the meantime; the server no longer
            // includes it in favorite=true.
            repository.refreshResult = listOf(BEBOP)
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                FavoritesUiState.Success(entries = listOf(BEBOP)),
                viewModel.state.value,
            )
        }

    /**
     * "You haven't favourited anything yet" — never a copy that reads as an empty LIBRARY, which
     * is a different and false claim (decision, this task's brief; same distinction C-B forced on
     * `LibraryUiState`). This ViewModel test can only pin the DATA shape an empty favourites list
     * produces; `FavoritesScreenTest` is what pins the actual string rendered for it.
     */
    @Test
    fun `an empty favourites list says so, and does not claim an empty library`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshResult = emptyList())
            val viewModel = FavoritesViewModel(repository)
            advanceUntilIdle()

            assertEquals(
                FavoritesUiState.Success(entries = emptyList()),
                viewModel.state.value,
            )
        }

    @Test
    fun `a failing initial load is captured instead of escaping the coroutine`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(refreshFailure = failure)
            val viewModel = FavoritesViewModel(repository)
            advanceUntilIdle()

            assertEquals(FavoritesUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `retrying after a failed load shows loading immediately, not the stale error`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(refreshFailure = failure)
            val viewModel = FavoritesViewModel(repository)
            advanceUntilIdle()
            assertEquals(FavoritesUiState.Error(failure), viewModel.state.value)

            repository.refreshFailure = null
            repository.refreshResult = listOf(FRIEREN)
            viewModel.state.test {
                assertEquals(FavoritesUiState.Error(failure), awaitItem())
                viewModel.refresh()
                assertEquals(FavoritesUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(FavoritesUiState.Success(entries = listOf(FRIEREN)), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `loadMore is not fired again while one is in flight`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN))
            val viewModel = FavoritesViewModel(repository)
            advanceUntilIdle()

            viewModel.loadMore()
            viewModel.loadMore()
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(1, repository.loadMoreCalls)
        }

    @Test
    fun `a failed loadMore leaves entries standing with a page error`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN), loadMoreFailure = failure)
            val viewModel = FavoritesViewModel(repository)
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                FavoritesUiState.Success(entries = listOf(FRIEREN), loadingMore = false, pageError = failure),
                viewModel.state.value,
            )
        }

    @Test
    fun `a successful loadMore clears a previous page error`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val repository =
                FakeLibraryRepository(
                    refreshResult = listOf(FRIEREN),
                    loadMoreFailure = failure,
                    loadMoreAppends = listOf(BEBOP),
                )
            val viewModel = FavoritesViewModel(repository)
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(failure, (viewModel.state.value as FavoritesUiState.Success).pageError)

            repository.loadMoreFailure = null
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                FavoritesUiState.Success(entries = listOf(FRIEREN, BEBOP), loadingMore = false, pageError = null),
                viewModel.state.value,
            )
        }

    private class FakeLibraryRepository(
        var refreshResult: List<LibraryEntry> = emptyList(),
        var refreshFailure: Throwable? = null,
        var loadMoreAppends: List<LibraryEntry> = emptyList(),
        var loadMoreFailure: Throwable? = null,
    ) : LibraryRepository {
        private val mutableFavorites = MutableStateFlow<List<LibraryEntry>>(emptyList())
        override val favoriteEntries: StateFlow<List<LibraryEntry>> = mutableFavorites.asStateFlow()

        var loadMoreCalls = 0
            private set

        // The GENERAL-purpose surface, deliberately unreachable — see `the feed requests only
        // favourites`'s KDoc for what a call reaching one of these actually proves.
        override fun observeLibrary(): Flow<List<LibraryEntry>> = error("not exercised by FavoritesViewModel")

        override suspend fun refresh(): Unit = error("not exercised by FavoritesViewModel")

        override suspend fun loadMore(): Unit = error("not exercised by FavoritesViewModel")

        override suspend fun applyFilter(filter: LibraryFilter): Unit = error("not exercised by FavoritesViewModel")

        override suspend fun add(
            source: MediaSource,
            externalId: String,
        ): LibraryEntry = error("not exercised by FavoritesViewModel")

        override suspend fun update(
            entryId: String,
            patch: LibraryPatch,
        ): LibraryEntry = error("not exercised by FavoritesViewModel")

        override suspend fun entryForMedia(mediaId: String): LibraryEntry? =
            error("not exercised by FavoritesViewModel")

        override suspend fun refreshFavorites() {
            refreshFailure?.let { throw it }
            mutableFavorites.value = refreshResult
        }

        override suspend fun loadMoreFavorites() {
            loadMoreCalls++
            loadMoreFailure?.let { throw it }
            mutableFavorites.value = mutableFavorites.value + loadMoreAppends
        }
    }

    private companion object {
        fun entry(
            id: String,
            title: String,
        ) = LibraryEntry(
            id = id,
            status = UserMediaStatus.WATCHING,
            score = null,
            progress = 0,
            favorite = true,
            updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
            media =
                Media(
                    id = "media-$id",
                    source = MediaSource.ANILIST,
                    externalId = id,
                    type = MediaType.ANIME,
                    title = title,
                    year = 2023,
                    genres = listOf("fantasy"),
                    coverImageUrl = null,
                    status = MediaStatus.AIRING,
                    nextEpisodeSeason = null,
                    nextEpisodeNumber = null,
                    nextEpisodeDate = null,
                    daysUntilNextEpisode = null,
                ),
        )

        val FRIEREN = entry(id = "frieren", title = "Frieren")
        val BEBOP = entry(id = "bebop", title = "Cowboy Bebop")
    }
}
