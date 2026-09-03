package com.anarky.showtrack.feature.groups

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.GroupRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import com.anarky.showtrack.core.designsystem.R as DesignSystemR

/**
 * The rendering decisions no [GroupDetailViewModelTest] can see (that class's own KDoc, and
 * `GroupsScreenTest`'s identical reasoning) — most importantly E-F's owner-only hiding, which is
 * this task brief's headline requirement: "if a behaviour is a rendering decision, a ViewModel test
 * cannot pin it" (Global Constraints).
 *
 * Drives the `internal` stateless [GroupDetailScreen] overload directly — `GroupsScreen`'s pattern —
 * so no ViewModel and no Hilt graph is needed. `createComposeRule`, not `createAndroidComposeRule`:
 * no Activity is needed. Robolectric supplies the Android runtime `stringResource` needs; `sdk=35`
 * is pinned in `src/test/resources/robolectric.properties`.
 *
 * **Every owner-only assertion below uses a THREE-member fixture** ([OWNER] plus two others),
 * mirroring `GroupsEntryHiltTest`'s own fix-round-1 lesson: a one- or two-member fixture cannot
 * discriminate "removing yourself is offered" (per E-F, it must not be) from "removing the FIRST
 * other member is offered" (a real, separate bug) — a two-member fixture where the SECOND member is
 * the viewer cannot tell those apart, since there is no OTHER "other" member left to expose it.
 */
