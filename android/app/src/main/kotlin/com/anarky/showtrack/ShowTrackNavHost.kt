package com.anarky.showtrack

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.anarky.showtrack.core.model.AuthEvent
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import kotlinx.coroutines.flow.Flow

/**
 * The app's single nav graph, plus the auth gate that can redirect out of any of it.
 *
 * [AuthGate] sits OUTSIDE the `NavHost` deliberately: it must keep collecting while any
 * destination is on screen, and a collector placed inside a destination's composable would be
 * cancelled the moment that destination left the composition — i.e. exactly when the user
 * navigated somewhere that then 401s.
 */
@Composable
internal fun ShowTrackNavHost(
    navController: NavHostController,
    authEvents: Flow<AuthEvent>,
    modifier: Modifier = Modifier,
) {
    AuthGate(authEvents = authEvents, onLoggedOut = navController::navigateToAuthClearingStack)

    NavHost(
        navController = navController,
        startDestination = LibraryRoute,
        modifier = modifier,
    ) {
        // `NavController.navigate(route: Any)` accepts an AppRoute, and Function1 is
        // contravariant in its parameter, so this reference satisfies `(AppRoute) -> Unit`.
        showTrackDestinations(onNavigate = navController::navigate)
    }
}

/**
 * Goes to the auth screen and leaves nothing behind it.
 *
 * Clearing the stack is load-bearing, not tidiness: without it, `back` from the login screen
 * returns to a screen whose every request 401s, and the app looks broken rather than logged out.
 *
 * `popUpTo(graph.id)`, NOT `popUpTo(0)`. The literal 0 happens to work today only because a
 * `NavHost` built from a `startDestination` with no `route` class gets a root graph whose id is
 * 0. Give the graph a route — the natural move the first time Phase 9 nests a sub-graph — and the
 * id is that route's hash, `popUpTo(0)` matches nothing, and it silently degrades into a no-op
 * that pops NOTHING while still compiling and still navigating. Reading the id off the graph
 * survives that refactor. `AuthNavigationTest` pins both halves: the graph shape used today, and
 * one built with a route class, where `popUpTo(0)` demonstrably pops nothing.
 *
 * A named extension rather than a lambda inline in [ShowTrackNavHost] so the behaviour is
 * reachable from a test at all — the composable's version could only be exercised by composing.
 */
internal fun NavHostController.navigateToAuthClearingStack() {
    navigate(AuthRoute) {
        popUpTo(graph.id) { inclusive = true }
    }
}
