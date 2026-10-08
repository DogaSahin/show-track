package com.anarky.showtrack.feature.detail

import androidx.lifecycle.SavedStateHandle
import com.anarky.showtrack.core.model.Episode
import com.anarky.showtrack.core.model.EpisodeList
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.Review
import com.anarky.showtrack.core.model.Season
import com.anarky.showtrack.feature.detail.DetailViewModelTest.Companion.ENTRY
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant

/** The Episodes section through the real ViewModel: what each action sends and how it settles. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DetailEpisodesTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        library: DetailViewModelTest.FakeLibrary,
        media: DetailViewModelTest.FakeMedia = DetailViewModelTest.FakeMedia().also { it.episodesResult = LIST },
    ) = DetailViewModel(
        SavedStateHandle(mapOf("mediaId" to "media-1")),
        media,
        library,
        FakeGroupRepository(),
        FakeAuthRepository(),
    )

    private val DetailViewModel.ready: EpisodesState.Ready
        get() = (state.value as DetailUiState.Success).episodes as EpisodesState.Ready

    @Test
    fun `tapping an episode sends one request and takes the recounted progress`() =
        runTest(dispatcher) {
            val library =
                DetailViewModelTest.FakeLibrary(entry = ENTRY).also {
                    it.setWatchedResult =
                        ENTRY.copy(progress = 1)
                }
            val viewModel = viewModel(library)
            advanceUntilIdle()

            viewModel.toggleEpisode(EP1)
            advanceUntilIdle()

            assertEquals(listOf(setOf("e1") to true), library.setWatchedCalls)
            assertTrue("e1" in viewModel.ready.watched)
            assertEquals(1, (viewModel.state.value as DetailUiState.Success).data.entry?.progress)
        }

    @Test
    fun `marking past a gap offers to catch up, and accepting sends the gap in one batch`() =
        runTest(dispatcher) {
            val library = DetailViewModelTest.FakeLibrary(entry = ENTRY)
            val viewModel = viewModel(library)
            advanceUntilIdle()

            viewModel.toggleEpisode(EP3)
            advanceUntilIdle()
            assertEquals(
                CatchUp(season = 1, fromNumber = 1, toNumber = 2, episodeIds = setOf("e1", "e2")),
                viewModel.ready.catchUp,
            )

            viewModel.acceptCatchUp(viewModel.ready.catchUp!!)
            advanceUntilIdle()

            assertEquals(listOf(setOf("e3") to true, setOf("e1", "e2") to true), library.setWatchedCalls)
            assertNull(viewModel.ready.catchUp)
            assertEquals(setOf("e1", "e2", "e3"), viewModel.ready.watched)
        }

    @Test
    fun `dismissing the prompt keeps just the one episode`() =
        runTest(dispatcher) {
            val library = DetailViewModelTest.FakeLibrary(entry = ENTRY)
            val viewModel = viewModel(library)
            advanceUntilIdle()

            viewModel.toggleEpisode(EP3)
            viewModel.dismissCatchUp(viewModel.ready.catchUp!!)
            advanceUntilIdle()

            assertEquals(listOf(setOf("e3") to true), library.setWatchedCalls)
            assertEquals(setOf("e3"), viewModel.ready.watched)
        }

    @Test
    fun `a failed save puts back exactly its own episodes and says so`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val library =
                DetailViewModelTest.FakeLibrary(entry = ENTRY).also {
                    it.setWatchedGate = gate
                    it.setWatchedFailure = IOException("offline")
                }
            val viewModel = viewModel(library)
            advanceUntilIdle()

            viewModel.toggleEpisode(EP1)
            advanceUntilIdle()
            // Optimistic: already ticked while the request is out.
            assertTrue("e1" in viewModel.ready.watched)
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(emptySet<String>(), viewModel.ready.watched)
            assertTrue((viewModel.state.value as DetailUiState.Success).actionError is DetailActionError.Edit)
        }

    @Test
    fun `an episode not aired yet cannot be ticked`() =
        runTest(dispatcher) {
            val library = DetailViewModelTest.FakeLibrary(entry = ENTRY)
            val viewModel = viewModel(library)
            advanceUntilIdle()

            viewModel.toggleEpisode(UNAIRED)
            advanceUntilIdle()

            assertEquals(emptyList<Pair<Set<String>, Boolean>>(), library.setWatchedCalls)
        }

    @Test
    fun `marking a season sends only its aired episodes not yet watched`() =
        runTest(dispatcher) {
            val library = DetailViewModelTest.FakeLibrary(entry = ENTRY).also { it.watched = setOf("e1") }
            val viewModel = viewModel(library)
            advanceUntilIdle()

            viewModel.markSeason(1, watched = true)
            advanceUntilIdle()

            assertEquals(listOf(setOf("e2", "e3") to true), library.setWatchedCalls)
        }

    @Test
    fun `not in the library shows the seasons without circles, and adding turns them on`() =
        runTest(dispatcher) {
            val library = DetailViewModelTest.FakeLibrary(entry = null, addResult = ENTRY)
            val viewModel = viewModel(library)
            advanceUntilIdle()
            assertEquals(false, viewModel.ready.tracking)

            viewModel.toggleEpisode(EP1)
            viewModel.addToLibrary()
            advanceUntilIdle()

            assertEquals(emptyList<Pair<Set<String>, Boolean>>(), library.setWatchedCalls)
            assertEquals(true, viewModel.ready.tracking)
        }

    @Test
    fun `removing from the library clears the entry and the circles`() =
        runTest(dispatcher) {
            val library = DetailViewModelTest.FakeLibrary(entry = ENTRY).also { it.watched = setOf("e1") }
            val viewModel = viewModel(library)
            advanceUntilIdle()

            viewModel.removeFromLibrary()
            advanceUntilIdle()

            assertEquals(1, library.removeCalls)
            assertNull((viewModel.state.value as DetailUiState.Success).data.entry)
            assertEquals(false, viewModel.ready.tracking)
            assertEquals(emptySet<String>(), viewModel.ready.watched)
        }

    @Test
    fun `a list the server has not fetched yet says so`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    DetailViewModelTest.FakeLibrary(entry = ENTRY),
                    media = DetailViewModelTest.FakeMedia(),
                )
            advanceUntilIdle()

            assertEquals(EpisodesState.NotAvailable, (viewModel.state.value as DetailUiState.Success).episodes)
        }

    @Test
    fun `adding while the episodes are still loading still turns the circles on`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val media =
                DetailViewModelTest.FakeMedia().also {
                    it.episodesResult = LIST
                    it.episodesGate = gate
                }
            val library = DetailViewModelTest.FakeLibrary(entry = null, addResult = ENTRY)
            val viewModel = viewModel(library, media)
            advanceUntilIdle()

            viewModel.addToLibrary()
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(true, viewModel.ready.tracking)
        }

    @Test
    fun `a save that answers after a removal does not bring the entry back`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val library = DetailViewModelTest.FakeLibrary(entry = ENTRY).also { it.setWatchedGate = gate }
            val viewModel = viewModel(library)
            advanceUntilIdle()

            viewModel.toggleEpisode(EP1)
            advanceUntilIdle()
            viewModel.removeFromLibrary()
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertNull((viewModel.state.value as DetailUiState.Success).data.entry)
        }

    @Test
    fun `answering an older prompt leaves the newer one alone`() =
        runTest(dispatcher) {
            val library = DetailViewModelTest.FakeLibrary(entry = ENTRY)
            val viewModel = viewModel(library)
            advanceUntilIdle()
            viewModel.toggleEpisode(EP3) // offers E1-E2
            val older = viewModel.ready.catchUp!!
            viewModel.toggleEpisode(EP2) // offers E1
            val newer = viewModel.ready.catchUp
            assertTrue(newer != null && newer != older)

            viewModel.dismissCatchUp(older)

            assertEquals(newer, viewModel.ready.catchUp)
        }

    @Test
    fun `deleting your review removes it at once, before the group list reloads`() =
        runTest(dispatcher) {
            val key = DetailViewModelTest.GROUP_ID to "media-1"
            val groups = FakeGroupRepository().also { it.reviewsResults[key] = listOf(DetailViewModelTest.MY_REVIEW) }
            val viewModel = groupViewModel(groups)
            viewModel.setActiveGroup(DetailViewModelTest.GROUP_ID)
            advanceUntilIdle()
            // The reload after the delete is held, and would still answer with the old review.
            val gate = CompletableDeferred<Unit>()
            groups.reviewsGates[key] = gate

            viewModel.deleteReview()
            advanceUntilIdle()

            assertEquals(listOf(DetailViewModelTest.MY_REVIEW.id), groups.deletedReviews)
            val section = (viewModel.state.value as DetailUiState.Success).groupSection as GroupSectionState.Loaded
            assertEquals(emptyList<Review>(), section.reviews)
            gate.complete(Unit)
        }

    @Test
    fun `a failed delete keeps the review and says so`() =
        runTest(dispatcher) {
            val key = DetailViewModelTest.GROUP_ID to "media-1"
            val groups =
                FakeGroupRepository().also {
                    it.reviewsResults[key] = listOf(DetailViewModelTest.MY_REVIEW)
                    it.deleteReviewFailure = GroupFailure.Network
                }
            val viewModel = groupViewModel(groups)
            viewModel.setActiveGroup(DetailViewModelTest.GROUP_ID)
            advanceUntilIdle()

            viewModel.deleteReview()
            advanceUntilIdle()

            val success = viewModel.state.value as DetailUiState.Success
            assertEquals(
                listOf(DetailViewModelTest.MY_REVIEW),
                (success.groupSection as GroupSectionState.Loaded).reviews,
            )
            assertEquals(GroupFailure.Network, success.reviewDeleteError)
            assertEquals(false, success.deletingReview)
        }

    @Test
    fun `a review already deleted elsewhere counts as deleted`() =
        runTest(dispatcher) {
            val key = DetailViewModelTest.GROUP_ID to "media-1"
            val groups =
                FakeGroupRepository().also {
                    it.reviewsResults[key] = listOf(DetailViewModelTest.MY_REVIEW)
                    it.deleteReviewFailure = GroupFailure.NoSuchEntry
                }
            val viewModel = groupViewModel(groups)
            viewModel.setActiveGroup(DetailViewModelTest.GROUP_ID)
            advanceUntilIdle()
            groups.reviewsResults[key] = emptyList()

            viewModel.deleteReview()
            advanceUntilIdle()

            val success = viewModel.state.value as DetailUiState.Success
            assertEquals(null, success.reviewDeleteError)
            assertEquals(emptyList<Review>(), (success.groupSection as GroupSectionState.Loaded).reviews)
        }

    private fun groupViewModel(groups: FakeGroupRepository) =
        DetailViewModel(
            SavedStateHandle(mapOf("mediaId" to "media-1")),
            DetailViewModelTest.FakeMedia(),
            DetailViewModelTest.FakeLibrary(entry = ENTRY),
            groups,
            FakeAuthRepository(),
        )

    private companion object {
        val EP1 = Episode(id = "e1", number = 1, title = "Pilot", airDate = null, aired = true)
        val EP2 = Episode(id = "e2", number = 2, title = null, airDate = null, aired = true)
        val EP3 = Episode(id = "e3", number = 3, title = null, airDate = null, aired = true)
        val UNAIRED = Episode(id = "e4", number = 4, title = null, airDate = null, aired = false)
        val LIST =
            EpisodeList(
                syncedAt = Instant.parse("2026-10-01T08:00:00Z"),
                totalEpisodes = 4,
                seasons = listOf(Season(number = 1, episodes = listOf(EP1, EP2, EP3, UNAIRED))),
            )
    }
}
