package com.anarky.showtrack.feature.profile

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.ImportRoute
import com.anarky.showtrack.core.navigation.LibraryRoute

/**
 * This module's contribution to the app's nav graph for [ImportRoute] (task 9b.6). `:app` calls
 * it; nothing else can, because nothing else depends on this module (architecture rule 1).
 *
 * Both the skip action and the terminal success screen's "Done" button resolve to
 * `onNavigate(LibraryRoute)` — see [ImportScreen]'s own KDoc for why one callback covers both.
 *
 * **Round 1 correction (task 9b.6 fix round):** an earlier version of this KDoc claimed
 * `ShowTrackNavHost`'s routing table treats every arrival at `LibraryRoute` the same way. It does
 * not, on purpose — `routeShowTrackNavigation`'s `LibraryRoute` branch tells "an already-signed-in
 * session's import screen is returning" (this door, reached from Profile) apart from "a sign-in or
 * onboarding is completing" (a fresh login, or finishing onboarding after registration), and does
 * something DIFFERENT for each — a `popBackStack()` for the former, a promoting navigate for the
 * latter. This function still only ever calls `onNavigate(LibraryRoute)`; which of those two
 * things actually happens is entirely `routeShowTrackNavigation`'s decision.
 *
 * **Round 2 correction:** an earlier version of the sentence above said that decision was "made
 * from information (`start`) this module has no business knowing". Round 2 deleted the `start:
 * AppStart` parameter from `routeShowTrackNavigation` entirely — it was measured to be the WRONG
 * signal (a sign-out never moves it backward, so it can be stale by the time a real decision needs
 * making) — and replaced it with the actual back stack shape
 * (`NavHostController.currentDestination`/`previousBackStackEntry`), read fresh at the moment each
 * navigation fires. See `routeShowTrackNavigation`'s own KDoc for the full split and why the back
 * stack, not `start`, is what this module still has no business reading.
 */
fun NavGraphBuilder.importEntry(onNavigate: (AppRoute) -> Unit) {
    composable<ImportRoute> {
        ImportScreen(onFinished = importFinishedNavigation(onNavigate))
    }
}

/**
 * The mapping "finished" drives, pulled out of the `composable<ImportRoute> { }` lambda above —
 * `signOutNavigation`'s own reasoning (`ProfileNavigation.kt`): [importEntry]'s lambda constructs
 * `ImportScreen` WITHOUT passing `viewModel`, evaluating its `hiltViewModel()` default, which
 * this module has no Hilt harness for — so a test reaches this function directly instead.
 */
internal fun importFinishedNavigation(onNavigate: (AppRoute) -> Unit): () -> Unit = { onNavigate(LibraryRoute) }
