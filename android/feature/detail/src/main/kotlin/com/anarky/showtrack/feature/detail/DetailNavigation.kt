package com.anarky.showtrack.feature.detail

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.anarky.showtrack.core.navigation.DETAIL_DEEP_LINK_BASE_PATH
import com.anarky.showtrack.core.navigation.DetailRoute

/**
 * The one destination in the graph that carries an argument, and the reason `:core:navigation`
 * uses type-safe routes at all (decision A-F).
 *
 * `entry.toRoute<DetailRoute>()` reconstructs the route instance from the back-stack entry's
 * arguments through the generated `KSerializer`, so `mediaId` arrives as a `String` that the
 * compiler has already checked exists. The string-route alternative —
 * `entry.arguments?.getString("mediaId")` — is a nullable lookup by a key nothing verifies, and
 * a typo in it is a crash the user finds.
 *
 * The deep link is what a push notification's tap resolves to. `navDeepLink<DetailRoute>` derives
 * the URI pattern from the route's own serializer, so the `{mediaId}` placeholder is generated
 * from the data class rather than written out here — the same argument as `toRoute` below, one
 * layer up. The reified form matters: the string form would have to spell the path by hand and
 * could silently disagree with the route it is attached to.
 *
 * Registering it here rather than in `:app` keeps the destination and the way in to it in one
 * file: a rename of `mediaId` moves both, or neither.
 */
fun NavGraphBuilder.detailEntry() {
    composable<DetailRoute>(
        deepLinks = listOf(navDeepLink<DetailRoute>(basePath = DETAIL_DEEP_LINK_BASE_PATH)),
    ) { entry ->
        DetailScreen(mediaId = entry.toRoute<DetailRoute>().mediaId)
    }
}
