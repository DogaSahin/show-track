package com.anarky.showtrack.feature.groups

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.designsystem.component.EndOfListTrigger
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.GroupRole
import com.anarky.showtrack.core.model.WatchlistEntry

/**
 * The stateful entry point. `hiltViewModel()` is the only line here that touches DI —
 * `GroupsScreen`/`FavoritesScreen`'s shape.
 *
 * [onLeft] fires exactly once, right after a confirmed leave succeeds — [GroupDetailViewModel.left]
 * flipping to `true` is what the [LaunchedEffect] below reacts to, `ProfileScreen`'s
 * `LaunchedEffect(signedOut) { if (signedOut) onSignedOut() }` applied to this screen's own exit.
 * `GroupsNavigation.kt`'s `groupDetailEntry` is the module boundary that turns this into
 * `onNavigate(GroupsRoute)` — this screen itself names no route, the same split `ProfileNavigation.kt`
 * draws for `onSignedOut`/`AuthRoute`.
 *
 * No `LifecycleResumeEffect` — [GroupDetailViewModel]'s own KDoc explains why this screen's
 * ViewModel loads from `init` instead, `DetailViewModel`'s pattern, not `GroupsScreen`'s.
 *
 * Collects [GroupDetailViewModel.currentUserId] alongside [GroupDetailViewModel.state] — round 1
 * review moved identity off [GroupDetailUiState] entirely (that type's own KDoc), so the stateless
 * overload below needs it as a SIBLING parameter, not a field it can read off [state].
 */
