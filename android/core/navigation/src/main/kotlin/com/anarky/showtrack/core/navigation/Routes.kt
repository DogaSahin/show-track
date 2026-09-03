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

/**
 * One group's detail screen (task 9c.1 adds the route; task 9c.2 builds the real screen behind
 * it — feed, watchlist, members). [groupId] rather than a whole [com.anarky.showtrack.core.model.Group]:
 * the same reasoning [DetailRoute] carries a bare `mediaId`, not a `Media` — a route argument is a
 * navigation key, not a data payload, and the destination re-fetches its own state from the id.
 *
 * Reached from [GroupsRoute]'s list (`GroupsNavigation.kt`'s `groupsEntry`), never constructed with
 * an invented id — `:feature:groups`' own screen is the only place a [com.anarky.showtrack.core.model.Group.id]
 * from a loaded list or a fresh create/join response is in scope.
 */
@Serializable
data class GroupDetailRoute(
    val groupId: String,
) : AppRoute

@Serializable
data object FeedRoute : AppRoute

/**
 * The AniList import screen (task 9b.6). Lives in `:feature:profile` — the module already owns
 * account-level actions (push opt-in, sign-out) — but the route sits here in `:core:navigation`
 * rather than as a `:feature:profile`-only concept, because `:app` reaches it from TWO places that
 * must not depend on each other: `:feature:profile` itself (an ordinary settings action) and
 * `:feature:auth`'s post-register success (onboarding, offered as the alternative to an empty
 * library). Routing `:feature:auth` straight to a `:feature:profile` screen would violate
 * architecture rule 1; routing it through this shared route contract does not.
 */
@Serializable
data object ImportRoute : AppRoute

/**
 * The app's private deep-link scheme, and the one route reachable through it.
 *
 * This is what makes "the notification opens the title it is about" possible ACROSS modules
 * without breaking architecture rule 1. `:feature:profile` builds the notification and must send
 * the user to `:feature:detail`'s screen; naming that module would fail the build. A URI is the
 * same trick `onNavigate: (AppRoute) -> Unit` plays for in-app navigation — the route contract
 * travels, the module does not — except that it also survives a cold start, because the tap goes
 * through the system rather than through a live NavController.
 *
 * `showtrack://` rather than an `https://` App Link: an App Link needs a verified domain, and
 * hosting for this project is still undecided (design doc §11). A custom scheme is not
 * verifiable and any app may claim it, which is why nothing sensitive travels in it — the media
 * id is already a public identifier, and every screen behind it requires a token.
 *
 * The `basePath` form `scheme://host` matches `navDeepLink<DetailRoute>`'s convention: the route's
 * own arguments are appended by the generated serializer, so a required `mediaId` becomes
 * `showtrack://detail/{mediaId}` and nothing hand-writes that path. `:app`'s
 * `NavGraphRegistrationTest` asserts the built graph actually answers a concrete one.
 */
const val DEEP_LINK_SCHEME: String = "showtrack"

/** The `basePath` `:feature:detail` registers its destination's deep link under. */
const val DETAIL_DEEP_LINK_BASE_PATH: String = "$DEEP_LINK_SCHEME://detail"

/** The concrete URI for one title. The only correct way to build one — see [DETAIL_DEEP_LINK_BASE_PATH]. */
fun detailDeepLink(mediaId: String): String = "$DETAIL_DEEP_LINK_BASE_PATH/$mediaId"
