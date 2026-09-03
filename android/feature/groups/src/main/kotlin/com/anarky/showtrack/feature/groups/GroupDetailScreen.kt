package com.anarky.showtrack.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    GroupDetailScreen(
        state = state,
        actionState = actionState,
        currentUserId = currentUserId,
        onRetry = viewModel::refresh,
        onRotateInvite = viewModel::rotateInvite,
        onLeaveGroup = viewModel::leaveGroup,
        onRemoveMember = viewModel::removeMember,
        onDismissRotatedInvite = viewModel::dismissRotatedInvite,
        onRotateDialogOpened = viewModel::clearRotateError,
        onLeaveDialogOpened = viewModel::clearLeaveError,
        onRemoveDialogOpened = viewModel::clearRemoveError,
        onLoadMoreWatchlist = viewModel::loadMoreWatchlist,
        onProposeTitle = viewModel::proposeTitle,
        onRemoveWatchlistEntry = viewModel::removeFromWatchlist,
        onProposeDialogOpened = viewModel::clearProposeError,
        onRemoveEntryDialogOpened = viewModel::clearRemoveEntryError,
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
    state: GroupDetailUiState,
    actionState: GroupDetailActionState,
    currentUserId: String?,
    onRetry: () -> Unit,
    onRotateInvite: () -> Unit,
    onLeaveGroup: () -> Unit,
    onRemoveMember: (String) -> Unit,
    onDismissRotatedInvite: () -> Unit,
    onRotateDialogOpened: () -> Unit,
    onLeaveDialogOpened: () -> Unit,
    onRemoveDialogOpened: () -> Unit,
    onLoadMoreWatchlist: () -> Unit,
    onProposeTitle: (String) -> Unit,
    onRemoveWatchlistEntry: (String) -> Unit,
    onProposeDialogOpened: () -> Unit,
    onRemoveEntryDialogOpened: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dialogState = rememberGroupDetailDialogState(state = state, actionState = actionState)
    var showRotateDialog by dialogState.showRotateDialog
    var showLeaveDialog by dialogState.showLeaveDialog
    var pendingRemoveTarget by dialogState.pendingRemoveTarget
    var removeAttempted by dialogState.removeAttempted
    var showProposeDialog by dialogState.showProposeDialog
    var proposeAttempted by dialogState.proposeAttempted
    var pendingRemoveEntryTarget by dialogState.pendingRemoveEntryTarget
    var removeEntryAttempted by dialogState.removeEntryAttempted

    GroupDetailBody(
        state = state,
        currentUserId = currentUserId,
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
        onDismissRotatedInvite = onDismissRotatedInvite,
        onLoadMoreWatchlist = onLoadMoreWatchlist,
        onProposeClick = {
            onProposeDialogOpened()
            showProposeDialog = true
            proposeAttempted = false
        },
        onRemoveEntryClick = { entry ->
            onRemoveEntryDialogOpened()
            pendingRemoveEntryTarget = entry
            removeEntryAttempted = false
        },
        modifier = modifier,
    )

    GroupDetailScreenDialogs(
        dialogState = dialogState,
        actionState = actionState,
        onRotateInvite = onRotateInvite,
        onLeaveGroup = onLeaveGroup,
        onRemoveMember = onRemoveMember,
        onProposeTitle = onProposeTitle,
        onRemoveWatchlistEntry = onRemoveWatchlistEntry,
    )
}

/**
 * [GroupDetailScreen]'s own `remember`ed dialog-visibility state, plus the [DialogCloseEffects]
 * wiring over it — pulled out purely to keep that function's own length under detekt's `LongMethod`
 * threshold; no behaviour moved with it that a caller could observe differently. [GroupDetailScreen]'s
 * own KDoc documents WHY each field exists and how it is used.
 *
 * `@Suppress("LongParameterList")`: a private, internal state carrier for one screen's five
 * dialogs — `FakeGroupRepository`'s own suppression carries the identical "this is what the shape
 * genuinely needs" reasoning, not a bag of unrelated fields that should have been split.
 */
