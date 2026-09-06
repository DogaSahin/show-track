package com.anarky.showtrack.feature.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.EndOfListTrigger
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.GroupSwitcher
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.StaleDataBanner
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.FeedEntry
import com.anarky.showtrack.core.model.GroupFailure
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.GroupsRoute
import kotlinx.coroutines.flow.StateFlow

/**
 * The stateful entry point, and the fifth top-level tab (task 9c.4). `hiltViewModel()` is the
 * only line here that touches DI — `GroupsScreen`/`FavoritesScreen`'s shape.
 *
 * [activeGroup] is a PARAMETER, not read from [FeedViewModel] or any singleton — E-C's own
 * requirement ("features RECEIVE the active group, they never read a singleton for it") is
 * satisfied by construction: nothing in this module names `ActiveGroupStore` at all.
 *
 * [activeGroup] is a `StateFlow<ActiveGroupState>`, not a plain value (task 9c.5) — collected here,
 * inside this composable's own body, so this screen reacts to a switch (`GroupSwitcher`'s own
 * callback, ultimately `ActiveGroupViewModel.selectGroup`) even though `feedEntry`'s registration
 * itself runs far less often than that — see `FeedNavigation.kt`'s own KDoc for why a plain value
 * could not do this. [LifecycleResumeEffect] is keyed on the COLLECTED, resolved group id (only
 * meaningful for [ActiveGroupState.Success]), not the `StateFlow` reference itself (which never
 * changes): the effect re-fires the moment the resolved group changes, without this composable
 * needing to know why. Never fires [FeedViewModel.selectGroup] while [activeGroup] is
 * [ActiveGroupState.Loading]/[ActiveGroupState.Error]/a [ActiveGroupState.Success] with no active
 * group — none of those states has anything to select, `FeedViewModel`'s own KDoc.
 *
 * [onEntryClick] resolves a tap into a real navigation call — `entry.mediaId?.let { onNavigate(...) }`
 * building a `DetailRoute(mediaId = it)` — rather than the stateless overload doing it directly, so
 * [FeedScreenTest] can drive the internal overload with a bare `(FeedEntry) -> Unit` and assert on
 * the entry alone, while
 * [FeedEntryHiltTest] pins the REAL binding end to end (this task's own brief: "assert WHICH route
 * and id, not merely that navigation happened" — `GroupDetailEntryHiltTest`'s model). An entry with
 * no `mediaId` (an [ActivityKind.IMPORTED] row — E-H) is simply never clickable in the first place;
 * see [FeedEntryRow]'s own KDoc.
 *
 * [onRetryGroups] retries the GROUPS fetch (`ActiveGroupViewModel.refresh`); `onRetry` on the
 * stateless overload below retries the FEED fetch (`FeedViewModel.refresh`) — two different
 * operations behind two different failures (decision C-S, fix round 1 BLOCKING B3), never
 * conflated into one channel.
 */
