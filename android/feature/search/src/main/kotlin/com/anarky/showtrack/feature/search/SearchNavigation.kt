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
 * The `mediaId` is `SearchScreen`'s own `onNavigateToDetail: (String) -> Unit` callback argument,
 * which is only ever invoked with the id `SearchViewModel.navigateToDetail` emits AFTER
 * `POST /v1/library` creates the row — never a raw tap on a search result, which carries no id at
 * all (decision C-N; see `SearchViewModel`'s KDoc).
 */
fun NavGraphBuilder.searchEntry(onNavigate: (AppRoute) -> Unit) {
    composable<SearchRoute> {
        SearchScreen(onNavigateToDetail = { mediaId -> onNavigate(DetailRoute(mediaId = mediaId)) })
    }
}
