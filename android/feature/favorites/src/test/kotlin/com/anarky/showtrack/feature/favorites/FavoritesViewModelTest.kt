package com.anarky.showtrack.feature.favorites

import app.cash.turbine.test
import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.MediaType
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The tab's behaviour: what each read asks for, how the three parts are assembled, paging that
 * cannot double up, and removal that puts a poster back exactly where it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun repository() =
        FakeLibraryRepository(
            mutableMapOf(
                (null to null) to Page(listOf(FRIEREN, SEVERANCE, UNSCORED), null),
                (MediaType.ANIME to null) to Page(listOf(FRIEREN, UNSCORED), "anime-2"),
                (MediaType.TV to null) to Page(listOf(SEVERANCE), null),
                (MediaType.ANIME to "anime-2") to Page(listOf(MADE_IN_ABYSS, FRIEREN), null),
            ),
        )

    private fun FavoritesViewModel.success() = state.value as FavoritesUiState.Success

    @Test
    fun `refresh builds a scored podium and one shelf per type, all by score`() =
        runTest(dispatcher) {
            val repository = repository()
            val viewModel = FavoritesViewModel(repository)

            viewModel.refresh()
            advanceUntilIdle()

            val success = viewModel.success()
            assertEquals(listOf(FRIEREN, SEVERANCE), success.podium)
            assertEquals(listOf(FRIEREN, UNSCORED), success.anime.entries)
            assertEquals("anime-2", success.anime.nextCursor)
            assertEquals(listOf(SEVERANCE), success.tv.entries)
            assertTrue(repository.pageRequests.all { it.sort == LibrarySort.SCORE })
            assertEquals(PODIUM_SIZE + 1, repository.pageRequests.single { it.type == null }.limit)
        }

    @Test
    fun `a failed first load is an error, a failed refresh over rows keeps them as stale`() =
        runTest(dispatcher) {
            val repository = repository()
            val viewModel = FavoritesViewModel(repository)
            repository.pageFailure = IOException("offline")
            viewModel.refresh()
            advanceUntilIdle()
            assertTrue(viewModel.state.value is FavoritesUiState.Error)

            repository.pageFailure = null
            viewModel.refresh()
            advanceUntilIdle()
            repository.pageFailure = IOException("offline")
            viewModel.refresh()
            advanceUntilIdle()

            assertTrue(viewModel.success().isStale)
            assertEquals(listOf(FRIEREN, UNSCORED), viewModel.success().anime.entries)
        }

    @Test
    fun `loadMore appends the next page of that shelf only, without repeating an id`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository())
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.loadMore(MediaType.ANIME)
            advanceUntilIdle()
            viewModel.loadMore(MediaType.ANIME) // exhausted: no cursor, no request
            advanceUntilIdle()

            assertEquals(listOf(FRIEREN, UNSCORED, MADE_IN_ABYSS), viewModel.success().anime.entries)
            assertEquals(null, viewModel.success().anime.nextCursor)
            assertEquals(listOf(SEVERANCE), viewModel.success().tv.entries)
        }

    /** The page belonged to the rows a newer refresh replaced; appending it would mix two loads. */
    @Test
    fun `a page that lands after a newer refresh started is dropped`() =
        runTest(dispatcher) {
            val repository = repository()
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            repository.pageGate = gate
            viewModel.loadMore(MediaType.ANIME)
            advanceUntilIdle()
            repository.pages[MediaType.ANIME to null] = Page(listOf(UNSCORED), null)
            viewModel.refresh() // starts while the load-more request is still parked on the gate
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(UNSCORED), viewModel.success().anime.entries)
        }

    @Test
    fun `removing takes the poster off the podium and its shelf at once, then offers undo`() =
        runTest(dispatcher) {
            val repository = repository()
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.remove(FRIEREN)
                assertEquals(listOf(SEVERANCE), viewModel.success().podium)
                assertEquals(listOf(UNSCORED), viewModel.success().anime.entries)
                advanceUntilIdle()

                assertEquals(FavoritesEvent.Removed(FRIEREN), awaitItem())
                assertEquals(listOf(FRIEREN.id to LibraryPatch(favorite = false)), repository.updates)
            }
        }

    @Test
    fun `a refused removal puts the poster back in the same place and says so`() =
        runTest(dispatcher) {
            val repository = repository()
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()
            repository.updateFailure = IOException("offline")

            viewModel.events.test {
                viewModel.remove(FRIEREN)
                advanceUntilIdle()

                assertEquals(FavoritesEvent.EditFailed, awaitItem())
                assertEquals(listOf(FRIEREN, SEVERANCE), viewModel.success().podium)
                assertEquals(listOf(FRIEREN, UNSCORED), viewModel.success().anime.entries)
            }
        }

    @Test
    fun `undo restores the poster in the same place and favourites it again`() =
        runTest(dispatcher) {
            val repository = repository()
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()
            viewModel.remove(FRIEREN)
            advanceUntilIdle()

            viewModel.undo(FRIEREN)
            advanceUntilIdle()

            assertEquals(listOf(FRIEREN, SEVERANCE), viewModel.success().podium)
            assertEquals(listOf(FRIEREN, UNSCORED), viewModel.success().anime.entries)
            assertEquals(LibraryPatch(favorite = true), repository.updates.last().second)
        }

    /**
     * A refresh fetched while the removal was in flight still lists the title; it must stay
     * hidden. A refresh started after the server confirmed it shows the server's truth, so a title
     * re-favourited elsewhere comes back.
     */
    @Test
    fun `a refresh racing a removal keeps it hidden, a later one shows the server's answer`() =
        runTest(dispatcher) {
            val repository = repository()
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            val updateGate = CompletableDeferred<Unit>()
            repository.updateGate = updateGate
            viewModel.remove(FRIEREN)
            viewModel.refresh() // the server still lists FRIEREN
            advanceUntilIdle()
            assertEquals(listOf(UNSCORED), viewModel.success().anime.entries)

            updateGate.complete(Unit)
            advanceUntilIdle()
            viewModel.refresh() // started after the removal was confirmed; FRIEREN was re-added
            advanceUntilIdle()
            assertEquals(listOf(FRIEREN, UNSCORED), viewModel.success().anime.entries)
        }

    /** The shelf would otherwise show a spinner forever and never page again. */
    @Test
    fun `a refresh that fails while a page is loading clears that shelf's spinner`() =
        runTest(dispatcher) {
            val repository = repository()
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            repository.pageGate = gate
            viewModel.loadMore(MediaType.ANIME)
            advanceUntilIdle()
            repository.pageFailure = IOException("offline")
            viewModel.refresh()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(false, viewModel.success().anime.loadingMore)
            assertTrue(viewModel.success().isStale)
        }

    @Test
    fun `removing a podium title moves the next scored favourite up`() =
        runTest(dispatcher) {
            val repository = repository()
            repository.pages[null to null] = Page(listOf(FRIEREN, SEVERANCE, MADE_IN_ABYSS, FOURTH), null)
            val viewModel = FavoritesViewModel(repository)
            viewModel.refresh()
            advanceUntilIdle()

            viewModel.remove(FRIEREN)

            assertEquals(listOf(SEVERANCE, MADE_IN_ABYSS, FOURTH), viewModel.success().podium.take(PODIUM_SIZE))
        }

    private companion object {
        val FRIEREN = favourite(id = "frieren", title = "Frieren: Beyond Journey's End", score = "9.5")
        val SEVERANCE = favourite(id = "severance", title = "Severance", type = MediaType.TV, score = "9.0")
        val UNSCORED = favourite(id = "unscored", title = "Mushishi")
        val MADE_IN_ABYSS = favourite(id = "abyss", title = "Made in Abyss", score = "8.5")
        val FOURTH = favourite(id = "mononoke", title = "Mononoke", score = "8.0")
    }
}
