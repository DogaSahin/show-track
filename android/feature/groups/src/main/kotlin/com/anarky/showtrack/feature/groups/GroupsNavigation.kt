package com.anarky.showtrack.feature.groups

import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.GroupDetailRoute
import com.anarky.showtrack.core.navigation.GroupsRoute

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
 */
fun NavGraphBuilder.groupsEntry(onNavigate: (AppRoute) -> Unit) {
    composable<GroupsRoute> {
        GroupsScreen(
            onGroupClick = { group: Group -> onNavigate(GroupDetailRoute(groupId = group.id)) },
        )
    }
}

/**
 * A placeholder registration for [GroupDetailRoute], introduced in the same task that first makes
 * the route reachable ([groupsEntry]'s tap-through above). `:app`'s `NavGraphRegistrationTest`
 * demands exactly one destination per leaf route declared in `AppRoute`
 * (`` `every declared route appears in appDestinations exactly once` ``/
 * `` `the built nav graph holds one destination per declared route` ``), so a route cannot be
 * declared in `:core:navigation` without also being wired somewhere — the same reason `GroupsRoute`
 * itself shipped with a bare `Text("Groups")` destination when the original nine routes were first
 * declared, well before this task gave it real content.
 *
 * Task 9c.2 replaces the body of this destination with the real `GroupDetailScreen` (state, feed,
 * watchlist, members) — this function's SIGNATURE and its `GroupDetailRoute` registration stay;
 * only what `composable<GroupDetailRoute> { }` renders changes.
 */
fun NavGraphBuilder.groupDetailEntry() {
    composable<GroupDetailRoute> {
        Text(text = stringResource(R.string.groups_detail_placeholder))
    }
}
