package com.anarky.showtrack.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.GroupRole

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
    GroupDetailScreen(
        state = state,
        actionState = actionState,
        onRetry = viewModel::refresh,
        onRotateInvite = viewModel::rotateInvite,
        onLeaveGroup = viewModel::leaveGroup,
        onRemoveMember = viewModel::removeMember,
        onDismissRotatedInvite = viewModel::dismissRotatedInvite,
        onRotateDialogOpened = viewModel::clearRotateError,
        onLeaveDialogOpened = viewModel::clearLeaveError,
        onRemoveDialogOpened = viewModel::clearRemoveError,
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
 *   [GroupDetailActionState.removeError] still `null` — the SUCCESS signature, since the failure
 *   branch sets both fields together (`GroupDetailViewModel.removeMember`'s own `copy(removingUserId
 *   = null, removeError = failure.failure)`). Keyed on the ACTION's own flags, not on the member
 *   list's content, on purpose: `GroupDetailViewModel.reloadMembers`'s own KDoc notes a reload can
 *   fail right after a successful delete (marking the screen [GroupDetailUiState.Success.isStale]
 *   rather than dropping the row), and this dialog must still close in that case — the removal
 *   itself DID succeed, and waiting for a row to visibly disappear that a failed reload will never
 *   show would leave the confirm dialog stuck open over a success.
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
    onRetry: () -> Unit,
    onRotateInvite: () -> Unit,
    onLeaveGroup: () -> Unit,
    onRemoveMember: (String) -> Unit,
    onDismissRotatedInvite: () -> Unit,
    onRotateDialogOpened: () -> Unit,
    onLeaveDialogOpened: () -> Unit,
    onRemoveDialogOpened: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRotateDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var pendingRemoveTarget by remember { mutableStateOf<GroupMember?>(null) }

    val rotatedInvite = (state as? GroupDetailUiState.Success)?.rotatedInvite
    LaunchedEffect(rotatedInvite) {
        if (rotatedInvite != null) showRotateDialog = false
    }
    LaunchedEffect(actionState.removingUserId, actionState.removeError) {
        if (pendingRemoveTarget != null && actionState.removingUserId == null && actionState.removeError == null) {
            pendingRemoveTarget = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.groups_detail_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        GroupDetailContent(
            state = state,
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
            },
            onDismissRotatedInvite = onDismissRotatedInvite,
            modifier = Modifier.weight(weight = 1f).fillMaxWidth(),
        )
    }

    RotateDialogHost(
        visible = showRotateDialog,
        actionState = actionState,
        onConfirm = onRotateInvite,
        onDismiss = { showRotateDialog = false },
    )
    LeaveDialogHost(
        visible = showLeaveDialog,
        actionState = actionState,
        onConfirm = onLeaveGroup,
        onDismiss = { showLeaveDialog = false },
    )
    RemoveDialogHost(
        target = pendingRemoveTarget,
        actionState = actionState,
        onConfirm = onRemoveMember,
        onDismiss = { pendingRemoveTarget = null },
    )
}

/**
 * [RotateInviteDialog]'s own visibility guard — pulled out to keep [GroupDetailScreen] under
 * detekt's `LongMethod` threshold.
 */
@Composable
private fun RotateDialogHost(
    visible: Boolean,
    actionState: GroupDetailActionState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        RotateInviteDialog(
            submitting = actionState.rotating,
            error = actionState.rotateError,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/** [LeaveGroupDialog]'s own visibility guard — [RotateDialogHost]'s identical reasoning. */
@Composable
private fun LeaveDialogHost(
    visible: Boolean,
    actionState: GroupDetailActionState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        LeaveGroupDialog(
            submitting = actionState.leaving,
            error = actionState.leaveError,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/**
 * [RemoveMemberDialog]'s own visibility guard — [RotateDialogHost]'s identical reasoning, except
 * visibility is carried by [target] itself (non-null means "showing"), matching
 * [GroupDetailScreen]'s own `pendingRemoveTarget?.let { }` this replaces.
 */
@Composable
private fun RemoveDialogHost(
    target: GroupMember?,
    actionState: GroupDetailActionState,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (target != null) {
        RemoveMemberDialog(
            username = target.username,
            submitting = actionState.removingUserId == target.userId,
            error = actionState.removeError,
            onConfirm = { onConfirm(target.userId) },
            onDismiss = onDismiss,
        )
    }
}

/**
 * The body below the title — pulled out of the stateless [GroupDetailScreen] overload purely to
 * keep that function's own length under detekt's `LongMethod` threshold, `GroupsScreen.kt`'s
 * `GroupsContent` precedent.
 */
@Suppress("LongParameterList")
@Composable
private fun GroupDetailContent(
    state: GroupDetailUiState,
    onRetry: () -> Unit,
    onRotateClick: () -> Unit,
    onLeaveClick: () -> Unit,
    onRemoveClick: (GroupMember) -> Unit,
    onDismissRotatedInvite: () -> Unit,
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
                    onRetry = onRetry,
                    onRotateClick = onRotateClick,
                    onLeaveClick = onLeaveClick,
                    onRemoveClick = onRemoveClick,
                    onDismissRotatedInvite = onDismissRotatedInvite,
                )
        }
    }
}

/**
 * [GroupDetailUiState.Success]'s own rendering. Owner-ness (E-F) is derived HERE, every
 * recomposition, from [GroupDetailUiState.Success.members]/[GroupDetailUiState.Success.currentUserId]
 * — never cached, never a field on [GroupDetailUiState] itself — E-F's own stated mitigation made
 * literal: [self] below is looked up fresh from the LIVE member list this render is showing, so a
 * stale cached role can never diverge from what the rest of this composable already displays.
 *
 * [self] can be `null` — the signed-in member briefly missing from their OWN member list — only in
 * the moment between an owner removing themselves elsewhere (another device, the API directly) and
 * this screen's next reload; `isOwner` reads `false` for that case, which hides rotate/remove rather
 * than crashing, and "Leave group" stays offered regardless (leaving a group you already left is a
 * harmless, idempotent-in-effect repeat of the same DELETE call).
 */
@Suppress("LongParameterList")
@Composable
private fun GroupDetailSuccessContent(
    state: GroupDetailUiState.Success,
    onRetry: () -> Unit,
    onRotateClick: () -> Unit,
    onLeaveClick: () -> Unit,
    onRemoveClick: (GroupMember) -> Unit,
    onDismissRotatedInvite: () -> Unit,
) {
    val self = state.members.firstOrNull { it.userId == state.currentUserId }
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
        MembersList(
            members = state.members,
            currentUserId = state.currentUserId,
            isOwner = isOwner,
            onRemoveClick = onRemoveClick,
            modifier = Modifier.weight(weight = 1f).fillMaxWidth(),
        )
        TextButton(onClick = onLeaveClick, modifier = Modifier.padding(all = 16.dp)) {
            Text(text = stringResource(R.string.groups_detail_leave_action))
        }
    }
}

@Composable
private fun MembersList(
    members: List<GroupMember>,
    currentUserId: String,
    isOwner: Boolean,
    onRemoveClick: (GroupMember) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(all = 12.dp),
        verticalArrangement = Arrangement.spacedBy(space = 8.dp),
    ) {
        // Keyed by userId, GroupsList's identical reasoning: without a key a reorder from a
        // refresh re-uses the wrong composable state for the wrong row.
        items(items = members, key = GroupMember::userId) { member ->
            MemberRow(
                member = member,
                // Owner-only (E-F), and NEVER for the viewer's own row — design doc §1.1: removing
                // yourself is "Leave group", not this control reused with your own id.
                showRemove = isOwner && member.userId != currentUserId,
                onRemoveClick = { onRemoveClick(member) },
            )
        }
    }
}

@Composable
private fun MemberRow(
    member: GroupMember,
    showRemove: Boolean,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(all = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = member.username, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(member.role.labelRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showRemove) {
                TextButton(onClick = onRemoveClick) {
                    Text(text = stringResource(R.string.groups_detail_remove_action))
                }
            }
        }
    }
}