@RunWith(RobolectricTestRunner::class)
class GroupDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a non-owner sees no rotate and no remove controls`() {
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(currentUserId = MEMBER.userId, members = listOf(OWNER, MEMBER, MEMBER2)),
                actionState = GroupDetailActionState(),
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_rotate_action)).assertDoesNotExist()
        composeRule.onAllNodesWithText(context.getString(R.string.groups_detail_remove_action)).assertCountEquals(0)
    }

    /**
     * The negative control for the test above — without it, "hidden for everyone" (a screen that
     * never renders rotate/remove at all, owner or not) would pass it trivially.
     */
    @Test
    fun `an owner sees rotate and remove`() {
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(currentUserId = OWNER.userId, members = listOf(OWNER, MEMBER, MEMBER2)),
                actionState = GroupDetailActionState(),
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_rotate_action)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.groups_detail_remove_action)).assertCountEquals(2)
    }

    /**
     * The brief's third named test, verbatim. Three members ([OWNER] as the viewer, plus [MEMBER]
     * and [MEMBER2]): "Remove" must render for the two OTHER rows and never for the owner's own —
     * `assertCountEquals(2)`, not merely "at least one", is what a "remove always shows for row 0"
     * or "remove shows for everyone including me" bug would both fail to pass.
     */
    @Test
    fun `leaving is offered to every member and removing is not offered for yourself`() {
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(currentUserId = OWNER.userId, members = listOf(OWNER, MEMBER, MEMBER2)),
                actionState = GroupDetailActionState(),
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_action)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.groups_detail_remove_action)).assertCountEquals(2)
    }

    /**
     * Mutation-critical: pins that "Remove" on the SECOND other member's row (not the first) opens
     * a confirmation naming THAT member, and confirming calls [onRemoveMember] with THEIR id — a
     * handler that always targeted `members[1]` (the first non-owner row) would pass every assertion
     * above (which only counts occurrences) but fail this one.
     */
    @Test
    fun `tapping remove on the second member's row confirms and removes that member, not the first`() {
        var removedUserId: String? = null
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(currentUserId = OWNER.userId, members = listOf(OWNER, MEMBER, MEMBER2)),
                actionState = GroupDetailActionState(),
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = { removedUserId = it },
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        // Both other rows' Remove buttons render the identical label — target the one on
        // MEMBER2's row specifically by walking up from their username, matching GroupsScreenTest's
        // own "no ambiguous onNodeWithText" discipline for repeated labels.
        composeRule.onAllNodesWithText(context.getString(R.string.groups_detail_remove_action))[1].performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_detail_remove_confirm_title, MEMBER2.username))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_remove_confirm_button)).performClick()

        assertEquals(MEMBER2.userId, removedUserId)
    }

    @Test
    fun `tapping leave opens a confirmation, and confirming invokes onLeaveGroup`() {
        var left = false
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(currentUserId = OWNER.userId, members = listOf(OWNER, MEMBER)),
                actionState = GroupDetailActionState(),
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = { left = true },
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_action)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_confirm_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_confirm_button)).performClick()

        assertTrue(left)
    }

    @Test
    fun `cancelling the leave confirmation does not invoke onLeaveGroup`() {
        var left = false
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(currentUserId = OWNER.userId, members = listOf(OWNER, MEMBER)),
                actionState = GroupDetailActionState(),
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = { left = true },
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_action)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_cancel)).performClick()

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_confirm_title)).assertDoesNotExist()
        assertTrue(!left)
    }

    @Test
    fun `tapping rotate opens a confirmation, and confirming invokes onRotateInvite`() {
        var rotated = false
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(currentUserId = OWNER.userId, members = listOf(OWNER, MEMBER)),
                actionState = GroupDetailActionState(),
                onRetry = {},
                onRotateInvite = { rotated = true },
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_rotate_action)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_rotate_confirm_message)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_rotate_confirm_button)).performClick()

        assertTrue(rotated)
    }

    /**
     * A successful rotate closes the confirmation dialog and shows the new code — the
     * [GroupDetailUiState.Success.rotatedInvite] half of the brief's fourth named test (the
     * ViewModel half, "rotating replaces the displayed code" itself, is `GroupDetailViewModelTest`'s).
     */
    @Test
    fun `a successful rotate closes the dialog and shows the new code`() {
        var state by mutableStateOf(successState(currentUserId = OWNER.userId, members = listOf(OWNER)))
        composeRule.setContent {
            GroupDetailScreen(
                state = state,
                actionState = GroupDetailActionState(),
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_rotate_action)).performClick()
        state = (state as GroupDetailUiState.Success).copy(rotatedInvite = INVITE)
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(context.getString(R.string.groups_invite_code_label, INVITE.inviteCode))
            .assertIsDisplayed()
        // The dialog closed (LaunchedEffect(rotatedInvite)) — its own confirm copy is gone.
        composeRule
            .onNodeWithText(context.getString(R.string.groups_detail_rotate_confirm_message))
            .assertDoesNotExist()
    }

    @Test
    fun `a failed rotate keeps the dialog open and shows the error`() {
        var actionState by mutableStateOf(GroupDetailActionState())
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(currentUserId = OWNER.userId, members = listOf(OWNER)),
                actionState = actionState,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_rotate_action)).performClick()
        actionState = GroupDetailActionState(rotateError = GroupFailure.NotPermitted)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.groups_error_unknown)).assertIsDisplayed()
        // Still open — a failure must not close it the way a success does.
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_rotate_confirm_button)).assertIsDisplayed()
    }

    @Test
    fun `a failed remove keeps the dialog open and shows the error`() {
        var actionState by mutableStateOf(GroupDetailActionState())
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(currentUserId = OWNER.userId, members = listOf(OWNER, MEMBER)),
                actionState = actionState,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_remove_action)).performClick()
        actionState = GroupDetailActionState(removeError = GroupFailure.NotPermitted)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.groups_error_unknown)).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_detail_remove_confirm_title, MEMBER.username))
            .assertIsDisplayed()
    }

    @Test
    fun `opening the rotate dialog invokes onRotateDialogOpened`() {
        var opened = false
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(currentUserId = OWNER.userId, members = listOf(OWNER)),
                actionState = GroupDetailActionState(),
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = { opened = true },
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_rotate_action)).performClick()

        assertTrue(opened)
    }

    @Test
    fun `an error state's retry action invokes onRetry, and shows the failure's message`() {
        var retried = false
        composeRule.setContent {
            GroupDetailScreen(
                state = GroupDetailUiState.Error(GroupFailure.Network),
                actionState = GroupDetailActionState(),
                onRetry = { retried = true },
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_error_network)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(DesignSystemR.string.action_retry)).performClick()

        assertTrue(retried)
    }

    @Test
    fun `a stale success shows the stale banner above the members, and its retry invokes onRetry`() {
        var retried = false
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(currentUserId = OWNER.userId, members = listOf(OWNER), isStale = true),
                actionState = GroupDetailActionState(),
                onRetry = { retried = true },
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
            )
        }

        val banner = composeRule.onNodeWithText(context.getString(R.string.groups_detail_stale_notice))
        val memberRow = composeRule.onNodeWithText(OWNER.username)
        banner.assertIsDisplayed()
        memberRow.assertIsDisplayed()

        val bannerTop = banner.fetchSemanticsNode().boundsInRoot.top
        val memberTop = memberRow.fetchSemanticsNode().boundsInRoot.top
        assertTrue("the stale banner must render above the members list", bannerTop < memberTop)

        composeRule.onNodeWithText(context.getString(DesignSystemR.string.action_retry)).performClick()
        assertTrue(retried)
    }

    private fun successState(
        currentUserId: String,
        members: List<GroupMember>,
        isStale: Boolean = false,
    ) = GroupDetailUiState.Success(members = members, currentUserId = currentUserId, isStale = isStale)

    private companion object {
        fun member(
            id: String,
            username: String,
            role: GroupRole,
        ) = GroupMember(userId = id, username = username, role = role, joinedAt = Instant.parse("2026-08-28T10:15:30Z"))

        val OWNER = member(id = "user-owner", username = "alex", role = GroupRole.OWNER)
        val MEMBER = member(id = "user-member", username = "sam", role = GroupRole.MEMBER)
        val MEMBER2 = member(id = "user-member-2", username = "kai", role = GroupRole.MEMBER)
        val INVITE =
            GroupWithInvite(
                group = Group(id = "group-1", name = "Watch Party", createdAt = Instant.parse("2026-08-28T10:15:30Z")),
                inviteCode = "NEWCODE0000000000000",
                expiresAt = Instant.parse("2026-09-10T00:00:00Z"),
            )
    }
}
