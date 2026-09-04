package com.anarky.showtrack.feature.detail

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupActor
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.LibraryPatch
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.Review
import com.anarky.showtrack.core.model.ScoreChange
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.model.WatchlistEntry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import com.anarky.showtrack.core.designsystem.R as DesignSystemR

/**
 * The seam `DetailScreenTest` cannot see (fix round 1, BLOCKING B1): that suite drives only the
 * `internal` STATELESS [DetailScreen] overload, so nothing exercised the stateful
 * `DetailScreen(activeGroup, viewModel)` — the `LifecycleResumeEffect`/`collectAsStateWithLifecycle`
 * wiring that makes [DetailViewModel.setActiveGroup]/[DetailViewModel.proposeToGroup] REACHABLE at
 * all. Three realistic mutations of `DetailScreen.kt:81-102` (dropping `currentGroupId` from the
 * effect's key, calling `setActiveGroup(null)` unconditionally, and wiring the propose button to a
 * no-op) all compiled and left the whole suite green — `FeedEntryHiltTest`'s identical finding one
 * task ago, for the identical `StateFlow<ActiveGroupState>` → `LifecycleResumeEffect` → ViewModel
 * shape.
 *
 * **Fix round 2 addition:** the propose wire's own sibling, `onRetryGroupSection`, had the
 * identical hole — B1's own finding "when you fix a wire, check its siblings." A defaulted or
 * dropped `onRetryGroupSection` leaves the group section's Retry button permanently inert on the
 * REAL screen (a member removed from a group while Detail is open, `progress()` 404s, and nothing
 * short of leaving the screen recovers) while every stateless-overload test stays green, since
 * those tests supply the callback explicitly.
 *
 * `createAndroidComposeRule<ComponentActivity>()`, not `createComposeRule()`, and no Hilt harness:
 * [DetailScreen]'s `viewModel` parameter has a `hiltViewModel()` DEFAULT that is never evaluated
 * once a real [DetailViewModel] is passed explicitly — `FavoritesResumeTest`'s exact technique,
 * verbatim, extended from a `LibraryRepository`-only ViewModel to one that also takes a
 * [GroupRepository][com.anarky.showtrack.core.data.repository.GroupRepository]. No new Gradle
 * dependency: `androidx.compose.ui:ui-test-junit4` (already a test dependency here) carries
 * `androidx.activity:activity-compose` transitively.
 *
 * **Fix round 1 addition, task 9c.7 (BLOCKING B1):** `onOpenReviewEditor`/`onSaveReview`/
 * `onCancelReviewEditor` had the identical hole one more time over — this time not a DROPPED wire
 * (`DetailScreen.kt`'s own KDoc now states plainly that a no-default parameter cannot catch this
 * class of bug at all), but a STUBBED one: `onSaveReview = { _, _ -> }` compiles, type-checks, and
 * leaves `DetailScreenTest` fully green, because that suite supplies its own working lambda and
 * never exercises the STATEFUL wiring above it. The two tests below are what actually prove
 * `DetailScreen`'s own three review-editor callbacks reach a real [DetailViewModel].
 *
 * **Fix round 2 addition — the SAME hole, introduced by the fix for round 1's own small item 5, in
 * the SAME commit whose KDoc immediately above this one explains the seam.** `onClearReviewError`
 * is a fourth callback added alongside the three round 1 named, and it shipped stubbed exactly the
 * way round 1's three did before this file existed. The working rule going forward: any NEW
 * callback added to the stateful wrapper gets its OWN case here in the SAME change — this file is
 * not a one-time fix, it is the standing gate for this seam.
 *
 * **Fix round 3 addition — a full callback sweep** (stubbing EVERY wire on the stateful wrapper
 * simultaneously, not just the ones already reported broken) found the identical hole on the SEVEN
 * wires this task never touched: `onRetry`, `onAddToLibrary`, `onScoreSelected`, `onScoreCleared`,
 * `onProgressChange`, `onStatusSelected`, `onFavoriteToggle` — all predating this task, all inert-safe
 * the same way. `the library edit controls on the real, composed screen actually save` covers five
 * of the seven in one flow (score select, score clear, progress, status, favourite — one screen,
 * one natural sequence, per the coordinator's own steer against seven separate cases);
 * `the Add to library button...`/`the retry button on a failed initial load...` cover the other two.
 */
