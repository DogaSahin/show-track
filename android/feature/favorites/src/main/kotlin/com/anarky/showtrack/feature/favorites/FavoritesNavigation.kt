package com.anarky.showtrack.feature.favorites

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.FavoritesRoute

/**
 * This module's contribution to the app's nav graph. `:app` calls it; nothing else can, because
 * nothing else depends on this module (architecture rule 1). The route type comes from
 * `:core:navigation`, so registering a destination costs no knowledge of any other feature.
 *
 * `onNavigate(DetailRoute(mediaId))`, the same `onNavigate: (AppRoute) -> Unit` shape
 * `libraryEntry`/`discoverEntry` already use — naming `DetailRoute` here is not a dependency on
 * `:feature:detail`; this module's build file names only `:core:navigation` and `:core:data`.
 *
 * `entry.media.id`, NOT `entry.id`: a [LibraryEntry]'s own id identifies the user's library ROW,
 * while `DetailRoute.mediaId` identifies the title — the same distinction `LibraryNavigation.kt`'s
 * KDoc explains for the identical translation.
 *
 * This screen gained a real destination to navigate to now that it renders actual rows (task
 * 9b.4) — before this task it had nowhere to go and took no `onNavigate` parameter at all
 * (`AppDestination.kt`'s own note on why a screen with nowhere to go declares no such parameter
 * rather than accepting and dropping one).
 */
fun NavGraphBuilder.favoritesEntry(onNavigate: (AppRoute) -> Unit) {
    composable<FavoritesRoute> {
        FavoritesScreen(
            onEntryClick = { entry: LibraryEntry -> onNavigate(DetailRoute(mediaId = entry.media.id)) },
        )
    }
}
