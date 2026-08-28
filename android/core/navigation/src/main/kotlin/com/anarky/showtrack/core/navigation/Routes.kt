package com.anarky.showtrack.core.navigation

import kotlinx.serialization.Serializable

/**
 * Every screen ShowTrack's NavHost can navigate to, as a closed hierarchy.
 *
 * Sealed rather than nine unrelated `@Serializable` types: `AppRoute::class.sealedSubclasses`
 * makes the full route set enumerable by reflection, so a test can assert "every route has
 * exactly one destination, none twice" against this hierarchy directly instead of against a
 * hand-maintained list that silently drifts the day a tenth route is added and nobody updates it.
 *
 * Type-safe rather than string routes ("detail/{mediaId}") (decision A-F): `DetailRoute("abc")`
 * is checked by the compiler; a malformed or missing argument in a string route is checked by the
 * user's crash report. `:app` (Task 9) is the only module that will ever see all nine — every
 * `:feature:*` module names its own destination and, at most, one other feature's *route* to
 * navigate to it, never that feature's module.
 */
sealed interface AppRoute

@Serializable
data object AuthRoute : AppRoute

@Serializable
data object LibraryRoute : AppRoute

@Serializable
data class DetailRoute(
    val mediaId: String,
) : AppRoute

@Serializable
data object DiscoverRoute : AppRoute

@Serializable
data object FavoritesRoute : AppRoute

@Serializable
data object ProfileRoute : AppRoute

@Serializable
data object SearchRoute : AppRoute

@Serializable
data object GroupsRoute : AppRoute

@Serializable
data object FeedRoute : AppRoute
