package com.anarky.showtrack.feature.detail

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.MediaRepository
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
            val viewModel =
                DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = null), FakeGroupRepository())
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
            DetailViewModel(savedState("media-42"), media, library, FakeGroupRepository())
            advanceUntilIdle()

            assertEquals("media-42", media.lastMediaId)
            assertEquals("media-42", library.lastEntryForMediaId)
        }

    @Test
    fun `a title already in the library loads with its entry`() =
        runTest(dispatcher) {
            val viewModel =
                DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), FakeGroupRepository())
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
            val viewModel = DetailViewModel(savedState("media-1"), media, FakeLibrary(), FakeGroupRepository())

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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, FakeGroupRepository())
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, FakeGroupRepository())
            advanceUntilIdle()

            viewModel.clearScore()
            advanceUntilIdle()

            assertEquals(LibraryPatch(score = ScoreChange.Clear), library.lastPatch)
        }

    @Test
    fun `changing the progress sends only the progress`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, FakeGroupRepository())
            advanceUntilIdle()

            viewModel.setProgress(7)
            advanceUntilIdle()

            assertEquals(LibraryPatch(progress = 7), library.lastPatch)
        }

    @Test
    fun `toggling favorite sends the flipped value`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, FakeGroupRepository())
            advanceUntilIdle()

            viewModel.toggleFavorite()
            advanceUntilIdle()

            assertEquals(LibraryPatch(favorite = !ENTRY.favorite), library.lastPatch)
        }

    @Test
    fun `changing the status sends only the status`() =
        runTest(dispatcher) {
            val library = FakeLibrary(entry = ENTRY)
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, FakeGroupRepository())
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, FakeGroupRepository())
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, FakeGroupRepository())
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, FakeGroupRepository())

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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, FakeGroupRepository())
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, FakeGroupRepository())
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, FakeGroupRepository())
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
                DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), FakeGroupRepository())
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), groups)
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
                DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), FakeGroupRepository())
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), groups)
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), groups)
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), groups)
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), library, groups)
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
            val viewModel = DetailViewModel(savedState("media-1"), media, FakeLibrary(entry = null), groups)
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), groups)
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), groups)
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), groups)
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), groups)
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), groups)
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
            val viewModel = DetailViewModel(savedState("media-1"), FakeMedia(), FakeLibrary(entry = ENTRY), groups)
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
