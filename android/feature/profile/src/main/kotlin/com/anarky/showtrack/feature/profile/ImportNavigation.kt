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
 * `ShowTrackNavHost`'s routing table treats navigating TO `LibraryRoute` the same way regardless
 * of which screen it came from (`AuthRoute` or here): it clears `AuthRoute` off the stack when
 * present and promotes `AppViewModel.start`, which is a no-op the second time it runs — see that
 * table's own KDoc.
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