@Suppress("LongParameterList")
private class GroupDetailDialogState(
    val showRotateDialog: MutableState<Boolean>,
    val showLeaveDialog: MutableState<Boolean>,
    val pendingRemoveTarget: MutableState<GroupMember?>,
    val removeAttempted: MutableState<Boolean>,
    val showProposeDialog: MutableState<Boolean>,
    val proposeAttempted: MutableState<Boolean>,
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
    val showProposeDialog = remember { mutableStateOf(false) }
    val proposeAttempted = remember { mutableStateOf(false) }
    val pendingRemoveEntryTarget = remember { mutableStateOf<WatchlistEntry?>(null) }
    val removeEntryAttempted = remember { mutableStateOf(false) }

    DialogCloseEffects(
        rotatedInvite = (state as? GroupDetailUiState.Success)?.rotatedInvite,
        actionState = actionState,
        removeAttempted = removeAttempted.value,
        proposeAttempted = proposeAttempted.value,
        removeEntryAttempted = removeEntryAttempted.value,
        onRotateDialogShouldClose = { showRotateDialog.value = false },
        onRemoveDialogShouldClose = {
            pendingRemoveTarget.value = null
            removeAttempted.value = false
        },
        onProposeDialogShouldClose = {
            showProposeDialog.value = false
            proposeAttempted.value = false
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
        showProposeDialog = showProposeDialog,
        proposeAttempted = proposeAttempted,
        pendingRemoveEntryTarget = pendingRemoveEntryTarget,
        removeEntryAttempted = removeEntryAttempted,
    )
}

/**
 * The five confirm/dismiss callbacks [GroupDetailActionDialogs] needs, built from [dialogState] and
 * the raw mutation calls — pulled out of [GroupDetailScreen] for the identical `LongMethod` reason
 * [rememberGroupDetailDialogState] was. [onRemoveConfirm]/[onProposeConfirm]'s own inline comments
 * (below) are where the "only mark an attempt when nothing is already in flight" reasoning lives —
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
    onProposeTitle: (String) -> Unit,
    onRemoveWatchlistEntry: (String) -> Unit,
) {
    var showRotateDialog by dialogState.showRotateDialog
    var pendingRemoveTarget by dialogState.pendingRemoveTarget
    var removeAttempted by dialogState.removeAttempted
    var showProposeDialog by dialogState.showProposeDialog
    var proposeAttempted by dialogState.proposeAttempted
    var pendingRemoveEntryTarget by dialogState.pendingRemoveEntryTarget
    var removeEntryAttempted by dialogState.removeEntryAttempted

    GroupDetailActionDialogs(
        showRotateDialog = showRotateDialog,
        showLeaveDialog = dialogState.showLeaveDialog.value,
        pendingRemoveTarget = pendingRemoveTarget,
        showProposeDialog = showProposeDialog,
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
        onProposeConfirm = { mediaId ->
            // [onRemoveConfirm]'s own note above, applied identically here.
            if (!actionState.proposing) {
                proposeAttempted = true
            }
            onProposeTitle(mediaId)
        },
        onProposeDismiss = { showProposeDialog = false },
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
 * [proposeAttempted]/[removeEntryAttempted] (task 9c.3) are [removeAttempted]'s IDENTICAL shape,
 * applied to the two new dialogs: propose has no natural "just succeeded" signal to key on the way
 * rotate's [rotatedInvite] does (there is no per-success unique VALUE this task exposes — the
 * watchlist LIST changing is not specific enough, [GroupDetailViewModel.reloadWatchlist]'s own KDoc
 * on why keying a close effect on list CONTENT would be wrong, the identical reasoning
 * [removeAttempted] itself already carries for [GroupDetailActionState.removingUserId]), so both
 * new dialogs use the attempted-flag fix rather than rotate's simpler shape.
 */
@Suppress("LongParameterList")
@Composable
private fun DialogCloseEffects(
    rotatedInvite: GroupWithInvite?,
    actionState: GroupDetailActionState,
    removeAttempted: Boolean,
    proposeAttempted: Boolean,
    removeEntryAttempted: Boolean,
    onRotateDialogShouldClose: () -> Unit,
    onRemoveDialogShouldClose: () -> Unit,
    onProposeDialogShouldClose: () -> Unit,
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
    LaunchedEffect(actionState.proposing, actionState.proposeError) {
        if (proposeAttempted && !actionState.proposing && actionState.proposeError == null) {
            onProposeDialogShouldClose()
        }
    }
    LaunchedEffect(actionState.removingEntryId, actionState.removeEntryError) {
        if (removeEntryAttempted && actionState.removingEntryId == null && actionState.removeEntryError == null) {
            onRemoveEntryDialogShouldClose()
        }
    }
}

/**
 * The five owner/member/watchlist confirmation dialogs, bundled — pulled out of [GroupDetailScreen]
 * for the identical [DialogCloseEffects] reason above.
 */
@Suppress("LongParameterList")
@Composable
private fun GroupDetailActionDialogs(
    showRotateDialog: Boolean,
    showLeaveDialog: Boolean,
    pendingRemoveTarget: GroupMember?,
    showProposeDialog: Boolean,
    pendingRemoveEntryTarget: WatchlistEntry?,
    actionState: GroupDetailActionState,
    onRotateConfirm: () -> Unit,
    onRotateDismiss: () -> Unit,
    onLeaveConfirm: () -> Unit,
    onLeaveDismiss: () -> Unit,
    onRemoveConfirm: (String) -> Unit,
    onRemoveDismiss: () -> Unit,
    onProposeConfirm: (String) -> Unit,
    onProposeDismiss: () -> Unit,
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
    ProposeDialogHost(
        visible = showProposeDialog,
        actionState = actionState,
        onPropose = onProposeConfirm,
        onDismiss = onProposeDismiss,
    )
    RemoveWatchlistEntryDialogHost(
        target = pendingRemoveEntryTarget,
        actionState = actionState,
        onConfirm = onRemoveEntryConfirm,
        onDismiss = onRemoveEntryDismiss,
    )
}

/**
 * The `Column` [GroupDetailScreen] renders — pulled out purely to keep that function's own length
 * under detekt's `LongMethod` threshold; no behaviour moved with it that a caller could observe
 * differently. "Leave group" lives HERE, a sibling of [GroupDetailContent] rather than nested
 * inside its `Success`-only branch — [GroupDetailScreen]'s own KDoc explains why.
 */
@Suppress("LongParameterList")
@Composable
private fun GroupDetailBody(
    state: GroupDetailUiState,
    currentUserId: String?,
    onRetry: () -> Unit,
    onRotateClick: () -> Unit,
    onLeaveClick: () -> Unit,
    onRemoveClick: (GroupMember) -> Unit,
    onDismissRotatedInvite: () -> Unit,
    onLoadMoreWatchlist: () -> Unit,
    onProposeClick: () -> Unit,
    onRemoveEntryClick: (WatchlistEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.groups_detail_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        GroupDetailContent(
            state = state,
            currentUserId = currentUserId,
            onRetry = onRetry,
            onRotateClick = onRotateClick,
            onRemoveClick = onRemoveClick,
            onDismissRotatedInvite = onDismissRotatedInvite,
            onLoadMoreWatchlist = onLoadMoreWatchlist,
            onProposeClick = onProposeClick,
            onRemoveEntryClick = onRemoveEntryClick,
            modifier = Modifier.weight(weight = 1f).fillMaxWidth(),
        )
        TextButton(onClick = onLeaveClick, modifier = Modifier.padding(all = 16.dp)) {
            Text(text = stringResource(R.string.groups_detail_leave_action))
        }
    }
}

/**
 * The body below the title — pulled out of the stateless [GroupDetailScreen] overload purely to
 * keep that function's own length under detekt's `LongMethod` threshold, `GroupsScreen.kt`'s
 * `GroupsContent` precedent. "Leave group" is NOT here — [GroupDetailScreen]'s own KDoc explains
 * why it renders outside this state-dependent body entirely.
 */
@Suppress("LongParameterList")
@Composable
private fun GroupDetailContent(
    state: GroupDetailUiState,
    currentUserId: String?,
    onRetry: () -> Unit,
    onRotateClick: () -> Unit,
    onRemoveClick: (GroupMember) -> Unit,
    onDismissRotatedInvite: () -> Unit,
    onLoadMoreWatchlist: () -> Unit,
    onProposeClick: () -> Unit,
    onRemoveEntryClick: (WatchlistEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        when (state) {
            is GroupDetailUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
            is GroupDetailUiState.Error ->
                ErrorState(
                    message = stringResource(state.cause.messageRes()),
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            is GroupDetailUiState.Success ->
                GroupDetailSuccessContent(
                    state = state,
                    currentUserId = currentUserId,
                    onRetry = onRetry,
                    onRotateClick = onRotateClick,
                    onRemoveClick = onRemoveClick,
                    onDismissRotatedInvite = onDismissRotatedInvite,
                    onLoadMoreWatchlist = onLoadMoreWatchlist,
                    onProposeClick = onProposeClick,
                    onRemoveEntryClick = onRemoveEntryClick,
                )
        }
    }
}

/**
 * [GroupDetailUiState.Success]'s own rendering (minus "Leave group", which
 * [GroupDetailScreen] now renders itself, unconditionally — see that function's own KDoc).
 * Owner-ness (E-F) is derived HERE, every recomposition, from
 * [GroupDetailUiState.Success.members]/[currentUserId] — never cached, never a field on
 * [GroupDetailUiState] itself (round 1 review moved [currentUserId] to its own ViewModel field;
 * [GroupDetailUiState.Success]'s own KDoc has the full reasoning) — E-F's own stated mitigation
 * made literal: [self] below is looked up fresh from the LIVE member list this render is showing,
 * so a stale cached role can never diverge from what the rest of this composable already displays.
 *
 * [self] can be `null` for two reasons now, not one: [currentUserId] itself can still be `null`
 * (identity has not resolved yet, or its own background fetch failed —
 * `GroupDetailViewModel.currentUserId`'s own KDoc), or the signed-in member can be briefly missing
 * from their OWN member list (the moment between an owner removing themselves elsewhere — another
 * device, the API directly — and this screen's next reload). Both read `isOwner` as `false`, which
 * hides rotate/remove rather than crashing — E-F's own "hidden, not disabled" applied to an
 * UNKNOWN role, not only a known non-owner one.
 *
 * **Task 9c.3 adds the shared watchlist below the member list, in the SAME `LazyColumn`** as
 * [membersItems] (`GroupMembersSection.kt`) and [watchlistItems] (`GroupWatchlistSection.kt`) — one
 * scrollable region for the whole screen, rather than members and watchlist each owning a separate
 * one: whichever section has more rows simply scrolls further, the same as any ordinary list screen.
 * `GroupWatchlistSection.kt`'s own KDoc has the fuller reasoning, including a Robolectric testing
 * characteristic this choice does NOT by itself fix — `GroupDetailScreenTest` scrolls explicitly for
 * rows a `LazyColumn` does not reach on its first layout pass.
 *
 * [EndOfListTrigger]'s [itemCount] is members + the watchlist header + watchlist rows (or one, for
 * the empty-state row [watchlistItems] emits when there are none) — an approximation of the ACTUAL
 * emitted item count, not a index-exact one, which is fine: [EndOfListTrigger]'s own `threshold`
 * already tolerates being a few items off, and firing [onLoadMoreWatchlist] a little early or late
 * near the bottom of a combined list is harmless — `GroupDetailViewModel.loadMoreWatchlist`'s own
 * re-entrancy and exhaustion guards are what make an extra call actually safe, not this count being
 * exact.
 */
@Suppress("LongParameterList")
@Composable
private fun GroupDetailSuccessContent(
    state: GroupDetailUiState.Success,
    currentUserId: String?,
    onRetry: () -> Unit,
    onRotateClick: () -> Unit,
    onRemoveClick: (GroupMember) -> Unit,
    onDismissRotatedInvite: () -> Unit,
    onLoadMoreWatchlist: () -> Unit,
    onProposeClick: () -> Unit,
    onRemoveEntryClick: (WatchlistEntry) -> Unit,
) {
    val self = state.members.firstOrNull { it.userId == currentUserId }
    val isOwner = self?.role == GroupRole.OWNER

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.isStale) {
            StaleDataBanner(onRetry = onRetry, messageRes = R.string.groups_detail_stale_notice)
        }
        state.rotatedInvite?.let { invite ->
            InviteCodeCard(
                invite = invite,
                title = stringResource(R.string.groups_detail_rotated_title),
                onDismiss = onDismissRotatedInvite,
            )
        }
        if (isOwner) {
            TextButton(onClick = onRotateClick, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(text = stringResource(R.string.groups_detail_rotate_action))
            }
        }
        GroupDetailList(
            state = state,
            currentUserId = currentUserId,
            isOwner = isOwner,
            onRemoveClick = onRemoveClick,
            onLoadMoreWatchlist = onLoadMoreWatchlist,
            onProposeClick = onProposeClick,
            onRemoveEntryClick = onRemoveEntryClick,
            modifier = Modifier.weight(weight = 1f).fillMaxWidth(),
        )
    }
}

