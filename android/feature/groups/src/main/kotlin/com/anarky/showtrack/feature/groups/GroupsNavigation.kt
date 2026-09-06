package com.anarky.showtrack.feature.groups

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.model.WatchlistEntry
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.GroupDetailRoute
import com.anarky.showtrack.core.navigation.GroupsRoute
import kotlinx.coroutines.flow.StateFlow

/**
 * This module's contribution to the app's nav graph for [GroupsRoute]. `:app` calls it; nothing
 * else can, because nothing else depends on this module (architecture rule 1). The route type
 * comes from `:core:navigation`, so registering a destination costs no knowledge of any other
 * feature.
 *
 * `onNavigate(GroupDetailRoute(groupId = group.id))`, the same `onNavigate: (AppRoute) -> Unit`
 * shape `favoritesEntry`/`libraryEntry` already use — naming `GroupDetailRoute` here is not a
 * dependency on another feature module: it lives in `:core:navigation`, and this module registers
 * ITS destination itself, below.
 *
 * This screen gained a real destination to navigate to in this task (9c.1) — before it, `GroupsScreen`
 * rendered a bare placeholder and `groupsEntry` took no `onNavigate` at all (`AppDestination.kt`'s own
 * note on why a screen with nowhere to go declares no such parameter rather than accepting and
 * dropping one).
 *
 * [activeGroup] arrives as a `StateFlow<ActiveGroupState>` (task 9c.5), collected by
 * [GroupsScreen]'s stateful overload — `feedEntry`'s identical reasoning (`FeedNavigation.kt`'s own
 * KDoc): this registration runs far less often than the active group can change, so a plain
 * captured value would go stale.
 *
 * **The [ActiveGroupState.Success.groups] list it carries IS consumed here now (whole-branch fix
 * round, BLOCKING 3)** — an earlier version of this KDoc said the opposite, and that was the bug:
 * `GroupSwitcher` read [GroupsViewModel]'s separate list while `ActiveGroupViewModel` validated the
 * selection against its own, so a group created or joined on this screen was offered as a tab and
 * then rejected on tap. See [GroupsScreen]'s stateful overload for the full account.
 *
 * [onGroupsChanged] fires when a create/join succeeds. `:app` binds it to
 * `ActiveGroupViewModel::refresh`, which is also what `feedEntry`'s `onRetryGroups` is bound to —
 * one function, two parameters named for what each CALLER means by it rather than for the binding
 * they happen to share. Without it the single owner of "which groups exist" would not learn about a
 * group created on this very screen until the user navigated away and back.
 */
fun NavGraphBuilder.groupsEntry(
    activeGroup: StateFlow<ActiveGroupState>,
    onSwitchGroup: (String) -> Unit,
    onGroupsChanged: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
) {
    composable<GroupsRoute> {
        GroupsScreen(
            activeGroup = activeGroup,
            onSwitchGroup = onSwitchGroup,
            onGroupsChanged = onGroupsChanged,
            onGroupClick = { group: Group -> onNavigate(GroupDetailRoute(groupId = group.id)) },
        )
    }
}

/**
 * [GroupDetailRoute]'s real destination (task 9c.2) — task 9c.1 shipped this as a bare placeholder
 * `Text` purely so `:app`'s `NavGraphRegistrationTest` had a destination to find (that test demands
 * exactly one per leaf route declared in `AppRoute`, so a route cannot be declared in
 * `:core:navigation` without also being wired somewhere — the same reason `GroupsRoute` itself
 * shipped with a bare `Text("Groups")` destination when the original nine routes were first
 * declared). This function's SIGNATURE gained [onNavigate] in this task — the placeholder took
 * none, since it had nowhere to go — matching every other destination that needs somewhere real to
 * navigate (`groupsEntry` just above).
 *
 * [leaveNavigation] is `ProfileNavigation.kt`'s `signOutNavigation`/`importNavigation` pattern:
 * pulled out of this function's `composable<GroupDetailRoute> { }` lambda so the mapping
 * (`onLeft` → `onNavigate(GroupsRoute)`) is reachable by a plain unit test without composing
 * anything — `GroupDetailScreen` itself is constructed WITHOUT `viewModel` here, which evaluates
 * its `hiltViewModel()` default, so a bare unit test cannot drive the BINDING this way; it can
 * drive this mapping directly, and `GroupDetailEntryHiltTest` covers the binding end to end.
 *
 * **`onEntryClick` (fix round 1, task 9c.3):** `entry.mediaId`, not `entry.media.id` — a
 * [WatchlistEntry]'s [com.anarky.showtrack.core.model.MediaSummary] carries no id by design
 * (decision C-N), which is exactly why [WatchlistEntry.mediaId] exists as a sibling field
 * (`WatchlistEntry`'s own KDoc, `:core:model`) — `LibraryNavigation.kt`'s `libraryEntry` has the
 * identical inline-lambda shape for its own `entry.media.id`, not pulled into a named function
 * either, since neither takes more than one line to read correctly.
 */
fun NavGraphBuilder.groupDetailEntry(onNavigate: (AppRoute) -> Unit) {
    composable<GroupDetailRoute> {
        GroupDetailScreen(
            onLeft = leaveNavigation(onNavigate),
            onEntryClick = { entry: WatchlistEntry -> onNavigate(DetailRoute(mediaId = entry.mediaId)) },
        )
    }
}

/**
 * The mapping a successful leave drives — see [groupDetailEntry]'s own KDoc for why this is a
 * separate, unit-testable function rather than an inline lambda.
 *
 * `onNavigate(GroupsRoute)`, not a raw `popBackStack()`: nothing in `:feature:groups` holds a
 * `NavHostController` (architecture rule 1's whole point — cross-screen navigation goes through
 * `:core:navigation` route contracts, stitched in `:app`), so "go back to the groups list" is
 * expressed the same way every other exit in this app is, by NAMING the destination and letting
 * `:app`'s `routeShowTrackNavigation` decide how to get there. `ShowTrackNavHost.kt`'s own
 * `GroupsRoute` branch pops back to the existing list rather than pushing a second one, the
 * identical shape it already gives `LibraryRoute` returning from `ImportRoute`.
 */
internal fun leaveNavigation(onNavigate: (AppRoute) -> Unit): () -> Unit = { onNavigate(GroupsRoute) }
