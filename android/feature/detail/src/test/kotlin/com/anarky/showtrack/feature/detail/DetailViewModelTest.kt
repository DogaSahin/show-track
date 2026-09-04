package com.anarky.showtrack.feature.detail

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.model.AuthFailure
import com.anarky.showtrack.core.model.GroupActor
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.MemberProgress
import com.anarky.showtrack.core.model.Review
import com.anarky.showtrack.core.model.ScoreChange
import com.anarky.showtrack.core.model.SearchResults
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.model.WatchlistEntry
import kotlinx.coroutines.CompletableDeferred
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
 *
 * `@Suppress("LargeClass")` (task 9c.7) — `GroupDetailViewModelTest`'s own identical suppression
 * and identical reasoning, one feature over: this class pins load, edit, the group section,
 * propose AND (as of this task) the review editor for ONE screen's ONE ViewModel; splitting it by
 * sub-concern would scatter the fixtures every test shares (`FakeMedia`/`FakeLibrary`/
 * `FakeGroupRepository`/`FakeAuthRepository`, the `savedState`/`assertIsError` helpers, the
 * `MEDIA`/`ENTRY`/`MY_REVIEW` companion fixtures) for a lint threshold's sake rather than a real
 * cohesion problem.
 */
@Suppress("LargeClass")
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
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = null),
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
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
            DetailViewModel(savedState("media-42"), media, library, FakeGroupRepository(), FakeAuthRepository())
            advanceUntilIdle()

            assertEquals("media-42", media.lastMediaId)
            assertEquals("media-42", library.lastEntryForMediaId)
        }

    @Test
    fun `a title already in the library loads with its entry`() =
        runTest(dispatcher) {
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            assertEquals(ENTRY, (viewModel.state.value as DetailUiState.Success).data.entry)
            assertEquals(MEDIA, (viewModel.state.value as DetailUiState.Success).data.media)
        }

    @Test
    fun `a failing load reports Error instead of escaping the coroutine`() =
        runTest(dispatcher) {
            val failure = IOException("offline")
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(detailFailure = failure),
                    FakeLibrary(),
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )

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
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    media,
                    FakeLibrary(),
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )

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

    /**
     * Fix round 2, coordinator finding 5: `load()`'s success branch used to name only two of
     * (now) seven `DetailUiState.Success` fields, silently resetting every other one to its
     * default whenever `load()` ran with a Success already on screen. Unreachable through any
     * wired button today (retry() is only wired from the Error branch — this class's own KDoc),
     * but `retry()` itself is a public function with no such guard, so this drives it directly
     * while a Success — with a field `load()` itself never touches — is already showing.
     */
    @Test
    fun `retrying while a Success already exists preserves fields load does not touch`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository(proposeResult = WATCHLIST_ENTRY)
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.proposeToGroup("group-9")
            advanceUntilIdle()
            assertEquals("group-9", (viewModel.state.value as DetailUiState.Success).justProposedToGroupId)

            viewModel.retry()
            advanceUntilIdle()

            assertEquals("group-9", (viewModel.state.value as DetailUiState.Success).justProposedToGroupId)
        }

    @Test
    fun `changing the score sends only the score`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    library,
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
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
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    library,
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.clearScore()
            advanceUntilIdle()

            assertEquals(LibraryPatch(score = ScoreChange.Clear), library.lastPatch)
        }

    @Test
    fun `changing the progress sends only the progress`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    library,
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.setProgress(7)
            advanceUntilIdle()

            assertEquals(LibraryPatch(progress = 7), library.lastPatch)
        }

    @Test
    fun `toggling favorite sends the flipped value`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    library,
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.toggleFavorite()
            advanceUntilIdle()

            assertEquals(LibraryPatch(favorite = !ENTRY.favorite), library.lastPatch)
        }

    @Test
    fun `changing the status sends only the status`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    library,
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
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
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    library,
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
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
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    library,
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
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
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    library,
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )

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
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    library,
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
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
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    library,
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
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
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    library,
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
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

    // --- The group section (task 9c.6) -------------------------------------------------------

    @Test
    fun `the group section stays absent when there is no active group`() =
        runTest(dispatcher) {
            // Both ActiveGroupState.Loading/Error AND a genuinely-empty account collapse into this
            // same call from DetailScreen's stateful wrapper — GroupSectionState's own KDoc.
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.setActiveGroup(null)
            advanceUntilIdle()

            assertEquals(GroupSectionState.Absent, (viewModel.state.value as DetailUiState.Success).groupSection)
        }

    @Test
    fun `selecting an active group loads its progress and reviews`() =
        runTest(dispatcher) {
            val groups =
                FakeGroupRepository(
                    progressResults = mutableMapOf((GROUP_ID to "media-1") to listOf(PROGRESS_ROW)),
                    reviewsResults = mutableMapOf((GROUP_ID to "media-1") to listOf(REVIEW)),
                )
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()

            val section = (viewModel.state.value as DetailUiState.Success).groupSection as GroupSectionState.Loaded
            assertEquals(listOf(PROGRESS_ROW), section.progress)
            assertEquals(listOf(REVIEW), section.reviews)
            assertFalse(section.isStale)
        }

    @Test
    fun `a title nobody else tracks shows an empty Loaded section, not a broken one`() =
        runTest(dispatcher) {
            // §9.12's acceptance criterion. The fake's default (unconfigured) response for this
            // key is an empty list on both — the honest "nobody else tracks this yet" outcome.
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    FakeGroupRepository(),
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()

            assertEquals(
                GroupSectionState.Loaded(progress = emptyList(), reviews = emptyList()),
                (viewModel.state.value as DetailUiState.Success).groupSection,
            )
        }

    @Test
    fun `a failed first fetch for a group reports GroupSectionState Error`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository()
            groups.progressFailures[GROUP_ID to "media-1"] = GroupFailure.Network
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()

            assertEquals(
                GroupSectionState.Error(GroupFailure.Network),
                (viewModel.state.value as DetailUiState.Success).groupSection,
            )
        }

    @Test
    fun `a failed reload over an already-loaded section keeps the rows and marks them stale`() =
        runTest(dispatcher) {
            // The settled refresh shape (Global Constraints), applied to the group section: a
            // retry that fails must never blank or error away rows already on screen.
            val groups =
                FakeGroupRepository(progressResults = mutableMapOf((GROUP_ID to "media-1") to listOf(PROGRESS_ROW)))
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()
            val before = (viewModel.state.value as DetailUiState.Success).groupSection as GroupSectionState.Loaded
            assertEquals(listOf(PROGRESS_ROW), before.progress)
            assertFalse(before.isStale)

            groups.progressFailures[GROUP_ID to "media-1"] = GroupFailure.Network
            viewModel.retryGroupSection()
            advanceUntilIdle()

            val after = (viewModel.state.value as DetailUiState.Success).groupSection as GroupSectionState.Loaded
            assertEquals(listOf(PROGRESS_ROW), after.progress)
            assertTrue(after.isStale)
        }

    /**
     * One of the two pairs the Global Constraints call out by name: "the active group changing
     * while a progress or reviews fetch is in flight". Group A's fetch is held open with a gate
     * while the active group switches to B; B's own (ungated) fetch must land normally, and A's
     * late response — arriving only after the switch — must be DROPPED rather than overwriting B's
     * already-rendered rows. This is what [DetailViewModel.groupSectionGeneration] exists to
     * prevent; deleting its check is exactly the mutation this test is built to catch.
     */
    @Test
    fun `switching the active group while its fetch is in flight drops the stale response`() =
        runTest(dispatcher) {
            val groups =
                FakeGroupRepository(
                    progressResults =
                        mutableMapOf(
                            ("group-a" to "media-1") to listOf(PROGRESS_ROW),
                            ("group-b" to "media-1") to listOf(OTHER_PROGRESS_ROW),
                        ),
                )
            val groupAGate = CompletableDeferred<Unit>()
            groups.progressGates["group-a" to "media-1"] = groupAGate
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.setActiveGroup("group-a")
            advanceUntilIdle() // group A's fetch launches and suspends on groupAGate.

            viewModel.setActiveGroup("group-b")
            advanceUntilIdle() // group B's own fetch is ungated and lands immediately.

            val whileAPending =
                (viewModel.state.value as DetailUiState.Success).groupSection as GroupSectionState.Loaded
            assertEquals(listOf(OTHER_PROGRESS_ROW), whileAPending.progress)

            groupAGate.complete(Unit)
            advanceUntilIdle()

            val afterALands = (viewModel.state.value as DetailUiState.Success).groupSection as GroupSectionState.Loaded
            assertEquals(listOf(OTHER_PROGRESS_ROW), afterALands.progress)
        }

    /**
     * The OTHER named pair: "a group-scoped failure arriving while a library edit is in
     * progress". Decision C-S — one error channel per operation — means neither direction may
     * clobber the other: the edit finishing later must not erase the group failure, and the group
     * failure landing mid-edit must not touch `saving`/`actionError`. A `.copy()`-based update on
     * either channel that regressed to a field-by-field rebuild (Global Constraints' own named
     * failure shape) would fail one of the two assertions below.
     */
    @Test
    fun `a group section failure mid-edit and the edit finishing leave each other's channel untouched`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val updateGate = CompletableDeferred<Unit>()
            library.updateGate = updateGate
            val groups = FakeGroupRepository()
            groups.progressFailures[GROUP_ID to "media-1"] = GroupFailure.Network
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, groups, FakeAuthRepository())
            advanceUntilIdle()

            viewModel.setScore(BigDecimal("9.0"))
            // The synchronous half of edit() has already run — saving is true before the
            // coroutine that awaits updateGate is even dispatched.
            assertTrue((viewModel.state.value as DetailUiState.Success).saving)

            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()

            val midEdit = viewModel.state.value as DetailUiState.Success
            assertTrue("the edit must still be in flight", midEdit.saving)
            assertEquals(GroupFailure.Network, (midEdit.groupSection as GroupSectionState.Error).cause)

            updateGate.complete(Unit)
            advanceUntilIdle()

            val afterEdit = viewModel.state.value as DetailUiState.Success
            assertFalse(afterEdit.saving)
            assertNull(afterEdit.actionError)
            assertEquals(GroupFailure.Network, (afterEdit.groupSection as GroupSectionState.Error).cause)
        }

    /**
     * Fix round 1, BLOCKING B1's companion coverage gap (the reviewer's own item 1): the class
     * KDoc's canonical-[groupSection]-field race, forced deterministically. [FakeMedia.detailGate]
     * holds the TITLE load suspended in `mediaRepository.detail(...)` while the group-section
     * fetch — launched independently by [DetailViewModel.setActiveGroup] — runs to completion
     * first. `load()`'s own success branch reads the [DetailViewModel]-private `groupSection`
     * field when it finally builds its [DetailUiState.Success]; deleting `groupSection =
     * groupSection` there (reverting to the case class's own default, [GroupSectionState.Absent])
     * reddens this test without touching anything the group-switch/generation tests already cover.
     */
    @Test
    fun `the group section survives when it resolves before the title load does`() =
        runTest(dispatcher) {
            val titleGate = CompletableDeferred<Unit>()
            val media = FakeMedia()
            media.detailGate = titleGate
            val groups =
                FakeGroupRepository(progressResults = mutableMapOf((GROUP_ID to "media-1") to listOf(PROGRESS_ROW)))
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    media,
                    FakeLibrary(entry = null),
                    groups,
                    FakeAuthRepository(),
                )
            // load() is already suspended inside media.detail(), awaiting titleGate — nothing has
            // advanced it yet, so DetailUiState.Success does not exist for setActiveGroup to patch.

            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle() // resolves the group-section fetch fully; the title stays gated.

            assertEquals(DetailUiState.Loading, viewModel.state.value)

            titleGate.complete(Unit)
            advanceUntilIdle()

            val success = viewModel.state.value as DetailUiState.Success
            assertEquals(
                GroupSectionState.Loaded(progress = listOf(PROGRESS_ROW), reviews = emptyList()),
                success.groupSection,
            )
        }

    /**
     * Residue item (fix round 1): [FakeGroupRepository.reviewsFailures] existed but was never
     * exercised — this is the first test to use it. Pins a real, if unlovely, existing choice:
     * [DetailViewModel.reloadGroupSection] fetches progress and reviews inside ONE
     * `coroutineScope { }` (the class KDoc's own "neither call depends on the other" reasoning,
     * mirroring `load()`), so a reviews-only failure cancels the sibling `progress` call under
     * structured concurrency and errors the WHOLE section — a single flaky endpoint takes both
     * down together. Deliberate (a genuinely partial section — progress shown, reviews silently
     * missing — would misreport what the group actually has), but unpinned until now.
     */
    @Test
    fun `a reviews-only failure errors the whole section, not just the reviews half`() =
        runTest(dispatcher) {
            val groups =
                FakeGroupRepository(progressResults = mutableMapOf((GROUP_ID to "media-1") to listOf(PROGRESS_ROW)))
            groups.reviewsFailures[GROUP_ID to "media-1"] = GroupFailure.Network
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()

            assertEquals(
                GroupSectionState.Error(GroupFailure.Network),
                (viewModel.state.value as DetailUiState.Success).groupSection,
            )
        }

    // --- Propose to a group (task 9c.6) -------------------------------------------------------

    @Test
    fun `proposing to a group calls proposeTitle with that group and this title`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository(proposeResult = WATCHLIST_ENTRY)
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.proposeToGroup("group-9")
            advanceUntilIdle()

            assertEquals("group-9", groups.lastProposeGroupId)
            assertEquals("media-1", groups.lastProposeMediaId)
            val success = viewModel.state.value as DetailUiState.Success
            assertFalse(success.proposing)
            assertNull(success.proposeError)
        }

    @Test
    fun `a failed propose reports NoSuchTitle without disturbing the rest of the screen`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository(proposeFailure = GroupFailure.NoSuchTitle)
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.proposeToGroup("group-9")
            advanceUntilIdle()

            val success = viewModel.state.value as DetailUiState.Success
            assertEquals(GroupFailure.NoSuchTitle, success.proposeError)
            assertFalse(success.proposing)
            // The title itself, and its library entry, are untouched by a propose failure.
            assertEquals(ENTRY, success.data.entry)
        }

    @Test
    fun `a second propose is ignored while one is already in flight`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository(proposeResult = WATCHLIST_ENTRY)
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.proposeToGroup("group-9")
            viewModel.proposeToGroup("group-10")
            advanceUntilIdle()

            assertEquals(1, groups.proposeCalls)
            assertEquals("group-9", groups.lastProposeGroupId)
        }

    /**
     * Fix round 1, coordinator finding 4: a single-group propose opens no picker and no dialog,
     * so [DetailUiState.Success.justProposedToGroupId] is the ONLY feedback that action gets.
     * Deleting the `justProposedToGroupId = groupId` write on the success path reddens this test
     * without touching `proposing`/`proposeError`, which the existing propose tests already pin.
     */
    @Test
    fun `a successful propose records which group it went to`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository(proposeResult = WATCHLIST_ENTRY)
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.proposeToGroup("group-9")
            advanceUntilIdle()

            assertEquals("group-9", (viewModel.state.value as DetailUiState.Success).justProposedToGroupId)
        }

    /**
     * The banner is a ONE-SHOT confirmation, `GroupsUiState.Success.justCreated`'s own discipline:
     * a second propose — even a FAILED one — must clear the first success's stale confirmation
     * before its own result is known, or a failed retry would leave "Proposed to group-9" on
     * screen while the retry for group-10 is still in flight.
     */
    @Test
    fun `starting a second propose clears the previous propose's confirmation immediately`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository(proposeResult = WATCHLIST_ENTRY)
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.proposeToGroup("group-9")
            advanceUntilIdle()
            assertEquals("group-9", (viewModel.state.value as DetailUiState.Success).justProposedToGroupId)

            viewModel.proposeToGroup("group-10")

            // Cleared synchronously, before the second propose's own coroutine even launches —
            // the same "clear before a retry launches, not only on success" rule actionError/
            // proposeError already follow (decision C-S).
            assertNull((viewModel.state.value as DetailUiState.Success).justProposedToGroupId)
        }

    // --- Writing and editing a review (task 9c.7) ---------------------------------------------

    /**
     * The brief's own first named test, E-G. The editor is opened BEFORE the group section has
     * loaded — [DetailViewModel.findOwnReview]'s own honest-null case — so it starts as a fresh
     * draft ([ReviewEditorState.Open.reviewId] null). By the time [DetailViewModel.saveReview]
     * runs, the section HAS loaded (with this account's own existing review already in it, the
     * same list `list_group_reviews` returns to any of this account's own groups). `POST
     * /v1/reviews` 409s; [DetailViewModel.handleSaveFailure] resolves it to that review and sets
     * [ReviewEditorState.Open.confirmOverwrite] (fix round 1 — round 0's ORIGINAL shape retried
     * transparently as a `PATCH` here, with no confirmation step; that shape is GONE, replaced by
     * this two-tap one — see [ReviewEditorState.Open.confirmOverwrite]'s own KDoc for why). This
     * test drives BOTH taps: the first produces the confirm state with nothing yet sent, the
     * second is what actually reaches `PATCH`.
     */
    @Test
    fun `a 409 switches to editing the existing review rather than showing an error`() =
        runTest(dispatcher) {
            val groups =
                FakeGroupRepository(reviewsResults = mutableMapOf((GROUP_ID to "media-1") to listOf(MY_REVIEW)))
            groups.createReviewFailure = GroupFailure.AlreadyReviewed(existingReviewId = null)
            groups.updateReviewResult = MY_REVIEW.copy(body = "revised")
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.openReviewEditor()
            advanceUntilIdle()
            val opened = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertNull("the section had not loaded yet, so resolution must have failed at open time", opened.reviewId)

            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()

            viewModel.saveReview("revised", false)
            advanceUntilIdle()

            // Fix round 1: resolving a 409 no longer retries automatically — the reader has never
            // seen MY_REVIEW's own text, so nothing is sent yet. The editor switches to editing
            // (reviewId set) and asks for one more explicit tap (confirmOverwrite) instead.
            val confirming = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals(MY_REVIEW.id, confirming.reviewId)
            assertTrue(
                "a resolved 409 must ask for one more explicit Save, not send anything yet",
                confirming.confirmOverwrite,
            )
            assertNull("no error is ever shown for a RESOLVED 409 — E-G's own wording", confirming.error)
            assertFalse("nothing has been sent for this first tap", confirming.saving)
            assertEquals(listOf(Triple("media-1", "revised", false)), groups.createReviewCalls)
            assertEquals(0, groups.updateReviewCalls.size)

            // No error was ever shown (E-G's own wording), and the second, explicit Save tap is
            // what actually sends the PATCH.
            viewModel.saveReview("revised", false)
            advanceUntilIdle()

            assertEquals(ReviewEditorState.Closed, (viewModel.state.value as DetailUiState.Success).reviewEditor)
            assertEquals(listOf(Triple(MY_REVIEW.id, "revised", false)), groups.updateReviewCalls)
        }

    /**
     * Fix round 1, BLOCKING B2: a no-groups account writes a review, closes the editor, and reopens
     * it — WITHOUT ever having an active group to resolve from. Round 0 returned null from
     * [DetailViewModel.findOwnReview] unconditionally in that case, which locked the account out of
     * ever editing a review it had just written, permanently, in the same session.
     * [DetailViewModel.onReviewSaved] now caches the server's own response into `lastOwnReview`, and
     * [DetailViewModel.findOwnReview] falls back to it exactly when the section itself cannot answer.
     */
    @Test
    fun `a no-groups account can still edit a review it just wrote, from the cached copy`() =
        runTest(dispatcher) {
            val created = MY_REVIEW.copy(id = "review-fresh", body = "first draft")
            val groups = FakeGroupRepository()
            groups.createReviewResult = created
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            // No setActiveGroup call anywhere in this test — groupSection stays Absent throughout,
            // E-K's own no-groups state.

            viewModel.openReviewEditor()
            advanceUntilIdle()
            viewModel.saveReview("first draft", false)
            advanceUntilIdle()
            assertEquals(ReviewEditorState.Closed, (viewModel.state.value as DetailUiState.Success).reviewEditor)

            viewModel.openReviewEditor()
            advanceUntilIdle()

            val reopened = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals(created.id, reopened.reviewId)
            assertEquals("first draft", reopened.seedBody)
        }

    /**
     * Fix round 1, small item 1: [DetailViewModel.handleSaveFailure] used to rebuild from the
     * CAPTURED `editor` snapshot, so a close that ran during the in-flight create could be silently
     * resurrected as [ReviewEditorState.Open] once the 409 resolved. `saveReview`'s own gate holds
     * the create call suspended so [DetailViewModel.closeReviewEditor] can run in the genuine gap
     * between "the request was sent" and "the response arrived".
     */
    @Test
    fun `closing the editor while a 409 resolution is in flight does not resurrect it`() =
        runTest(dispatcher) {
            val groups =
                FakeGroupRepository(reviewsResults = mutableMapOf((GROUP_ID to "media-1") to listOf(MY_REVIEW)))
            val gate = CompletableDeferred<Unit>()
            groups.createReviewGate = gate
            groups.createReviewFailure = GroupFailure.AlreadyReviewed(existingReviewId = null)
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            // The editor opens BEFORE the section has loaded, so it resolves to a fresh draft
            // (reviewId == null) — test 1's own setup, needed here so the save below actually
            // reaches createReview (and its gate) rather than resolving straight to an edit.
            viewModel.openReviewEditor()
            advanceUntilIdle()
            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()

            viewModel.saveReview("typed while offline", false)
            // The create call has been made and is suspended on the gate — nothing has resolved yet.

            viewModel.closeReviewEditor()
            assertEquals(ReviewEditorState.Closed, (viewModel.state.value as DetailUiState.Success).reviewEditor)

            gate.complete(Unit)
            advanceUntilIdle()

            // The 409 resolves AFTER the close — handleSaveFailure must not reopen the form.
            assertEquals(ReviewEditorState.Closed, (viewModel.state.value as DetailUiState.Success).reviewEditor)
        }

    /**
     * Fix round 1, small item 6: `saveReview`'s `else` branch (a `PATCH`) routes its own failures
     * through the identical function a failed `POST` does — this pins that an update failure
     * reaches the ordinary error channel too, not only a create failure.
     */
    @Test
    fun `an update failure surfaces through the same error channel as a create failure`() =
        runTest(dispatcher) {
            val groups =
                FakeGroupRepository(reviewsResults = mutableMapOf((GROUP_ID to "media-1") to listOf(MY_REVIEW)))
            groups.updateReviewFailure = GroupFailure.Network
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()
            viewModel.openReviewEditor()
            advanceUntilIdle()
            val opened = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals(MY_REVIEW.id, opened.reviewId)

            viewModel.saveReview("edited", false)
            advanceUntilIdle()

            val editor = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals(ReviewSaveError.Remote(GroupFailure.Network), editor.error)
            assertFalse(editor.confirmOverwrite)
        }

    /**
     * Fix round 1, small item 5: decision C-S's clear-before-retry rule, extended to a keystroke —
     * [DetailViewModel.clearReviewError] is what [ReviewEditor.kt] calls the moment the reader
     * starts fixing the input, rather than leaving a stale validation message on screen until the
     * next Save tap.
     */
    @Test
    fun `clearReviewError clears a stale validation error`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository()
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.openReviewEditor()
            advanceUntilIdle()
            viewModel.saveReview("   ", false)
            advanceUntilIdle()
            assertEquals(
                ReviewSaveError.BodyRequired,
                ((viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open).error,
            )

            viewModel.clearReviewError()
            advanceUntilIdle()

            assertNull(((viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open).error)
        }

    /**
     * [DetailViewModel.openReviewEditor]'s own resolution branch, direct — no 409 round trip
     * needed at all when the section is ALREADY loaded by the time the reader taps "Write a
     * review": [DetailViewModel.findOwnReview] finds this account's own review synchronously, and
     * the editor opens straight into edit mode, pre-filled.
     */
    @Test
    fun `opening the editor when the section already has this account's own review resolves straight to editing`() =
        runTest(dispatcher) {
            val groups =
                FakeGroupRepository(reviewsResults = mutableMapOf((GROUP_ID to "media-1") to listOf(MY_REVIEW)))
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()

            viewModel.openReviewEditor()
            advanceUntilIdle()

            val editor = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals(MY_REVIEW.id, editor.reviewId)
            assertEquals(MY_REVIEW.body, editor.seedBody)
            assertEquals(MY_REVIEW.containsSpoilers, editor.seedContainsSpoilers)
        }

    /**
     * Fix round 2, SHOULD-FIX (proven by the coordinator's own scratch reproduction,
     * `expected:<review-mine> but was:<null>`): [DetailViewModel.onReviewSaved] caches
     * [lastOwnReview] and calls `reloadGroupSection()` — but round 1's own re-entrancy guard drops
     * that call whenever an OLDER `reviews()` fetch (launched before the write, e.g. by a resume)
     * is still in flight. That older fetch then lands with PRE-write data, `findOwnReview` trusts
     * its `Loaded` "no match" outright, and a fresher, correct [lastOwnReview] is discarded anyway
     * — the identical dead end BLOCKING B2 fixed for a no-groups account, reappearing WITH an
     * active group. [DetailViewModel.onReviewSaved] now bumps `groupSectionGeneration` before
     * reloading, reusing the SAME mechanism [DetailViewModel.setActiveGroup] already uses for a
     * group switch, so the older fetch's late landing is discarded instead.
     *
     * The two `reviews()` calls are given DIFFERENT gates (round 0's own `progressGates`/
     * `reviewsGates` per-key technique does not by itself separate two calls for the SAME key —
     * this test reassigns the map entry between the two, which only affects a call whose OWN gate
     * lookup happens AFTER the reassignment; the older, already-suspended call keeps awaiting the
     * SPECIFIC `CompletableDeferred` it already captured) so the test does not depend on dispatcher
     * scheduling order to decide which call sees which data.
     */
    @Test
    fun `a save does not lose the group section to an older, still in-flight reviews fetch`() =
        runTest(dispatcher) {
            val key = GROUP_ID to "media-1"
            val groups = FakeGroupRepository(reviewsResults = mutableMapOf(key to emptyList()))
            val oldFetchGate = CompletableDeferred<Unit>()
            groups.reviewsGates[key] = oldFetchGate
            val saved = MY_REVIEW.copy(id = "review-mine")
            groups.createReviewResult = saved
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()

            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()
            // The initial (generation 1) reviews() call is suspended on oldFetchGate; its progress()
            // sibling already resolved, but the enclosing coroutineScope waits for both.

            viewModel.openReviewEditor()
            advanceUntilIdle()

            // Simulate the write landing server-side: a FRESH reviews() call (not the one already
            // suspended above) would now see the review. Clearing the map entry only affects a
            // call whose OWN lookup happens from here on — the old, already-suspended call is
            // unaffected, since it is awaiting the SPECIFIC CompletableDeferred it already found.
            groups.reviewsGates.remove(key)
            groups.reviewsResults[key] = listOf(saved)

            viewModel.saveReview("first draft", false)
            advanceUntilIdle()

            assertEquals(
                "the save must trigger a SECOND reviews() fetch, not be dropped by the re-entrancy guard",
                2,
                groups.reviewsCalls.size,
            )
            val afterSave = (viewModel.state.value as DetailUiState.Success).groupSection as GroupSectionState.Loaded
            assertEquals(listOf(saved), afterSave.reviews)

            // The OLD (generation 1) fetch finally resolves — its result must be DISCARDED, not
            // overwrite what the fresh (generation 2) fetch already applied.
            oldFetchGate.complete(Unit)
            advanceUntilIdle()

            val afterOldFetchLands =
                (viewModel.state.value as DetailUiState.Success).groupSection as GroupSectionState.Loaded
            assertEquals(
                "the older fetch's late landing must not clobber the fresher, already-applied state",
                listOf(saved),
                afterOldFetchLands.reviews,
            )

            viewModel.openReviewEditor()
            advanceUntilIdle()
            val reopened = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals("review-mine", reopened.reviewId)
        }

    /**
     * Fix round 3, BLOCKING (the coordinator's own probe, reproduced here): the generation BUMP
     * (fix round 2) only decides which fetch's RESULT gets written — it says nothing about how
     * trustworthy an ALREADY-WRITTEN result still is. A save whose OWN post-save reload then FAILS
     * leaves the section exactly where the settled refresh shape says it should — holding the
     * PRE-write data, marked stale — and nothing else ever re-fetches it. Without
     * [DetailViewModel]'s own freshness stamp (`groupSectionAppliedGeneration` vs
     * `lastOwnReviewGeneration`), [DetailViewModel.findOwnReview] would trust that stale "no match"
     * PERMANENTLY, for the life of this ViewModel instance, over a [lastOwnReview] that is actually
     * correct — the identical dead end BLOCKING B2 fixed, reachable a THIRD way.
     */
    @Test
    fun `a failed post-save reload does not permanently hide the review behind stale pre-write data`() =
        runTest(dispatcher) {
            val key = GROUP_ID to "media-1"
            val groups = FakeGroupRepository(reviewsResults = mutableMapOf(key to emptyList()))
            val saved = MY_REVIEW.copy(id = "review-mine")
            groups.createReviewResult = saved
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()
            // The section is now Loaded(reviews = emptyList()) — nobody has reviewed this yet.

            viewModel.openReviewEditor()
            advanceUntilIdle()

            groups.reviewsFailures[key] = GroupFailure.Network
            viewModel.saveReview("first draft", false)
            advanceUntilIdle()

            val afterFailedReload =
                (viewModel.state.value as DetailUiState.Success).groupSection as GroupSectionState.Loaded
            assertTrue(
                "a failed reload keeps the OLD (pre-write) data, marked stale — the settled refresh shape",
                afterFailedReload.isStale,
            )
            assertEquals(emptyList<Review>(), afterFailedReload.reviews)

            viewModel.openReviewEditor()
            advanceUntilIdle()

            val reopened = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals(
                "the stale, pre-write section must not be trusted over the fresher cache",
                "review-mine",
                reopened.reviewId,
            )
        }

    /**
     * Fix round 3, BLOCKING's own companion: the freshness check has TWO ways to fail, not one.
     * This pins the direction the coordinator's probe did not — a section that genuinely IS
     * fresher than the cache (a successful load completed AFTER the write) must still be trusted
     * when it reports no match, not overridden by a now-stale [lastOwnReview]. Mutation-verified
     * separately: deleting `groupSectionAppliedGeneration = myGeneration` from
     * `reloadGroupSection`'s success branch leaves it stuck at its initial `-1` forever, so this
     * exact scenario would otherwise resolve to the stale cached id instead of `null`.
     */
    @Test
    fun `a genuinely fresh no-match is trusted over a now-stale cache`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository()
            groups.createReviewResult = MY_REVIEW.copy(id = "review-mine")
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.openReviewEditor()
            advanceUntilIdle()
            viewModel.saveReview("first draft", false)
            advanceUntilIdle()
            // lastOwnReview now caches "review-mine", with no active group involved yet.

            // A group becomes active AFTER the write and its section loads successfully, showing
            // no review from this account — e.g. the review was deleted through some other client.
            groups.reviewsResults[GROUP_ID to "media-1"] = emptyList()
            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()

            viewModel.openReviewEditor()
            advanceUntilIdle()

            val reopened = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertNull(
                "a fresh, successful no-match must win over a now-stale cache entry",
                reopened.reviewId,
            )
        }

    /**
     * Fix round 2, small item 2: a `PATCH` 404 ([GroupFailure.NoSuchEntry]) on the id
     * [DetailViewModel] itself cached means the CACHE is wrong, not just this one attempt — left
     * uncleared, [DetailViewModel.findOwnReview] would keep resolving every future open to the SAME
     * dead id, reproducing the identical 404 for the life of this ViewModel instance. Reopening
     * after the failure must start a FRESH draft instead.
     */
    @Test
    fun `a NoSuchEntry failure clears the cached review it points at, so a reopen starts fresh`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository()
            groups.createReviewResult = MY_REVIEW.copy(id = "review-mine")
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.openReviewEditor()
            advanceUntilIdle()
            viewModel.saveReview("first draft", false)
            advanceUntilIdle()
            // lastOwnReview now caches "review-mine" — no active group, so this is the only path
            // back to it (BLOCKING B2's own fallback).

            viewModel.openReviewEditor()
            advanceUntilIdle()
            val reopenedBeforeFailure =
                (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals("review-mine", reopenedBeforeFailure.reviewId)

            groups.updateReviewFailure = GroupFailure.NoSuchEntry
            viewModel.saveReview("edited", false)
            advanceUntilIdle()
            val afterFailure = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals(ReviewSaveError.Remote(GroupFailure.NoSuchEntry), afterFailure.error)

            viewModel.closeReviewEditor()
            viewModel.openReviewEditor()
            advanceUntilIdle()

            val reopenedAfterFailure =
                (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertNull("the dead cached review must not be offered again", reopenedAfterFailure.reviewId)
        }

    /**
     * Fix round 3, small item: [handleSaveFailure]'s `lastOwnReview?.id == editor.reviewId` scoping
     * clause had no test of its own — pins the claim its own comment makes, that a 404 naming some
     * OTHER id never clears an unrelated, still-good cache entry. `lastOwnReview` ("review-old") is
     * cached with no active group; a LIVE, loaded section then resolves the editor to a genuinely
     * DIFFERENT id ("review-different") — an edge case a real backend would never produce for one
     * account+title, constructed directly here to exercise the scoping clause in isolation — and
     * ONLY that different id's own 404 is fired.
     */
    @Test
    fun `a NoSuchEntry failure on a different id than the cache leaves the cache untouched`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository()
            groups.createReviewResult = MY_REVIEW.copy(id = "review-old")
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.openReviewEditor()
            advanceUntilIdle()
            viewModel.saveReview("first draft", false)
            advanceUntilIdle()
            // lastOwnReview now caches "review-old".

            groups.reviewsResults[GROUP_ID to "media-1"] = listOf(MY_REVIEW.copy(id = "review-different"))
            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()

            viewModel.openReviewEditor()
            advanceUntilIdle()
            val opened = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals("the live section resolves first, not the cache", "review-different", opened.reviewId)

            groups.updateReviewFailure = GroupFailure.NoSuchEntry
            viewModel.saveReview("edited", false)
            advanceUntilIdle()
            // The 404 named "review-different" — never the cached "review-old".

            viewModel.closeReviewEditor()
            viewModel.setActiveGroup(null)
            advanceUntilIdle()
            viewModel.openReviewEditor()
            advanceUntilIdle()

            val reopened = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals(
                "a 404 on a DIFFERENT id must not clear an unrelated, still-good cache entry",
                "review-old",
                reopened.reviewId,
            )
        }

    /**
     * Fix round 2, small item 4: [DetailViewModel.findOwnReview]'s SECOND fallback
     * (`currentUserId ?: return lastOwnReview`) had no test of its own — every existing test either
     * left [FakeAuthRepository] at its default (a resolving `currentUserId`) or exercised the
     * FIRST fallback (`groupSection` not `Loaded`) instead. Constructs identity resolution genuinely
     * failing (never retried — [DetailViewModel.resolveCurrentUserId]'s own KDoc) while the section
     * IS `Loaded`, so only the cache can possibly answer.
     */
    @Test
    fun `findOwnReview falls back to the cache when identity never resolved, even with a loaded section`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository(reviewsResults = mutableMapOf((GROUP_ID to "media-1") to emptyList()))
            val created = MY_REVIEW.copy(id = "review-mine")
            groups.createReviewResult = created
            val auth = FakeAuthRepository(currentUserIdFailure = AuthFailure.Offline(IOException("offline")))
            val viewModel =
                DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), groups, auth)
            advanceUntilIdle()

            viewModel.openReviewEditor()
            advanceUntilIdle()
            viewModel.saveReview("first draft", false)
            advanceUntilIdle()

            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()
            // The section IS Loaded now — nobody's review is in it, but that is irrelevant, since
            // there is no resolved identity to match against at all.

            viewModel.openReviewEditor()
            advanceUntilIdle()

            val opened = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals(created.id, opened.reviewId)
        }

    /** [saveReview]'s own re-entrancy guard — `edit`'s identical "a second edit is ignored" shape. */
    @Test
    fun `a second review save is ignored while one is already saving`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository()
            groups.createReviewResult = MY_REVIEW
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.openReviewEditor()
            advanceUntilIdle()

            viewModel.saveReview("first attempt", false)
            viewModel.saveReview("second attempt", false)
            advanceUntilIdle()

            assertEquals(1, groups.createReviewCalls.size)
        }

    /**
     * The reverse of the test above: no active group exists to resolve the id from at all, so
     * there is nothing for [DetailViewModel.handleSaveFailure] to retry against. `existingReviewId`
     * is null on the server's own 409 body (`GroupFailure.AlreadyReviewed`'s own KDoc) — this pins
     * that the honest degradation actually happens, rather than the save silently hanging or the
     * reader's typed text being discarded.
     */
    @Test
    fun `a 409 that cannot be resolved locally is reported, keeping the typed draft`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository()
            groups.createReviewFailure = GroupFailure.AlreadyReviewed(existingReviewId = null)
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.openReviewEditor()
            advanceUntilIdle()

            viewModel.saveReview("nobody else can see this yet", false)
            advanceUntilIdle()

            val editor = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertNull("no active group means no data to switch to editing WITH", editor.reviewId)
            assertEquals(ReviewSaveError.Remote(GroupFailure.AlreadyReviewed(null)), editor.error)
            assertFalse(editor.saving)
        }

    /** The brief's own second named test — `ReviewBody`'s server-side `min_length=1`, mirrored client-side. */
    @Test
    fun `an empty body cannot be submitted`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository()
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.openReviewEditor()
            advanceUntilIdle()

            // All-whitespace, not literally empty: the bound is checked AFTER stripping — the
            // exact server-side behaviour this client-side check mirrors.
            viewModel.saveReview("     ", false)
            advanceUntilIdle()

            assertEquals(0, groups.createReviewCalls.size)
            val editor = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals(ReviewSaveError.BodyRequired, editor.error)
        }

    /** The brief's own third named test — §3.6's 4000-character bound, mirrored client-side. */
    @Test
    fun `a body over 4000 characters cannot be submitted`() =
        runTest(dispatcher) {
            val groups = FakeGroupRepository()
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.openReviewEditor()
            advanceUntilIdle()

            viewModel.saveReview("x".repeat(4001), false)
            advanceUntilIdle()

            assertEquals(0, groups.createReviewCalls.size)
            val editor = (viewModel.state.value as DetailUiState.Success).reviewEditor as ReviewEditorState.Open
            assertEquals(ReviewSaveError.BodyTooLong, editor.error)
        }

    /**
     * The brief's own fourth named test. Closes the loop with task 9c.6: a review written with
     * `containsSpoilers = true` must be exactly what [GroupRepository.createReview] is CALLED
     * with, and exactly what the reloaded [GroupSectionState.Loaded.reviews] then carries — the
     * round trip, not a re-test of [SpoilerReview]'s own collapse (already covered elsewhere,
     * Global Constraints' own instruction).
     */
    @Test
    fun `the spoiler flag set at write time is what the group section honours`() =
        runTest(dispatcher) {
            val saved = MY_REVIEW.copy(id = "review-new", containsSpoilers = true, body = "spoilery")
            val groups = FakeGroupRepository()
            groups.createReviewResult = saved
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.setActiveGroup(GROUP_ID)
            advanceUntilIdle()
            // Nothing configured for GROUP_ID/media-1 yet, so the section loads EMPTY —
            // findOwnReview() finds nothing, and the editor below opens in CREATE mode.
            viewModel.openReviewEditor()
            advanceUntilIdle()

            // The server now "has" the review this save is about to create — configured so the
            // POST-SAVE reload (onReviewSaved -> reloadGroupSection), not this account already
            // owning a review before the save, is what makes it visible below.
            groups.reviewsResults[GROUP_ID to "media-1"] = listOf(saved)

            viewModel.saveReview("spoilery", true)
            advanceUntilIdle()

            assertEquals(listOf(Triple("media-1", "spoilery", true)), groups.createReviewCalls)
            val section = (viewModel.state.value as DetailUiState.Success).groupSection as GroupSectionState.Loaded
            assertTrue(section.reviews.single().containsSpoilers)
        }

    /**
     * One of the pairs Global Constraints calls out by name for this task: a group switch racing
     * an in-flight save. [DetailViewModel.onReviewSaved]'s own reload must target whichever group
     * is ACTIVE when the save actually FINISHES, not whichever was active when it STARTED — the
     * server-supplied `reviewsResults` for "group-b" is changed only AFTER group B's own initial
     * (pre-save) fetch has already landed, so the assertion below can only pass if the SAVE's own
     * completion is what triggers a SECOND fetch.
     */
    @Test
    fun `switching the active group while a save is in flight reloads the new group's section, not the old`() =
        runTest(dispatcher) {
            val savedReview = MY_REVIEW.copy(id = "review-new")
            val groups =
                FakeGroupRepository(
                    progressResults =
                        mutableMapOf(
                            ("group-a" to "media-1") to listOf(PROGRESS_ROW),
                            ("group-b" to "media-1") to listOf(OTHER_PROGRESS_ROW),
                        ),
                )
            groups.createReviewResult = savedReview
            val createGate = CompletableDeferred<Unit>()
            groups.createReviewGate = createGate
            val viewModel =
                DetailViewModel(
                    savedState("media-1"),
                    FakeMedia(),
                    FakeLibrary(entry = ENTRY),
                    groups,
                    FakeAuthRepository(),
                )
            advanceUntilIdle()
            viewModel.setActiveGroup("group-a")
            advanceUntilIdle()
            viewModel.openReviewEditor()
            advanceUntilIdle()
            viewModel.saveReview("body", false)
            advanceUntilIdle() // createReview launches and suspends on createGate.

            viewModel.setActiveGroup("group-b")
            advanceUntilIdle() // group B's own (ungated) fetch lands immediately, with no review yet.
            groups.reviewsResults["group-b" to "media-1"] = listOf(savedReview)

            createGate.complete(Unit)
            advanceUntilIdle() // the save resolves; onReviewSaved() reloads under the CURRENT groupId.

            val after = viewModel.state.value as DetailUiState.Success
            assertEquals(ReviewEditorState.Closed, after.reviewEditor)
            val section = after.groupSection as GroupSectionState.Loaded
            assertEquals(listOf(savedReview), section.reviews)
            assertEquals(listOf(OTHER_PROGRESS_ROW), section.progress)
        }

    /**
     * The other pair Global Constraints calls out by name: a review save failing while a library
     * edit is still in flight. Decision C-S — one error channel per operation — means neither
     * direction may clobber the other, the identical shape the existing group-section/edit pair
     * test above already pins for a different pair of channels.
     */
    @Test
    fun `a review save failure and an in-flight library edit leave each other's channel untouched`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val updateGate = CompletableDeferred<Unit>()
            library.updateGate = updateGate
            val groups = FakeGroupRepository()
            groups.createReviewFailure = GroupFailure.Network
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, groups, FakeAuthRepository())
            advanceUntilIdle()
            viewModel.openReviewEditor()
            advanceUntilIdle()

            viewModel.setScore(BigDecimal("9.0"))
            // The synchronous half of edit() has already run — saving is true before the
            // coroutine that awaits updateGate is even dispatched.
            assertTrue((viewModel.state.value as DetailUiState.Success).saving)

            viewModel.saveReview("a real review", false)
            advanceUntilIdle()

            val midEdit = viewModel.state.value as DetailUiState.Success
            assertTrue("the library edit must still be in flight", midEdit.saving)
            val editor = midEdit.reviewEditor as ReviewEditorState.Open
            assertEquals(ReviewSaveError.Remote(GroupFailure.Network), editor.error)
            assertFalse(editor.saving)

            updateGate.complete(Unit)
            advanceUntilIdle()

            val afterEdit = viewModel.state.value as DetailUiState.Success
            assertFalse(afterEdit.saving)
            assertNull(afterEdit.actionError)
            val editorAfter = afterEdit.reviewEditor as ReviewEditorState.Open
            assertEquals(ReviewSaveError.Remote(GroupFailure.Network), editorAfter.error)
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

    // internal, not private (fix round 1, BLOCKING B1): DetailResumeTest.kt needs a real
    // DetailViewModel constructed against a fake it does not have to duplicate — nested-class
    // access from another file in the same module (`DetailViewModelTest.FakeMedia(...)`) rather
    // than a second near-identical fixture.
    internal class FakeMedia(
        var detailFailure: Throwable? = null,
    ) : MediaRepository {
        override val searchResults: StateFlow<SearchResults> = MutableStateFlow(SearchResults.EMPTY)

        var lastMediaId: String? = null
            private set

        // Fix round 1 addition: lets a test hold `detail()` suspended so the TITLE load can be
        // observed genuinely in flight while a group-section fetch, launched independently,
        // resolves first — the canonical-field race `DetailViewModel`'s own KDoc describes.
        var detailGate: CompletableDeferred<Unit>? = null

        override suspend fun search(query: String): Unit = error("not exercised by DetailViewModel")

        override suspend fun loadMoreResults(): Unit = error("not exercised by DetailViewModel")

        override suspend fun detail(mediaId: String): Media {
            lastMediaId = mediaId
            detailGate?.await()
            detailFailure?.let { throw it }
            return MEDIA
        }
    }

    // internal, not private (fix round 1, BLOCKING B1) — see FakeMedia's own KDoc just above.
    internal class FakeLibrary(
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

        // Task 9c.6 addition: lets a test hold `update()` suspended so an edit can be observed
        // genuinely IN FLIGHT — needed to construct the "a group section failure arrives while a
        // library edit is in progress" pair the Global Constraints call out by name.
        var updateGate: CompletableDeferred<Unit>? = null

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
            updateGate?.await()
            updateFailure?.let { throw it }
            return updateResult
        }

        override suspend fun entryForMedia(mediaId: String): LibraryEntry? {
            lastEntryForMediaId = mediaId
            return entry
        }

        override val favoriteEntries: StateFlow<List<LibraryEntry>> = MutableStateFlow(emptyList())

        override suspend fun refreshFavorites(): Unit = error("not exercised by DetailViewModel")

        override suspend fun loadMoreFavorites(): Unit = error("not exercised by DetailViewModel")

        override suspend fun libraryStats() = error("not exercised by DetailViewModel")

        override suspend fun importAniList(username: String) = error("not exercised by DetailViewModel")
    }

    // internal, not private (fix round 3): DetailResumeTest reuses MEDIA/ENTRY directly rather
    // than duplicating these fixtures — the same reasoning FakeMedia/FakeLibrary's own KDoc
    // already gives for being internal.
    internal companion object {
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

        const val GROUP_ID = "group-1"

        val PROGRESS_ROW =
            MemberProgress(
                member = GroupActor(id = "user-1", username = "alice"),
                status = UserMediaStatus.WATCHING,
                progress = 12,
            )
        val OTHER_PROGRESS_ROW =
            MemberProgress(
                member = GroupActor(id = "user-2", username = "bob"),
                status = UserMediaStatus.COMPLETED,
                progress = 24,
            )
        val REVIEW =
            Review(
                id = "review-1",
                author = GroupActor(id = "user-1", username = "alice"),
                mediaId = "media-1",
                body = "Great pacing.",
                containsSpoilers = false,
                createdAt = Instant.parse("2026-08-28T10:15:30Z"),
                updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
            )

        // Authored by "user-self" — FakeAuthRepository's own default currentUserIdResult — so
        // findOwnReview() matches it once the group section has loaded, task 9c.7's own tests.
        val MY_REVIEW =
            Review(
                id = "review-mine",
                author = GroupActor(id = "user-self", username = "me"),
                mediaId = "media-1",
                body = "already reviewed this one",
                containsSpoilers = false,
                createdAt = Instant.parse("2026-08-28T10:15:30Z"),
                updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
            )
        val WATCHLIST_ENTRY =
            WatchlistEntry(
                id = "watchlist-1",
                media =
                    MediaSummary(
                        source = MediaSource.ANILIST,
                        externalId = "21",
                        type = MediaType.ANIME,
                        title = "One Piece",
                        year = 1999,
                        genres = listOf("Action"),
                        coverImageUrl = null,
                    ),
                mediaId = "media-1",
                proposedBy = "user-1",
                createdAt = Instant.parse("2026-08-28T10:15:30Z"),
            )
    }
}
