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
 * `FavoritesScreenTest`'s identical reasoning) — which STRING renders for a given [GroupsUiState],
 * and, specifically for this screen, the two named tests from the task brief that are rendering
 * claims by nature:
 *
 * - `` `a group loaded from the list shows no invite code` `` (the brief's second test): [Group]
 *   itself carries no invite-code field, so the only way this could ever go wrong is the SCREEN
 *   inventing one for a list row — a claim only a composed test can pin.
 * - `` `a failed join keeps the typed code in the field` `` (the brief's third test, its rendering
 *   half): the invite-code text field is [JoinGroupDialog]'s own `remember`ed draft, which no
 *   ViewModel test can observe at all.
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

    @Test
    fun `tapping a group invokes onGroupClick for it`() {
        var clicked: Group? = null
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA)),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = { clicked = it },
            )
        }

        composeRule.onNodeWithText(ALPHA.name).performClick()

        assertEquals(ALPHA, clicked)
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
     * The brief's third named test, its rendering half (see this class's own KDoc). The join
     * dialog's code field is [JoinGroupDialog]'s own `remember`ed draft — nothing in
     * [GroupsUiState.Success.joinError] resets it, so retyping a 20-character invite code after a
     * failed attempt is never required.
     */
    @Test
    fun `a failed join keeps the typed code in the field`() {
        var state by mutableStateOf<GroupsUiState>(GroupsUiState.Success(groups = emptyList()))
        composeRule.setContent {
            GroupsScreen(
                state = state,
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

        state = GroupsUiState.Success(groups = emptyList(), joinError = GroupFailure.Network)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("ABCDEFGHIJ1234567890").assertIsDisplayed()
    }

    @Test
    fun `a stale success shows the stale banner above the groups, and its retry invokes onRetry`() {
        var retried = false
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA), isStale = true),
                onRetry = { retried = true },
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = {},
            )
        }

        composeRule.onNodeWithText(ALPHA.name).assertIsDisplayed()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(DesignSystemR.string.stale_data_notice)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(DesignSystemR.string.action_retry)).performClick()

        assertTrue(retried)
    }

    @Test
    fun `an error state's retry action invokes onRetry`() {
        var retried = false
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Error(GroupFailure.Network),
                onRetry = { retried = true },
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onGroupClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(DesignSystemR.string.action_retry)).performClick()

        assertTrue(retried)
    }

    private companion object {
        val ALPHA =
            Group(id = "group-alpha", name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T10:15:30Z"))
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
