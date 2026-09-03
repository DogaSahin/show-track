package com.anarky.showtrack.feature.groups

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.model.Group

/**
 * The stateful entry point. `hiltViewModel()` is the only line here that touches DI —
 * `FavoritesScreen`/`LibraryScreen`'s shape.
 *
 * [onGroupClick] hands the whole [Group] to the caller rather than a raw id — `GroupsNavigation.kt`
 * is the module boundary that turns it into `GroupDetailRoute(groupId = group.id)`, the same split
 * `FavoritesNavigation.kt` draws for `entry.media.id`.
 *
 * [LifecycleResumeEffect] is the ENTIRE mechanism by which [GroupsViewModel] ever loads — no
 * `init` (see that class's own KDoc) — and the only place a resume after Detail -> Back re-fetches
 * the list and, structurally, clears any `justCreated` invite code left over from a create/join
 * that happened before this trip (`GroupsViewModel.refresh`'s own KDoc). `FavoritesScreen`'s
 * identical effect documents the same round-trip mechanism.
 *
 * Collects [GroupsViewModel.state] AND [GroupsViewModel.actionState] separately (fix round 1) —
 * see [GroupsActionState]'s own KDoc for why they are two independent flows rather than one.
 */
@Composable
fun GroupsScreen(
    onGroupClick: (Group) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupsViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actionState by viewModel.actionState.collectAsStateWithLifecycle()
    GroupsScreen(
        state = state,
        actionState = actionState,
        onRetry = viewModel::refresh,
        onCreateGroup = viewModel::createGroup,
        onJoinGroup = viewModel::joinGroup,
        onDismissInvite = viewModel::dismissJustCreated,
        onGroupClick = onGroupClick,
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be previewed and driven by a test with no ViewModel and
 * no Hilt — `FavoritesScreen`/`ImportScreen`'s pattern.
 *
 * [showCreateDialog]/[showJoinDialog] are this composable's OWN `remember`ed state, not part of
 * [GroupsUiState] or [GroupsActionState] — dialog visibility is a rendering decision, not a fact
 * about what the ViewModel knows (mirroring `ImportForm`'s `username`, which lives in
 * `ImportScreen`, not `ImportUiState`). Each dialog owns the text field it collects (name for
 * create, code for join) the identical way, which is what makes "a failed join keeps the typed
 * code" true with no code in either the ViewModel or this function: a failure only ever changes
 * [GroupsActionState.joinError], and nothing here resets the dialog's own `remember`ed draft in
 * response to it — the dialog only closes on [onDismiss] or on the [LaunchedEffect] below, neither
 * of which a failure triggers.
 *
 * [actionState] (fix round 1) drives BOTH dialogs regardless of [state] — `submitting`/`error` for
 * `CreateGroupDialog`/`JoinGroupDialog` no longer come from a `state as? GroupsUiState.Success`
 * cast, which is what makes both forms usable from [GroupsUiState.Loading]/[GroupsUiState.Error]
 * as well as [GroupsUiState.Success] (see [GroupsActionState]'s own KDoc for the bug this fixes).
 *
 * The [LaunchedEffect] closes whichever dialog is open the moment [GroupsUiState.Success.justCreated]
 * goes non-null — the create/join actually landed, so the form dialog's job is done and the invite
 * banner below takes over. Keyed on the invite itself (not just "is it non-null") so a second
 * create/join later in the same session — a different [GroupWithInvite] — re-fires this even if,
 * somehow, a dialog were still open when it landed.
 */
@Suppress("LongParameterList")
@Composable
internal fun GroupsScreen(
    state: GroupsUiState,
    actionState: GroupsActionState,
    onRetry: () -> Unit,
    onCreateGroup: (String) -> Unit,
    onJoinGroup: (String) -> Unit,
    onDismissInvite: () -> Unit,
    onGroupClick: (Group) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }

    val justCreated = (state as? GroupsUiState.Success)?.justCreated
    LaunchedEffect(justCreated) {
        if (justCreated != null) {
            showCreateDialog = false
            showJoinDialog = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        GroupsTopBar(onCreateClick = { showCreateDialog = true }, onJoinClick = { showJoinDialog = true })
        GroupsContent(
            state = state,
            onRetry = onRetry,
            onDismissInvite = onDismissInvite,
            onGroupClick = onGroupClick,
            modifier = Modifier.weight(weight = 1f).fillMaxWidth(),
        )
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            submitting = actionState.creating,
            error = actionState.createError,
            onCreate = onCreateGroup,
            onDismiss = { showCreateDialog = false },
        )
    }
    if (showJoinDialog) {
        JoinGroupDialog(
            submitting = actionState.joining,
            error = actionState.joinError,
            onJoin = onJoinGroup,
            onDismiss = { showJoinDialog = false },
        )
    }
}

/**
 * The body below [GroupsTopBar] — pulled out of the stateless [GroupsScreen] overload purely to
 * keep that function's own length under detekt's `LongMethod` threshold; no behaviour moved with
 * it that the caller could observe differently.
 */
@Composable
private fun GroupsContent(
    state: GroupsUiState,
    onRetry: () -> Unit,
    onDismissInvite: () -> Unit,
    onGroupClick: (Group) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        when (state) {
            is GroupsUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
            is GroupsUiState.Error ->
                ErrorState(
                    message = stringResource(state.cause.messageRes()),
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            is GroupsUiState.Success ->
                GroupsSuccessContent(
                    state = state,
                    onRetry = onRetry,
                    onDismissInvite = onDismissInvite,
                    onGroupClick = onGroupClick,
                )
        }
    }
}

/**
 * [GroupsUiState.Success]'s own rendering — isStale (decision C-B made real, `FavoritesScreen`'s
 * identical shape): the banner sits ABOVE the content rather than replacing it.
 *
 * `messageRes = R.string.groups_stale_notice` (fix round 1): `StaleDataBanner`'s own default copy
 * ("Showing saved titles…") names the wrong noun for a list of groups — see
 * [GroupsUiState.Success]'s own KDoc for why `isStale` is still meaningful here despite groups
 * having no Room cache, and `StaleDataBanner`'s own KDoc for why the fix was a parameter on the
 * shared component rather than a fork of it.
 */
@Composable
private fun GroupsSuccessContent(
    state: GroupsUiState.Success,
    onRetry: () -> Unit,
    onDismissInvite: () -> Unit,
    onGroupClick: (Group) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.isStale) {
            StaleDataBanner(onRetry = onRetry, messageRes = R.string.groups_stale_notice)
        }
        state.justCreated?.let { invite ->
            InviteCodeCard(invite = invite, onDismiss = onDismissInvite)
        }
        Box(modifier = Modifier.weight(weight = 1f).fillMaxWidth()) {
            if (state.groups.isEmpty()) {
                EmptyState(
                    message = stringResource(R.string.groups_empty_message),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                GroupsList(groups = state.groups, onGroupClick = onGroupClick)
            }
        }
    }
}

@Composable
private fun GroupsTopBar(
    onCreateClick: () -> Unit,
    onJoinClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.groups_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(weight = 1f),
        )
        TextButton(onClick = onJoinClick) {
            Text(text = stringResource(R.string.groups_join_action))
        }
        TextButton(onClick = onCreateClick) {
            Text(text = stringResource(R.string.groups_create_action))
        }
    }
}

