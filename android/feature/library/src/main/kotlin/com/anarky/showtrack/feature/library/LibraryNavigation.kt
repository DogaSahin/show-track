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
 * `LibraryViewModel` through `hiltViewModel()`, so a test that wants to exercise it needs a Hilt
 * harness to compose against — `LibraryNavigationTest` predates the one this module now has and
 * still calls this function directly instead, which stays a legitimate, narrower test in its own
 * right (see below).
 *
 * What IS covered: `LibraryScreenTest` pins icon tap → `onSearchClick`; `LibraryNavigationTest`
 * pins this function, `onSearchClick` (the parameter) → `onNavigate(SearchRoute)`;
 * `LibraryEntryHiltTest` now composes [libraryEntry] itself through a real Hilt-backed
 * `LibraryScreen` and pins the BINDING one line above — `onSearchClick =
 * searchNavigation(onNavigate)` — end to end: tap → `onNavigate(SearchRoute)`. Change that line to
 * `onSearchClick = {}` and `LibraryEntryHiltTest` is the one that fails; the other two, having
 * never composed [libraryEntry], stay green regardless — which is exactly why closing this gap
 * needed a fourth test rather than trusting the first three more.
 *
 * Not fixed by this: [com.anarky.showtrack.feature.profile.signOutNavigation] in
 * `ProfileNavigation.kt` has the identical shape of gap and no Hilt harness closes it — `:feature:profile`
 * gained no test harness in this change, only `:feature:library` did.
 */
internal fun searchNavigation(onNavigate: (AppRoute) -> Unit): () -> Unit = { onNavigate(SearchRoute) }
