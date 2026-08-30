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
    AuthGate(authEvents = authEvents) {
        navController.navigate(AuthRoute) {
            // Load-bearing, not tidiness. Without it, `back` from the login screen returns to a
            // screen whose every request 401s: the app looks broken rather than logged out.
            // popUpTo(0) targets the graph root's id, so `inclusive = true` clears the entire
            // back stack including the start destination.
            popUpTo(0) { inclusive = true }
        }
    }

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
