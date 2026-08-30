package com.anarky.showtrack.feature.library

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.LibraryRoute

/**
 * Library rows open the detail screen, so this entry translates the feature's own callback —
 * `onEntryClick: (String) -> Unit`, which knows nothing about navigation — into a route.
 *
 * The translation happens HERE rather than inside `LibraryScreen` on purpose: the screen stays a
 * function of its data and a click id, previewable and testable with no nav graph, while the
 * single line that knows a click means "go to detail" sits at the module's graph boundary.
 * Naming `DetailRoute` is not a dependency on `:feature:detail` — this module's build file names
 * only `:core:navigation` — which is architecture rule 1 holding by construction.
 */
fun NavGraphBuilder.libraryEntry(onNavigate: (AppRoute) -> Unit) {
    composable<LibraryRoute> {
        LibraryScreen(onEntryClick = { entryId -> onNavigate(DetailRoute(mediaId = entryId)) })
    }
}