@Composable
fun GroupDetailScreen(
    onLeft: () -> Unit,
    onEntryClick: (WatchlistEntry) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupDetailViewModel = hiltViewModel(),
) {
    val left by viewModel.left.collectAsStateWithLifecycle()
    LaunchedEffect(left) {
        if (left) onLeft()
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val actionState by viewModel.actionState.collectAsStateWithLifecycle()
    val currentUserId by viewModel.currentUserId.collectAsStateWithLifecycle()
    val invite by viewModel.invite.collectAsStateWithLifecycle()
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    GroupDetailScreen(
        groupId = viewModel.groupId,
        groupName = viewModel.groupName,
        state = state,
        actionState = actionState,
        currentUserId = currentUserId,
        invite = invite,
        onLoadInvite = viewModel::loadInvite,
        onBack = { backDispatcher?.onBackPressed() },
        onRetry = viewModel::refresh,
        onRotateInvite = viewModel::rotateInvite,
        onLeaveGroup = viewModel::leaveGroup,
        onRemoveMember = viewModel::removeMember,
        onRotateDialogOpened = viewModel::clearRotateError,
        onLeaveDialogOpened = viewModel::clearLeaveError,
        onRemoveDialogOpened = viewModel::clearRemoveError,
        onLoadMoreWatchlist = viewModel::loadMoreWatchlist,
        onRemoveWatchlistEntry = viewModel::removeFromWatchlist,
        onRemoveEntryDialogOpened = viewModel::clearRemoveEntryError,
        onEntryClick = onEntryClick,
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be previewed and driven by a test with no ViewModel and
 * no Hilt — `GroupsScreen`/`FavoritesScreen`'s pattern. Every rendering decision this screen makes
 * lives here, which is deliberately why [GroupDetailViewModelTest] cannot pin them (Global
 * Constraints: "if a behaviour is a rendering decision, a ViewModel test cannot pin it") —
 * `GroupDetailScreenTest` is what drives this overload directly.
 *
 * **"Leave group" renders unconditionally, regardless of [state]** (round 1 review, BLOCKING 2's
 * screen-side half): it sits below [GroupDetailContent] in the `Column`, not inside
 * [GroupDetailSuccessContent] where round 0 left it — leaving a group has nothing to do with
 * whether ITS OWN member list happened to load, and hiding the button behind a successful load
 * silently un-did [GroupDetailViewModel.leaveGroup]'s own fix for that exact bug. No `isOwner`
 * gate either: every member, owner included, may leave (`GroupDetailViewModel.leaveGroup`'s own
 * KDoc) — round 1 review's minor 1 measured that gating this on `isOwner` leaves every OTHER
 * screen test green, since none of them drove a non-owner through Leave; `an owner and a non-owner
 * can both leave` below is the negative control that closes it.
 *
 * [showRotateDialog]/[showLeaveDialog]/[pendingRemoveTarget] are this composable's OWN `remember`ed
 * state, not part of [GroupDetailUiState] or [GroupDetailActionState] — dialog visibility is a
 * rendering decision, not a fact the ViewModel knows, `GroupsScreen`'s identical `showCreateDialog`/
 * `showJoinDialog` split.
 *
 * The two [LaunchedEffect]s close their own dialog on a SUCCESSFUL action, leaving it open with the
 * inline error visible on a failed one:
 * - Rotate closes on [GroupDetailUiState.Success.rotatedInvite] going non-null. Unlike
 *   `GroupsScreen`'s join dialog, no extra key on the `rotating` flag is needed — `rotateInvite`
 *   (task 9c.0) is not idempotent the way `joinGroup` is (decision G-I), so a genuinely new
 *   [com.anarky.showtrack.core.data.repository.GroupWithInvite] is what every successful rotate
 *   produces, never an equals-identical repeat that `MutableStateFlow` would conflate away.
 * - Remove closes when [GroupDetailActionState.removingUserId] returns to `null` with
 *   [GroupDetailActionState.removeError] still `null` **AND [removeAttempted] is true** (round 1
 *   review, BLOCKING 1). Without [removeAttempted], that `(null, null)` pair is ALSO the RESTING
 *   state — what `removingUserId`/`removeError` already read before any remove has ever been
 *   attempted for the currently-open dialog — and `onRemoveDialogOpened` is bound to
 *   `clearRemoveError`: reopening the dialog for a NEW target right after a PREVIOUS remove's
 *   error clears that error in the same recomposition that sets [pendingRemoveTarget], which
 *   flips the key from `(null, SomeError)` to `(null, null)` — the exact success signature — and
 *   the effect fired, nulling the just-set target before the user had done anything. [removeAttempted]
 *   is reset to `false` on every dialog OPEN (regardless of whether the target is the SAME member
 *   re-targeted, which a `remember(pendingRemoveTarget)` keyed only on the target's VALUE would
 *   miss, since a data class re-opened with an equal value does not re-key) and set to `true` only
 *   when the dialog's own confirm button actually fires — so the effect can never mistake "the
 *   dialog just (re)opened" for "the remove that was in flight when it opened just succeeded".
 *   Keyed on the ACTION's own flags plus [removeAttempted], not on the member list's content, on
 *   purpose: `GroupDetailViewModel.reloadMembers`'s own KDoc notes a reload can fail right after a
 *   successful delete (marking the screen [GroupDetailUiState.Success.isStale] rather than dropping
 *   the row), and this dialog must still close in that case — the removal itself DID succeed, and
 *   waiting for a row to visibly disappear that a failed reload will never show would leave the
 *   confirm dialog stuck open over a success.
 *
 *   [removeAttempted] is only ever set for the dialog CURRENTLY reacting to it, never for one
 *   already resolved or one whose call was dropped — that guard lives at the `onRemoveConfirm`
 *   lambda passed to [GroupDetailActionDialogs] below, not here. Without it: start removing sam
 *   (confirm sets `removeAttempted = true` and — a real `GroupDetailViewModel.removeMember`'s own
 *   re-entrancy guard — sets `removingUserId = sam`), dismiss sam's dialog with Back while it is
 *   still in flight (Confirm and Cancel are both disabled by `submitting`, so Back is the only
 *   way out; `pendingRemoveTarget`/[removeAttempted] reset to `null`/`false`, `removingUserId`
 *   untouched), open kai's dialog (`removeAttempted` reset `false` on open), tap kai's Confirm —
 *   the ViewModel's re-entrancy guard drops it because sam's remove is still in flight, so
 *   `actionState` never changes, but `removeAttempted` would flip `true` for KAI's dialog
 *   regardless. When sam's remove then lands (`removingUserId`/`removeError` both back to
 *   `null`), the effect reads that stale `true` and closes KAI's dialog as though kai had been
 *   removed. Self-correcting (kai is still in the reloaded list) but a false close all the same —
 *   `GroupDetailScreenTest`'s `a remove confirmed while a different member's remove is still in
 *   flight does not close that dialog early` is the regression test.
 *
 * Leave needs no such effect: a successful leave flips [GroupDetailViewModel.left], which the
 * STATEFUL [GroupDetailScreen] above reacts to by navigating away — the whole composable subtree,
 * confirm dialog included, is torn down before there is anything left here to close.
 */
@Suppress("LongParameterList")
@Composable
internal fun GroupDetailScreen(
    groupId: String,
    groupName: String?,
    state: GroupDetailUiState,
    actionState: GroupDetailActionState,
    currentUserId: String?,
    invite: InviteState,
    onLoadInvite: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRotateInvite: () -> Unit,
    onLeaveGroup: () -> Unit,
    onRemoveMember: (String) -> Unit,
    onRotateDialogOpened: () -> Unit,
    onLeaveDialogOpened: () -> Unit,
    onRemoveDialogOpened: () -> Unit,
    onLoadMoreWatchlist: () -> Unit,
    onRemoveWatchlistEntry: (String) -> Unit,
    onRemoveEntryDialogOpened: () -> Unit,
    onEntryClick: (WatchlistEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dialogState = rememberGroupDetailDialogState(state = state, actionState = actionState)
    var showRotateDialog by dialogState.showRotateDialog
    var showLeaveDialog by dialogState.showLeaveDialog
    var pendingRemoveTarget by dialogState.pendingRemoveTarget
    var removeAttempted by dialogState.removeAttempted
    var pendingRemoveEntryTarget by dialogState.pendingRemoveEntryTarget
    var removeEntryAttempted by dialogState.removeEntryAttempted

    GroupDetailBody(
        header = GroupHeaderData(groupId = groupId, name = groupName ?: stringResource(R.string.groups_detail_title)),
        state = state,
        currentUserId = currentUserId,
        invite = invite,
        onLoadInvite = onLoadInvite,
        onBack = onBack,
        onRetry = onRetry,
        onRotateClick = {
            onRotateDialogOpened()
            showRotateDialog = true
        },
        onLeaveClick = {
            onLeaveDialogOpened()
            showLeaveDialog = true
        },
        onRemoveClick = { member ->
            onRemoveDialogOpened()
            pendingRemoveTarget = member
            removeAttempted = false
        },
        onLoadMoreWatchlist = onLoadMoreWatchlist,
        onRemoveEntryClick = { entry ->
            onRemoveEntryDialogOpened()
            pendingRemoveEntryTarget = entry
            removeEntryAttempted = false
        },
        onEntryClick = onEntryClick,
        modifier = modifier,
    )

    GroupDetailScreenDialogs(
        dialogState = dialogState,
        actionState = actionState,
        onRotateInvite = onRotateInvite,
        onLeaveGroup = onLeaveGroup,
        onRemoveMember = onRemoveMember,
        onRemoveWatchlistEntry = onRemoveWatchlistEntry,
    )
}

/**
 * [GroupDetailScreen]'s own `remember`ed dialog-visibility state, plus the [DialogCloseEffects]
 * wiring over it — pulled out purely to keep that function's own length under detekt's `LongMethod`
 * threshold; no behaviour moved with it that a caller could observe differently. [GroupDetailScreen]'s
 * own KDoc documents WHY each field exists and how it is used.
 *
 * **Fix round 1 removed the propose dialog's own state** (`showProposeDialog`/`proposeAttempted`) —
 * see [GroupDetailActionState]'s own KDoc for why proposing moved to `:feature:detail` (task 9c.6).
 *
 * `@Suppress("LongParameterList")`: a private, internal state carrier for one screen's four
 * dialogs — `FakeGroupRepository`'s own suppression carries the identical "this is what the shape
 * genuinely needs" reasoning, not a bag of unrelated fields that should have been split.
 */
@Suppress("LongParameterList")
private class GroupDetailDialogState(
    val showRotateDialog: MutableState<Boolean>,
    val showLeaveDialog: MutableState<Boolean>,
    val pendingRemoveTarget: MutableState<GroupMember?>,
    val removeAttempted: MutableState<Boolean>,
    val pendingRemoveEntryTarget: MutableState<WatchlistEntry?>,
    val removeEntryAttempted: MutableState<Boolean>,
)

@Composable
private fun rememberGroupDetailDialogState(
    state: GroupDetailUiState,
    actionState: GroupDetailActionState,
): GroupDetailDialogState {
    val showRotateDialog = remember { mutableStateOf(false) }
    val showLeaveDialog = remember { mutableStateOf(false) }
    val pendingRemoveTarget = remember { mutableStateOf<GroupMember?>(null) }
    val removeAttempted = remember { mutableStateOf(false) }
    val pendingRemoveEntryTarget = remember { mutableStateOf<WatchlistEntry?>(null) }
    val removeEntryAttempted = remember { mutableStateOf(false) }

    DialogCloseEffects(
        rotatedInvite = (state as? GroupDetailUiState.Success)?.rotatedInvite,
        actionState = actionState,
        removeAttempted = removeAttempted.value,
        removeEntryAttempted = removeEntryAttempted.value,
        onRotateDialogShouldClose = { showRotateDialog.value = false },
        onRemoveDialogShouldClose = {
            pendingRemoveTarget.value = null
            removeAttempted.value = false
        },
        onRemoveEntryDialogShouldClose = {
            pendingRemoveEntryTarget.value = null
            removeEntryAttempted.value = false
        },
    )

    return GroupDetailDialogState(
        showRotateDialog = showRotateDialog,
        showLeaveDialog = showLeaveDialog,
        pendingRemoveTarget = pendingRemoveTarget,
        removeAttempted = removeAttempted,
        pendingRemoveEntryTarget = pendingRemoveEntryTarget,
        removeEntryAttempted = removeEntryAttempted,
    )
}

/**
 * The four confirm/dismiss callbacks [GroupDetailActionDialogs] needs, built from [dialogState] and
 * the raw mutation calls — pulled out of [GroupDetailScreen] for the identical `LongMethod` reason
 * [rememberGroupDetailDialogState] was. [onRemoveConfirm]'s own inline comment (below) is where the
 * "only mark an attempt when nothing is already in flight" reasoning lives —
 * [GroupDetailActionState]'s own KDoc has the higher-level "why one channel per operation" argument.
 */
@Suppress("LongParameterList")
@Composable
private fun GroupDetailScreenDialogs(
    dialogState: GroupDetailDialogState,
    actionState: GroupDetailActionState,
    onRotateInvite: () -> Unit,
    onLeaveGroup: () -> Unit,
    onRemoveMember: (String) -> Unit,
    onRemoveWatchlistEntry: (String) -> Unit,
) {
    var showRotateDialog by dialogState.showRotateDialog
    var pendingRemoveTarget by dialogState.pendingRemoveTarget
    var removeAttempted by dialogState.removeAttempted
    var pendingRemoveEntryTarget by dialogState.pendingRemoveEntryTarget
    var removeEntryAttempted by dialogState.removeEntryAttempted

    GroupDetailActionDialogs(
        showRotateDialog = showRotateDialog,
        showLeaveDialog = dialogState.showLeaveDialog.value,
        pendingRemoveTarget = pendingRemoveTarget,
        pendingRemoveEntryTarget = pendingRemoveEntryTarget,
        actionState = actionState,
        onRotateConfirm = onRotateInvite,
        onRotateDismiss = { showRotateDialog = false },
        onLeaveConfirm = onLeaveGroup,
        onLeaveDismiss = { dialogState.showLeaveDialog.value = false },
        onRemoveConfirm = { userId ->
            // A remove already in flight for a DIFFERENT member makes this call a silent no-op —
            // GroupDetailViewModel.removeMember's own re-entrancy guard drops it because
            // actionState.removingUserId is already someone else's id, so actionState itself
            // never changes because of THIS tap. Marking removeAttempted true anyway would leave
            // it wrongly set for THIS dialog's target; when the OTHER member's remove later
            // resolves (removingUserId back to null), the close effect would read that stale true
            // and close THIS dialog as though its own target had been removed. Setting it only
            // when nothing else is in flight keeps it meaning what it says: "the remove now
            // resolving is the one this open dialog actually asked for."
            if (actionState.removingUserId == null) {
                removeAttempted = true
            }
            onRemoveMember(userId)
        },
        onRemoveDismiss = {
            pendingRemoveTarget = null
            removeAttempted = false
        },
        onRemoveEntryConfirm = { entryId ->
            // [onRemoveConfirm]'s own note above, applied identically here, one resource over.
            if (actionState.removingEntryId == null) {
                removeEntryAttempted = true
            }
            onRemoveWatchlistEntry(entryId)
        },
        onRemoveEntryDismiss = {
            pendingRemoveEntryTarget = null
            removeEntryAttempted = false
        },
    )
}

/**
 * [GroupDetailScreen]'s own close-on-success effects — pulled out purely to keep that function's
 * own length under detekt's `LongMethod` threshold; no behaviour moved with it that a caller could
 * observe differently. [GroupDetailScreen]'s own KDoc documents WHY each condition is shaped the
 * way it is (BLOCKING 1's `removeAttempted` fix, most of all) — that reasoning stays there, not
 * duplicated here.
 *
 * [removeEntryAttempted] (task 9c.3) is [removeAttempted]'s IDENTICAL shape, applied to the
 * remove-entry dialog: no natural "just succeeded" signal to key on the way rotate's
 * [rotatedInvite] does (there is no per-success unique VALUE this task exposes — the watchlist LIST
 * changing is not specific enough, [GroupDetailViewModel.reloadWatchlist]'s own KDoc on why keying a
 * close effect on list CONTENT would be wrong, the identical reasoning [removeAttempted] itself
 * already carries for [GroupDetailActionState.removingUserId]).
 *
 * **Fix round 1's own residual note:** [removeAttempted]/[removeEntryAttempted] are read inside the
 * effect bodies below but are deliberately NOT [LaunchedEffect] keys themselves — 9c.2's own
 * `removeAttempted` carries the identical shape and the identical residual hole this file already
 * accepted once: the CONFIRM-SITE guard (`GroupDetailScreenDialogs`'s own `onRemoveConfirm`/
 * `onRemoveEntryConfirm`, both above) is what actually prevents a stale `true` from closing the
 * WRONG dialog, not this effect noticing the flag change on its own — see those two lambdas' own
 * inline comments. `GroupDetailScreenTest`'s `a remove-entry confirmed while a different entry's
 * remove is still in flight does not close that dialog early` is the regression test for the
 * remove-entry half; `a remove confirmed while a different member's remove is still in flight does
 * not close that dialog early` (9c.2) is its member-remove counterpart.
 */
@Suppress("LongParameterList")
@Composable
private fun DialogCloseEffects(
    rotatedInvite: GroupWithInvite?,
    actionState: GroupDetailActionState,
    removeAttempted: Boolean,
    removeEntryAttempted: Boolean,
    onRotateDialogShouldClose: () -> Unit,
    onRemoveDialogShouldClose: () -> Unit,
    onRemoveEntryDialogShouldClose: () -> Unit,
) {
    LaunchedEffect(rotatedInvite) {
        if (rotatedInvite != null) onRotateDialogShouldClose()
    }
    LaunchedEffect(actionState.removingUserId, actionState.removeError) {
        if (removeAttempted && actionState.removingUserId == null && actionState.removeError == null) {
            onRemoveDialogShouldClose()
        }
    }
    LaunchedEffect(actionState.removingEntryId, actionState.removeEntryError) {
        if (removeEntryAttempted && actionState.removingEntryId == null && actionState.removeEntryError == null) {
            onRemoveEntryDialogShouldClose()
        }
    }
}

/**
 * The four owner/member/watchlist confirmation dialogs, bundled — pulled out of [GroupDetailScreen]
 * for the identical [DialogCloseEffects] reason above.
 */
@Suppress("LongParameterList")
@Composable
private fun GroupDetailActionDialogs(
    showRotateDialog: Boolean,
    showLeaveDialog: Boolean,
    pendingRemoveTarget: GroupMember?,
    pendingRemoveEntryTarget: WatchlistEntry?,
    actionState: GroupDetailActionState,
    onRotateConfirm: () -> Unit,
    onRotateDismiss: () -> Unit,
    onLeaveConfirm: () -> Unit,
    onLeaveDismiss: () -> Unit,
    onRemoveConfirm: (String) -> Unit,
    onRemoveDismiss: () -> Unit,
    onRemoveEntryConfirm: (String) -> Unit,
    onRemoveEntryDismiss: () -> Unit,
) {
    RotateDialogHost(
        visible = showRotateDialog,
        actionState = actionState,
        onConfirm = onRotateConfirm,
        onDismiss = onRotateDismiss,
    )
    LeaveDialogHost(
        visible = showLeaveDialog,
        actionState = actionState,
        onConfirm = onLeaveConfirm,
        onDismiss = onLeaveDismiss,
    )
    RemoveDialogHost(
        target = pendingRemoveTarget,
        actionState = actionState,
        onConfirm = onRemoveConfirm,
        onDismiss = onRemoveDismiss,
    )
    RemoveWatchlistEntryDialogHost(
        target = pendingRemoveEntryTarget,
        actionState = actionState,
        onConfirm = onRemoveEntryConfirm,
        onDismiss = onRemoveEntryDismiss,
    )
}

/** The page's identity for its header and share text. */
internal data class GroupHeaderData(
    val groupId: String,
    val name: String,
)

/**
 * The page frame: back and ⋯ in the top bar, then the state-dependent body. "Leave group" lives in
 * the ⋯ menu, which renders in EVERY state, Error included — leaving a group has nothing to do
 * with whether its own member list loaded ([GroupDetailScreen]'s own KDoc). "New invite code" is in
 * the same menu, for the owner only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
private fun GroupDetailBody(
    header: GroupHeaderData,
    state: GroupDetailUiState,
    currentUserId: String?,
    invite: InviteState,
    onLoadInvite: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRotateClick: () -> Unit,
    onLeaveClick: () -> Unit,
    onRemoveClick: (GroupMember) -> Unit,
    onLoadMoreWatchlist: () -> Unit,
    onRemoveEntryClick: (WatchlistEntry) -> Unit,
    onEntryClick: (WatchlistEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOwner = (state as? GroupDetailUiState.Success)?.isOwner(currentUserId) == true
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.groups_back),
                        )
                    }
                },
                actions = { GroupMenu(isOwner = isOwner, onRotateClick = onRotateClick, onLeaveClick = onLeaveClick) },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                is GroupDetailUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
                is GroupDetailUiState.Error ->
                    ErrorState(
                        message = stringResource(state.cause.messageRes()),
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxSize(),
                    )
                is GroupDetailUiState.Success ->
                    GroupDetailList(
                        header = header,
                        state = state,
                        currentUserId = currentUserId,
                        isOwner = isOwner,
                        invite = invite,
                        onLoadInvite = onLoadInvite,
                        onRetry = onRetry,
                        onRotateClick = onRotateClick,
                        onRemoveClick = onRemoveClick,
                        onLoadMoreWatchlist = onLoadMoreWatchlist,
                        onRemoveEntryClick = onRemoveEntryClick,
                        onEntryClick = onEntryClick,
                    )
            }
        }
    }
}

/**
 * Owner-ness (E-F), derived every recomposition from the LIVE member list and [currentUserId] —
 * never cached. Unknown identity, or the viewer briefly missing from their own list, reads as "not
 * the owner", which hides owner-only controls rather than flashing them.
 */
private fun GroupDetailUiState.Success.isOwner(currentUserId: String?): Boolean =
    members.firstOrNull { it.userId == currentUserId }?.role == GroupRole.OWNER

@Composable
private fun GroupMenu(
    isOwner: Boolean,
    onRotateClick: () -> Unit,
    onLeaveClick: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more),
                contentDescription = stringResource(R.string.groups_detail_more),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (isOwner) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.groups_detail_rotate_action)) },
                    onClick = {
                        open = false
                        onRotateClick()
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.groups_detail_leave_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    open = false
                    onLeaveClick()
                },
            )
        }
    }
}

/**
 * The one `LazyColumn` for the page: header, the owner's invite strip, the tabs (pinned once they
 * reach the top), then the selected tab's rows. One scrollable region, so the header scrolls away
 * and whichever tab is longer simply scrolls further.
 *
 * The invite is loaded the first time this page knows the viewer is the owner — never for a member.
 *
 * Paging: [EndOfListTrigger] counts this LazyColumn's own items. It only arms on the Watchlist tab
 * with a non-empty list, so it cannot fire before the first page has landed (the race the
 * paginator's own lock exists for).
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun GroupDetailList(
    header: GroupHeaderData,
    state: GroupDetailUiState.Success,
    currentUserId: String?,
    isOwner: Boolean,
    invite: InviteState,
    onLoadInvite: () -> Unit,
    onRetry: () -> Unit,
    onRotateClick: () -> Unit,
    onRemoveClick: (GroupMember) -> Unit,
    onLoadMoreWatchlist: () -> Unit,
    onRemoveEntryClick: (WatchlistEntry) -> Unit,
    onEntryClick: (WatchlistEntry) -> Unit,
) {
    LaunchedEffect(isOwner) {
        if (isOwner) onLoadInvite()
    }
    var tab by rememberSaveable { mutableStateOf(GroupTab.WATCHLIST) }
    val listState = rememberLazyListState()
    val leadingItems = if (isOwner) TOP_ITEMS_WITH_STRIP else TOP_ITEMS
    val watchlistRows = (state.watchlist.size + WATCHLIST_COLUMNS - 1) / WATCHLIST_COLUMNS
    val itemCount = if (tab == GroupTab.WATCHLIST && state.watchlist.isNotEmpty()) leadingItems + watchlistRows else 0
    EndOfListTrigger(
        listState = listState,
        itemCount = itemCount,
        rearmKey = state.watchlist.size,
        onTriggered = onLoadMoreWatchlist,
    )

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") { GroupHeader(groupId = header.groupId, name = header.name, members = state.members) }
        if (isOwner) {
            item(key = "invite-strip") {
                InviteStrip(
                    groupName = header.name,
                    invite = invite,
                    onNewCode = onRotateClick,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        stickyHeader(key = "tabs") {
            GroupTabs(
                selected = tab,
                watchlistCount = if (state.watchlistComplete) state.watchlist.size else null,
                memberCount = state.members.size,
                onSelect = { tab = it },
            )
        }
        if (state.isStale) {
            item(key = "stale-banner") {
                StaleDataBanner(onRetry = onRetry, messageRes = R.string.groups_detail_stale_notice)
            }
        }
        when (tab) {
            GroupTab.WATCHLIST -> {
                if (state.watchlistIsStale) {
                    item(key = "watchlist-stale-banner") {
                        StaleDataBanner(onRetry = onRetry, messageRes = R.string.groups_watchlist_stale_notice)
                    }
                }
                watchlistItems(
                    entries = state.watchlist,
                    members = state.members,
                    loadingMore = state.watchlistLoadingMore,
                    pageError = state.watchlistPageError != null,
                    isStale = state.watchlistIsStale,
                    onLoadMore = onLoadMoreWatchlist,
                    onRemoveClick = onRemoveEntryClick,
                    onEntryClick = onEntryClick,
                )
            }
            GroupTab.MEMBERS ->
                membersItems(
                    members = state.members,
                    currentUserId = currentUserId,
                    isOwner = isOwner,
                    onRemoveClick = onRemoveClick,
                )
        }
    }
}

private const val WATCHLIST_COLUMNS = 3

// Header and tabs; plus the invite strip for the owner.
private const val TOP_ITEMS = 2
private const val TOP_ITEMS_WITH_STRIP = 3
