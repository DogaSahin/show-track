package com.anarky.showtrack.feature.groups

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import com.anarky.showtrack.core.designsystem.R as DesignSystemR

/**
 * The rendering decisions no [GroupsViewModelTest] can see (that class's own KDoc, and
 * `FavoritesScreenTest`'s identical reasoning) — which STRING renders for a given [GroupsUiState]/
 * [GroupsActionState], and, specifically for this screen, the two named tests from the task brief
 * that are rendering claims by nature:
 *
 * - `` `a group loaded from the list shows no invite code` `` (the brief's second test): [Group]
 *   itself carries no invite-code field, so the only way this could ever go wrong is the SCREEN
 *   inventing one for a list row — a claim only a composed test can pin.
 * - `` `a failed join keeps the typed code in the field` `` (the brief's third test, its rendering
 *   half): the invite-code text field is [JoinGroupDialog]'s own `remember`ed draft, which no
 *   ViewModel test can observe at all.
 *
 * Fix round 1 added the OTHER rendering half the review found missing: that a failure in
 * [GroupsActionState]/[GroupsUiState.Error] is not just STORED but actually SHOWN — `` `a failed
 * join shows its error message in the dialog` ``, `` `a failed create shows its error message in
 * the dialog` ``, and the message assertion added to `` `an error state's retry action invokes
 * onRetry` ``.
 *
 * Drives the `internal` stateless [GroupsScreen] overload directly — `FavoritesScreen`/
 * `ImportScreen`'s pattern — so no ViewModel and no Hilt graph is needed. `createComposeRule`, not
 * `createAndroidComposeRule`: no Activity is needed. Robolectric supplies the Android runtime
 * `stringResource` needs; `sdk = 35` is pinned in `src/test/resources/robolectric.properties`.
 */
