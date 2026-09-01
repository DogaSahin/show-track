package com.anarky.showtrack

import androidx.navigation.NavGraphBuilder
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.DiscoverRoute
import com.anarky.showtrack.core.navigation.FavoritesRoute
import com.anarky.showtrack.core.navigation.FeedRoute
import com.anarky.showtrack.core.navigation.GroupsRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import com.anarky.showtrack.core.navigation.ProfileRoute
import com.anarky.showtrack.core.navigation.SearchRoute
import com.anarky.showtrack.feature.auth.authEntry
import com.anarky.showtrack.feature.detail.detailEntry
import com.anarky.showtrack.feature.discover.discoverEntry
import com.anarky.showtrack.feature.favorites.favoritesEntry
import com.anarky.showtrack.feature.feed.feedEntry
import com.anarky.showtrack.feature.groups.groupsEntry
import com.anarky.showtrack.feature.library.libraryEntry
import com.anarky.showtrack.feature.profile.profileEntry
import com.anarky.showtrack.feature.search.searchEntry
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
 * modules declare no such parameter at all rather than accepting one and dropping it.
 */
internal val appDestinations: List<AppDestination> =
    listOf(
        AppDestination(AuthRoute::class) { onNavigate -> authEntry(onNavigate) },
        AppDestination(LibraryRoute::class) { onNavigate -> libraryEntry(onNavigate) },
        AppDestination(DetailRoute::class) { detailEntry() },
        AppDestination(DiscoverRoute::class) { discoverEntry() },
        AppDestination(FavoritesRoute::class) { favoritesEntry() },
        AppDestination(ProfileRoute::class) { onNavigate -> profileEntry(onNavigate) },
        AppDestination(SearchRoute::class) { onNavigate -> searchEntry(onNavigate) },
        AppDestination(GroupsRoute::class) { groupsEntry() },
        AppDestination(FeedRoute::class) { onNavigate -> feedEntry(onNavigate) },
    )

/**
 * Registers every destination in [appDestinations] into the graph being built.
 *
 * A named function rather than a `forEach` written inline in `ShowTrackNavHost`, so that the
 * NavHost and `NavGraphRegistrationTest` share the *same* line of wiring. If the test rebuilt the
 * graph its own way, it would go on passing after someone replaced this iteration with nine
 * hand-written calls — checking a table nothing reads.
 */
internal fun NavGraphBuilder.showTrackDestinations(onNavigate: (AppRoute) -> Unit) {
    appDestinations.forEach { destination -> destination.register(this, onNavigate) }
}