@RunWith(RobolectricTestRunner::class)
class DetailResumeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `changing the activeGroup flow re-scopes the group section fetch`() {
        val groups = FakeGroupRepository()
        val viewModel = detailViewModel(groups)
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(
                ActiveGroupState.Success(groups = listOf(ALPHA), activeGroupId = "group-a"),
            )

        composeRule.setContent { DetailScreen(activeGroup = activeGroup, viewModel = viewModel) }
        composeRule.waitForIdle()
        assertEquals(listOf("group-a" to "media-1"), groups.progressCalls)

        // The active group changes underneath an ALREADY-COMPOSED Detail screen — a switch made
        // from Feed or the group switcher while this screen sits on the back stack, the exact
        // event §3.5 says must re-scope the section rather than leave it showing the old group's
        // rows.
        activeGroup.value = ActiveGroupState.Success(groups = listOf(BETA), activeGroupId = "group-b")
        composeRule.waitForIdle()
        assertEquals(listOf("group-a" to "media-1", "group-b" to "media-1"), groups.progressCalls)
    }

    @Test
    fun `the propose button on the real, composed screen actually proposes`() {
        val groups = FakeGroupRepository(proposeResult = WATCHLIST_ENTRY)
        val viewModel = detailViewModel(groups)
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(
                ActiveGroupState.Success(groups = listOf(ALPHA), activeGroupId = "group-a"),
            )

        composeRule.setContent { DetailScreen(activeGroup = activeGroup, viewModel = viewModel) }
        composeRule.waitForIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.detail_group_propose_button))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, groups.proposeCalls)
        assertEquals("group-a", groups.lastProposeGroupId)
        assertEquals("media-1", groups.lastProposeMediaId)
    }

    /**
     * Fix round 2, coordinator finding 1: `onRetryGroupSection`, `onProposeToGroup`'s own sibling
     * wire, had the identical hole. `progressFailures` seeds a real, un-recovered 404; tapping the
     * REAL Retry button must reach [DetailViewModel.retryGroupSection] and fire a second fetch —
     * `progressCalls.size == 2` is the direct proof, not merely that SOME state changed.
     */
    @Test
    fun `the retry button on the real, composed screen actually retries`() {
        val groups = FakeGroupRepository()
        groups.progressFailures["group-a" to "media-1"] = GroupFailure.Network
        val viewModel = detailViewModel(groups)
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(
                ActiveGroupState.Success(groups = listOf(ALPHA), activeGroupId = "group-a"),
            )

        composeRule.setContent { DetailScreen(activeGroup = activeGroup, viewModel = viewModel) }
        composeRule.waitForIdle()
        assertEquals(1, groups.progressCalls.size)

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.action_retry))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(2, groups.progressCalls.size)
    }

    /**
     * BLOCKING B1's own named test: composes the REAL, stateful [DetailScreen] (not the stateless
     * overload [DetailScreenTest] drives) and proves the whole chain — tap "Write a review", type
     * into the real text field, tap Save — actually reaches [DetailViewModel.saveReview] and, from
     * there, [GroupRepository.createReview][com.anarky.showtrack.core.data.repository.GroupRepository.createReview].
     * No active group: writing a review is a title action (E-G), reachable with none.
     */
    @Test
    fun `the review editor on the real, composed screen actually saves`() {
        val groups = FakeGroupRepository()
        groups.createReviewResult = REVIEW
        val viewModel = detailViewModel(groups)
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(ActiveGroupState.Success(groups = emptyList(), activeGroupId = null))

        composeRule.setContent { DetailScreen(activeGroup = activeGroup, viewModel = viewModel) }
        composeRule.waitForIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.detail_review_write_button))
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.detail_review_body_label))
            .performScrollTo()
            .performTextInput("Written on the real, composed screen.")
        composeRule
            .onNodeWithText(context.getString(R.string.detail_review_save_button))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, groups.createReviewCalls.size)
        assertEquals("Written on the real, composed screen.", groups.createReviewCalls.single().second)
    }

    /** BLOCKING B1's own Cancel case: the real Cancel button actually closes the real editor. */
    @Test
    fun `cancelling the review editor on the real, composed screen actually closes it`() {
        val groups = FakeGroupRepository()
        val viewModel = detailViewModel(groups)
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(ActiveGroupState.Success(groups = emptyList(), activeGroupId = null))

        composeRule.setContent { DetailScreen(activeGroup = activeGroup, viewModel = viewModel) }
        composeRule.waitForIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.detail_review_write_button))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.detail_review_body_label)).assertExists()

        composeRule
            .onNodeWithText(context.getString(R.string.detail_review_cancel_button))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.detail_review_body_label)).assertDoesNotExist()
        composeRule
            .onNodeWithText(context.getString(R.string.detail_review_write_button))
            .performScrollTo()
            .assertExists()
        assertEquals(0, groups.createReviewCalls.size)
    }

    /**
     * Fix round 2, BLOCKING: `onClearReviewError`, added by fix round 1's own small item 5, had the
     * IDENTICAL hole `onOpenReviewEditor`/`onSaveReview`/`onCancelReviewEditor` were just fixed for,
     * in the SAME commit whose own KDoc names this exact seam — a stubbed `{}` compiles, passes
     * every stateless-overload test, and leaves a stale "Write something before saving." on screen
     * while the reader is visibly typing the fix. Drives the real, composed screen: trigger the
     * validation error with an empty Save, then type, then assert the error is actually gone.
     */
    @Test
    fun `typing after a validation error on the real, composed screen actually clears it`() {
        val groups = FakeGroupRepository()
        val viewModel = detailViewModel(groups)
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(ActiveGroupState.Success(groups = emptyList(), activeGroupId = null))

        composeRule.setContent { DetailScreen(activeGroup = activeGroup, viewModel = viewModel) }
        composeRule.waitForIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.detail_review_write_button))
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.detail_review_save_button))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(context.getString(R.string.detail_review_error_body_required))
            .assertExists()

        composeRule
            .onNodeWithText(context.getString(R.string.detail_review_body_label))
            .performScrollTo()
            .performTextInput("now typing a real review")
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(context.getString(R.string.detail_review_error_body_required))
            .assertDoesNotExist()
    }

    /**
     * Fix round 3: the full callback sweep's own five-in-one case. `FakeLibrary.updateResult`
     * defaults to `ENTRY` itself (unchanged by whatever patch was actually sent), so the screen
     * keeps rendering the SAME score/progress/status/favourite values between taps — letting each
     * control be found by its stable, unchanging text rather than by a value this test would
     * otherwise have to keep recomputing. Each tap's effect is checked directly against
     * `library.lastPatch`/`updateCalls`, not against anything re-rendered.
     */
    @Test
    fun `the library edit controls on the real, composed screen actually save`() {
        val groups = FakeGroupRepository()
        val library = DetailViewModelTest.FakeLibrary(entry = DetailViewModelTest.ENTRY)
        val viewModel = detailViewModel(groups, library = library)
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(ActiveGroupState.Success(groups = emptyList(), activeGroupId = null))

        composeRule.setContent { DetailScreen(activeGroup = activeGroup, viewModel = viewModel) }
        composeRule.waitForIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()

        // Score: select 9.0 (ENTRY.score is 8.5, so the chip's own text names the tap target).
        // The chip itself is a plain clickable Surface, not inside a Popup, so an ordinary
        // performClick() opens the dropdown reliably — confirmed by the "Clear score" item (unique
        // to the open menu) actually existing afterward.
        composeRule.onNodeWithText("8.5").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(context.getString(R.string.detail_score_clear))
            .assertExists("the score dropdown did not open")
        composeRule.onAllNodesWithText("9.0").onFirst().performSemanticsClick()
        composeRule.waitForIdle()
        assertEquals(1, library.updateCalls)
        assertEquals(LibraryPatch(score = ScoreChange.Set(BigDecimal("9.0"))), library.lastPatch)

        // Score: clear it — the chip still reads "8.5" (FakeLibrary.updateResult is unchanged).
        composeRule.onNodeWithText("8.5").performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.detail_score_clear)).performSemanticsClick()
        composeRule.waitForIdle()
        assertEquals(2, library.updateCalls)
        assertEquals(LibraryPatch(score = ScoreChange.Clear), library.lastPatch)

        // Progress: increase (ENTRY.progress is 3).
        composeRule
            .onNodeWithText(context.getString(R.string.detail_progress_increase))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(3, library.updateCalls)
        assertEquals(LibraryPatch(progress = 4), library.lastPatch)

        // Status: select Completed (ENTRY.status is WATCHING).
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.status_completed))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(4, library.updateCalls)
        assertEquals(LibraryPatch(status = UserMediaStatus.COMPLETED), library.lastPatch)

        // Favourite: toggle (ENTRY.favorite is false).
        composeRule
            .onNodeWithText(context.getString(R.string.detail_favorite_label))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(5, library.updateCalls)
        assertEquals(LibraryPatch(favorite = true), library.lastPatch)
    }

    /** Fix round 3: the sixth of the seven swept wires. */
    @Test
    fun `the Add to library button on the real, composed screen actually adds`() {
        val groups = FakeGroupRepository()
        val library = DetailViewModelTest.FakeLibrary(entry = null, addResult = DetailViewModelTest.ENTRY)
        val viewModel = detailViewModel(groups, library = library)
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(ActiveGroupState.Success(groups = emptyList(), activeGroupId = null))

        composeRule.setContent { DetailScreen(activeGroup = activeGroup, viewModel = viewModel) }
        composeRule.waitForIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.detail_add_button))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(MediaSource.ANILIST, library.lastAddSource)
        assertEquals("21", library.lastAddExternalId)
    }

    /** Fix round 3: the seventh of the seven swept wires — only reachable from a failed initial load. */
    @Test
    fun `the retry button on a failed initial load, on the real composed screen, actually retries`() {
        val groups = FakeGroupRepository()
        val media = DetailViewModelTest.FakeMedia(detailFailure = IOException("offline"))
        val viewModel = detailViewModel(groups, media = media)
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(ActiveGroupState.Success(groups = emptyList(), activeGroupId = null))

        composeRule.setContent { DetailScreen(activeGroup = activeGroup, viewModel = viewModel) }
        composeRule.waitForIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.detail_error_message)).assertExists()

        media.detailFailure = null
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.action_retry))
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.detail_error_message)).assertDoesNotExist()
    }

    /**
     * Fix round 3, discovered writing the library-controls flow test below: an ordinary
     * `performClick()` — a real, coordinate-based gesture at the node's measured bounds — is
     * unreliable against content INSIDE a Material3 `DropdownMenu`'s own `Popup` under Robolectric.
     * The node is genuinely found by a text query (`assertExists` on it passes), so the popup IS
     * composed and its semantics tree IS attached; the click gesture itself is what does not
     * reliably land, most likely a Robolectric window/popup bounds quirk this project's test suite
     * has never exercised before (no existing test anywhere in this codebase interacts with a
     * `DropdownMenu`'s open contents). Invoking the node's own `OnClick` semantics action directly
     * — the same action a real click would eventually trigger — sidesteps the coordinate question
     * entirely and is reliable. Used ONLY for the two taps that land inside the open popup (a
     * score value, "Clear score"); every other control in this file is a plain, non-popup
     * clickable and keeps using ordinary `performClick()`.
     */
    private fun SemanticsNodeInteraction.performSemanticsClick() {
        val node = fetchSemanticsNode()
        val onClick = node.config.getOrNull(SemanticsActions.OnClick)
        checkNotNull(onClick) { "node has no OnClick semantics action" }.action?.invoke()
    }

    private fun detailViewModel(
        groups: FakeGroupRepository,
        media: DetailViewModelTest.FakeMedia = DetailViewModelTest.FakeMedia(),
        library: LibraryRepository = DetailViewModelTest.FakeLibrary(entry = null),
    ): DetailViewModel =
        DetailViewModel(
            SavedStateHandle(mapOf("mediaId" to "media-1")),
            media,
            library,
            groups,
            FakeAuthRepository(),
        )

    private companion object {
        val ALPHA = Group(id = "group-a", name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T10:15:30Z"))
        val BETA = Group(id = "group-b", name = "Beta Watchers", createdAt = Instant.parse("2026-08-29T09:00:00Z"))

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

        val REVIEW =
            Review(
                id = "review-1",
                author = GroupActor(id = "user-self", username = "me"),
                mediaId = "media-1",
                body = "Written on the real, composed screen.",
                containsSpoilers = false,
                createdAt = Instant.parse("2026-08-28T10:15:30Z"),
                updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
            )
    }
}
