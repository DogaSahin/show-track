package com.anarky.showtrack.feature.detail

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
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
 */
fun NavGraphBuilder.detailEntry() {
    composable<DetailRoute> { entry ->
        DetailScreen(mediaId = entry.toRoute<DetailRoute>().mediaId)
    }
}
