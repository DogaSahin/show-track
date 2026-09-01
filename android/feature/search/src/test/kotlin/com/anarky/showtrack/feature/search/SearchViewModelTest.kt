package com.anarky.showtrack.feature.search

import app.cash.turbine.test
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
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
import org.junit.Assert.assertNull
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
    fun `adding a result yields the media id from the response`() =
        runTest(dispatcher) {
            // A search result carries NO id (decision C-N). The id exists only once POST
            // /v1/library has created the row, and it comes back in that response.
            val library = FakeLibraryRepository(addResult = ENTRY_WITH_MEDIA_ID_M1)
            val viewModel = SearchViewModel(FakeMediaRepository(), library)

            viewModel.navigateToDetail.test {
                viewModel.add(SUMMARY)
                advanceUntilIdle()
                assertEquals("m-1", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a degraded provider is surfaced rather than swallowed`() =
        runTest(dispatcher) {
            // has_more is false because the provider that ANSWERED has no more — so without
            // reading `sources` a TMDB outage is indistinguishable from a complete result set
            // (decision C-O).
            val degradedResults =
                SearchResults(items = listOf(SUMMARY), hasMore = false, degraded = listOf(MediaSource.TMDB))
            val media = FakeMediaRepository(resultsAfterSearch = degradedResults)
            val viewModel = SearchViewModel(media, FakeLibraryRepository())

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
            val viewModel = SearchViewModel(media, FakeLibraryRepository())

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
            val viewModel = SearchViewModel(media, FakeLibraryRepository())

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
            val viewModel = SearchViewModel(media, FakeLibraryRepository())

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
            val results = SearchResults(items = listOf(SUMMARY), hasMore = false, degraded = emptyList())
            val media = FakeMediaRepository(resultsAfterSearch = results)
            val viewModel = SearchViewModel(media, FakeLibraryRepository())

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
            val viewModel = SearchViewModel(media, FakeLibraryRepository())
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
            val viewModel = SearchViewModel(media, FakeLibraryRepository())
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
            val results = SearchResults(items = listOf(SUMMARY), hasMore = true, degraded = emptyList())
            val failure = IOException("offline")
            val media = FakeMediaRepository(resultsAfterSearch = results, loadMoreFailure = failure)
            val viewModel = SearchViewModel(media, FakeLibraryRepository())
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
            val results = SearchResults(items = listOf(SUMMARY), hasMore = true, degraded = emptyList())
            val failure = IOException("offline")
            val media = FakeMediaRepository(resultsAfterSearch = results, loadMoreFailure = failure)
            val viewModel = SearchViewModel(media, FakeLibraryRepository())
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
    fun `adding a result sets adding to its externalId until the call resolves`() =
        runTest(dispatcher) {
            val results = SearchResults(items = listOf(SUMMARY), hasMore = false, degraded = emptyList())
            val media = FakeMediaRepository(resultsAfterSearch = results)
            val library = FakeLibraryRepository(addResult = ENTRY_WITH_MEDIA_ID_M1)
            val viewModel = SearchViewModel(media, library)
            viewModel.onQueryChange("one piece")
            advanceUntilIdle()

            viewModel.state.test {
                assertEquals(
                    SearchUiState.Success(results = results, adding = null),
                    awaitItem(),
                )
                viewModel.add(SUMMARY)
                assertEquals(SUMMARY.externalId, (awaitItem() as SearchUiState.Success).adding)
                advanceUntilIdle()
                assertNull((awaitItem() as SearchUiState.Success).adding)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a failed add leaves results standing and reports beside the failed row`() =
        runTest(dispatcher) {
            // add() may have already succeeded server-side (LibraryRepositoryImpl.add's post-add
            // refresh() can fail independently of the POST — task 9a.5's carried-forward note),
            // so a failure here must not wipe the results list the user is looking at.
            val results = SearchResults(items = listOf(SUMMARY), hasMore = false, degraded = emptyList())
            val media = FakeMediaRepository(resultsAfterSearch = results)
            val failure = IOException("offline")
            val library = FakeLibraryRepository(addFailure = failure)
            val viewModel = SearchViewModel(media, library)
            viewModel.onQueryChange("one piece")
            advanceUntilIdle()

            viewModel.add(SUMMARY)
            advanceUntilIdle()

            val success = viewModel.state.value as SearchUiState.Success
            assertEquals(results, success.results)
            assertNull(success.adding)
            assertEquals(AddFailure(SUMMARY.externalId, failure), success.addError)
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
            val results = SearchResults(items = listOf(SUMMARY), hasMore = false, degraded = emptyList())
            val searchGate = CompletableDeferred<Unit>()
            val media = FakeMediaRepository(resultsAfterSearch = results, searchGate = searchGate)
            val viewModel = SearchViewModel(media, FakeLibraryRepository())

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
    fun `a superseded search resolving mid-add does not let a second add through`() =
        runTest(dispatcher) {
            // Regression test: replaceSuccess { it.copy(adding = null, addError = null) } — or,
            // before this fix, a completely fresh Success from a superseding search — resets
            // `SearchUiState.Success.adding` back to null while the FIRST add is still in flight.
            // The old guard read `current.adding` off that state, so it would pass a second tap
            // here. `addInFlight` is a ViewModel field, not part of the state's shape, so it must
            // still block the second call.
            val firstResults = SearchResults(items = listOf(SUMMARY), hasMore = false, degraded = emptyList())
            val secondResults = SearchResults(items = listOf(OTHER_SUMMARY), hasMore = false, degraded = emptyList())
            val media = FakeMediaRepository(resultsAfterSearch = firstResults)
            val addGate = CompletableDeferred<Unit>()
            val library = FakeLibraryRepository(addResult = ENTRY_WITH_MEDIA_ID_M1, addGate = addGate)
            val viewModel = SearchViewModel(media, library)
            viewModel.onQueryChange("one piece")
            advanceUntilIdle()

            viewModel.add(SUMMARY)
            // add() is now in flight, gated on addGate — the state's own `adding` field reads
            // SUMMARY.externalId at this point.

            // A second, superseding search resolves BEFORE the add call does, publishing a fresh
            // Success whose `adding` defaults back to null.
            media.resultsAfterSearch = secondResults
            viewModel.onQueryChange("dandadan")
            advanceUntilIdle()
            assertNull((viewModel.state.value as SearchUiState.Success).adding)

            // The state now says "nothing is adding" — a second tap must still be dropped,
            // because the ORIGINAL add is still in flight.
            viewModel.add(OTHER_SUMMARY)
            addGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, library.addCalls)
        }

    @Test
    fun `a second add is ignored while one is already in flight`() =
        runTest(dispatcher) {
            val results = SearchResults(items = listOf(SUMMARY, OTHER_SUMMARY), hasMore = false, degraded = emptyList())
            val media = FakeMediaRepository(resultsAfterSearch = results)
            val library = FakeLibraryRepository(addResult = ENTRY_WITH_MEDIA_ID_M1)
            val viewModel = SearchViewModel(media, library)
            viewModel.onQueryChange("one piece")
            advanceUntilIdle()

            viewModel.add(SUMMARY)
            viewModel.add(OTHER_SUMMARY)
            advanceUntilIdle()

            assertEquals(1, library.addCalls)
        }

    @Test
    fun `a successful add does not clear a standing page error`() =
        runTest(dispatcher) {
            // The inverse of the bug 9a.8 shipped: a shared slot would let ANY success clear an
            // unrelated failure. loadMore's pageError and add's addError must stay independent.
            val results = SearchResults(items = listOf(SUMMARY), hasMore = true, degraded = emptyList())
            val loadMoreFailure = IOException("offline")
            val media = FakeMediaRepository(resultsAfterSearch = results, loadMoreFailure = loadMoreFailure)
            val library = FakeLibraryRepository(addResult = ENTRY_WITH_MEDIA_ID_M1)
            val viewModel = SearchViewModel(media, library)
            viewModel.onQueryChange("one piece")
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(loadMoreFailure, (viewModel.state.value as SearchUiState.Success).pageError)

            viewModel.add(SUMMARY)
            advanceUntilIdle()

            assertEquals(loadMoreFailure, (viewModel.state.value as SearchUiState.Success).pageError)
        }

    private class FakeMediaRepository(
        var searchFailure: Throwable? = null,
        var loadMoreFailure: Throwable? = null,
        var resultsAfterSearch: SearchResults = SearchResults.EMPTY,
        // Lets a test suspend search() mid-call to control interleaving with another action
        // (e.g. clearing the field, or a second search) — null (the default) behaves exactly as
        // before: search() completes synchronously with no suspension point of its own.
        private val searchGate: CompletableDeferred<Unit>? = null,
    ) : MediaRepository {
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

        override suspend fun update(
            entryId: String,
            patch: LibraryPatch,
        ): LibraryEntry = error("not exercised by SearchViewModel")

        override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("not exercised by SearchViewModel")
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
