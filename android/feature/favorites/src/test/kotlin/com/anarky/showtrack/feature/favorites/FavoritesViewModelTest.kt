package com.anarky.showtrack.feature.favorites

import app.cash.turbine.test
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 *
 * [FavoritesViewModel] has no `init { refresh() }` (review finding, round 2 — see that class's own
 * KDoc for why): `FavoritesScreen`'s `LifecycleResumeEffect` is the only thing that ever calls
 * [FavoritesViewModel.refresh] in production, and there is no Composable here to fire it. Every
 * test below calls it explicitly, once, right after construction — standing in for that first
 * resume.
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
    fun `the feed reads the favourites surface, never the general library one`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            assertEquals(
                FavoritesUiState.Success(entries = listOf(FRIEREN, BEBOP)),
                viewModel.state.value,
            )
        }

    /**
     * [FavoritesViewModel.refresh]'s own data-shape half of the acceptance criterion:
     * favouriting/unfavouriting happens on Detail or Library, never on this screen, so a fresh
     * `refresh()` re-reading `favorite=true` from the server must drop a title the response no
     * longer includes. This is a WHITE-BOX unit test of `refresh()` in isolation — calling it
     * directly is not something a user can do. The other half — that a user's actual round trip
     * (Favorites -> Detail -> unfavourite -> Back) ever CALLS `refresh()` at all — is
     * [FavoritesResumeTest], which drives it through a real `Lifecycle` resume rather than this
     * shortcut (review finding: a ViewModel-only test cannot see whether anything in the
     * Composable layer actually triggers it).
     */
    @Test
    fun `refresh drops an entry the server no longer returns, once called`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN, BEBOP))
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
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
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
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
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            assertEquals(FavoritesUiState.Error(failure), viewModel.state.value)
        }

    /**
     * The Important finding this round exists to close: an earlier version of [FavoritesViewModel.refresh]
     * wrote [FavoritesUiState.Loading] UNCONDITIONALLY, so every resume over an already-populated
     * screen — the exact path [FavoritesResumeTest] drives — blanked the list to a full-screen
     * spinner for the round trip's duration and, at the Compose layer, reset scroll position and
     * dropped pages 2..n: the identical failure class this screen's own dedicated paginator exists
     * to avoid, reintroduced here by a different route. `repository.refreshGate` is what makes
     * this observable at all — every fake before this round resolved synchronously, so `Loading`
     * was never actually visible mid-flight, which is exactly why this shipped unnoticed the first
     * time.
     */
    @Test
    fun `a resume over an already-populated screen keeps the stale list, not a spinner, mid-fetch`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN))
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()
            assertEquals(FavoritesUiState.Success(entries = listOf(FRIEREN)), viewModel.state.value)

            repository.refreshResult = listOf(FRIEREN, BEBOP)
            repository.refreshGate = CompletableDeferred()
            viewModel.refresh()
            advanceUntilIdle()

            // Still the OLD entries, and still Success — never FavoritesUiState.Loading — while the
            // network round trip this resume triggered is genuinely still in flight.
            assertEquals(FavoritesUiState.Success(entries = listOf(FRIEREN)), viewModel.state.value)

            repository.refreshGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(FavoritesUiState.Success(entries = listOf(FRIEREN, BEBOP)), viewModel.state.value)
        }

    /**
     * The Important finding round 3 exists to close: a resume's FAILED background re-fetch used
     * to replace a working, populated screen with [FavoritesUiState.Error] wholesale — a train
     * entering a tunnel while the user is reading 40 rows would blank them to a centred error and
     * a Retry button, for a request the user never asked for. Decision C-B's objection was never
     * to showing older rows — only to showing them UNMARKED — and this fix had already accepted
     * showing possibly-stale rows with no marker at all (the round-2 fix's own in-flight window),
     * so a bare `Error` on failure was the wrong direction on both axes: it REMOVES information
     * instead of adding the marker that was missing. `isStale = true` is that marker.
     */
    @Test
    fun `a failed resume over an already-populated screen marks it stale instead of replacing it`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN))
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()
            assertEquals(FavoritesUiState.Success(entries = listOf(FRIEREN)), viewModel.state.value)

            val failure = IOException("offline")
            repository.refreshGate = CompletableDeferred()
            repository.refreshFailure = failure
            viewModel.refresh()
            advanceUntilIdle()

            // Still the OLD entries, and still Success — never FavoritesUiState.Error — while the
            // failing round trip is genuinely still in flight.
            assertEquals(FavoritesUiState.Success(entries = listOf(FRIEREN)), viewModel.state.value)

            repository.refreshGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                FavoritesUiState.Success(entries = listOf(FRIEREN), isStale = true),
                viewModel.state.value,
            )
        }

    @Test
    fun `a successful resume clears a previous stale mark`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN))
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            repository.refreshFailure = IOException("offline")
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(
                FavoritesUiState.Success(entries = listOf(FRIEREN), isStale = true),
                viewModel.state.value,
            )

            repository.refreshFailure = null
            repository.refreshResult = listOf(FRIEREN, BEBOP)
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(
                FavoritesUiState.Success(entries = listOf(FRIEREN, BEBOP), isStale = false),
                viewModel.state.value,
            )
        }

    /**
     * The re-entrancy guard task 9c.8 (E-M) adds: `FavoritesScreen` wires the SAME [FavoritesViewModel.refresh]
     * to both `LifecycleResumeEffect` and `StaleDataBanner`/[FavoritesUiState.Error]'s retry
     * action, so a manual retry can land while a resume-triggered fetch is still in flight. Asserts
     * the repository call COUNT, not the resulting state — a count on a fake cannot pass when the
     * call never happens, which is what makes it discriminate.
     */
    @Test
    fun `a retry landing inside an in-flight resume fetch does not double-fetch`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN))
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()
            assertEquals(1, repository.refreshCalls)

            repository.refreshGate = CompletableDeferred()
            viewModel.refresh() // the resume-triggered fetch
            viewModel.refresh() // a manual retry landing while it is still in flight
            advanceUntilIdle()

            // Only the resume's own call — the retry that landed inside it must be dropped, not
            // queued behind it.
            assertEquals(2, repository.refreshCalls)

            repository.refreshGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, repository.refreshCalls)
        }

    @Test
    fun `retrying after a failed load shows loading immediately, not the stale error`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val repository = FakeLibraryRepository(refreshFailure = failure)
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
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

    /**
     * BLOCKING 4's ViewModel half (whole-branch fix round). `refreshInFlight` existed here and
     * guarded [FavoritesViewModel.refresh] only; [FavoritesViewModel.loadMore] — driving the same
     * repository shape `DiscoverViewModel.loadMore` drives, which HAS this half — did not use it,
     * so a resume-driven refresh and a scroll-driven page fetch were free to overlap.
     *
     * Two assertions, and both are needed. The first pins the DEFER: no `loadMoreFavorites()` while
     * the refresh is in flight. The second pins the RE-ISSUE, which is what makes the drop legal
     * under the project's dropped-call rule — `EndOfListTrigger` fires only on its own false->true
     * edge, and a dropped `loadMore()` changes neither the item count nor the scroll position, so
     * nothing in the Compose layer would ever ask again. A bare `if (refreshInFlight) return`
     * passes the first assertion and fails the second.
     */
    @Test
    fun `a loadMore during an in-flight refresh is deferred, then re-issued`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN))
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
            advanceUntilIdle()

            repository.refreshGate = CompletableDeferred()
            viewModel.refresh()
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(0, repository.loadMoreCalls)

            repository.refreshGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, repository.loadMoreCalls)
        }

    @Test
    fun `loadMore is not fired again while one is in flight`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN))
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
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
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
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
            viewModel.refresh() // stands in for LifecycleResumeEffect's first call — see class KDoc
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
