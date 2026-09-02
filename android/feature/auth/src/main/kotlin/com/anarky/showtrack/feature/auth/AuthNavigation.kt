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
        AuthScreen(onAuthenticated = authenticatedNavigation(onNavigate))
    }
}

/**
 * The mapping a successful authentication drives, pulled out of the `composable<AuthRoute> { }`
 * lambda above so it is reachable by a plain unit test — `ProfileNavigation.kt`'s
 * `signOutNavigation`/`importNavigation` are the pattern this follows: [authEntry]'s lambda
 * constructs `AuthScreen` WITHOUT passing `viewModel`, which evaluates its `hiltViewModel()`
 * default, and `:feature:auth` has no Hilt test harness — so a test cannot compose [authEntry]
 * itself to observe which route a given `isNewAccount` value produces; it can call this function
 * directly instead (round 1, task 9b.6 fix round: nothing in the repository referenced [authEntry]
 * at all before this, so a round-0 defect that inverted or deleted the condition on the line this
 * replaces would have passed the entire suite).
 */
internal fun authenticatedNavigation(onNavigate: (AppRoute) -> Unit): (Boolean) -> Unit =
    { isNewAccount -> onNavigate(if (isNewAccount) ImportRoute else LibraryRoute) }
