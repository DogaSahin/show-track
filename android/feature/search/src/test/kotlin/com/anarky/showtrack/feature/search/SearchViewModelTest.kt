package com.anarky.showtrack.feature.search

import app.cash.turbine.test
import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.data.search.RecentSearchStore
import com.anarky.showtrack.core.data.search.RecentSearches
import com.anarky.showtrack.core.model.EpisodeList
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.SearchResult
import com.anarky.showtrack.core.model.SearchResults
import com.anarky.showtrack.core.model.UserMediaStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import java.time.Instant

/**
 * The ViewModel is exercised against FAKE `MediaRepository`/`LibraryRepository`, which is the
 * point of the interfaces: nothing here knows Retrofit or Room exists, and this test needs no
 * graph, no Robolectric and no device — unlike `DetailViewModelTest`, `SearchViewModel` reads no
 * `SavedStateHandle` argument, so a bare JVM `StandardTestDispatcher` is enough.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    // viewModelScope is hard-wired to Dispatchers.Main, which has no implementation on a plain
    // JVM. Substituting a TestDispatcher is what makes the debounce pipeline launched from `init`
    // (and every action below) run at all.
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a degraded provider is surfaced rather than swallowed`() =
        runTest(dispatcher) {
            // has_more is false because the provider that ANSWERED has no more — so without
            // reading `sources` a TMDB outage is indistinguishable from a complete result set
            // (decision C-O).
            val degradedResults =
                SearchResults(
                    items = listOf(SearchResult(SUMMARY)),
                    hasMore = false,
                    degraded = listOf(MediaSource.TMDB),
                )
            val media = FakeMediaRepository(resultsAfterSearch = degradedResults)
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())

            viewModel.onQueryChange("one piece")
            advanceUntilIdle()

            val success = viewModel.state.value as SearchUiState.Success
            assertTrue(success.results.isDegraded)
            assertEquals(listOf(MediaSource.TMDB), success.results.degraded)
        }

    @Test
    fun `a query change debounces into a single search`() =
        runTest(dispatcher) {
            // Searching per keystroke is a request per character against two upstream APIs with
            // rate limits.
            val media = FakeMediaRepository()
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())

            viewModel.onQueryChange("f")
            advanceTimeBy(100)
            viewModel.onQueryChange("fr")
            advanceTimeBy(100)
            viewModel.onQueryChange("fri")
            advanceTimeBy(100)
            viewModel.onQueryChange("frie")
            advanceUntilIdle()

            assertEquals(listOf("frie"), media.searchCalls)
        }

    @Test
    fun `a blank query shows Idle instead of running a search`() =
        runTest(dispatcher) {
            val media = FakeMediaRepository()
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())

            viewModel.onQueryChange("   ")
            advanceUntilIdle()

            assertEquals(SearchUiState.Idle, viewModel.state.value)
            assertTrue(media.searchCalls.isEmpty())
        }

    @Test
    fun `a failing search is captured instead of escaping the coroutine`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val media = FakeMediaRepository(searchFailure = failure)
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())

            viewModel.onQueryChange("one piece")
            advanceUntilIdle()

            assertEquals(SearchUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `a failed search following a successful one shows the new failure, not a stale list`() =
        runTest(dispatcher) {
            // MediaRepository.search() restores the previous query's results internally on
            // failure (task 9a.4's carried-forward fix) — this asserts the SCREEN, not the
            // repository: a second, failing query must not leave the first query's results
            // silently on screen underneath no error at all.
            val results = SearchResults(items = listOf(SearchResult(SUMMARY)), hasMore = false, degraded = emptyList())
            val media = FakeMediaRepository(resultsAfterSearch = results)
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())

            viewModel.onQueryChange("one piece")
            advanceUntilIdle()
            assertEquals(SearchUiState.Success(results = results), viewModel.state.value)

            val failure = IOException("offline")
            media.searchFailure = failure
            viewModel.onQueryChange("dandadan")
            advanceUntilIdle()

            assertEquals(SearchUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `retrying after a failed search shows loading immediately, not the stale error`() =
        runTest(dispatcher) {
            // 9a.8's other carried-forward lesson: clearing the error only on success leaves the
            // OLD error on screen, unchanged, for the retry's whole round trip.
            val failure = IOException("offline")
            val media = FakeMediaRepository(searchFailure = failure)
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())
            viewModel.onQueryChange("one piece")
            advanceUntilIdle()
            assertEquals(SearchUiState.Error(failure), viewModel.state.value)

            media.searchFailure = null
            viewModel.state.test {
                assertEquals(SearchUiState.Error(failure), awaitItem())
                viewModel.retry()
                assertEquals(SearchUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(SearchUiState.Success(results = SearchResults.EMPTY), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `loadMore is not fired again while one is in flight`() =
        runTest(dispatcher) {
            val media = FakeMediaRepository()
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())
            viewModel.onQueryChange("one piece")
            advanceUntilIdle()

            viewModel.loadMore()
            viewModel.loadMore()
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(1, media.loadMoreCalls)
        }

    @Test
    fun `a failed loadMore leaves results standing with a page error`() =
        runTest(dispatcher) {
            // The results a failed page-2 fetch left behind are still valid — this must never
            // promote state to a full-screen Error, which would discard a populated list over one
            // failed next page (carried forward from task 9a.8's shipped bug).
            val results = SearchResults(items = listOf(SearchResult(SUMMARY)), hasMore = true, degraded = emptyList())
            val failure = IOException("offline")
            val media = FakeMediaRepository(resultsAfterSearch = results, loadMoreFailure = failure)
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())
            viewModel.onQueryChange("one piece")
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                SearchUiState.Success(results = results, loadingMore = false, pageError = failure),
                viewModel.state.value,
            )
        }

    @Test
    fun `a successful loadMore clears a previous page error`() =
        runTest(dispatcher) {
            val results = SearchResults(items = listOf(SearchResult(SUMMARY)), hasMore = true, degraded = emptyList())
            val failure = IOException("offline")
            val media = FakeMediaRepository(resultsAfterSearch = results, loadMoreFailure = failure)
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())
            viewModel.onQueryChange("one piece")
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()

            media.loadMoreFailure = null
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                SearchUiState.Success(results = results, loadingMore = false, pageError = null),
                viewModel.state.value,
            )
        }

    @Test
    fun `clearing the query mid-search leaves Idle standing rather than stale results`() =
        runTest(dispatcher) {
            // Regression test: onQueryChange("") sets Idle SYNCHRONOUSLY and bypasses the
            // debounce collector entirely, so it does not serialise against an already in-flight
            // runSearch for the query that was just cleared. Without the `mutableQuery.value !=
            // searchQuery` guard in runSearch, the in-flight search resolving after the clear
            // would overwrite Idle with a Success — a result list left standing under an empty
            // search box.
            val results = SearchResults(items = listOf(SearchResult(SUMMARY)), hasMore = false, degraded = emptyList())
            val searchGate = CompletableDeferred<Unit>()
            val media = FakeMediaRepository(resultsAfterSearch = results, searchGate = searchGate)
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())

            viewModel.onQueryChange("frieren")
            advanceUntilIdle()
            // The debounce has elapsed and runSearch("frieren") is suspended awaiting the gate,
            // having already published Loading.
            assertEquals(SearchUiState.Loading, viewModel.state.value)

            viewModel.onQueryChange("")
            assertEquals(SearchUiState.Idle, viewModel.state.value)

            // Let the superseded search resolve now that the field has been cleared.
            searchGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(SearchUiState.Idle, viewModel.state.value)
        }

    @Test
    fun `a superseded search that fails after the field was cleared does not surface an error`() =
        runTest(dispatcher) {
            // The same guard as the Idle case above, exercised on runSearch's OTHER write: a
            // cleared query must not show an error banner for a request that was abandoned before
            // it failed. Without the guard on the catch branch specifically, a search that fails
            // AFTER onQueryChange("") would overwrite Idle with SearchUiState.Error.
            val searchGate = CompletableDeferred<Unit>()
            val media = FakeMediaRepository(searchGate = searchGate)
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())

            viewModel.onQueryChange("frieren")
            advanceUntilIdle()
            assertEquals(SearchUiState.Loading, viewModel.state.value)

            viewModel.onQueryChange("")
            assertEquals(SearchUiState.Idle, viewModel.state.value)

            // The in-flight search fails only AFTER the field was cleared.
            media.searchFailure = IOException("offline")
            searchGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(SearchUiState.Idle, viewModel.state.value)
        }

    @Test
    fun `tapping a stored result opens it and never adds it`() =
        runTest(dispatcher) {
            val library = FakeLibraryRepository()
            val media = FakeMediaRepository()
            val viewModel = SearchViewModel(media, library, FakeRecentSearchStore())

            viewModel.events.test {
                viewModel.open(SearchResult(SUMMARY, mediaId = "m-9"))
                advanceUntilIdle()
                assertEquals(SearchEvent.OpenDetail("m-9"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(0, library.addCalls)
            assertEquals(0, media.resolveCalls)
        }

    @Test
    fun `tapping a result nobody stored resolves it first and still adds nothing`() =
        runTest(dispatcher) {
            val library = FakeLibraryRepository()
            val gate = CompletableDeferred<Unit>()
            val media = FakeMediaRepository(resultsAfterSearch = ONE_RESULT, resolveGate = gate)
            val viewModel = searchedViewModel(media, library)

            viewModel.events.test {
                viewModel.open(SearchResult(SUMMARY))
                advanceUntilIdle()
                // The row is tinted while it waits.
                assertEquals(SUMMARY.key, (viewModel.state.value as SearchUiState.Success).opening)

                gate.complete(Unit)
                advanceUntilIdle()
                assertEquals(SearchEvent.OpenDetail("m-1"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(null, (viewModel.state.value as SearchUiState.Success).opening)
            // A second tap now opens by the id the resolve returned, without resolving again.
            assertEquals(
                "m-1",
                (viewModel.state.value as SearchUiState.Success)
                    .results.items
                    .single()
                    .mediaId,
            )
            assertEquals(0, library.addCalls)
        }

    @Test
    fun `a failed resolve leaves the row as it was and says so`() =
        runTest(dispatcher) {
            val media = FakeMediaRepository(resultsAfterSearch = ONE_RESULT, resolveFailure = IOException("offline"))
            val viewModel = searchedViewModel(media)

            viewModel.events.test {
                viewModel.open(SearchResult(SUMMARY))
                advanceUntilIdle()
                assertEquals(SearchEvent.OpenFailed, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(SearchUiState.Success(results = ONE_RESULT), viewModel.state.value)
        }

    @Test
    fun `adding stays on search and turns the row into its status`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val library = FakeLibraryRepository(addGate = gate)
            val viewModel = searchedViewModel(FakeMediaRepository(resultsAfterSearch = ONE_RESULT), library)

            viewModel.events.test {
                viewModel.add(SearchResult(SUMMARY))
                advanceUntilIdle()
                assertEquals(SUMMARY.key, (viewModel.state.value as SearchUiState.Success).adding)

                gate.complete(Unit)
                advanceUntilIdle()
                // A snackbar offer, not a navigation.
                assertEquals(SearchEvent.Added(title = "One Piece", mediaId = "m-1"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            val success = viewModel.state.value as SearchUiState.Success
            assertEquals(null, success.adding)
            assertEquals(
                UserMediaStatus.PLANNED,
                success.results.items
                    .single()
                    .libraryStatus,
            )
            assertEquals(
                "m-1",
                success.results.items
                    .single()
                    .mediaId,
            )
        }

    @Test
    fun `a failed add leaves the row as it was and says so`() =
        runTest(dispatcher) {
            val library = FakeLibraryRepository(addFailure = IOException("offline"))
            val viewModel = searchedViewModel(FakeMediaRepository(resultsAfterSearch = ONE_RESULT), library)

            viewModel.events.test {
                viewModel.add(SearchResult(SUMMARY))
                advanceUntilIdle()
                assertEquals(SearchEvent.AddFailed, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(SearchUiState.Success(results = ONE_RESULT), viewModel.state.value)
        }

    @Test
    fun `a second add is ignored while one is in flight, even across a new search`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val library = FakeLibraryRepository(addGate = gate)
            val viewModel = searchedViewModel(FakeMediaRepository(resultsAfterSearch = ONE_RESULT), library)

            viewModel.add(SearchResult(SUMMARY))
            advanceUntilIdle()
            // A fresh search replaces the Success that held `adding`; the guard must survive it.
            viewModel.onQueryChange("dandadan")
            advanceUntilIdle()
            viewModel.add(SearchResult(OTHER_SUMMARY))
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, library.addCalls)
        }

    @Test
    fun `submitting saves the query, typing alone does not`() =
        runTest(dispatcher) {
            val recent = FakeRecentSearchStore()
            val viewModel = SearchViewModel(FakeMediaRepository(), FakeLibraryRepository(), recent)

            viewModel.onQueryChange("frieren")
            advanceUntilIdle()
            assertEquals(emptyList<String>(), recent.recorded)

            viewModel.onSubmit()
            advanceUntilIdle()
            assertEquals(listOf("frieren"), recent.recorded)
        }

    @Test
    fun `opening a result saves the query it came from`() =
        runTest(dispatcher) {
            val recent = FakeRecentSearchStore()
            val viewModel = SearchViewModel(FakeMediaRepository(), FakeLibraryRepository(), recent)
            viewModel.onQueryChange("one piece")
            advanceUntilIdle()

            viewModel.open(SearchResult(SUMMARY, mediaId = "m-1"))
            advanceUntilIdle()

            assertEquals(listOf("one piece"), recent.recorded)
        }

    @Test
    fun `a recent search runs at once, even when it is already in the field`() =
        runTest(dispatcher) {
            val media = FakeMediaRepository()
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())

            viewModel.fillQuery("bebop")
            advanceUntilIdle()
            assertTrue("↖ puts the text in the field without searching", media.searchCalls.isEmpty())
            assertEquals("bebop", viewModel.query.value)

            viewModel.runRecent("bebop")
            advanceUntilIdle()
            assertEquals(listOf("bebop"), media.searchCalls)
        }

    @Test
    fun `clear empties the recent searches`() =
        runTest(dispatcher) {
            val recent = FakeRecentSearchStore(initial = listOf("frieren"))
            val viewModel = SearchViewModel(FakeMediaRepository(), FakeLibraryRepository(), recent)

            viewModel.clearRecentSearches()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), viewModel.recentSearches.value)
        }

    @Test
    fun `the search key searches a filled-in recent query`() =
        runTest(dispatcher) {
            val media = FakeMediaRepository()
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())

            viewModel.fillQuery("bebop")
            advanceUntilIdle()
            viewModel.onSubmit()
            advanceUntilIdle()

            assertEquals(listOf("bebop"), media.searchCalls)
            assertTrue(viewModel.state.value is SearchUiState.Success)
        }

    @Test
    fun `retyping the query already shown does not search it again`() =
        runTest(dispatcher) {
            val media = FakeMediaRepository()
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())
            viewModel.onQueryChange("abc")
            advanceUntilIdle()

            viewModel.onQueryChange("abcd")
            advanceTimeBy(100)
            viewModel.onQueryChange("abc")
            advanceUntilIdle()
            viewModel.onSubmit()
            advanceUntilIdle()

            assertEquals(listOf("abc"), media.searchCalls)
        }

    @Test
    fun `a query typed away and back during its own slow search still lands`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val media = FakeMediaRepository(resultsAfterSearch = ONE_RESULT, searchGate = gate)
            val viewModel = SearchViewModel(media, FakeLibraryRepository(), FakeRecentSearchStore())
            viewModel.onQueryChange("abc")
            advanceUntilIdle()

            viewModel.onQueryChange("abcd")
            gate.complete(Unit)
            advanceTimeBy(100)
            viewModel.onQueryChange("abc")
            advanceUntilIdle()

            assertEquals(SearchUiState.Success(results = ONE_RESULT), viewModel.state.value)
        }

    @Test
    fun `a double tap opens the title once`() =
        runTest(dispatcher) {
            val viewModel = SearchViewModel(FakeMediaRepository(), FakeLibraryRepository(), FakeRecentSearchStore())

            viewModel.events.test {
                viewModel.open(SearchResult(SUMMARY, mediaId = "m-1"))
                viewModel.open(SearchResult(SUMMARY, mediaId = "m-1"))
                advanceUntilIdle()
                assertEquals(SearchEvent.OpenDetail("m-1"), awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun kotlinx.coroutines.test.TestScope.searchedViewModel(
        media: FakeMediaRepository,
        library: FakeLibraryRepository = FakeLibraryRepository(),
    ): SearchViewModel {
        val viewModel = SearchViewModel(media, library, FakeRecentSearchStore())
        viewModel.onQueryChange("one piece")
        advanceUntilIdle()
        return viewModel
    }

    private class FakeMediaRepository(
        var searchFailure: Throwable? = null,
        var loadMoreFailure: Throwable? = null,
        var resultsAfterSearch: SearchResults = SearchResults.EMPTY,
        // Lets a test suspend search() mid-call to control interleaving with another action
        // (e.g. clearing the field, or a second search) — null (the default) behaves exactly as
        // before: search() completes synchronously with no suspension point of its own.
        private val searchGate: CompletableDeferred<Unit>? = null,
        private val resolveGate: CompletableDeferred<Unit>? = null,
        private val resolveFailure: Throwable? = null,
    ) : MediaRepository {
        var resolveCalls = 0
            private set

        private val mutableSearchResults = MutableStateFlow(SearchResults.EMPTY)
        override val searchResults: StateFlow<SearchResults> = mutableSearchResults

        val searchCalls = mutableListOf<String>()
        var loadMoreCalls = 0
            private set

        override suspend fun search(query: String) {
            searchCalls.add(query)
            searchGate?.await()
            searchFailure?.let { throw it }
            mutableSearchResults.value = resultsAfterSearch
        }

        override suspend fun loadMoreResults() {
            loadMoreCalls++
            loadMoreFailure?.let { throw it }
        }

        override suspend fun resolve(
            source: MediaSource,
            externalId: String,
        ): Media {
            resolveCalls++
            resolveGate?.await()
            resolveFailure?.let { throw it }
            return ENTRY_WITH_MEDIA_ID_M1.media
        }

        override suspend fun episodes(mediaId: String): EpisodeList = error("not used here")

        override suspend fun detail(mediaId: String): Media = error("not exercised by SearchViewModel")
    }

    private class FakeLibraryRepository(
        var addResult: LibraryEntry = ENTRY_WITH_MEDIA_ID_M1,
        var addFailure: Throwable? = null,
        // Lets a test hold add() in flight while it drives other ViewModel actions — see the
        // addInFlight regression test above. Null (the default) behaves exactly as before.
        private val addGate: CompletableDeferred<Unit>? = null,
    ) : LibraryRepository {
        var addCalls = 0
            private set

        override fun observeLibrary(): Flow<List<LibraryEntry>> = error("not exercised by SearchViewModel")

        override suspend fun refresh(): Unit = error("not exercised by SearchViewModel")

        override suspend fun loadMore(): Unit = error("not exercised by SearchViewModel")

        override suspend fun applyFilter(filter: LibraryFilter): Unit = error("not exercised by SearchViewModel")

        override suspend fun add(
            source: MediaSource,
            externalId: String,
        ): LibraryEntry {
            addCalls++
            addGate?.await()
            addFailure?.let { throw it }
            return addResult
        }

        override suspend fun watchedEpisodes(entryId: String): Set<String> = error("not used here")

        override suspend fun setWatched(
            entryId: String,
            episodeIds: Collection<String>,
            watched: Boolean,
        ): LibraryEntry = error("not used here")

        override suspend fun update(
            entryId: String,
            patch: LibraryPatch,
        ): LibraryEntry = error("not exercised by SearchViewModel")

        override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("not exercised by SearchViewModel")

        override suspend fun favoritesPage(
            type: MediaType?,
            sort: LibrarySort,
            cursor: String?,
            limit: Int,
        ): Page<LibraryEntry> = Page(emptyList(), null)

        override suspend fun libraryStats() = error("not exercised by SearchViewModel")

        override suspend fun upcomingWatching(limit: Int): List<LibraryEntry> = emptyList()

        override suspend fun importAniList(username: String) = error("not exercised by SearchViewModel")
    }

    private class FakeRecentSearchStore(
        initial: List<String> = emptyList(),
    ) : RecentSearchStore {
        private val list = MutableStateFlow(initial)
        val recorded = mutableListOf<String>()
        override val recent: Flow<List<String>> = list

        override suspend fun record(query: String) {
            recorded += query
            list.value = RecentSearches.add(list.value, query)
        }

        override suspend fun clear() {
            list.value = emptyList()
        }
    }

    private companion object {
        val SUMMARY =
            MediaSummary(
                source = MediaSource.ANILIST,
                externalId = "21",
                type = MediaType.ANIME,
                title = "One Piece",
                year = 1999,
                genres = listOf("Action"),
                coverImageUrl = null,
            )

        val OTHER_SUMMARY = SUMMARY.copy(externalId = "22", title = "Dandadan")

        val ONE_RESULT = SearchResults(items = listOf(SearchResult(SUMMARY)), hasMore = false, degraded = emptyList())

        val ENTRY_WITH_MEDIA_ID_M1 =
            LibraryEntry(
                id = "entry-1",
                status = UserMediaStatus.PLANNED,
                score = null,
                progress = 0,
                favorite = false,
                updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
                media =
                    Media(
                        id = "m-1",
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
