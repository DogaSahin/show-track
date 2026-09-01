package com.anarky.showtrack.feature.library

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import com.anarky.showtrack.core.navigation.SearchRoute

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
 *
 * [searchNavigation] is the same trick applied to Gap 1: `SearchRoute` was registered in
 * `AppDestination.kt` and fully built, but nothing in the app ever navigated to it —
 * `:feature:search` was reachable code with no door in. Naming `SearchRoute` here is not a
 * dependency on `:feature:search` either, for the same reason `DetailRoute` above isn't one on
 * `:feature:detail`.
 */
fun NavGraphBuilder.libraryEntry(onNavigate: (AppRoute) -> Unit) {
    composable<LibraryRoute> {
        LibraryScreen(
            onEntryClick = { entry: LibraryEntry -> onNavigate(DetailRoute(mediaId = entry.media.id)) },
            onSearchClick = searchNavigation(onNavigate),
        )
    }
}

/**
 * The mapping the search action drives, pulled out of the `composable<LibraryRoute> { }` lambda
 * above so it is reachable by a plain unit test. `LibraryScreen`'s stateful overload resolves a
 * `LibraryViewModel` through `hiltViewModel()`, and this module has no Hilt test harness, so a test
 * cannot compose [libraryEntry] itself to observe what a tap does — it can call this function
 * directly instead.
 *
 * What IS covered: `LibraryScreenTest` pins icon tap → `onSearchClick`; `LibraryNavigationTest`
 * pins this function, `onSearchClick` (the parameter) → `onNavigate(SearchRoute)`.
 *
 * What is NOT: the BINDING one line above — `onSearchClick = searchNavigation(onNavigate)` — is
 * untested. Neither test composes [libraryEntry] itself, so nothing observes that this particular
 * argument is actually wired to this particular function. Change that line to
 * `onSearchClick = {}` and both tests above stay green while the search screen goes unreachable
 * again — the exact regression this file exists to prevent. Closing that needs a Hilt test
 * harness (to compose the stateful `LibraryScreen`), which does not exist anywhere in this repo;
 * see the phase-level item to build one.
 */
internal fun searchNavigation(onNavigate: (AppRoute) -> Unit): () -> Unit = { onNavigate(SearchRoute) }
