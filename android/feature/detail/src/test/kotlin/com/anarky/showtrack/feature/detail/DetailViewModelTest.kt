package com.anarky.showtrack.feature.detail

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.ScoreChange
import com.anarky.showtrack.core.model.SearchResults
import com.anarky.showtrack.core.model.UserMediaStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

/**
 * The ViewModel is exercised against FAKE `MediaRepository`/`LibraryRepository`, which is the
 * point of the interfaces: nothing here knows Retrofit or Room exists.
 *
 * Robolectric, unlike `LibraryViewModelTest`, because [DetailViewModel]'s constructor calls
 * `SavedStateHandle.toRoute<DetailRoute>()`, which builds an intermediate `android.os.Bundle`
 * internally (`RouteDecoder`'s `SavedStateHandleArgStore`) — unmocked, and therefore a crash, on
 * a bare JVM. `sdk = [35]` because Robolectric ships no shadow jar for 36 (`:app`'s
 * `NavGraphRegistrationTest` and `:core:database`'s DAO tests pin the same value for the same
 * reason); `application = Application::class` avoids standing up `ShowTrackApplication`'s
 * `@HiltAndroidApp` component, which this test needs neither DataStore nor the Keystore from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DetailViewModelTest {
    // viewModelScope is hard-wired to Dispatchers.Main, which has no implementation on a plain
    // JVM. Substituting a TestDispatcher is what makes the coroutine launched from `init` (and
    // from every action below) run at all.
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a title that is not in the library loads with a null entry`() =
        runTest(dispatcher) {
            // Reached from search and from a push deep-link. Treating "no entry" as an error
            // would make the deep-link open a broken screen for anything not yet tracked.
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = null))
            advanceUntilIdle()

            assertNull((viewModel.state.value as DetailUiState.Success).data.entry)
        }

    @Test
    fun `the mediaId from the route reaches both repositories`() =
        runTest(dispatcher) {
            // The central plumbing change this task made: DetailNavigation no longer decodes
            // mediaId itself, DetailViewModel does via SavedStateHandle.toRoute. Both fakes ignore
            // their argument in every other test, so this is the one place a regression to a
            // hard-coded or empty id would actually be caught.
            val media = FakeMedia()
            val library = FakeLibrary(entry = null)
            DetailViewModel(savedState("media-42"), media, library)
            advanceUntilIdle()

            assertEquals("media-42", media.lastMediaId)
            assertEquals("media-42", library.lastEntryForMediaId)
        }

    @Test
    fun `a title already in the library loads with its entry`() =
        runTest(dispatcher) {
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY))
            advanceUntilIdle()

            assertEquals(ENTRY, (viewModel.state.value as DetailUiState.Success).data.entry)
            assertEquals(MEDIA, (viewModel.state.value as DetailUiState.Success).data.media)
        }

    @Test
    fun `a failing load reports Error instead of escaping the coroutine`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(detailFailure = failure), FakeLibrary())

            advanceUntilIdle()

            // Not assertEquals(DetailUiState.Error(failure), ...): `failure` crosses a real
            // suspension point (the async/await pair `load()` uses for its parallel fetch), and
            // kotlinx.coroutines' stack-trace recovery replaces it in flight with a COPY of the
            // same type and message whose `cause` is the original — a JVM implementation detail
            // of suspend-function exception propagation, not a claim this ViewModel makes about
            // exception identity. Asserting type + message is what the production contract
            // actually promises.
            assertIsError(failure, viewModel.state.value)
        }

    @Test
    fun `retrying after a failed load clears the stale error immediately`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val media = FakeMedia(detailFailure = failure)
            val viewModel = DetailViewModel(savedState("media-1"), media, FakeLibrary())

            viewModel.state.test {
                assertEquals(DetailUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertIsError(failure, awaitItem())

                media.detailFailure = null
                viewModel.retry()
                // Loading must appear on its own, not the stale Error surviving underneath it —
                // 9a.8's carried-forward lesson: a retry that never clears the old error shows it
                // for the whole round trip because it outranks Loading.
                assertEquals(DetailUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(
                    DetailUiState.Success(DetailData(media = MEDIA, entry = null)),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `changing the score sends only the score`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library)
            advanceUntilIdle()

            viewModel.setScore(BigDecimal("9.0"))
            advanceUntilIdle()

            // Progress must not ride along: the user changed one thing.
            assertEquals(LibraryPatch(score = ScoreChange.Set(BigDecimal("9.0"))), library.lastPatch)
        }

    @Test
    fun `clearing the score sends the unrate leg of the tri-state`() =
        runTest(dispatcher) {
            // The third wire state score's own KDoc calls out: absent means "leave it", this
            // means "unrate it" — the one leg of the tri-state with no assertion until now.
            val library = FakeLibrary(entry = ENTRY)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library)
            advanceUntilIdle()

            viewModel.clearScore()
            advanceUntilIdle()

            assertEquals(LibraryPatch(score = ScoreChange.Clear), library.lastPatch)
        }

    @Test
    fun `changing the progress sends only the progress`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library)
            advanceUntilIdle()

            viewModel.setProgress(7)
            advanceUntilIdle()

            assertEquals(LibraryPatch(progress = 7), library.lastPatch)
        }

    @Test
    fun `toggling favorite sends the flipped value`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library)
            advanceUntilIdle()

            viewModel.toggleFavorite()
            advanceUntilIdle()

            assertEquals(LibraryPatch(favorite = !ENTRY.favorite), library.lastPatch)
        }

    @Test
    fun `changing the status sends only the status`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library)
            advanceUntilIdle()

            viewModel.setStatus(UserMediaStatus.COMPLETED)
            advanceUntilIdle()

            assertEquals(LibraryPatch(status = UserMediaStatus.COMPLETED), library.lastPatch)
        }

    @Test
    fun `the state shows the entry the server returned, not the one we sent`() =
        runTest(dispatcher) {
            // The server owns updated_at and may clamp a value. Optimistically keeping the local
            // guess is how a UI drifts from the database it claims to show.
            val returned = ENTRY.copy(progress = 5)
            val library = FakeLibrary(entry = ENTRY, updateResult = returned)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library)
            advanceUntilIdle()

            viewModel.setProgress(99)
            advanceUntilIdle()

            assertEquals(5, (viewModel.state.value as DetailUiState.Success).data.entry?.progress)
        }

    @Test
    fun `a failed edit restores the previous value and reports the failure`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val library = FakeLibrary(entry = ENTRY, updateFailure = failure)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library)
            advanceUntilIdle()

            viewModel.setScore(BigDecimal("9.0"))
            advanceUntilIdle()

            val success = viewModel.state.value as DetailUiState.Success
            // "Restores" here is trivial by construction: the entry is only ever replaced with
            // what the server returns (see the test above), so a failed edit never touched it.
            assertEquals(ENTRY, success.data.entry)
            assertEquals(DetailActionError.Edit(failure), success.actionError)
            assertFalse(success.saving)
        }

    @Test
    fun `an edit in flight sets saving and clears it on completion`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library)

            viewModel.state.test {
                assertEquals(DetailUiState.Loading, awaitItem())
                advanceUntilIdle()
                assertEquals(DetailUiState.Success(DetailData(media = MEDIA, entry = ENTRY)), awaitItem())

                viewModel.setScore(BigDecimal("9.0"))
                assertTrue((awaitItem() as DetailUiState.Success).saving)
                advanceUntilIdle()
                assertFalse((awaitItem() as DetailUiState.Success).saving)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a second edit is ignored while one is already saving`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library)
            advanceUntilIdle()

            viewModel.setScore(BigDecimal("9.0"))
            viewModel.setProgress(4)
            advanceUntilIdle()

            assertEquals(1, library.updateCalls)
        }

    @Test
    fun `adding to the library replaces the null entry with the one the server returned`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = null, addResult = ENTRY)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library)
            advanceUntilIdle()

            viewModel.addToLibrary()
            advanceUntilIdle()

            val success = viewModel.state.value as DetailUiState.Success
            assertEquals(ENTRY, success.data.entry)
            assertNull(success.actionError)
            assertFalse(success.saving)
            assertEquals(MediaSource.ANILIST, library.lastAddSource)
            assertEquals("21", library.lastAddExternalId)
        }

    @Test
    fun `a failed add leaves the entry null and reports the failure without wiping the screen`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val library = FakeLibrary(entry = null, addFailure = failure)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library)
            advanceUntilIdle()

            viewModel.addToLibrary()
            advanceUntilIdle()

            // The title stays fully on screen: an add() failure — which may be a POST failure OR
            // a successful POST followed by a failed post-add refresh() (LibraryRepositoryImpl's
            // documented wrinkle) — is never promoted to DetailUiState.Error.
            val success = viewModel.state.value as DetailUiState.Success
            assertNull(success.data.entry)
            assertEquals(DetailActionError.Add(failure), success.actionError)
            assertFalse(success.saving)
        }

    private fun savedState(mediaId: String): SavedStateHandle = SavedStateHandle(mapOf("mediaId" to mediaId))

    /** See the comment at its call site for why this is type + message rather than `assertEquals`. */
    private fun assertIsError(
        expected: Throwable,
        actual: DetailUiState,
    ) {
        val error = actual as? DetailUiState.Error ?: error("expected DetailUiState.Error, was $actual")
        assertEquals(expected::class, error.cause::class)
        assertEquals(expected.message, error.cause.message)
    }

    private class FakeMedia(
        var detailFailure: Throwable? = null,
    ) : MediaRepository {
        override val searchResults: StateFlow<SearchResults> = MutableStateFlow(SearchResults.EMPTY)

        var lastMediaId: String? = null
            private set

        override suspend fun search(query: String): Unit = error("not exercised by DetailViewModel")

        override suspend fun loadMoreResults(): Unit = error("not exercised by DetailViewModel")

        override suspend fun detail(mediaId: String): Media {
            lastMediaId = mediaId
            detailFailure?.let { throw it }
            return MEDIA
        }
    }

    private class FakeLibrary(
        private val entry: LibraryEntry? = null,
        var updateResult: LibraryEntry = ENTRY,
        var updateFailure: Throwable? = null,
        var addResult: LibraryEntry = ENTRY,
        var addFailure: Throwable? = null,
    ) : LibraryRepository {
        var lastPatch: LibraryPatch? = null
            private set

        var updateCalls = 0
            private set

        var lastAddSource: MediaSource? = null
            private set

        var lastAddExternalId: String? = null
            private set

        var lastEntryForMediaId: String? = null
            private set

        override fun observeLibrary() = error("not exercised by DetailViewModel")

        override suspend fun refresh(): Unit = error("not exercised by DetailViewModel")

        override suspend fun loadMore(): Unit = error("not exercised by DetailViewModel")

        override suspend fun applyFilter(filter: com.anarky.showtrack.core.model.LibraryFilter): Unit =
            error("not exercised by DetailViewModel")

        override suspend fun add(
            source: MediaSource,
            externalId: String,
        ): LibraryEntry {
            lastAddSource = source
            lastAddExternalId = externalId
            addFailure?.let { throw it }
            return addResult
        }

        override suspend fun update(
            entryId: String,
            patch: LibraryPatch,
        ): LibraryEntry {
            lastPatch = patch
            updateCalls++
            updateFailure?.let { throw it }
            return updateResult
        }

        override suspend fun entryForMedia(mediaId: String): LibraryEntry? {
            lastEntryForMediaId = mediaId
            return entry
        }
    }

    private companion object {
        val MEDIA =
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
            )

        val ENTRY =
            LibraryEntry(
                id = "entry-1",
                status = UserMediaStatus.WATCHING,
                score = BigDecimal("8.5"),
                progress = 3,
                favorite = false,
                updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
                media = MEDIA,
            )
    }
}