/**
 * The ONE `LazyColumn` [GroupDetailSuccessContent] renders — pulled out purely to keep that
 * function's own length under detekt's `LongMethod` threshold; no behaviour moved with it that a
 * caller could observe differently. [GroupDetailSuccessContent]'s own KDoc has the full reasoning
 * for why members and watchlist rows now share this one scrollable region.
 */
@Suppress("LongParameterList")
@Composable
private fun GroupDetailList(
    state: GroupDetailUiState.Success,
    currentUserId: String?,
    isOwner: Boolean,
    onRemoveClick: (GroupMember) -> Unit,
    onLoadMoreWatchlist: () -> Unit,
    onProposeClick: () -> Unit,
    onRemoveEntryClick: (WatchlistEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val itemCount = state.members.size + 1 + state.watchlist.size.coerceAtLeast(minimumValue = 1)
    EndOfListTrigger(listState = listState, itemCount = itemCount, onTriggered = onLoadMoreWatchlist)

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(all = 12.dp),
        verticalArrangement = Arrangement.spacedBy(space = 8.dp),
    ) {
        membersItems(
            members = state.members,
            currentUserId = currentUserId,
            isOwner = isOwner,
            onRemoveClick = onRemoveClick,
        )
        item(key = "watchlist-header") {
            WatchlistHeader(onProposeClick = onProposeClick, modifier = Modifier.fillMaxWidth())
        }
        watchlistItems(
            entries = state.watchlist,
            members = state.members,
            loadingMore = state.watchlistLoadingMore,
            pageError = state.watchlistPageError != null,
            onLoadMore = onLoadMoreWatchlist,
            onRemoveClick = onRemoveEntryClick,
        )
    }
}
