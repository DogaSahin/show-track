package com.anarky.showtrack.feature.library

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.LibraryRoute

/**
 * Library rows open the detail screen, so this entry translates the feature's own callback —
 * `onEntryClick: (LibraryEntry) -> Unit`, which knows nothing about navigation — into a route.
 *
 * The translation happens HERE rather than inside `LibraryScreen` on purpose: the screen stays a
 * function of its data and a click callback, previewable and testable with no nav graph, while
 * the single line that knows a click means "go to detail" sits at the module's graph boundary.
 * Naming `DetailRoute` is not a dependency on `:feature:detail` — this module's build file names
 * only `:core:navigation` — which is architecture rule 1 holding by construction.
 *
 * `entry.media.id`, NOT `entry.id`: a [LibraryEntry]'s own id identifies the user's library ROW
 * (`UserMedia`), while `DetailRoute.mediaId` identifies the title (`Media`) — two different
 * primary keys. The previous version of this file passed the entry id through unchanged, which
 * was silently wrong (nothing read `DetailRoute.mediaId` yet) rather than loudly wrong; task
 * 9a.9's detail screen is the first thing that would have surfaced it, as a title that resolves
 * to the wrong media or a 404, depending on whether the two ids ever collided by accident.
 */
fun NavGraphBuilder.libraryEntry(onNavigate: (AppRoute) -> Unit) {
    composable<LibraryRoute> {
        LibraryScreen(onEntryClick = { entry: LibraryEntry -> onNavigate(DetailRoute(mediaId = entry.media.id)) })
    }
}
