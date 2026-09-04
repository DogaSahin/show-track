package com.anarky.showtrack.feature.feed

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.FeedEntry
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupActor
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import com.anarky.showtrack.core.designsystem.R as DesignSystemR

/**
 * The rendering decisions [FeedViewModelTest] cannot see — `GroupsScreenTest`'s identical
 * reasoning: which STRING renders for a given [FeedUiState], and specifically for this screen, the
 * task brief's own four named tests, every one of which is a rendering claim by nature.
 *
 * Drives the `internal` stateless [FeedScreen] overload directly — `GroupsScreenTest`'s pattern —
 * so no ViewModel and no Hilt graph is needed. `createComposeRule`, not `createAndroidComposeRule`:
 * no Activity is needed. Robolectric supplies the Android runtime `stringResource` needs; `sdk = 35`
 * is pinned in `src/test/resources/robolectric.properties`.
 */
@RunWith(RobolectricTestRunner::class)
class FeedScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the no-groups empty state shows when the account genuinely has zero groups`() {
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Success(groups = emptyList(), activeGroupId = null),
                state = FeedUiState.Loading,
                onLoadMore = {},
                onRetry = {},
                onEntryClick = {},
                onSwitchGroup = {},
                onRetryGroups = {},
                onCreateOrJoinGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.feed_no_group_message)).assertIsDisplayed()
    }

    /**
     * The brief's own named test, verbatim, and BLOCKING B2's fix: the empty state is a real
     * create-or-join DOOR, not just a sentence — tapping its action must reach [onCreateOrJoinGroup].
     */
    @Test
    fun `a user in no groups reaches create-or-join, not an empty feed`() {
        var reachedCreateOrJoin = false
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Success(groups = emptyList(), activeGroupId = null),
                state = FeedUiState.Loading,
                onLoadMore = {},
                onRetry = {},
                onEntryClick = {},
                onCreateOrJoinGroup = { reachedCreateOrJoin = true },
                onSwitchGroup = {},
                onRetryGroups = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.feed_no_group_action)).performClick()

        assertTrue(reachedCreateOrJoin)
    }

    /**
     * BLOCKING B3: loading is distinct from a genuinely empty account — the create-or-join
     * invitation must NOT render while the groups fetch is still in flight.
     */
    @Test
    fun `a groups load in progress shows a spinner, not the create-or-join invitation`() {
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Loading,
                state = FeedUiState.Loading,
                onLoadMore = {},
                onRetry = {},
                onEntryClick = {},
                onSwitchGroup = {},
                onRetryGroups = {},
                onCreateOrJoinGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.feed_no_group_message)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.feed_no_group_action)).assertDoesNotExist()
    }

    /**
     * BLOCKING B3's other half: a failed groups fetch shows an error with its own retry
     * ([onRetryGroups]) — never the create-or-join invitation, and never [onRetry] (the FEED
     * retry, decision C-S's separate channel).
     */
    @Test
    fun `a failed groups load shows an error with its own retry, not the create-or-join invitation`() {
        var retriedGroups = false
        var retriedFeed = false
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Error(GroupFailure.Network),
                state = FeedUiState.Loading,
                onLoadMore = {},
                onRetry = { retriedFeed = true },
                onEntryClick = {},
                onRetryGroups = { retriedGroups = true },
                onSwitchGroup = {},
                onCreateOrJoinGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.feed_no_group_message)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.feed_groups_error_retry)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(DesignSystemR.string.action_retry)).performClick()

        assertTrue(retriedGroups)
        assertTrue("the FEED retry channel must stay untouched by the groups error's own retry", !retriedFeed)
    }

    @Test
    fun `the empty-activity message shows when a group is active but has no entries`() {
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Success(groups = emptyList(), activeGroupId = GROUP_ID),
                state = FeedUiState.Success(entries = emptyList()),
                onLoadMore = {},
                onRetry = {},
                onEntryClick = {},
                onSwitchGroup = {},
                onRetryGroups = {},
                onCreateOrJoinGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.feed_empty_activity)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.feed_no_group_message)).assertDoesNotExist()
    }

    /**
     * The task brief's own single most important test, verbatim name. [IMPORTED] carries
     * `media = null` (E-H, backend decision S-A): asserts the row renders WITHOUT the generic
     * unknown-title fallback leaking in (the count string instead), and that tapping it invokes
     * nothing — `onEntryClick` never fires.
     */
    @Test
    fun `an imported entry renders without a title and is not tappable`() {
        var clicked: FeedEntry? = null
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Success(groups = emptyList(), activeGroupId = GROUP_ID),
                state = FeedUiState.Success(entries = listOf(IMPORTED)),
                onLoadMore = {},
                onRetry = {},
                onEntryClick = { clicked = it },
                onSwitchGroup = {},
                onRetryGroups = {},
                onCreateOrJoinGroup = {},
            )
        }

        val expected = context.getString(R.string.feed_entry_imported, ACTOR.username, "5")
        composeRule.onNodeWithText(expected).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.feed_unknown_title)).assertDoesNotExist()

        // assertHasNoClickAction, not performClick: a node with no click semantics action makes
        // performClick() itself throw (Compose UI testing asserts the action exists before firing
        // it), which would fail this test for the CORRECT behaviour rather than pinning it.
        composeRule.onNodeWithText(expected).assertHasNoClickAction()
        assertEquals(null, clicked)
    }

    /** The brief's own named test, verbatim. */
    @Test
    fun `each of the six activity kinds renders its own copy`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Success(groups = emptyList(), activeGroupId = GROUP_ID),
                state = FeedUiState.Success(entries = listOf(ADDED, IMPORTED, PROGRESSED, RATED, COMPLETED, DROPPED)),
                onLoadMore = {},
                onRetry = {},
                onEntryClick = {},
                onSwitchGroup = {},
                onRetryGroups = {},
                onCreateOrJoinGroup = {},
            )
        }

        composeRule
            .onNodeWithText(context.getString(R.string.feed_entry_added, ACTOR.username, TITLE))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.feed_entry_imported, ACTOR.username, "5"))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.feed_entry_progressed, ACTOR.username, TITLE))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.feed_entry_rated, ACTOR.username, TITLE))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.feed_entry_completed, ACTOR.username, TITLE))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.feed_entry_dropped, ACTOR.username, TITLE))
            .assertIsDisplayed()
    }

    /** The brief's own named test, verbatim. */
    @Test
    fun `an unknown activity kind renders a generic line rather than crashing`() {
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Success(groups = emptyList(), activeGroupId = GROUP_ID),
                state = FeedUiState.Success(entries = listOf(UNKNOWN_KIND)),
                onLoadMore = {},
                onRetry = {},
                onEntryClick = {},
                onSwitchGroup = {},
                onRetryGroups = {},
                onCreateOrJoinGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val row = composeRule.onNodeWithText(context.getString(R.string.feed_entry_unknown, ACTOR.username))
        row.assertIsDisplayed()
        // Round 1, small item 2: FeedEntryRow's KDoc claims UNKNOWN is handled "with no code
        // change" via the mediaId != null check — this is what actually pins that claim rather
        // than only asserting the copy renders.
        row.assertHasNoClickAction()
    }

    /**
     * The tappability positive control, and the fixture the "not tappable" test above needs a
     * companion for: a TWO-element, media-bearing fixture, tapping the SECOND row — mirroring the
     * exact BLOCKING-3 fix `GroupsScreenTest`'s own "not the first one" test documents (a one-item
     * fixture cannot discriminate "the tapped row's own entry" from "always the first row").
     */
    @Test
    fun `tapping an entry with media invokes onEntryClick with that entry, not the first one`() {
        var clicked: FeedEntry? = null
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Success(groups = emptyList(), activeGroupId = GROUP_ID),
                state = FeedUiState.Success(entries = listOf(ADDED, RATED)),
                onLoadMore = {},
                onRetry = {},
                onEntryClick = { clicked = it },
                onSwitchGroup = {},
                onRetryGroups = {},
                onCreateOrJoinGroup = {},
            )
        }

        val ratedRow = composeRule.onNodeWithText(context.getString(R.string.feed_entry_rated, ACTOR.username, TITLE))
        ratedRow.assertHasClickAction()
        ratedRow.performClick()

        assertEquals(RATED, clicked)
    }

    @Test
    fun `a stale success shows the stale banner above the entries, and its retry invokes onRetry`() {
        var retried = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Success(groups = emptyList(), activeGroupId = GROUP_ID),
                state = FeedUiState.Success(entries = listOf(ADDED), isStale = true),
                onLoadMore = {},
                onRetry = { retried = true },
                onEntryClick = {},
                onSwitchGroup = {},
                onRetryGroups = {},
                onCreateOrJoinGroup = {},
            )
        }

        val banner = composeRule.onNodeWithText(context.getString(R.string.feed_stale_notice))
        val row = composeRule.onNodeWithText(context.getString(R.string.feed_entry_added, ACTOR.username, TITLE))
        banner.assertIsDisplayed()
        row.assertIsDisplayed()

        val bannerTop = banner.fetchSemanticsNode().boundsInRoot.top
        val rowTop = row.fetchSemanticsNode().boundsInRoot.top
        assertTrue("the stale banner must render above the entries", bannerTop < rowTop)

        composeRule.onNodeWithText(context.getString(DesignSystemR.string.action_retry)).performClick()

        assertTrue(retried)
    }

    @Test
    fun `an error state shows the failure's message and its retry invokes onRetry`() {
        var retried = false
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Success(groups = emptyList(), activeGroupId = GROUP_ID),
                state = FeedUiState.Error(GroupFailure.Network),
                onLoadMore = {},
                onRetry = { retried = true },
                onEntryClick = {},
                onSwitchGroup = {},
                onRetryGroups = {},
                onCreateOrJoinGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.feed_error_network)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(DesignSystemR.string.action_retry)).performClick()

        assertTrue(retried)
    }

    /**
     * The switcher's own gating/selection behaviour is [GroupSwitcherTest]'s job
     * (`:core:designsystem`) — this is only the WIRING check: [FeedScreen] actually plugs
     * [com.anarky.showtrack.core.designsystem.component.GroupSwitcher] into its own [groups]/
     * [onSwitchGroup] parameters, rather than, say, swapping them or dropping the callback.
     */
    @Test
    fun `tapping a group in the switcher invokes onSwitchGroup with that group's id`() {
        var selected: String? = null
        composeRule.setContent {
            FeedScreen(
                activeGroupState =
                    ActiveGroupState.Success(groups = listOf(GROUP, OTHER_GROUP), activeGroupId = GROUP_ID),
                onSwitchGroup = { selected = it },
                state = FeedUiState.Success(entries = listOf(ADDED)),
                onLoadMore = {},
                onRetry = {},
                onEntryClick = {},
                onRetryGroups = {},
                onCreateOrJoinGroup = {},
            )
        }

        composeRule.onNodeWithText(OTHER_GROUP.name).performClick()

        assertEquals(OTHER_GROUP.id, selected)
    }

    /** The negative control: a single active group renders no switcher at all (E-K). */
    @Test
    fun `a single active group shows no switcher`() {
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Success(groups = listOf(GROUP), activeGroupId = GROUP_ID),
                onSwitchGroup = {},
                state = FeedUiState.Success(entries = listOf(ADDED)),
                onLoadMore = {},
                onRetry = {},
                onEntryClick = {},
                onRetryGroups = {},
                onCreateOrJoinGroup = {},
            )
        }

        composeRule.onNodeWithText(GROUP.name).assertDoesNotExist()
    }

    @Test
    fun `a page error footer appears under the list and tapping it invokes onLoadMore`() {
        var loadedMore = false
        composeRule.setContent {
            FeedScreen(
                activeGroupState = ActiveGroupState.Success(groups = emptyList(), activeGroupId = GROUP_ID),
                state = FeedUiState.Success(entries = listOf(ADDED), pageError = GroupFailure.Network),
                onLoadMore = { loadedMore = true },
                onRetry = {},
                onEntryClick = {},
                onSwitchGroup = {},
                onRetryGroups = {},
                onCreateOrJoinGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.feed_page_error)).performClick()

        assertTrue(loadedMore)
    }

    private companion object {
        const val GROUP_ID = "group-1"
        const val TITLE = "Frieren"
        val ACTOR = GroupActor(id = "user-1", username = "alex")
        val GROUP = Group(id = GROUP_ID, name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T09:00:00Z"))
        val OTHER_GROUP =
            Group(id = "group-2", name = "Beta Watchers", createdAt = Instant.parse("2026-08-29T09:00:00Z"))
        val MEDIA =
            MediaSummary(
                source = MediaSource.ANILIST,
                externalId = "Frieren",
                type = MediaType.ANIME,
                title = TITLE,
                year = 2024,
                genres = emptyList(),
                coverImageUrl = null,
            )
        val ADDED =
            FeedEntry(
                id = "entry-added",
                actor = ACTOR,
                kind = ActivityKind.ADDED,
                media = MEDIA,
                mediaId = "media-1",
                payload = emptyMap(),
                createdAt = Instant.parse("2026-08-28T10:15:30Z"),
            )
        val IMPORTED =
            FeedEntry(
                id = "entry-imported",
                actor = ACTOR,
                kind = ActivityKind.IMPORTED,
                media = null,
                mediaId = null,
                payload = mapOf("count" to "5"),
                createdAt = Instant.parse("2026-08-28T11:00:00Z"),
            )
        val PROGRESSED =
            FeedEntry(
                id = "entry-progressed",
                actor = ACTOR,
                kind = ActivityKind.PROGRESSED,
                media = MEDIA,
                mediaId = "media-1",
                payload = mapOf("progress" to "5"),
                createdAt = Instant.parse("2026-08-28T12:00:00Z"),
            )
        val RATED =
            FeedEntry(
                id = "entry-rated",
                actor = ACTOR,
                kind = ActivityKind.RATED,
                media = MEDIA,
                mediaId = "media-1",
                payload = mapOf("score" to "8.5"),
                createdAt = Instant.parse("2026-08-28T13:00:00Z"),
            )
        val COMPLETED =
            FeedEntry(
                id = "entry-completed",
                actor = ACTOR,
                kind = ActivityKind.COMPLETED,
                media = MEDIA,
                mediaId = "media-1",
                payload = mapOf("status" to "completed"),
                createdAt = Instant.parse("2026-08-28T14:00:00Z"),
            )
        val DROPPED =
            FeedEntry(
                id = "entry-dropped",
                actor = ACTOR,
                kind = ActivityKind.DROPPED,
                media = MEDIA,
                mediaId = "media-1",
                payload = mapOf("status" to "dropped"),
                createdAt = Instant.parse("2026-08-28T15:00:00Z"),
            )
        val UNKNOWN_KIND =
            FeedEntry(
                id = "entry-unknown",
                actor = ACTOR,
                kind = ActivityKind.UNKNOWN,
                media = null,
                mediaId = null,
                payload = emptyMap(),
                createdAt = Instant.parse("2026-08-28T16:00:00Z"),
            )
    }
}
