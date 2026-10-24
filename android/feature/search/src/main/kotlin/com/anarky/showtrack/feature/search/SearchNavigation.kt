package com.anarky.showtrack.feature.search

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.SearchRoute

/**
 * This module's contribution to the app's nav graph. `:app` calls it; nothing else can, because
 * nothing else depends on this module (architecture rule 1). The route type comes from
 * `:core:navigation`, so registering a destination costs no knowledge of any other feature.
 *
 * `onNavigate(DetailRoute(mediaId))`, the same `onNavigate: (AppRoute) -> Unit` shape
 * `libraryEntry`/`authEntry` already use — naming `DetailRoute` here is not a dependency on
 * `:feature:detail`; this module's build file names only `:core:navigation`.
 *
 * The `mediaId` is the one `SearchViewModel` emits when a result is opened: the id the result
 * already carried, or the one `POST /v1/media/resolve` returned for it.
 */
fun NavGraphBuilder.searchEntry(onNavigate: (AppRoute) -> Unit) {
    composable<SearchRoute> {
        SearchScreen(onNavigateToDetail = { mediaId -> onNavigate(DetailRoute(mediaId = mediaId)) })
    }
}