@RunWith(RobolectricTestRunner::class)
class GroupsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the empty state shows when there are no groups`() {
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = emptyList()),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_empty_message)).assertIsDisplayed()
    }

    /**
     * BLOCKING 3 (fix round 1 review): the previous version of this test used a ONE-element
     * fixture, so `GroupsList` handing the tapped row's OWN [Group] to [onGroupClick] and
     * `GroupsList` handing `groups.first()` regardless of which row was tapped were
     * indistinguishable — both pass every assertion the old test made. A TWO-element fixture,
     * tapping the SECOND row, is what actually discriminates them.
     */
    @Test
    fun `tapping a group invokes onGroupClick for that group, not the first one`() {
        var clicked: Group? = null
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA, BETA)),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = { clicked = it },
            )
        }

        composeRule.onNodeWithText(BETA.name).performClick()

        assertEquals(BETA, clicked)
    }

    /**
     * The brief's second named test. `GET /v1/groups` returns `GroupRead`, which carries no invite
     * code — pinned here by asserting the one piece of copy unique to the invite banner
     * ([R.string.groups_invite_dismiss]) never renders for a plain [GroupsUiState.Success.justCreated]
     * of `null`, which is exactly what a list-sourced (never created-or-joined-this-session) state
     * looks like.
     */
    @Test
    fun `a group loaded from the list shows no invite code`() {
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA)),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_invite_dismiss)).assertDoesNotExist()
    }

    /**
     * The end-to-end form -> callback -> banner path: opens the create dialog, types a name,
     * submits (asserting [onCreateGroup] receives exactly what was typed), then simulates the
     * ViewModel's own success write and asserts the invite code renders AND the dialog closed —
     * `GroupsScreen`'s own `LaunchedEffect(justCreated)`.
     */
    @Test
    fun `creating a group successfully shows the invite code banner and closes the dialog`() {
        var state by mutableStateOf<GroupsUiState>(GroupsUiState.Success(groups = listOf(ALPHA)))
        var createdName: String? = null
        composeRule.setContent {
            GroupsScreen(
                state = state,
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = { name -> createdName = name },
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_create_action)).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_create_name_label))
            .performTextInput("Gamma Watchers")
        composeRule.onNodeWithText(context.getString(R.string.groups_create_submit)).performClick()

        assertEquals("Gamma Watchers", createdName)

        state = GroupsUiState.Success(groups = listOf(ALPHA, GAMMA), justCreated = INVITE)
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(context.getString(R.string.groups_invite_code_label, INVITE.inviteCode))
            .assertIsDisplayed()
        // The dialog closed (LaunchedEffect(justCreated)) — its own name field is gone.
        composeRule.onNodeWithText(context.getString(R.string.groups_create_name_label)).assertDoesNotExist()
    }

    @Test
    fun `dismissing the invite banner invokes onDismissInvite`() {
        var dismissed = false
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA), justCreated = INVITE),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = { dismissed = true },
                onGroupClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_invite_dismiss)).performClick()

        assertTrue(dismissed)
    }

    /**
     * Fix round 1: E-I shows the invite code exactly once and clears it on the next resume, so
     * copy-to-clipboard is the only way it reaches whoever the group owner is inviting — see
     * `InviteCodeCard`'s own KDoc. `groups_invite_copied` (rather than reading actual clipboard
     * contents, which Robolectric does not reliably expose) is the observable evidence the tap's
     * handler ran.
     */
    @Test
    fun `tapping copy code updates the button label to copied`() {
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA), justCreated = INVITE),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_invite_copy)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.groups_invite_copied)).assertIsDisplayed()
    }

    /**
     * The brief's third named test, its rendering half (see this class's own KDoc). The join
     * dialog's code field is [JoinGroupDialog]'s own `remember`ed draft — nothing in
     * [GroupsActionState.joinError] resets it, so retyping a 20-character invite code after a
     * failed attempt is never required. Fix round 1: [state] itself no longer carries the error —
     * that field moved to [GroupsActionState] — so this test now flips [actionState], not [state].
     */
    @Test
    fun `a failed join keeps the typed code in the field`() {
        var actionState by mutableStateOf(GroupsActionState())
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = emptyList()),
                actionState = actionState,
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_join_action)).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_join_code_label))
            .performTextInput("ABCDEFGHIJ1234567890")

        actionState = GroupsActionState(joinError = GroupFailure.Network)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("ABCDEFGHIJ1234567890").assertIsDisplayed()
    }

    /**
     * BLOCKING 2 (fix round 1 review): deleting `JoinGroupDialog`'s own `error?.let { Text(...) }`
     * left every pre-existing test green, because none of them asserted the error message was
     * actually RENDERED — only that [GroupsActionState.joinError] existed in state, or that the
     * typed code survived. This is the specific copy for [GroupFailure.Unknown] (`messageRes`'s
     * `unknownRes` parameter) — a bad or expired code is the single most common outcome of this
     * form.
     */
    @Test
    fun `a failed join shows its error message in the dialog`() {
        var actionState by mutableStateOf(GroupsActionState())
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = emptyList()),
                actionState = actionState,
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_join_action)).performClick()

        actionState = GroupsActionState(joinError = GroupFailure.Unknown(IllegalStateException("bad code")))
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.groups_join_error_bad_code)).assertIsDisplayed()
    }

    /** [CreateGroupDialog]'s mirror of the join error-rendering test above. */
    @Test
    fun `a failed create shows its error message in the dialog`() {
        var actionState by mutableStateOf(GroupsActionState())
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = emptyList()),
                actionState = actionState,
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_create_action)).performClick()

        actionState = GroupsActionState(createError = GroupFailure.Network)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.groups_error_network)).assertIsDisplayed()
    }

    /**
     * Fix round 1: asserts POSITION, not just presence — the previous version of this test's name
     * claimed the banner renders "above the groups" but never checked, so swapping the two in
     * `GroupsSuccessContent` would have left it green. Also switched to the groups-specific
     * `groups_stale_notice` copy (fix round 1's `StaleDataBanner` `messageRes` parameter) — the
     * shared component's own default ("Showing saved titles…") names the wrong noun here.
     */
    @Test
    fun `a stale success shows the stale banner above the groups, and its retry invokes onRetry`() {
        var retried = false
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA), isStale = true),
                actionState = GroupsActionState(),
                onRetry = { retried = true },
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val banner = composeRule.onNodeWithText(context.getString(R.string.groups_stale_notice))
        val groupRow = composeRule.onNodeWithText(ALPHA.name)
        banner.assertIsDisplayed()
        groupRow.assertIsDisplayed()

        val bannerTop = banner.fetchSemanticsNode().boundsInRoot.top
        val groupTop = groupRow.fetchSemanticsNode().boundsInRoot.top
        assertTrue("the stale banner must render above the groups list", bannerTop < groupTop)

        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.action_retry))
            .performClick()

        assertTrue(retried)
    }

    /**
     * BLOCKING 2's third instance (fix round 1 review): this test used to only click Retry, never
     * asserting WHICH message rendered for [GroupsUiState.Error] — deleting the message entirely
     * from `GroupsContent`'s `ErrorState` call left it green.
     */
    @Test
    fun `an error state's retry action invokes onRetry, and shows the failure's message`() {
        var retried = false
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Error(GroupFailure.Network),
                actionState = GroupsActionState(),
                onRetry = { retried = true },
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_error_network)).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.action_retry))
            .performClick()

        assertTrue(retried)
    }

    private companion object {
        val ALPHA =
            Group(id = "group-alpha", name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T10:15:30Z"))
        val BETA =
            Group(id = "group-beta", name = "Beta Watchers", createdAt = Instant.parse("2026-08-29T09:00:00Z"))
        val GAMMA =
            Group(id = "group-gamma", name = "Gamma Watchers", createdAt = Instant.parse("2026-08-30T09:00:00Z"))
        val INVITE =
            GroupWithInvite(
                group = GAMMA,
                inviteCode = "ABCDEFGHIJ1234567890",
                expiresAt = Instant.parse("2026-09-10T00:00:00Z"),
            )
    }
}
