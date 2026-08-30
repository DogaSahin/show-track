package com.anarky.showtrack.feature.feed

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.FeedRoute

/**
 * `FeedScreen` already builds its own `DetailRoute` (see its KDoc), so this entry forwards
 * [onNavigate] untouched rather than translating anything.
 */
fun NavGraphBuilder.feedEntry(onNavigate: (AppRoute) -> Unit) {
    composable<FeedRoute> {
        FeedScreen(onNavigate = onNavigate)
    }
}
