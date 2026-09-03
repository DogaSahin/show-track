package com.anarky.showtrack.feature.groups

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
 * Task 9c.3 extends this destination with the shared watchlist (plan doc: "Modify:
 * `GroupDetailUiState.kt`, `GroupDetailViewModel.kt`, `GroupDetailScreen.kt`, `strings.xml`") —
 * nothing here is written to preclude it.
 */
fun NavGraphBuilder.groupDetailEntry(onNavigate: (AppRoute) -> Unit) {
    composable<GroupDetailRoute> {
        GroupDetailScreen(onLeft = leaveNavigation(onNavigate))
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
