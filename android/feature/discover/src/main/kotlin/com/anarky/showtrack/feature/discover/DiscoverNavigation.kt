package com.anarky.showtrack.feature.discover

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.DiscoverRoute

/**
 * This module's contribution to the app's nav graph. `:app` calls it; nothing else can, because
 * nothing else depends on this module (architecture rule 1). The route type comes from
 * `:core:navigation`, so registering a destination costs no knowledge of any other feature.
 *
 * `onNavigate(DetailRoute(mediaId))`, the same `onNavigate: (AppRoute) -> Unit` shape
 * `searchEntry`/`libraryEntry` already use — naming `DetailRoute` here is not a dependency on
 * `:feature:detail`; this module's build file names only `:core:navigation`.
 *
 * A recommendation row's [com.anarky.showtrack.core.model.Recommendation.media] IS a persisted
 * title with a real id (unlike a search result — decision C-N), so `mediaId` here is read straight
 * off the tapped row rather than minted by a `POST /v1/library` response the way `SearchNavigation`'s
 * is: no add-first workaround needed.
 *
 * Registered as a `TopLevelDestination` in `:app`'s `MainActivity.kt` (this task) — see that
 * file's own KDoc for why Discover needed adding to the tab set rather than merely being reachable
 * in principle: a registered route with no door in is exactly the Gap 1/Gap 2 failure mode Phase 9a
 * shipped twice.
 */
fun NavGraphBuilder.discoverEntry(onNavigate: (AppRoute) -> Unit) {
    composable<DiscoverRoute> {
        DiscoverScreen(onNavigateToDetail = { mediaId -> onNavigate(DetailRoute(mediaId = mediaId)) })
    }
}
