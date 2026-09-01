package com.anarky.showtrack

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.model.AuthEvent
import com.anarky.showtrack.core.navigation.AppRoute
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
 *
 * [AppViewModel] is the OTHER half of the gate — see its doc. Its `start` begins `Undecided`,
 * and this renders [LoadingState] for that one frame rather than building the graph with a
 * guessed `startDestination` and navigating away afterwards: a guessed Library followed by a
 * navigate to Auth would leave Library on the back stack underneath it, and Back would then land
 * a signed-out user on the library. Building the `NavHost` only once `start` resolves is what
 * keeps Library off the stack entirely for a signed-out cold start.
 */
@Composable
internal fun ShowTrackNavHost(
    navController: NavHostController,
    authEvents: Flow<AuthEvent>,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = hiltViewModel(),
) {
    AuthGate(authEvents = authEvents, onLoggedOut = navController::navigateToAuthClearingStack)

    when (val start = appViewModel.start.collectAsStateWithLifecycle().value) {
        AppStart.Undecided -> LoadingState(modifier = modifier)
        AppStart.Auth ->
            ShowTrackGraph(navController = navController, startDestination = AuthRoute, modifier = modifier)
        AppStart.Library ->
            ShowTrackGraph(navController = navController, startDestination = LibraryRoute, modifier = modifier)
    }
}

@Composable
private fun ShowTrackGraph(
    navController: NavHostController,
    startDestination: AppRoute,
    modifier: Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        showTrackDestinations(onNavigate = navController::routeShowTrackNavigation)
    }
}

/**
 * How `onNavigate` routes to a destination in the graph [ShowTrackGraph] builds. A named extension
 * rather than the lambda that used to sit inline in [ShowTrackGraph]'s `NavHost(onNavigate = ...)`
 * call, for the same reason [navigateToAuthClearingStack] and [navigateToLibraryClearingAuth] are
 * named extensions rather than lambdas: composing the whole app graph to exercise one branch of
 * this `when` needs Hilt (every screen resolves a `@HiltViewModel`), and `:app` has no Hilt test
 * harness, so [ShowTrackGraphRoutingTest] calls this directly on a bare `NavHostController` instead
 * — the same technique `AuthNavigationTest` already uses for the two extensions below.
 */
internal fun NavHostController.routeShowTrackNavigation(route: AppRoute) {
    when (route) {
        // Navigating TO LibraryRoute through this table only ever happens once, from
        // AuthNavigation on a successful login/register — nothing else in the app reaches Library
        // through onNavigate (it is a start destination, not a target other screens link to).
        // popUpTo<AuthRoute> there is load-bearing, not incidental: a plain push leaves Auth on
        // the back stack and Back returns to a login form that already succeeded.
        is LibraryRoute -> navigateToLibraryClearingAuth()

        // Navigating TO AuthRoute through this table happens from ProfileNavigation on sign-out
        // (Gap 2, Phase 9a device walkthroughs). AuthRepository.logout() clears the session
        // without emitting AuthEvent.LoggedOut — that event is reserved for a token REFRESH
        // failing, not a user-initiated sign-out with a perfectly valid session — so AuthGate's
        // reactive collector above never fires for this path. Reusing navigateToAuthClearingStack()
        // here, rather than a plain push, is what keeps Back from returning to a profile screen
        // whose session is already gone: the exact same failure mode AuthGate's own use of it
        // exists to prevent, reached by a second door.
        is AuthRoute -> navigateToAuthClearingStack()

        else -> navigate(route)
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

/**
 * The reverse trip: reached only via `authEntry`'s `onNavigate(LibraryRoute)` after a successful
 * login or registration. `popUpTo<AuthRoute>` rather than a plain push, so Back does not return
 * to a login form that already succeeded — the type-safe overload is available here (unlike
 * [navigateToAuthClearingStack]'s graph-id form) because `AuthRoute` is always a real destination
 * on the stack at this point, never the graph's own possibly-routeless root.
 */
internal fun NavHostController.navigateToLibraryClearingAuth() {
    navigate(LibraryRoute) {
        popUpTo<AuthRoute> { inclusive = true }
    }
}
