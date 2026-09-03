package com.anarky.showtrack.feature.groups

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.GroupRole
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.WatchlistEntry
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
 *
 * **`currentUserId` is now a SIBLING parameter to [state]** (round 1 review moved it off
 * [GroupDetailUiState.Success] entirely — that type's own KDoc), passed to every call below
 * alongside `state`/`actionState`.
 *
 * `@Suppress("LargeClass")` (task 9c.3): this class now pins the members section AND the shared
 * watchlist section of ONE screen — splitting it by section would scatter the dialog-close
 * regression tests (BLOCKING 1's own class of bug, reproduced for BOTH the remove-member and the
 * two new watchlist dialogs) away from the `successState`/fixture helpers and `context` property
 * they all share, for a lint threshold's sake rather than a real cohesion problem.
 */
@Suppress("LargeClass")
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
                state = successState(members = listOf(OWNER, MEMBER, MEMBER2)),
                actionState = GroupDetailActionState(),
                currentUserId = MEMBER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
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
                state = successState(members = listOf(OWNER, MEMBER, MEMBER2)),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_rotate_action)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.groups_detail_remove_action)).assertCountEquals(2)
    }

    /**
     * `currentUserId == null` (identity has not resolved yet, or its own background fetch failed —
     * `GroupDetailViewModel.currentUserId`'s own KDoc) must hide owner-only controls exactly like a
     * known non-owner does — E-F's "hidden, not disabled" applied to an UNKNOWN role, not only a
     * known non-owner one.
     */
    @Test
    fun `an unresolved currentUserId hides rotate and remove, the same as a non-owner`() {
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER, MEMBER, MEMBER2)),
                actionState = GroupDetailActionState(),
                currentUserId = null,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_rotate_action)).assertDoesNotExist()
        composeRule.onAllNodesWithText(context.getString(R.string.groups_detail_remove_action)).assertCountEquals(0)
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
                state = successState(members = listOf(OWNER, MEMBER, MEMBER2)),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_action)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.groups_detail_remove_action)).assertCountEquals(2)
    }

    /**
     * Round 1 review, minor 1: "leaving is offered to every member" was only ever driven with the
     * OWNER as the viewer — mutating the Leave button to `if (isOwner) { … }` left every other test
     * in this file green. [MEMBER] as the viewer here is the negative control that closes it: Leave
     * must render regardless of role, since [GroupDetailScreen] renders it OUTSIDE the
     * `isOwner`-gated success content entirely now (that function's own KDoc).
     */
    @Test
    fun `a non-owner can also see and use Leave group`() {
        var left = false
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER, MEMBER)),
                actionState = GroupDetailActionState(),
                currentUserId = MEMBER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = { left = true },
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_action)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_confirm_button)).performClick()

        assertTrue(left)
    }

    /**
     * "Leave group" must also render for [GroupDetailUiState.Error] — round 1 review, BLOCKING
     * 2's screen-side half.
     */
    @Test
    fun `Leave group is offered even when the member list failed to load`() {
        composeRule.setContent {
            GroupDetailScreen(
                state = GroupDetailUiState.Error(GroupFailure.Network),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_action)).assertIsDisplayed()
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
                state = successState(members = listOf(OWNER, MEMBER, MEMBER2)),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = { removedUserId = it },
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
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

    /**
     * BLOCKING 1 (round 1 review) — the actual regression: reopening the remove confirmation for a
     * NEW target right after a PREVIOUS remove's error must not dismiss itself. Round 0's close
     * effect keyed on `(removingUserId, removeError)` alone: `onRemoveDialogOpened` (bound to
     * `clearRemoveError`) clears the error in the SAME recomposition that sets the new target,
     * which flips the key to `(null, null)` — indistinguishable from a genuine success — and the
     * dialog closed itself before the user had done anything. Reproduced here exactly as the
     * review found it: fail sam's remove, cancel, then remove kai — a NEW target, not a re-tap of
     * the same one.
     */
    @Test
    fun `reopening remove for a new target after a previous remove's error does not self-dismiss`() {
        var actionState by mutableStateOf(GroupDetailActionState())
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER, MEMBER, MEMBER2)),
                actionState = actionState,
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = { actionState = actionState.copy(removeError = null) },
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        // Remove sam (the first "other" row): open, confirm, fail.
        composeRule.onAllNodesWithText(context.getString(R.string.groups_detail_remove_action))[0].performClick()
        actionState = actionState.copy(removingUserId = MEMBER.userId)
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_remove_confirm_button)).performClick()
        actionState = actionState.copy(removingUserId = null, removeError = GroupFailure.NotPermitted)
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_detail_remove_confirm_title, MEMBER.username))
            .assertIsDisplayed()

        // Cancel sam's dialog, then open kai's — a genuinely NEW target, with sam's error still
        // live in actionState until onRemoveDialogOpened (wired above, matching production) clears
        // it on this very open.
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_remove_cancel)).performClick()
        composeRule.onAllNodesWithText(context.getString(R.string.groups_detail_remove_action))[1].performClick()
        composeRule.waitForIdle()

        // Must still be showing — a self-dismiss here is BLOCKING 1 reproduced.
        composeRule
            .onNodeWithText(context.getString(R.string.groups_detail_remove_confirm_title, MEMBER2.username))
            .assertIsDisplayed()
    }

    /**
     * A second, distinct shape of BLOCKING 1's class of bug, which survived that fix.
     * [DialogCloseEffects]'s remove-close `LaunchedEffect` is keyed on
     * `(actionState.removingUserId, actionState.removeError)` alone — `removeAttempted` is READ
     * inside the effect body but never listed among its KEYS. Reachable sequence: start removing
     * sam (confirm, so `removeAttempted = true` and a real ViewModel sets `removingUserId = sam`
     * synchronously) -> dismiss sam's dialog with the system Back button while it is still in
     * flight (both Confirm and Cancel are disabled by `submitting`, so Back is the only way out)
     * -> `pendingRemoveTarget`/`removeAttempted` both reset to `false`, but `removingUserId` is
     * untouched, still sam's id -> open kai's dialog (`removeAttempted` reset `false` again on
     * open, per BLOCKING 1's own fix) -> tap kai's Confirm, which sets `removeAttempted = true`
     * and calls `onRemoveMember(kai)` — a real `GroupDetailViewModel.removeMember`'s own
     * re-entrancy guard (`GroupDetailViewModel.kt`) drops this because sam's remove is still in
     * flight, so `actionState` does not change here, but `removeAttempted` is now `true` for
     * KAI's dialog. When sam's remove finally lands (`removingUserId -> null`,
     * `removeError -> null`), the two keys the effect DOES watch both flip, the effect fires,
     * sees `removeAttempted == true`, and closes KAI's dialog as though kai had been removed —
     * self-correcting (kai is still in the reloaded member list) but a false close all the same.
     */
    @Test
    fun `a remove confirmed while a different member's remove is still in flight does not close that dialog early`() {
        var actionState by mutableStateOf(GroupDetailActionState())
        val removeCalls = mutableListOf<String>()
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER, MEMBER, MEMBER2)),
                actionState = actionState,
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = { userId ->
                    removeCalls += userId
                    // Mirrors GroupDetailViewModel.removeMember's own synchronous
                    // guard-then-set (GroupDetailViewModel.kt:304-305) — a remove already in
                    // flight for a DIFFERENT member makes this call a no-op; otherwise
                    // removingUserId is set in the SAME synchronous call as the confirm click,
                    // atomically with the screen's own removeAttempted flip, the same ordering
                    // a real ViewModel gives it.
                    if (actionState.removingUserId == null) {
                        actionState = actionState.copy(removingUserId = userId, removeError = null)
                    }
                },
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = { actionState = actionState.copy(removeError = null) },
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        // Start removing sam: open, confirm — the fake onRemoveMember above sets
        // removingUserId synchronously, the same call the confirm click makes.
        composeRule.onAllNodesWithText(context.getString(R.string.groups_detail_remove_action))[0].performClick()
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_remove_confirm_button)).performClick()
        composeRule.waitForIdle()

        // Dismiss with the system Back button while sam's remove is still in flight — Cancel is
        // disabled (submitting), so Back is the only way out, exactly as found.
        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_detail_remove_confirm_title, MEMBER.username))
            .assertDoesNotExist()

        // Open and confirm kai's dialog. Sam's remove is STILL in flight — actionState carries no
        // change from the dismissal above — so a real ViewModel's re-entrancy guard would drop
        // this call. This fake still records it; what the screen reacts to is actionState alone.
        composeRule.onAllNodesWithText(context.getString(R.string.groups_detail_remove_action))[1].performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_detail_remove_confirm_title, MEMBER2.username))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_remove_confirm_button)).performClick()

        // Sam's remove now lands successfully.
        actionState = actionState.copy(removingUserId = null, removeError = null)
        composeRule.waitForIdle()

        // Kai was never removed — his dialog must still be showing.
        composeRule
            .onNodeWithText(context.getString(R.string.groups_detail_remove_confirm_title, MEMBER2.username))
            .assertIsDisplayed()
        assertEquals(listOf(MEMBER.userId, MEMBER2.userId), removeCalls)
    }

    @Test
    fun `tapping leave opens a confirmation, and confirming invokes onLeaveGroup`() {
        var left = false
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER, MEMBER)),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = { left = true },
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
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
                state = successState(members = listOf(OWNER, MEMBER)),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = { left = true },
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
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
                state = successState(members = listOf(OWNER, MEMBER)),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = { rotated = true },
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
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
        var state by mutableStateOf(successState(members = listOf(OWNER)))
        composeRule.setContent {
            GroupDetailScreen(
                state = state,
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
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
                state = successState(members = listOf(OWNER)),
                actionState = actionState,
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
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
                state = successState(members = listOf(OWNER, MEMBER)),
                actionState = actionState,
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
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

    /** Round 1 review, minor 4: `NotAMember` gets its own copy now, not the generic fallback. */
    @Test
    fun `an error state shows NotAMember's dedicated message, not the generic one`() {
        composeRule.setContent {
            GroupDetailScreen(
                state = GroupDetailUiState.Error(GroupFailure.NotAMember),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_error_not_a_member)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.groups_error_unknown)).assertDoesNotExist()
    }

    @Test
    fun `opening the rotate dialog invokes onRotateDialogOpened`() {
        var opened = false
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER)),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = { opened = true },
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
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
                currentUserId = OWNER.userId,
                onRetry = { retried = true },
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
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
                state = successState(members = listOf(OWNER), isStale = true),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = { retried = true },
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
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

    /**
     * This task's own first named test, verbatim. [ENTRY_1]'s `proposedBy` resolves against
     * [OWNER] (present in the `members` fixture) to a real username; [ENTRY_2]'s is `null` (design
     * §5.3 — a deleted account). Both entries in ONE fixture is deliberate — Global Constraints'
     * own "multi-element fixtures, always": a single-entry fixture could not catch a row mapper
     * that used the SAME (right or wrong) copy for every row regardless of `proposedBy`.
     */
    @Test
    fun `an entry proposed by a deleted account renders without a proposer, not a blank name`() {
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER), watchlist = listOf(ENTRY_1, ENTRY_2)),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        composeRule
            .onNodeWithText(context.getString(R.string.groups_watchlist_proposed_by, OWNER.username))
            .assertIsDisplayed()
        // ENTRY_2's row is not composed at all on first layout (GroupWatchlistSection.kt's own
        // KDoc — a Robolectric root does not reach a second poster-sized watchlist row the way it
        // reaches several text-only member rows), so scroll the LIST to ENTRY_2's own remove
        // button (a stable anchor within its row) before asserting on its proposer text.
        composeRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("watchlist-remove-${ENTRY_2.id}"))
        composeRule.onNodeWithText(context.getString(R.string.groups_watchlist_no_proposer)).assertIsDisplayed()
    }

    /**
     * Mutation-critical: pins that "Remove" on the SECOND entry's row confirms and removes THAT
     * entry, not the first — `tapping remove on the second member's row...`'s identical shape one
     * resource over.
     */
    @Test
    fun `tapping remove on the second watchlist entry confirms and removes that entry, not the first`() {
        var removedEntryId: String? = null
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER), watchlist = listOf(ENTRY_1, ENTRY_2)),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = { removedEntryId = it },
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        // ENTRY_2's own remove button, reached by scrolling the LazyColumn's underlying
        // LazyListState to it directly — `performScrollToNode` works even when the target row is
        // not yet composed at all (Robolectric's default viewport does not reach a second
        // poster-sized row on first layout; `GroupWatchlistSection.kt`'s own KDoc), unlike chaining
        // `performScrollTo()` onto a query that has already failed to find the node.
        val entry2RemoveTag = "watchlist-remove-${ENTRY_2.id}"
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(entry2RemoveTag))
        composeRule.onNodeWithTag(entry2RemoveTag).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_watchlist_remove_confirm_title, ENTRY_2.media.title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.groups_watchlist_remove_confirm_button)).performClick()

        assertEquals(ENTRY_2.id, removedEntryId)
    }

    /**
     * [RemoveDialogHost]'s own BLOCKING-1-shaped regression, reproduced one resource over: fail
     * ENTRY_1's remove, cancel, then remove ENTRY_2 — a NEW target, not a re-tap of the same one.
     */
    @Test
    fun `reopening remove-entry for a new target after a previous failure does not self-dismiss`() {
        var actionState by mutableStateOf(GroupDetailActionState())
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER), watchlist = listOf(ENTRY_1, ENTRY_2)),
                actionState = actionState,
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = { actionState = actionState.copy(removeEntryError = null) },
                onEntryClick = {},
            )
        }

        // Remove ENTRY_1: open, confirm, fail.
        composeRule.onAllNodesWithText(context.getString(R.string.groups_watchlist_remove_action))[0].performClick()
        actionState = actionState.copy(removingEntryId = ENTRY_1.id)
        composeRule.onNodeWithText(context.getString(R.string.groups_watchlist_remove_confirm_button)).performClick()
        actionState = actionState.copy(removingEntryId = null, removeEntryError = GroupFailure.NoSuchEntry)
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_watchlist_remove_confirm_title, ENTRY_1.media.title))
            .assertIsDisplayed()

        // Cancel ENTRY_1's dialog, then open ENTRY_2's — a genuinely NEW target, with ENTRY_1's
        // error still live in actionState until onRemoveEntryDialogOpened (wired above, matching
        // production) clears it on this very open. ENTRY_2's own row, reached via
        // performScrollToNode — GroupWatchlistSection.kt's own KDoc on why a second poster-sized
        // row is not guaranteed composed on Robolectric's first layout pass.
        composeRule.onNodeWithText(context.getString(R.string.groups_watchlist_remove_cancel)).performClick()
        val entry2RemoveTag = "watchlist-remove-${ENTRY_2.id}"
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(entry2RemoveTag))
        composeRule.onNodeWithTag(entry2RemoveTag).performClick()
        composeRule.waitForIdle()

        // Must still be showing — a self-dismiss here is BLOCKING 1's class of bug reproduced.
        composeRule
            .onNodeWithText(context.getString(R.string.groups_watchlist_remove_confirm_title, ENTRY_2.media.title))
            .assertIsDisplayed()
    }

    /**
     * The brief's own "footer, never a promotion to full-screen Error" rule, pinned at the screen
     * layer: the members list AND the watchlist's own entries stay visible underneath the footer —
     * this is a rendering claim [GroupDetailViewModelTest] cannot make (Global Constraints).
     */
    @Test
    fun `a watchlist page error renders as an inline footer, with the members still visible`() {
        composeRule.setContent {
            GroupDetailScreen(
                state =
                    successState(
                        members = listOf(OWNER),
                        watchlist = listOf(ENTRY_1),
                        watchlistPageError = GroupFailure.Network,
                    ),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        composeRule.onNodeWithText(OWNER.username).assertIsDisplayed()
        composeRule.onNodeWithText(ENTRY_1.media.title).assertIsDisplayed()
        // The footer is the LAST row and is not composed at all on first layout — scroll the list
        // to its own testTag first (GroupWatchlistSection.kt's own KDoc on why Robolectric's
        // default viewport does not reach every row without an explicit scroll).
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("watchlist-page-error"))
        composeRule
            .onNodeWithText(context.getString(R.string.groups_watchlist_page_error))
            .assertIsDisplayed()
    }

    @Test
    fun `an empty watchlist shows its own empty state, not a blank scroll area`() {
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER), watchlist = emptyList()),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_watchlist_empty_message)).assertIsDisplayed()
    }

    /**
     * Fix round 1, "smaller item 1": `GroupDetailList`'s own `itemCount` is `0` when the watchlist
     * is empty, not `coerceAtLeast(1)` counting the empty-state row round 0 fed `EndOfListTrigger`.
     * `EndOfListTrigger`'s own `itemCount > 0` guard means it cannot fire at all in that state — and
     * because that guard evaluates against `lastVisibleIndex >= itemCount - threshold`, an
     * `itemCount` of exactly `1` (round 0's shape) is trivially "near its own end" on the VERY FIRST
     * laid-out frame with no scroll needed, which is what let it fire immediately in production and
     * race `reloadWatchlist`'s own initial fetch (finding B1). No scroll simulation is needed here
     * either, for the identical reason: composing this state ONCE is enough to prove the trigger
     * never fires no-scroll-needed under fix round 1, the same way it never needed a scroll to
     * misfire under round 0.
     */
    @Test
    fun `an empty watchlist never triggers loadMoreWatchlist on its own`() {
        var loadMoreCalls = 0
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER), watchlist = emptyList()),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = { loadMoreCalls++ },
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }
        composeRule.waitForIdle()

        assertEquals(0, loadMoreCalls)
    }

    /** [GroupDetailUiState.Success.watchlistLoadingMore]'s own rendering — a footer spinner. */
    @Test
    fun `the loading-more footer shows a spinner while a page fetch is in flight`() {
        composeRule.setContent {
            GroupDetailScreen(
                state =
                    successState(
                        members = listOf(OWNER),
                        watchlist = listOf(ENTRY_1),
                        watchlistLoadingMore = true,
                    ),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("watchlist-loading-more"))
        composeRule.onNodeWithTag("watchlist-loading-more").assertIsDisplayed()
        // The footer error row must NOT also render — loading and pageError are mutually exclusive
        // branches in watchlistItems, never both at once.
        composeRule
            .onNodeWithText(context.getString(R.string.groups_watchlist_page_error))
            .assertDoesNotExist()
    }

    /**
     * [GroupDetailUiState.Success.watchlistIsStale]'s own rendering (fix round 1, findings B2/B3):
     * a banner above the watchlist rows, distinct from the page-fetch footer — its retry is
     * `onRetry`, the SAME callback the top-level members banner already uses, not a second bespoke
     * one. Existing rows (including one whose delete may not actually be reflected — finding B3)
     * stay visible underneath it.
     */
    @Test
    fun `a stale watchlist shows a stale banner above its rows, and retrying invokes onRetry`() {
        var retried = false
        composeRule.setContent {
            GroupDetailScreen(
                state =
                    successState(
                        members = listOf(OWNER),
                        watchlist = listOf(ENTRY_1),
                        watchlistIsStale = true,
                    ),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = { retried = true },
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.groups_watchlist_stale_notice)).assertIsDisplayed()
        composeRule.onNodeWithText(ENTRY_1.media.title).assertIsDisplayed()
        // Never the empty-state message, even though nothing ELSE claims the watchlist is genuinely
        // empty here — this fixture is not empty, but a stale + empty combination must not show
        // "No one has proposed a title yet" contradicting the banner above it (watchlistItems' own
        // guard); this assertion pins the non-contradiction for the populated case.
        composeRule
            .onNodeWithText(context.getString(R.string.groups_watchlist_empty_message))
            .assertDoesNotExist()

        composeRule.onNodeWithText(context.getString(DesignSystemR.string.action_retry)).performClick()
        assertTrue(retried)
    }

    /**
     * Mutation-critical: a two-entry fixture, tapping the SECOND row — `tapping remove on the
     * second watchlist entry...`'s identical discriminating shape, applied to the row's own click
     * rather than its remove button. A handler that always reported `entries.first()` would pass a
     * single-entry test but fail this one.
     */
    @Test
    fun `tapping a watchlist entry invokes onEntryClick with that entry, not another`() {
        var clicked: WatchlistEntry? = null
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER), watchlist = listOf(ENTRY_1, ENTRY_2)),
                actionState = GroupDetailActionState(),
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = {},
                onRemoveEntryDialogOpened = {},
                onEntryClick = { clicked = it },
            )
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(ENTRY_2.media.title))
        composeRule.onNodeWithText(ENTRY_2.media.title).performClick()

        assertEquals(ENTRY_2, clicked)
    }

    /**
     * The remove-entry dialog's own version of 9c.2's BLOCKING-1-adjacent second bug (`a remove
     * confirmed while a different member's remove is still in flight does not close that dialog
     * early`) — fix round 1's own smaller item 2: `removeEntryAttempted` is read but not a
     * `LaunchedEffect` key, so this confirms the CONFIRM-SITE guard (`GroupDetailScreenDialogs`'s
     * own `onRemoveEntryConfirm`) is what actually prevents a stale `true` from closing the wrong
     * dialog, reproducing the identical sequence the member-remove regression test does one
     * resource over.
     */
    @Test
    fun `a remove-entry confirmed while a different entry's remove is in flight does not close its dialog early`() {
        var actionState by mutableStateOf(GroupDetailActionState())
        val removeCalls = mutableListOf<String>()
        composeRule.setContent {
            GroupDetailScreen(
                state = successState(members = listOf(OWNER), watchlist = listOf(ENTRY_1, ENTRY_2)),
                actionState = actionState,
                currentUserId = OWNER.userId,
                onRetry = {},
                onRotateInvite = {},
                onLeaveGroup = {},
                onRemoveMember = {},
                onDismissRotatedInvite = {},
                onRotateDialogOpened = {},
                onLeaveDialogOpened = {},
                onRemoveDialogOpened = {},
                onLoadMoreWatchlist = {},
                onRemoveWatchlistEntry = { entryId ->
                    removeCalls += entryId
                    // Mirrors GroupDetailViewModel.removeFromWatchlist's own synchronous
                    // guard-then-set — a remove already in flight for a DIFFERENT entry makes this
                    // call a no-op.
                    if (actionState.removingEntryId == null) {
                        actionState = actionState.copy(removingEntryId = entryId, removeEntryError = null)
                    }
                },
                onRemoveEntryDialogOpened = { actionState = actionState.copy(removeEntryError = null) },
                onEntryClick = {},
            )
        }

        // Start removing ENTRY_1: open, confirm — the fake onRemoveWatchlistEntry above sets
        // removingEntryId synchronously, the same call the confirm click makes.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("watchlist-remove-${ENTRY_1.id}"))
        composeRule.onNodeWithTag("watchlist-remove-${ENTRY_1.id}").performClick()
        composeRule.onNodeWithText(context.getString(R.string.groups_watchlist_remove_confirm_button)).performClick()
        composeRule.waitForIdle()

        // Dismiss with the system Back button while ENTRY_1's remove is still in flight — Cancel is
        // disabled (submitting), so Back is the only way out.
        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_watchlist_remove_confirm_title, ENTRY_1.media.title))
            .assertDoesNotExist()

        // Open and confirm ENTRY_2's dialog. ENTRY_1's remove is STILL in flight — actionState
        // carries no change from the dismissal above — so a real ViewModel's re-entrancy guard
        // would drop this call. This fake still records it; what the screen reacts to is
        // actionState alone.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("watchlist-remove-${ENTRY_2.id}"))
        composeRule.onNodeWithTag("watchlist-remove-${ENTRY_2.id}").performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_watchlist_remove_confirm_title, ENTRY_2.media.title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.groups_watchlist_remove_confirm_button)).performClick()

        // ENTRY_1's remove now lands successfully.
        actionState = actionState.copy(removingEntryId = null, removeEntryError = null)
        composeRule.waitForIdle()

        // ENTRY_2 was never removed — its dialog must still be showing.
        composeRule
            .onNodeWithText(context.getString(R.string.groups_watchlist_remove_confirm_title, ENTRY_2.media.title))
            .assertIsDisplayed()
        assertEquals(listOf(ENTRY_1.id, ENTRY_2.id), removeCalls)
    }

    // @Suppress("LongParameterList"): a test fixture builder with one named default per
    // GroupDetailUiState.Success field a test might want to vary — splitting it further would only
    // move the same six knobs into a second type built solely to hold them.
    @Suppress("LongParameterList")
    private fun successState(
        members: List<GroupMember>,
        watchlist: List<WatchlistEntry> = emptyList(),
        watchlistLoadingMore: Boolean = false,
        watchlistPageError: GroupFailure? = null,
        watchlistIsStale: Boolean = false,
        isStale: Boolean = false,
    ) = GroupDetailUiState.Success(
        members = members,
        watchlist = watchlist,
        watchlistLoadingMore = watchlistLoadingMore,
        watchlistPageError = watchlistPageError,
        watchlistIsStale = watchlistIsStale,
        isStale = isStale,
    )

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

        fun summary(title: String) =
            MediaSummary(
                source = MediaSource.ANILIST,
                externalId = title,
                type = MediaType.ANIME,
                title = title,
                year = 2024,
                genres = emptyList(),
                coverImageUrl = null,
            )

        fun entry(
            id: String,
            title: String,
            mediaId: String,
            proposedBy: String?,
        ) = WatchlistEntry(
            id = id,
            media = summary(title),
            mediaId = mediaId,
            proposedBy = proposedBy,
            createdAt = Instant.parse("2026-08-28T10:15:30Z"),
        )

        // proposedBy set to OWNER's own id, resolvable against a members fixture that includes
        // OWNER — WatchlistList's own lookup, GroupDetailScreenTest's identical reasoning.
        val ENTRY_1 = entry(id = "entry-1", title = "Frieren", mediaId = "media-1", proposedBy = OWNER.userId)

        // proposedBy null — a deleted account, per design §5.3 (this task's own first named test).
        val ENTRY_2 = entry(id = "entry-2", title = "Mushishi", mediaId = "media-2", proposedBy = null)
    }
}
