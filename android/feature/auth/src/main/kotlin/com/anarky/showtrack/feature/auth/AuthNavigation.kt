package com.anarky.showtrack.feature.auth

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.LibraryRoute

/**
 * This module's contribution to the app's nav graph. `:app` calls it; nothing else can, because
 * nothing else depends on this module (architecture rule 1). The route type comes from
 * `:core:navigation`, so registering a destination costs no knowledge of any other feature.
 *
 * `onNavigate(LibraryRoute)` on success, same signature `libraryEntry`/`feedEntry` already use —
 * `ShowTrackNavHost` is what turns navigating TO `LibraryRoute` into a `popUpTo<AuthRoute>`
 * navigation, so Back cannot return to a login form that already succeeded. This module names
 * only `LibraryRoute`, never `:feature:library`.
 */
fun NavGraphBuilder.authEntry(onNavigate: (AppRoute) -> Unit) {
    composable<AuthRoute> {
        AuthScreen(onAuthenticated = { onNavigate(LibraryRoute) })
    }
}
