package com.anarky.showtrack

import androidx.navigation.NavGraphBuilder
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.DiscoverRoute
import com.anarky.showtrack.core.navigation.FavoritesRoute
import com.anarky.showtrack.core.navigation.FeedRoute
import com.anarky.showtrack.core.navigation.GroupDetailRoute
import com.anarky.showtrack.core.navigation.GroupsRoute
import com.anarky.showtrack.core.navigation.ImportRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import com.anarky.showtrack.core.navigation.ProfileRoute
import com.anarky.showtrack.core.navigation.SearchRoute
import com.anarky.showtrack.feature.auth.authEntry
import com.anarky.showtrack.feature.detail.detailEntry
import com.anarky.showtrack.feature.discover.discoverEntry
import com.anarky.showtrack.feature.favorites.favoritesEntry
import com.anarky.showtrack.feature.feed.feedEntry
import com.anarky.showtrack.feature.groups.groupDetailEntry
import com.anarky.showtrack.feature.groups.groupsEntry
import com.anarky.showtrack.feature.library.libraryEntry
import com.anarky.showtrack.feature.profile.importEntry
import com.anarky.showtrack.feature.profile.profileEntry
import com.anarky.showtrack.feature.search.searchEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass

/**
 * One route and the entry function that registers it.
 *
 * [appDestinations] is the only place in the app where all nine features are named — which is what
 * makes architecture rule 1 survivable: a feature reaches another feature's screen by handing a
 * `:core:navigation` route to `onNavigate`, and the wiring happens here.
 *
 * Why a list rather than nine calls written straight into the `NavHost` builder: the registration
 * then becomes data that a JVM test can compare against `AppRoute::class.sealedSubclasses`. Nine
 * inline calls are equally correct and completely un-inspectable — a tenth route added in Phase 9
 * and never wired is a crash on a screen nobody opened during development, which is exactly the
 * regression that is hard to notice and cheap to catch.
 *
 * Not a `data class`: [register] is a function, so generated `equals`/`hashCode` would compare
 * lambda identity and mean nothing.
 */
internal class AppDestination(
    val route: KClass<out AppRoute>,
    val register: NavGraphBuilder.(onNavigate: (AppRoute) -> Unit) -> Unit,
)

/**
 * Every destination the app can show. Order is presentation-only; `ShowTrackNavHost`'s
 * `startDestination` is what decides where the graph opens.
 *
 * The entries that ignore `onNavigate` are the screens with nowhere to go yet — their feature
 * modules declare no such parameter at all rather than accepting one and dropping it. `Discover`
 * moved out of that set in task 9b.3: a recommendation row now opens Detail directly (its `media`
 * is persisted and carries a real id — no add-first workaround), so `discoverEntry` gained the same
 * `onNavigate` parameter `libraryEntry`/`searchEntry` already have. `Favorites` moves out of it in
 * this task (9b.4) for the identical reason: it now renders real rows, each with a real
 * `entry.media.id`, so `favoritesEntry` gained the same parameter too. `Groups` moves out of it in
 * task 9c.1 for the same reason again: tapping a loaded or just-created group now needs somewhere
 * real to go, so `groupsEntry` gained the same parameter. `GroupDetailRoute` moves out of it in
 * task 9c.2: leaving the group now needs somewhere real to go back to, so `groupDetailEntry` gained
 * the same parameter too — see `ShowTrackNavHost.kt`'s `routeShowTrackNavigation` for how
 * `onNavigate(GroupsRoute)` resolves to a pop back to the existing list rather than a second push.
 *
 * **A function, not a `val`, as of task 9c.5.** `GroupsRoute`/`FeedRoute` now need the ACTIVE group
 * — a value that changes at runtime (E-C) — and `NavHost`'s own `builder` lambda (the thing that
 * ultimately calls this) is only re-invoked when its `remember(startDestination, ...)` keys change,
 * which for a signed-in session is effectively once. A `val` computed at class-load time would bake
 * in whatever `activeGroupId`/`groups` happened to hold at that one moment, permanently — the exact
 * trap `FeedRoute`'s hard-coded `activeGroupId = null` sidestepped by being a compile-time constant
 * instead of a value that was ever supposed to change. The fix is NOT calling this function more
 * often; it is that [activeGroupId]/[groups] are `StateFlow`s — stable references this function
 * closes over once, which `feedEntry`/`groupsEntry` then read reactively (`collectAsStateWithLifecycle`)
 * from INSIDE their own `composable<Route> { }` content lambda, which recomposes on every emission
 * regardless of how rarely the outer graph itself rebuilds — `ShowTrackNavHost`'s `authEvents:
 * Flow<AuthEvent>` is the exact same technique, one layer further out.
 */
internal fun appDestinations(
    activeGroupId: StateFlow<String?> = MutableStateFlow(null),
    groups: StateFlow<List<Group>> = MutableStateFlow(emptyList()),
    onSwitchGroup: (String) -> Unit = {},
): List<AppDestination> =
    listOf(
        AppDestination(AuthRoute::class) { onNavigate -> authEntry(onNavigate) },
        AppDestination(LibraryRoute::class) { onNavigate -> libraryEntry(onNavigate) },
        AppDestination(DetailRoute::class) { detailEntry() },
        AppDestination(DiscoverRoute::class) { onNavigate -> discoverEntry(onNavigate) },
        AppDestination(FavoritesRoute::class) { onNavigate -> favoritesEntry(onNavigate) },
        AppDestination(ProfileRoute::class) { onNavigate -> profileEntry(onNavigate) },
        AppDestination(SearchRoute::class) { onNavigate -> searchEntry(onNavigate) },
        AppDestination(GroupsRoute::class) { onNavigate ->
            groupsEntry(activeGroupId = activeGroupId, onSwitchGroup = onSwitchGroup, onNavigate = onNavigate)
        },
        AppDestination(GroupDetailRoute::class) { onNavigate -> groupDetailEntry(onNavigate) },
        AppDestination(FeedRoute::class) { onNavigate ->
            feedEntry(
                activeGroupId = activeGroupId,
                groups = groups,
                onSwitchGroup = onSwitchGroup,
                onNavigate = onNavigate,
            )
        },
        AppDestination(ImportRoute::class) { onNavigate -> importEntry(onNavigate) },
    )

/**
 * Registers every destination [appDestinations] describes into the graph being built.
 *
 * A named function rather than a `forEach` written inline in `ShowTrackNavHost`, so that the
 * NavHost and `NavGraphRegistrationTest` share the *same* line of wiring. If the test rebuilt the
 * graph its own way, it would go on passing after someone replaced this iteration with nine
 * hand-written calls — checking a table nothing reads.
 *
 * [activeGroupId]/[groups]/[onSwitchGroup] default to an empty, static selection so every existing
 * caller that does not care about groups (`NavGraphRegistrationTest`, `ShowTrackGraphRoutingTest`,
 * `AuthNavigationTest`, `TopLevelNavigationTest`, `ShouldShowNavigationTabsTest` — none of them
 * drive Feed or Groups) keeps compiling unchanged; only `ShowTrackNavHost` passes the real,
 * `ActiveGroupViewModel`-backed values.
 */
internal fun NavGraphBuilder.showTrackDestinations(
    onNavigate: (AppRoute) -> Unit,
    activeGroupId: StateFlow<String?> = MutableStateFlow(null),
    groups: StateFlow<List<Group>> = MutableStateFlow(emptyList()),
    onSwitchGroup: (String) -> Unit = {},
) {
    appDestinations(activeGroupId, groups, onSwitchGroup).forEach { destination ->
        destination.register(this, onNavigate)
    }
}
