package com.anarky.showtrack.feature.favorites

import androidx.lifecycle.SavedStateHandle
import com.anarky.showtrack.core.data.paging.Page
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.MediaType
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The See all grid: it reads its type from the route, asks only for that type, and a new sort
 * starts again from the first page. Robolectric because `toRoute` decodes through Android's Bundle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FavoritesGridViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the grid loads its route's type, and a new sort reloads from the first page`() =
        runTest(dispatcher) {
            val severance = favourite(id = "severance", type = MediaType.TV, score = "9.0")
            val repository =
                FakeLibraryRepository(mutableMapOf((MediaType.TV to null) to Page(listOf(severance), null)))
            val viewModel = FavoritesGridViewModel(SavedStateHandle(mapOf("type" to "tv")), repository)

            viewModel.refresh()
            advanceUntilIdle()
            viewModel.selectSort(FavoritesGridSort.TITLE)
            advanceUntilIdle()

            assertEquals(MediaType.TV, viewModel.type)
            assertEquals(
                listOf(
                    PageRequest(MediaType.TV, LibrarySort.SCORE, null, FAVORITES_PAGE_SIZE),
                    PageRequest(MediaType.TV, LibrarySort.TITLE, null, FAVORITES_PAGE_SIZE),
                ),
                repository.pageRequests,
            )
            assertEquals(listOf(severance), (viewModel.state.value as FavoritesGridUiState.Success).shelf.entries)
        }

    @Test
    fun `a refused removal puts the poster back at its old place`() =
        runTest(dispatcher) {
            val first = favourite(id = "a", type = MediaType.TV)
            val second = favourite(id = "b", type = MediaType.TV)
            val third = favourite(id = "c", type = MediaType.TV)
            val repository =
                FakeLibraryRepository(mutableMapOf((MediaType.TV to null) to Page(listOf(first, second, third), null)))
            val viewModel = FavoritesGridViewModel(SavedStateHandle(mapOf("type" to "tv")), repository)
            viewModel.refresh()
            advanceUntilIdle()
            repository.updateFailure = IOException("offline")

            viewModel.remove(second)
            advanceUntilIdle()

            assertEquals(
                listOf(first, second, third),
                (viewModel.state.value as FavoritesGridUiState.Success).shelf.entries,
            )
        }
}