/**
 * [GroupWithInvite.expiresAt] is deliberately not rendered — this task's scope is "the code is
 * shown once", not a countdown UI; a member who needs the code again rotates it.
 *
 * **Fix round 1 — copy-to-clipboard.** E-I shows the code exactly once and clears it on the very
 * next [GroupsViewModel.refresh] (a resume, a tab switch, navigating to the new group and back).
 * With no way to copy it, "once" effectively meant "never usable" for the actual job this card
 * exists for: the code has to leave this screen — into a chat app, a text message — to reach
 * whoever the group owner is inviting, and switching away to paste it is itself the kind of resume
 * that clears it. [copied] is local, `remember`ed against [invite]'s own code so a SECOND
 * create/join later in the session (a different code) starts the button fresh rather than still
 * reading "Copied" from the last one.
 */
@Composable
private fun InviteCodeCard(
    invite: GroupWithInvite,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var copied by remember(invite.inviteCode) { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    Card(modifier = modifier.fillMaxWidth().padding(all = 12.dp)) {
        Column(
            modifier = Modifier.padding(all = 16.dp),
            verticalArrangement = Arrangement.spacedBy(space = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.groups_invite_title, invite.group.name),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.groups_invite_code_label, invite.inviteCode),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(space = 8.dp)) {
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(invite.inviteCode))
                        copied = true
                    },
                ) {
                    Text(
                        text =
                            stringResource(
                                if (copied) R.string.groups_invite_copied else R.string.groups_invite_copy,
                            ),
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.groups_invite_dismiss))
                }
            }
        }
    }
}

@Composable
private fun GroupsList(
    groups: List<Group>,
    onGroupClick: (Group) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(all = 12.dp),
        verticalArrangement = Arrangement.spacedBy(space = 8.dp),
    ) {
        // Keyed by group id: without a key, LazyColumn identifies items by index and a refresh
        // that reorders the list re-uses the wrong composable state for the wrong row.
        items(items = groups, key = Group::id) { group ->
            GroupRow(group = group, onClick = { onGroupClick(group) })
        }
    }
}

@Composable
private fun GroupRow(
    group: Group,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            text = group.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(all = 16.dp),
        )
    }
}

// CreateGroupDialog, JoinGroupDialog and the GroupFailure -> string mapping they share live in
// GroupsDialogs.kt — split out purely to keep this file under detekt's TooManyFunctions threshold.