@Suppress("LongParameterList")
@Composable
fun FeedScreen(
    activeGroup: StateFlow<ActiveGroupState>,
    onSwitchGroup: (String) -> Unit,
    onRetryGroups: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val currentActiveGroup by activeGroup.collectAsStateWithLifecycle()
    val currentGroupId = (currentActiveGroup as? ActiveGroupState.Success)?.activeGroupId
    LifecycleResumeEffect(viewModel, currentGroupId) {
        currentGroupId?.let { groupId -> viewModel.selectGroup(groupId) }
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    FeedScreen(
        activeGroupState = currentActiveGroup,
        state = state,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::refresh,
        onEntryClick = { entry -> entry.mediaId?.let { mediaId -> onNavigate(DetailRoute(mediaId = mediaId)) } },
        onSwitchGroup = onSwitchGroup,
        onRetryGroups = onRetryGroups,
        onCreateOrJoinGroup = { onNavigate(GroupsRoute) },
        modifier = modifier,
    )
}

/**
 * The stateless half, split out so it can be previewed and driven by a test with no ViewModel and
 * no Hilt — `GroupsScreen`/`FavoritesScreen`'s pattern.
 *
 * [activeGroupState] drives the top-level branch, read BEFORE [state] on purpose — a stale
 * [FeedUiState.Success] left over from a previously active group must never show under the wrong
 * branch here (`FeedViewModel` never blanks [state] back to [FeedUiState.Loading] just because the
 * active group changed away from a value, since [FeedViewModel.selectGroup] is never even called
 * for that case).
 *
 * Three [ActiveGroupState] cases, distinct (fix round 1, BLOCKING B3 — the shape this task shipped
 * with before this fix collapsed all three into "activeGroupId == null" and showed the same
 * create-or-join invitation for a load in progress, a load failure, AND a genuinely empty account):
 * - [ActiveGroupState.Loading] → a spinner, not an empty state — we do not know yet.
 * - [ActiveGroupState.Error] → an error with [onRetryGroups], not an empty state — the fetch failed.
 * - [ActiveGroupState.Success] with `activeGroupId == null` → E-K's actual empty state: the account
 *   genuinely has zero groups, so [EmptyState] renders WITH an action (fix round 1, BLOCKING B2:
 *   §9.11's acceptance criterion is "reaches create-or-join", which a text-only message cannot do
 *   on a screen with no other door to Groups — Groups is reached from Profile, not a tab).
 * - [ActiveGroupState.Success] with a non-null `activeGroupId` → the switcher (E-B, gated on 2+
 *   groups inside [GroupSwitcher] itself) plus the ordinary [FeedContent] rendering.
 *
 * **[onSwitchGroup]/[onRetryGroups]/[onCreateOrJoinGroup] carry no default (fix round 2, BLOCKING
 * F2).** They did, briefly — `= {}` on all three, purely so pre-existing tests kept compiling — and
 * a reviewer measured the actual cost: dropping BOTH real callbacks from the stateful overload's
 * own call just above still compiled, and the switcher/groups-retry silently did nothing. A default
 * here is not test ergonomics, it is the exact hole BLOCKING B1 closed one layer up reopened one
 * layer down. Every test call site in `FeedScreenTest` now passes all three explicitly.
 */
@Suppress("LongParameterList")
@Composable
internal fun FeedScreen(
    activeGroupState: ActiveGroupState,
    state: FeedUiState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onEntryClick: (FeedEntry) -> Unit,
    onSwitchGroup: (String) -> Unit,
    onRetryGroups: () -> Unit,
    onCreateOrJoinGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.feed_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when (activeGroupState) {
            is ActiveGroupState.Loading -> LoadingState(modifier = Modifier.weight(weight = 1f).fillMaxWidth())
            is ActiveGroupState.Error ->
                ErrorState(
                    message = stringResource(R.string.feed_groups_error_retry),
                    onRetry = onRetryGroups,
                    modifier = Modifier.weight(weight = 1f).fillMaxWidth(),
                )
            is ActiveGroupState.Success -> {
                val activeGroupId = activeGroupState.activeGroupId
                if (activeGroupId != null) {
                    GroupSwitcher(
                        groups = activeGroupState.groups,
                        activeGroupId = activeGroupId,
                        onGroupSelected = onSwitchGroup,
                    )
                }
                Box(modifier = Modifier.weight(weight = 1f).fillMaxWidth()) {
                    if (activeGroupId == null) {
                        EmptyState(
                            message = stringResource(R.string.feed_no_group_message),
                            actionLabel = stringResource(R.string.feed_no_group_action),
                            onAction = onCreateOrJoinGroup,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        FeedContent(
                            state = state,
                            onLoadMore = onLoadMore,
                            onRetry = onRetry,
                            onEntryClick = onEntryClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedContent(
    state: FeedUiState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onEntryClick: (FeedEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            is FeedUiState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())
            is FeedUiState.Error ->
                ErrorState(
                    message = stringResource(state.cause.messageRes()),
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            is FeedUiState.Success ->
                FeedSuccessContent(
                    state = state,
                    onLoadMore = onLoadMore,
                    onRetry = onRetry,
                    onEntryClick = onEntryClick,
                )
        }
    }
}

/**
 * [FeedUiState.Success.isStale]'s own rendering — `GroupsScreen`/`LibraryScreen`'s identical shape:
 * the banner sits ABOVE the content rather than replacing it.
 */
@Composable
private fun FeedSuccessContent(
    state: FeedUiState.Success,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onEntryClick: (FeedEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.isStale) {
            StaleDataBanner(onRetry = onRetry, messageRes = R.string.feed_stale_notice)
        }
        Box(modifier = Modifier.weight(weight = 1f).fillMaxWidth()) {
            if (state.entries.isEmpty()) {
                EmptyState(
                    message = stringResource(R.string.feed_empty_activity),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                FeedList(success = state, onLoadMore = onLoadMore, onEntryClick = onEntryClick)
            }
        }
    }
}

/**
 * The list itself, plus paging — `LibraryList`'s identical shape (end-of-list detection via
 * [EndOfListTrigger], a footer spinner while [FeedUiState.Success.loadingMore], a tap-to-retry
 * footer for [FeedUiState.Success.pageError] that never replaces the list itself).
 */
@Composable
private fun FeedList(
    success: FeedUiState.Success,
    onLoadMore: () -> Unit,
    onEntryClick: (FeedEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = success.entries
    val listState = rememberLazyListState()

    EndOfListTrigger(listState = listState, itemCount = entries.size, onTriggered = onLoadMore)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(all = 12.dp),
        verticalArrangement = Arrangement.spacedBy(space = 8.dp),
    ) {
        // Keyed by entry id: without a key, LazyColumn identifies items by index and a refresh
        // that reorders the list re-uses the wrong composable state for every row — the exact
        // duplicate-key crash CursorPaginator's own KDoc names for task 9c.3's round-0 bug.
        items(items = entries, key = FeedEntry::id) { entry ->
            FeedEntryRow(entry = entry, onClick = { onEntryClick(entry) })
        }
        if (success.loadingMore) {
            item { LoadingState() }
        } else if (success.pageError != null) {
            item {
                Text(
                    text = stringResource(R.string.feed_page_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onLoadMore)
                            .padding(all = 12.dp),
                )
            }
        }
    }
}

/**
 * One feed row. Tappable if and only if [FeedEntry.mediaId] is non-null — NOT gated on
 * `entry.kind == ActivityKind.IMPORTED` specifically, deliberately: [FeedEntry]'s own
 * `init` block enforces "media and mediaId are null together" structurally, so this reads that
 * invariant directly rather than re-deriving it from the kind. That also makes this row correct,
 * with no code change, for [ActivityKind.UNKNOWN] and for any future kind the backend adds that
 * also carries no media — this task's own "a seventh kind must render, not crash" requirement,
 * extended from "must render" to "must not offer a dead tap" too.
 *
 * `Modifier.clickable` is only ever ADDED when [FeedEntry.mediaId] is non-null, rather than
 * always-present with a conditional no-op lambda: the latter would still register the semantics
 * node as clickable (a screen reader announces it, `performClick()` in a test would silently
 * succeed against a no-op) for a row this task's own acceptance criterion says must not respond to
 * a tap at all.
 */
@Composable
private fun FeedEntryRow(
    entry: FeedEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowModifier =
        if (entry.mediaId != null) {
            modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            modifier.fillMaxWidth()
        }
    Card(modifier = rowModifier) {
        Text(
            text = entry.feedText(),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(all = 16.dp),
        )
    }
}

/**
 * One string per [ActivityKind] (E-A/E-H). [ActivityKind.IMPORTED] is the only kind whose copy
 * does not name a title — it names a COUNT instead, read out of [FeedEntry.payload] (the backend's
 * `payload={"count": inserted}`, `library/service.py`) — because [FeedEntry.media] is null for
 * this kind by construction (S-A) and every other kind's string takes a title argument that does
 * not exist here. [ActivityKind.UNKNOWN] renders a generic line naming neither a title nor a
 * payload key, since this client has no idea what either would mean for a kind it does not
 * recognise — the same defensive posture `GroupMapper.kindOf()` already takes at the mapping
 * boundary, carried through to rendering rather than stopping at "decodes without crashing."
 *
 * `entry.media?.title` (not a non-null assertion) even for the five kinds the backend always pairs
 * with a title today: [FeedEntry]'s own `init` guarantees `media`/`mediaId` are null TOGETHER, not
 * that only [ActivityKind.IMPORTED] can be the one that's null — nothing in the type system ties
 * "null media" to "this specific kind." A fallback here is what keeps a hypothetical future
 * media-less `added`/`rated`/… row rendering instead of crashing, the identical posture
 * [FeedEntryRow]'s own tappability check already takes for the same reason.
 */
@Composable
private fun FeedEntry.feedText(): String {
    val actor = actor.username
    val title = media?.title ?: stringResource(R.string.feed_unknown_title)
    return when (kind) {
        ActivityKind.ADDED -> stringResource(R.string.feed_entry_added, actor, title)
        ActivityKind.PROGRESSED -> stringResource(R.string.feed_entry_progressed, actor, title)
        ActivityKind.RATED -> stringResource(R.string.feed_entry_rated, actor, title)
        ActivityKind.COMPLETED -> stringResource(R.string.feed_entry_completed, actor, title)
        ActivityKind.DROPPED -> stringResource(R.string.feed_entry_dropped, actor, title)
        ActivityKind.IMPORTED -> {
            val count = payload["count"] ?: stringResource(R.string.feed_import_count_unknown)
            stringResource(R.string.feed_entry_imported, actor, count)
        }
        ActivityKind.UNKNOWN -> stringResource(R.string.feed_entry_unknown, actor)
    }
}

/**
 * The one place a [GroupFailure] becomes copy in this module — `GroupsDialogs.kt`'s
 * `GroupFailure.messageRes()` pattern, re-implemented here rather than reused: that function is
 * `internal` to `:feature:groups`, and architecture rule 1 forbids `:feature:feed` depending on
 * another feature module for it either way.
 *
 * Exhaustive over all EIGHT [GroupFailure] cases, no `else` — this task's own carried-forward
 * instruction ("Failures arrive as a thrown GroupOperationException... An exhaustive when handles
 * all eight with no else"), so a ninth case added later fails this file to COMPILE rather than
 * silently falling through to a generic message nobody chose. [GroupFailure.NotAMember] gets its
 * own copy — the single most likely non-network failure here (an owner removes this account from
 * the group while its feed screen is open) — mirroring `GroupsDialogs.kt`'s identical reasoning
 * for `GroupDetailScreen`. Every other case ([GroupFailure.NotPermitted], [GroupFailure.NoSuchTitle],
 * [GroupFailure.NoSuchEntry], [GroupFailure.AlreadyReviewed], [GroupFailure.InvalidInviteCode],
 * [GroupFailure.Unknown]) describes an endpoint this screen never calls, so a dedicated string for
 * any of them would name a case `GroupRepository.feed` cannot actually produce.
 * [GroupFailure.Unknown.cause] is never read here — that field is for logging only.
 */
internal fun GroupFailure.messageRes(): Int =
    when (this) {
        GroupFailure.Network -> R.string.feed_error_network
        GroupFailure.NotAMember -> R.string.feed_error_not_a_member
        GroupFailure.NotPermitted,
        GroupFailure.NoSuchTitle,
        GroupFailure.NoSuchEntry,
        is GroupFailure.AlreadyReviewed,
        GroupFailure.InvalidInviteCode,
        is GroupFailure.Unknown,
        -> R.string.feed_error_unknown
    }
