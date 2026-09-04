package com.anarky.showtrack.feature.groups

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.GroupSwitcher
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.Group
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
 *
 * [activeGroup] arrives as a `StateFlow<ActiveGroupState>` (task 9c.5) and is collected here,
 * inside this composable's own body — `FeedScreen`'s identical reasoning (`FeedNavigation.kt`'s own
 * KDoc): `groupsEntry`'s registration runs far less often than the active group can change. Only
 * [ActiveGroupState.Success.activeGroupId] is used here — [ActiveGroupState.Loading]/[ActiveGroupState.Error]
 * both resolve to `null`, so no switcher renders.
 *
 * **What that actually looks like for the person on this screen (fix round 2 — an earlier version
 * of this paragraph described the CODE, not what is on screen).** [GroupsViewModel.state] is
 * independent of [activeGroup] and loads on its own resume-driven schedule, so it is entirely
 * possible for [GroupsViewModel.state] to already be a [GroupsUiState.Success] with three groups
 * fully listed WHILE [activeGroup] is still [ActiveGroupState.Loading] or has landed on
 * [ActiveGroupState.Error] — the two fetches race, and nothing here waits for one on the other. In
 * that window, the person sees the full group list and no switcher: no indication of which group is
 * currently active, and no way to find out from this screen at all. This is accepted, not
 * overlooked — it self-heals: [ActiveGroupViewModel.refresh] already fires on arrival at Groups
 * (`ShowTrackApp`'s own `LaunchedEffect`), and Feed offers `activeGroup`'s own [ActiveGroupState.Error]
 * a real retry ([FeedScreen]'s own `onRetryGroups`) if the fetch is genuinely stuck rather than
 * merely still in flight — but it is a real, user-visible gap for as long as the race lasts, not
 * merely an implementation detail.
 */
@Composable
fun GroupsScreen(
    activeGroup: StateFlow<ActiveGroupState>,
    onSwitchGroup: (String) -> Unit,
    onGroupClick: (Group) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupsViewModel = hiltViewModel(),
) {
    val currentActiveGroup by activeGroup.collectAsStateWithLifecycle()
    val currentActiveGroupId = (currentActiveGroup as? ActiveGroupState.Success)?.activeGroupId
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actionState by viewModel.actionState.collectAsStateWithLifecycle()
    GroupsScreen(
        activeGroupId = currentActiveGroupId,
        onSwitchGroup = onSwitchGroup,
        state = state,
        actionState = actionState,
        onRetry = viewModel::refresh,
        onCreateGroup = viewModel::createGroup,
        onJoinGroup = viewModel::joinGroup,
        onDismissInvite = viewModel::dismissJustCreated,
        onCreateDialogOpened = viewModel::clearCreateError,
        onJoinDialogOpened = viewModel::clearJoinError,
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
 * The [LaunchedEffect] closes whichever dialog is open once the create/join actually landed, so
 * the form dialog's job is done and the invite banner below takes over.
 *
 * **Fix round 3 (review finding) — why this is keyed on [GroupsActionState.creating]/
 * [GroupsActionState.joining] TOO, not on [GroupsUiState.Success.justCreated] alone.** An earlier
 * version keyed ONLY on `justCreated`, defended by a comment claiming a second create/join later
 * in the session is "a DIFFERENT [GroupWithInvite]" and would therefore re-fire this — false
 * whenever the SAME code is redeemed twice for a group whose invite has not been rotated:
 * `GroupRepository.joinGroup` is deliberately idempotent (decision G-I) and returns an
 * `equals`-identical [GroupWithInvite] both times, so `MutableStateFlow`'s own conflation never
 * emits a "new" value for it, [state] never visibly changes, and a `LaunchedEffect` keyed only on
 * `justCreated` never re-runs — the join dialog stayed open after a successful REPEAT join, with
 * no visible feedback (cosmetic: Cancel still dismisses it, but the comment defended precisely the
 * case it failed).
 *
 * [GroupsActionState.creating]/[joining] do not have that problem: `GroupsViewModel.createGroup`/
 * `joinGroup` write them `true` UNCONDITIONALLY at the start of every call — a real, distinct
 * boolean flip away from their `false` resting state regardless of whether the eventual RESULT is
 * value-identical to a previous one — so these keys reliably change on every call, including a
 * repeat one. Adding them as EXTRA keys (not replacing `justCreated`) keeps the original path
 * working unchanged for the ordinary case (`justCreated` going `null` -> non-null, a genuinely new
 * value) while also catching the repeat-identical one: on the falling edge (`creating`/`joining`
 * going back to `false`), [state] already reflects [GroupsUiState.Success.justCreated] non-null
 * (both writes happen synchronously, one `viewModelScope.launch` block, before either is observed),
 * so the `if (justCreated != null)` check below still finds it and closes the dialog even though
 * `justCreated`'s own value never changed enough to trigger a recomposition on its own.
 *
 * **Fix round 2, small item 3:** [onCreateDialogOpened]/[onJoinDialogOpened] fire alongside
 * `showCreateDialog`/`showJoinDialog` going `true` — before this, reopening a dialog after a
 * failed attempt showed THAT attempt's error before the user had done anything on this new open,
 * reading as though the fresh attempt had already failed. Wired to
 * `GroupsViewModel.clearCreateError`/`clearJoinError`, which clear only their own
 * [GroupsActionState] field — the SEPARATE channel discipline (decision C-S) applies here too.
 *
 * **[activeGroupId]/[onSwitchGroup] (task 9c.5)** carry no default (fix round 2, BLOCKING F2 —
 * they briefly did, `= null`/`= {}`, purely so pre-existing tests kept compiling, and a reviewer
 * measured the cost: dropping the real [onSwitchGroup] argument from the stateful overload's own
 * call above still compiled, and the switcher's tap silently did nothing). Every test call site in
 * `GroupsScreenTest` now passes both explicitly, most with `activeGroupId = null` — with that,
 * [GroupSwitcher] is never reached, same rendering outcome the old default produced, but no longer
 * because a missing argument is invisible. The row reads its group list from [state]'s own
 * [GroupsUiState.Success.groups] rather than a separate parameter: this screen already loads the
 * full list for its own content, and [GroupSwitcher]'s own `groups.size < 2` gate (E-K) means an
 * empty/loading/error [state] (no [GroupsUiState.Success] to read from) simply renders nothing
 * here, same as a genuinely single-group account would.
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
    onCreateDialogOpened: () -> Unit,
    onJoinDialogOpened: () -> Unit,
    onGroupClick: (Group) -> Unit,
    activeGroupId: String?,
    onSwitchGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }

    val justCreated = (state as? GroupsUiState.Success)?.justCreated
    LaunchedEffect(justCreated, actionState.creating, actionState.joining) {
        if (justCreated != null) {
            showCreateDialog = false
            showJoinDialog = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        GroupsTopBar(
            onCreateClick = {
                onCreateDialogOpened()
                showCreateDialog = true
            },
            onJoinClick = {
                onJoinDialogOpened()
                showJoinDialog = true
            },
        )
        if (activeGroupId != null) {
            val switcherGroups = (state as? GroupsUiState.Success)?.groups ?: emptyList()
            GroupSwitcher(groups = switcherGroups, activeGroupId = activeGroupId, onGroupSelected = onSwitchGroup)
        }
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
            InviteCodeCard(
                invite = invite,
                title = stringResource(R.string.groups_invite_title, invite.group.name),
                onDismiss = onDismissInvite,
            )
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
 *
 * **Fix round 2 — the copy is marked sensitive.** [LocalClipboard]/[ClipEntry], not
 * `LocalClipboardManager`/`AnnotatedString` (round 1's shape): decision E-I calls the invite code
 * a credential, and on API 33+ the system clipboard preview and IME clipboard history retain
 * whatever is copied unless the `ClipData` itself carries `ClipDescription.EXTRA_IS_SENSITIVE` —
 * a flag [ClipEntry]'s `ClipData`-based constructor can set and the older `AnnotatedString`-based
 * `LocalClipboardManager.setText` cannot. Copying itself is not what E-I forbids — the code exists
 * to be relayed off-device, and the system clipboard is not this app's own persistence — but
 * leaving it visible in a clipboard-history UI after that is the same exposure E-I already refuses
 * to leave in `ActiveGroupStore`/Room. `minSdk` is 29, so [sensitiveInviteCodeClipEntry] guards the
 * flag behind API 33 — the constant is safe to reference below that (it is a plain string key an
 * older platform simply never looks for), but setting it has no effect there either way.
 *
 * **Task 9c.2 — [title] became a parameter, and this became `internal`.** `GroupDetailScreen`
 * needs the identical "show a credential once, offer sensitive copy-to-clipboard, offer dismiss"
 * behaviour for a ROTATED code, where "You're in %1$s" (this card's original, hardcoded title) is
 * nonsensical — the reader is already a member. Decision C-T's own reasoning ("a shared
 * presentation belongs [in `:core:designsystem`] once a second screen with the same question is a
 * matter of when, not if") argued for `:core:designsystem` originally; this stays module-internal
 * instead, because the second caller is `GroupDetailScreen` in this SAME module, not another
 * feature — an ordinary `internal` Kotlin function already reaches it with no cross-module
 * dependency to justify moving it. [sensitiveInviteCodeClipEntry] stays `private`: only this
 * function calls it.
 */
@Composable
internal fun InviteCodeCard(
    invite: GroupWithInvite,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var copied by remember(invite.inviteCode) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.groups_invite_clip_label)
    Card(modifier = modifier.fillMaxWidth().padding(all = 12.dp)) {
        Column(
            modifier = Modifier.padding(all = 16.dp),
            verticalArrangement = Arrangement.spacedBy(space = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.groups_invite_code_label, invite.inviteCode),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(space = 8.dp)) {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            clipboard.setClipEntry(sensitiveInviteCodeClipEntry(clipLabel, invite.inviteCode))
                            copied = true
                        }
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

/**
 * Builds the `ClipEntry` [InviteCodeCard]'s copy button hands to [LocalClipboard] — pulled out to
 * a plain function so it is callable (and its `Build.VERSION.SDK_INT` branch testable in
 * isolation) without composing anything.
 */
private fun sensitiveInviteCodeClipEntry(
    label: String,
    code: String,
): ClipEntry {
    val clipData = ClipData.newPlainText(label, code)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clipData.description.extras =
            PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
    }
    return ClipEntry(clipData)
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
