package com.anarky.showtrack.feature.detail

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import com.anarky.showtrack.core.navigation.DETAIL_DEEP_LINK_BASE_PATH
import com.anarky.showtrack.core.navigation.DetailRoute

/**
 * The one destination in the graph that carries an argument, and the reason `:core:navigation`
 * uses type-safe routes at all (decision A-F).
 *
 * `mediaId` is not read here at all — `DetailScreen`'s `hiltViewModel()` call resolves a
 * `SavedStateHandle` already populated from this same back-stack entry's arguments, and
 * `DetailViewModel` reads `mediaId` from it via `SavedStateHandle.toRoute<DetailRoute>()`. That
 * keeps the type-safe argument decode in exactly one place (see `DetailViewModel`'s KDoc for why
 * it is the ViewModel, not the Composable, that owns the read).
 *
 * The deep link is what a push notification's tap resolves to. `navDeepLink<DetailRoute>` derives
 * the URI pattern from the route's own serializer, so the `{mediaId}` placeholder is generated
 * from the data class rather than written out here. The reified form matters: the string form
 * would have to spell the path by hand and could silently disagree with the route it is attached to.
 *
 * Registering it here rather than in `:app` keeps the destination and the way in to it in one
 * file: a rename of `mediaId` moves both, or neither.
 */
fun NavGraphBuilder.detailEntry() {
    composable<DetailRoute>(
        deepLinks = listOf(navDeepLink<DetailRoute>(basePath = DETAIL_DEEP_LINK_BASE_PATH)),
    ) {
        DetailScreen()
    }
}
