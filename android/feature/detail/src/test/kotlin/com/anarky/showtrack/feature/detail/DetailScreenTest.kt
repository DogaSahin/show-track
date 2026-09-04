package com.anarky.showtrack.feature.detail

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupActor
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.MemberProgress
import com.anarky.showtrack.core.model.Review
import com.anarky.showtrack.core.model.UserMediaStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.Instant
import com.anarky.showtrack.core.designsystem.R as DesignSystemR

/**
 * The rendering decisions [DetailViewModelTest] cannot see — `FeedScreenTest`'s identical
 * reasoning, applied to the group section this task adds. Drives the `internal` stateless
 * [DetailScreen] overload directly, no ViewModel and no Hilt graph — `sdk = 35` from this module's
 * own `src/test/resources/robolectric.properties` (task 9c.6's own addition).
 */
@RunWith(RobolectricTestRunner::class)
class DetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The task 9c.6 brief's own named test. §9.12's acceptance criterion: a title nobody else
     * tracks is a real, EMPTY section, not a broken one — the empty-state message renders, and
     * nothing that would only appear for an actual row (a member's username, a review body) does.
     */
    @Test
    fun `a title nobody else tracks shows an empty group section, not a broken one`() {
        composeRule.setContent {
            DetailScreen(
                state =
                    successState(
                        groupSection = GroupSectionState.Loaded(progress = emptyList(), reviews = emptyList()),
                    ),
                groups = listOf(ALPHA),
                onRetry = {},
                onAddToLibrary = {},
                onScoreSelected = {},
                onScoreCleared = {},
                onProgressChange = {},
                onStatusSelected = {},
                onFavoriteToggle = {},
                onProposeToGroup = {},
                onRetryGroupSection = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.detail_group_section_empty)).assertExists()
        composeRule.onNodeWithText(ALICE.username).assertDoesNotExist()
    }

    /**
     * The task 9c.6 brief's own named test. E-K's no-groups state reaches this screen too — the
     * section's own title text must not render AT ALL, and neither must the propose button, since
     * there is nowhere for either to point.
     */
    @Test
    fun `the section is absent when there is no active group`() {
        composeRule.setContent {
            DetailScreen(
                state = successState(groupSection = GroupSectionState.Absent),
                groups = emptyList(),
                onRetry = {},
                onAddToLibrary = {},
                onScoreSelected = {},
                onScoreCleared = {},
                onProgressChange = {},
                onStatusSelected = {},
                onFavoriteToggle = {},
                onProposeToGroup = {},
                onRetryGroupSection = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.detail_group_section_title)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.detail_group_propose_button)).assertDoesNotExist()
    }

    /** A loaded, non-empty section renders one row per member who tracks the title. */
    @Test
    fun `a title three members track shows three progress rows`() {
        val progress =
            listOf(
                MemberProgress(member = GroupActor("u1", "alice"), status = UserMediaStatus.WATCHING, progress = 4),
                MemberProgress(member = GroupActor("u2", "bob"), status = UserMediaStatus.COMPLETED, progress = 24),
                MemberProgress(member = GroupActor("u3", "cara"), status = UserMediaStatus.PAUSED, progress = 1),
            )
        composeRule.setContent {
            DetailScreen(
                state =
                    successState(
                        groupSection = GroupSectionState.Loaded(progress = progress, reviews = emptyList()),
                    ),
                groups = listOf(ALPHA),
                onRetry = {},
                onAddToLibrary = {},
                onScoreSelected = {},
                onScoreCleared = {},
                onProgressChange = {},
                onStatusSelected = {},
                onFavoriteToggle = {},
                onProposeToGroup = {},
                onRetryGroupSection = {},
            )
        }

        // A precise format-string match would duplicate StatusPresentation's own label mapping
        // here; asserting each member's username (unique per row) is present is what actually
        // proves three DISTINCT rows rendered, without coupling this test to that mapping's exact
        // wording.
        composeRule.onNodeWithText("alice", substring = true).assertExists()
        composeRule.onNodeWithText("bob", substring = true).assertExists()
        composeRule.onNodeWithText("cara", substring = true).assertExists()
    }

    /** E-K's own "a picker over one option is noise" precedent, extended to the propose control. */
    @Test
    fun `proposing with exactly one group proposes directly, with no picker`() {
        var proposedTo: String? = null
        composeRule.setContent {
            DetailScreen(
                state = successState(groupSection = GroupSectionState.Absent),
                groups = listOf(ALPHA),
                onRetry = {},
                onAddToLibrary = {},
                onScoreSelected = {},
                onScoreCleared = {},
                onProgressChange = {},
                onStatusSelected = {},
                onFavoriteToggle = {},
                onProposeToGroup = { proposedTo = it },
                onRetryGroupSection = {},
            )
        }
        val context = ApplicationProvider.getApplicationContext<Context>()

        // performScrollTo() first: the group section sits below the fold of DetailContent's own
        // verticalScroll Column in this Robolectric-sized viewport — performClick() dispatches a
        // real gesture at the node's (unscrolled) position, which lands nowhere if that position
        // is still off-screen (`FavoritesScreenTest`/`GroupDetailScreenTest` do not need this only
        // because their target rows are the FIRST thing on screen).
        composeRule
            .onNodeWithText(
                context.getString(R.string.detail_group_propose_button),
            ).performScrollTo()
            .performClick()

        assertEquals(ALPHA.id, proposedTo)
        composeRule.onNodeWithText(context.getString(R.string.detail_group_propose_picker_title)).assertDoesNotExist()
    }

    /** Two or more groups: the button opens a picker instead of guessing which group is meant. */
    @Test
    fun `proposing with two groups opens a picker, and choosing a group proposes to that one`() {
        var proposedTo: String? = null
        composeRule.setContent {
            DetailScreen(
                state = successState(groupSection = GroupSectionState.Absent),
                groups = listOf(ALPHA, BETA),
                onRetry = {},
                onAddToLibrary = {},
                onScoreSelected = {},
                onScoreCleared = {},
                onProgressChange = {},
                onStatusSelected = {},
                onFavoriteToggle = {},
                onProposeToGroup = { proposedTo = it },
                onRetryGroupSection = {},
            )
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(
                context.getString(R.string.detail_group_propose_button),
            ).performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.detail_group_propose_picker_title)).assertExists()

        composeRule.onNodeWithText(BETA.name).performClick()

        assertEquals(BETA.id, proposedTo)
        composeRule.onNodeWithText(context.getString(R.string.detail_group_propose_picker_title)).assertDoesNotExist()
    }

    /** A failed group-section fetch shows a retry control that calls the group section's OWN channel. */
    @Test
    fun `retrying the group section calls onRetryGroupSection, not onRetry`() {
        var groupRetryCalls = 0
        var titleRetryCalls = 0
        composeRule.setContent {
            DetailScreen(
                state = successState(groupSection = GroupSectionState.Error(GroupFailure.Network)),
                groups = listOf(ALPHA),
                onRetry = { titleRetryCalls++ },
                onAddToLibrary = {},
                onScoreSelected = {},
                onScoreCleared = {},
                onProgressChange = {},
                onStatusSelected = {},
                onFavoriteToggle = {},
                onProposeToGroup = {},
                onRetryGroupSection = { groupRetryCalls++ },
            )
        }
        val context = ApplicationProvider.getApplicationContext<Context>()

        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.action_retry), substring = false)
            .performScrollTo()
            .performClick()

        assertEquals(1, groupRetryCalls)
        assertEquals(0, titleRetryCalls)
    }

    /**
     * The brief's own fifth named test, under no dedicated name until fix round 1 (coordinator
     * finding 3): `a failed group section leaves the rest of the detail screen usable`. Neither
     * substitute test above asserts this — the ViewModel tests pin the STATE channels, and Global
     * Constraints is explicit that a RENDERING decision cannot be pinned by a ViewModel test. This
     * asserts the title and an edit control are still on screen with `groupSection is Error`.
     */
    @Test
    fun `a failed group section leaves the title and edit controls on screen`() {
        composeRule.setContent {
            DetailScreen(
                state = successState(groupSection = GroupSectionState.Error(GroupFailure.Network)),
                groups = listOf(ALPHA),
                onRetry = {},
                onAddToLibrary = {},
                onScoreSelected = {},
                onScoreCleared = {},
                onProgressChange = {},
                onStatusSelected = {},
                onFavoriteToggle = {},
                onProposeToGroup = {},
                onRetryGroupSection = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(MEDIA.title).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.detail_favorite_label)).assertExists()
    }

    /**
     * Coordinator finding 4: `groups.size == 1` opens no picker and no dialog, so this banner is
     * the ONLY feedback a single-group propose gets — indistinguishable from a dead button
     * without it (the exact shape mutation 3 in fix round 1's report created).
     */
    @Test
    fun `a successful propose shows which group it went to`() {
        composeRule.setContent {
            DetailScreen(
                state = successState(groupSection = GroupSectionState.Absent, justProposedToGroupId = ALPHA.id),
                groups = listOf(ALPHA),
                onRetry = {},
                onAddToLibrary = {},
                onScoreSelected = {},
                onScoreCleared = {},
                onProgressChange = {},
                onStatusSelected = {},
                onFavoriteToggle = {},
                onProposeToGroup = {},
                onRetryGroupSection = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.detail_group_propose_success, ALPHA.name))
            .performScrollTo()
            .assertExists()
    }

    /**
     * Fix round 1, coordinator finding 2: `GroupSectionContent` renders reviews in a plain
     * `Column.forEach` — not a `LazyColumn` — so a reload that swaps in a DIFFERENT review at the
     * SAME list position risks the new review inheriting whatever composition state (SpoilerReview's
     * own `revealed`) the old occupant of that slot left behind. Drives [GroupSection] directly,
     * through ONE composition (a single `setContent`, `reviews` mutated afterward) rather than two
     * independent `setContent` calls — two calls would each start a brand-new composition and could
     * never observe a slot-reuse bug at all.
     *
     * **Measured, not assumed: the two guards are REDUNDANT, not each independently required.**
     * `GroupSection.kt`'s `key(review.id)` wrapper and `SpoilerReview.kt`'s own
     * `rememberSaveable(review.id)` each individually held this test green when mutated alone —
     * either one on its own already prevents the leak. Only removing BOTH at once reddens it,
     * confirmed by mutating both together before writing this KDoc's claim.
     */
    @Test
    fun `a new review replacing an old one at the same position starts collapsed, not still revealed`() {
        var reviews by mutableStateOf(listOf(SPOILER_A))
        composeRule.setContent {
            GroupSection(
                groupSection = GroupSectionState.Loaded(progress = emptyList(), reviews = reviews),
                groups = listOf(ALPHA),
                proposing = false,
                proposeError = null,
                justProposedToGroupId = null,
                onProposeToGroup = {},
                onRetry = {},
            )
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        // No performScrollTo() here — unlike DetailScreen's own tests, GroupSection is driven
        // directly with no surrounding DetailContent scroll container, and this small a tree fits
        // the viewport outright.
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.spoiler_review_reveal))
            .performClick()
        composeRule.onNodeWithText(SPOILER_A.body).assertExists()

        reviews = listOf(SPOILER_B)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(SPOILER_B.body).assertDoesNotExist()
    }

    private fun successState(
        groupSection: GroupSectionState,
        justProposedToGroupId: String? = null,
    ): DetailUiState.Success =
        DetailUiState.Success(
            data = DetailData(media = MEDIA, entry = ENTRY),
            groupSection = groupSection,
            justProposedToGroupId = justProposedToGroupId,
        )

    private companion object {
        val ALICE = GroupActor(id = "u1", username = "alice")
        val ALPHA =
            Group(id = "group-alpha", name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T10:15:30Z"))
        val BETA = Group(id = "group-beta", name = "Beta Watchers", createdAt = Instant.parse("2026-08-29T09:00:00Z"))

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

        val SPOILER_A =
            Review(
                id = "review-a",
                author = ALICE,
                mediaId = "media-1",
                body = "Review A's spoiler body.",
                containsSpoilers = true,
                createdAt = Instant.parse("2026-08-28T10:15:30Z"),
                updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
            )
        val SPOILER_B =
            Review(
                id = "review-b",
                author = GroupActor(id = "u2", username = "bob"),
                mediaId = "media-1",
                body = "Review B's completely different spoiler body.",
                containsSpoilers = true,
                createdAt = Instant.parse("2026-08-29T09:00:00Z"),
                updatedAt = Instant.parse("2026-08-29T09:00:00Z"),
            )
    }
}
