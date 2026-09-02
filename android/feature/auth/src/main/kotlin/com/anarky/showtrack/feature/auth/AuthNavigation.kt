package com.anarky.showtrack.feature.auth

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.ImportRoute
import com.anarky.showtrack.core.navigation.LibraryRoute

/**
 * This module's contribution to the app's nav graph. `:app` calls it; nothing else can, because
 * nothing else depends on this module (architecture rule 1). The route type comes from
 * `:core:navigation`, so registering a destination costs no knowledge of any other feature.
 *
 * `onNavigate(ImportRoute)` on a fresh registration, `onNavigate(LibraryRoute)` on a login or a
 * returning session (task 9b.6) — same signature `libraryEntry`/`feedEntry` already use.
 * `ShowTrackNavHost` is what turns navigating to EITHER destination into a stack-clearing
 * navigation, so Back cannot return to a login form that already succeeded, and it promotes
 * `AppViewModel.start` either way — see that routing table's own KDoc. This module names only
 * `ImportRoute`/`LibraryRoute`, never `:feature:profile` or `:feature:library`.
 */
fun NavGraphBuilder.authEntry(onNavigate: (AppRoute) -> Unit) {
    composable<AuthRoute> {
        AuthScreen(
            onAuthenticated = { isNewAccount -> onNavigate(if (isNewAccount) ImportRoute else LibraryRoute) },
        )
    }
}
