package com.anarky.showtrack.feature.detail

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupActor
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.Review
import com.anarky.showtrack.core.model.WatchlistEntry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    private fun detailViewModel(groups: FakeGroupRepository): DetailViewModel =
        DetailViewModel(
            SavedStateHandle(mapOf("mediaId" to "media-1")),
            DetailViewModelTest.FakeMedia(),
            DetailViewModelTest.FakeLibrary(entry = null),
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
