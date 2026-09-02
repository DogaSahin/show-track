package com.anarky.showtrack.feature.discover

import app.cash.turbine.test
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.RecommendationRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.Recommendation
import com.anarky.showtrack.core.model.RecommendationReason
import com.anarky.showtrack.core.model.UserMediaStatus
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant

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
    fun `the feed loads on construction`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            advanceUntilIdle()

            assertEquals(
                DiscoverUiState.Success(items = listOf(FRIEREN, BEBOP)),
                viewModel.state.value,
            )
        }

    @Test
    fun `a failing initial load is captured instead of escaping the coroutine`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val recommendations = FakeRecommendationRepository(refreshFailure = failure)
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
            advanceUntilIdle()

            assertEquals(DiscoverUiState.Error(failure), viewModel.state.value)
        }

    @Test
    fun `retrying after a failed load shows loading immediately, not the stale error`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val recommendations = FakeRecommendationRepository(refreshFailure = failure)
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
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

    @Test
    fun `loadMore is not fired again while one is in flight`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN))
            val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())
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

    @Test
    fun `adding a row removes it from the feed immediately, before the network call resolves`() =
        runTest(dispatcher) {
            val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val addGate = CompletableDeferred<Unit>()
            val library = FakeLibraryRepository(addGate = addGate)
            val viewModel = DiscoverViewModel(recommendations, library)
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
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()

            assertTrue(recommendations.removedIds.isEmpty())
            assertNull((viewModel.state.value as DiscoverUiState.Success).addError)
        }

    private class FakeRecommendationRepository(
        var refreshResult: List<Recommendation> = emptyList(),
        var refreshFailure: Throwable? = null,
        var loadMoreAppends: List<Recommendation> = emptyList(),
        var loadMoreFailure: Throwable? = null,
    ) : RecommendationRepository {
        private val mutableFeed = MutableStateFlow<List<Recommendation>>(emptyList())
        override val feed: StateFlow<List<Recommendation>> = mutableFeed.asStateFlow()

        var loadMoreCalls = 0
            private set
        val removedIds = mutableListOf<String>()

        override suspend fun refresh() {
            refreshFailure?.let { throw it }
            mutableFeed.value = refreshResult
        }

        override suspend fun loadMore() {
            loadMoreCalls++
            loadMoreFailure?.let { throw it }
            mutableFeed.value = mutableFeed.value + loadMoreAppends
        }

        override fun remove(mediaId: String) {
            removedIds += mediaId
            mutableFeed.value = mutableFeed.value.filterNot { it.media.id == mediaId }
        }

        override fun restore(
            index: Int,
            recommendation: Recommendation,
        ) {
            mutableFeed.value =
                mutableFeed.value.toMutableList().apply {
                    add(index.coerceIn(0, size), recommendation)
                }
        }
    }

    private class FakeLibraryRepository(
        var addFailure: Throwable? = null,
        // Lets a test hold add() in flight while it drives other ViewModel actions — mirrors
        // SearchViewModelTest's FakeLibraryRepository. Null (the default) behaves exactly as
        // before: add() completes synchronously with no suspension point of its own.
        private val addGate: CompletableDeferred<Unit>? = null,
    ) : LibraryRepository {
        val addCalls = mutableListOf<Pair<MediaSource, String>>()

        override fun observeLibrary(): Flow<List<LibraryEntry>> = error("not exercised by DiscoverViewModel")

        override suspend fun refresh(): Unit = error("not exercised by DiscoverViewModel")

        override suspend fun loadMore(): Unit = error("not exercised by DiscoverViewModel")

        override suspend fun applyFilter(filter: LibraryFilter): Unit = error("not exercised by DiscoverViewModel")

        override suspend fun add(
            source: MediaSource,
            externalId: String,
        ): LibraryEntry {
            addCalls += source to externalId
            addGate?.await()
            addFailure?.let { throw it }
            return DUMMY_ENTRY
        }

        override suspend fun update(
            entryId: String,
            patch: LibraryPatch,
        ): LibraryEntry = error("not exercised by DiscoverViewModel")

        override suspend fun entryForMedia(mediaId: String): LibraryEntry? = error("not exercised by DiscoverViewModel")

        override val favoriteEntries: StateFlow<List<LibraryEntry>> = MutableStateFlow(emptyList())

        override suspend fun refreshFavorites(): Unit = error("not exercised by DiscoverViewModel")

        override suspend fun loadMoreFavorites(): Unit = error("not exercised by DiscoverViewModel")
    }

    private companion object {
        val DUMMY_ENTRY =
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
