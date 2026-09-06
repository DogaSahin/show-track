package com.anarky.showtrack.feature.groups

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.GroupFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
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
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = { clicked = it },
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
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
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
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
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
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
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
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
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_invite_copy)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.groups_invite_copied)).assertIsDisplayed()
    }

    /**
     * Fix round 3: verifies the actual clipboard content, not just the button label — pins that
     * `sensitiveInviteCodeClipEntry` really does set `ClipDescription.EXTRA_IS_SENSITIVE` on API
     * 33+ (decision E-I: the invite code is a credential). Per-method `@Config(sdk = [33])`
     * overrides this module's `robolectric.properties` `sdk=35` default — Robolectric 4.15.1 ships
     * a real `ClipboardManager` shadow for API 33, so this reads the system clipboard directly
     * rather than only asserting on-screen text.
     */
    @Config(sdk = [33])
    @Test
    fun `copying the invite code marks the clip sensitive on API 33`() {
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA), justCreated = INVITE),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_invite_copy)).performClick()
        composeRule.waitForIdle()

        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboardManager.primaryClip
        assertEquals(INVITE.inviteCode, clip?.getItemAt(0)?.text.toString())
        assertTrue(clip?.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
    }

    /**
     * The negative control: `minSdk` is 29, and the sensitivity flag is guarded behind
     * `Build.VERSION.SDK_INT >= TIRAMISU` — below that, the extra must never be set (the platform
     * would not honour it either way, but the code must not claim it did).
     */
    @Config(sdk = [29])
    @Test
    fun `copying the invite code does not set the sensitive flag below API 33`() {
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA), justCreated = INVITE),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_invite_copy)).performClick()
        composeRule.waitForIdle()

        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboardManager.primaryClip
        assertEquals(INVITE.inviteCode, clip?.getItemAt(0)?.text.toString())
        assertFalse(clip?.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
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
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
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
     * typed code survived. This is the specific copy for [GroupFailure.InvalidInviteCode] (fix round 2 —
     * a bad or expired code is the single most common outcome of this form).
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
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_join_action)).performClick()

        actionState = GroupsActionState(joinError = GroupFailure.InvalidInviteCode)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.groups_join_error_bad_code)).assertIsDisplayed()
    }

    /**
     * Fix round 2 — the negative control for the test above: a [GroupFailure.Unknown] (a 500, an
     * expired session) on the SAME form must NOT get the bad-code copy — that was round 1's actual
     * bug (`messageRes(unknownRes = …)` could not tell a genuine 400 apart from any other unmapped
     * failure, since both arrived as [GroupFailure.Unknown]).
     */
    @Test
    fun `a join failure that is not a bad code shows the generic message, not the bad-code copy`() {
        var actionState by mutableStateOf(GroupsActionState())
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = emptyList()),
                actionState = actionState,
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_join_action)).performClick()

        actionState = GroupsActionState(joinError = GroupFailure.Unknown(IllegalStateException("server error")))
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.groups_error_unknown)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.groups_join_error_bad_code)).assertDoesNotExist()
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
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
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
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
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
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_error_network)).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.action_retry))
            .performClick()

        assertTrue(retried)
    }

    /**
     * Small item 2 (fix round 1 review, closed in fix round 2): round 1's BLOCKING 1 fix made
     * create/join work from [GroupsUiState.Error] at the ViewModel level, but no COMMITTED test
     * pinned the rendering half — hiding `GroupsTopBar`'s Create/Join buttons behind
     * `state is GroupsUiState.Success` would have left every round-1 test green, since none of
     * them drove the form from an `Error` state. This test does.
     */
    @Test
    fun `the join form is reachable and submits from an Error state, not only Success`() {
        var joinedWith: String? = null
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Error(GroupFailure.Network),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = { code -> joinedWith = code },
                onDismissInvite = {},
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_join_action)).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_join_code_label))
            .performTextInput("ABCDEFGHIJ1234567890")
        composeRule.onNodeWithText(context.getString(R.string.groups_join_submit)).performClick()

        assertEquals("ABCDEFGHIJ1234567890", joinedWith)
    }

    /**
     * Fix round 2, small item 3: opening a dialog after a previous failed attempt must not show
     * that attempt's error before the user has done anything new. The CLEARING itself is
     * `GroupsViewModel.clearCreateError`/`clearJoinError` (pinned in `GroupsViewModelTest`); what
     * only a screen test can see is whether OPENING the dialog actually reaches that callback.
     */
    @Test
    fun `opening the create dialog invokes onCreateDialogOpened`() {
        var opened = false
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA)),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onCreateDialogOpened = { opened = true },
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_create_action)).performClick()

        assertTrue(opened)
    }

    /** [onCreateDialogOpened]'s mirror for the join dialog. */
    @Test
    fun `opening the join dialog invokes onJoinDialogOpened`() {
        var opened = false
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA)),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onCreateDialogOpened = {},
                onJoinDialogOpened = { opened = true },
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_join_action)).performClick()

        assertTrue(opened)
    }

    /**
     * Fix round 3 (review finding): `GroupRepository.joinGroup` is deliberately idempotent
     * (decision G-I) — redeeming the SAME code twice for a group already joined returns an
     * `equals`-identical [GroupWithInvite] both times. [state] here never changes at all (it is a
     * fixed `val`, exactly modelling `MutableStateFlow`'s own conflation of an equal value), so
     * only [actionState] toggling `joining` true then false is what the fixed `LaunchedEffect` key
     * has to react to — pinning that the dialog closes even when `justCreated`'s VALUE never
     * visibly changes.
     */
    @Test
    fun `an idempotent rejoin closes the dialog even though the invite value never changes`() {
        var actionState by mutableStateOf(GroupsActionState())
        val state = GroupsUiState.Success(groups = listOf(ALPHA), justCreated = INVITE)
        composeRule.setContent {
            GroupsScreen(
                state = state,
                actionState = actionState,
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = null,
                switcherGroups = emptyList(),
                onSwitchGroup = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        // Reopen the join dialog — the round trip a user pasting the same code again would take.
        composeRule.onNodeWithText(context.getString(R.string.groups_join_action)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.groups_join_code_label)).assertIsDisplayed()

        // The second join completes: `joining` flips true then false, while `state` — and
        // therefore `justCreated` — stays exactly as it already was.
        actionState = GroupsActionState(joining = true)
        composeRule.waitForIdle()
        actionState = GroupsActionState(joining = false)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.groups_join_code_label)).assertDoesNotExist()
    }

    /**
     * The switcher's own gating/selection behaviour is `GroupSwitcherTest`'s job
     * (`:core:designsystem`) — this is only the WIRING check: [GroupsScreen] actually plugs
     * `GroupSwitcher` into its own `activeGroupId`/`onSwitchGroup` parameters and reads its group
     * list from [GroupsUiState.Success.groups], rather than, say, dropping the callback.
     *
     * `state.groups` and `switcherGroups` are given the SAME two groups here — the ordinary case,
     * where the account's list and the active-group list agree — so [GroupsList] also renders a row
     * for `BETA.name` and the name is genuinely ambiguous on this screen. `.onFirst()` is the
     * switcher's own tab: [GroupSwitcher] renders unconditionally ABOVE `GroupsContent` in this
     * screen's `Column` — production ordering this test relies on, not an assumption about traversal
     * order in general. The case where the two lists DISAGREE is the next test down.
     */
    @Test
    fun `tapping a group in the switcher invokes onSwitchGroup with that group's id`() {
        var selected: String? = null
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA, BETA)),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = ALPHA.id,
                switcherGroups = listOf(ALPHA, BETA),
                onSwitchGroup = { selected = it },
            )
        }

        composeRule.onAllNodesWithText(BETA.name).onFirst().performClick()

        assertEquals(BETA.id, selected)
    }

    /**
     * BLOCKING 3's own pin (whole-branch fix round). The switcher's tabs must come from
     * [switcherGroups] — `ActiveGroupState.Success.groups`, the same list
     * `ActiveGroupViewModel.recompute` validates a selection against — and NOT from
     * [GroupsUiState.Success.groups], which this screen refreshes independently and appends to
     * in place the moment a create or join succeeds.
     *
     * The fixture makes the two disagree deliberately: the account's own list holds ALPHA alone
     * (one row, so [GroupSwitcher]'s `groups.size < 2` gate would suppress it), while the
     * active-group list holds ALPHA and BETA. `BETA.name` can therefore only have come from a
     * switcher tab. Restoring the old `(state as? GroupsUiState.Success)?.groups` read makes the
     * switcher see one group, render nothing, and this assertion fail — which is exactly the state
     * a user reached by joining a second group from this screen, where the new tab was offered and
     * then rejected on tap.
     */
    @Test
    fun `the switcher renders the active-group list, not this screen's own list`() {
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA)),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = ALPHA.id,
                switcherGroups = listOf(ALPHA, BETA),
                onSwitchGroup = {},
            )
        }

        composeRule.onAllNodesWithText(BETA.name).assertCountEquals(1)
        composeRule.onAllNodesWithText(ALPHA.name).assertCountEquals(2)
    }

    /**
     * The negative control: a single group in the ACTIVE-GROUP list renders no switcher at all
     * (E-K). `ALPHA.name`
     * still renders once, as the ordinary list row — [onAllNodesWithText]'s count is what actually
     * discriminates "the switcher also rendered a tab with the same name" from "only the list row
     * exists": a plain `onNodeWithText` would merely throw on an ambiguous match either way, which
     * reads as a broken test, not a failing assertion, if this regressed.
     */
    @Test
    fun `a single active group shows no switcher`() {
        composeRule.setContent {
            GroupsScreen(
                state = GroupsUiState.Success(groups = listOf(ALPHA)),
                actionState = GroupsActionState(),
                onRetry = {},
                onCreateGroup = {},
                onJoinGroup = {},
                onDismissInvite = {},
                onCreateDialogOpened = {},
                onJoinDialogOpened = {},
                onGroupClick = {},
                activeGroupId = ALPHA.id,
                switcherGroups = listOf(ALPHA),
                onSwitchGroup = {},
            )
        }

        composeRule.onAllNodesWithText(ALPHA.name).assertCountEquals(1)
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
