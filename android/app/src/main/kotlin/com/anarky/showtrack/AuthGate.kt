package com.anarky.showtrack

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.anarky.showtrack.core.model.AuthEvent
import kotlinx.coroutines.flow.Flow

/**
 * Turns a terminal auth failure into a navigation. Nothing else in the app is allowed to decide
 * that the user is logged out — `TokenRefreshAuthenticator` discovers it, `AuthEventBus` carries
 * it, and this is where it becomes a screen change (decision A-J).
 *
 * Renders nothing: it is an effect with a composable's lifetime. Placed by [ShowTrackNavHost]
 * above the `NavHost` so the subscription outlives every destination.
 *
 * `LaunchedEffect(authEvents)` keys on the flow, not on `Unit`: the flow is a singleton in
 * practice, so this restarts never — but keying on `Unit` would silently keep collecting the
 * *old* flow if the parameter ever changed, which is the bug the key parameter exists to prevent.
 *
 * `rememberUpdatedState` around [onLoggedOut] is the other half of the same pitfall: the
 * coroutine captures the lambda from the composition that started it and would go on calling a
 * stale `navController` after a recomposition handed in a new one. This is the standard
 * "long-lived effect, short-lived callback" pattern — see
 * https://developer.android.com/develop/ui/compose/side-effects#rememberupdatedstate
 */
@Composable
internal fun AuthGate(
    authEvents: Flow<AuthEvent>,
    onLoggedOut: () -> Unit,
) {
    val currentOnLoggedOut by rememberUpdatedState(onLoggedOut)
    LaunchedEffect(authEvents) {
        authEvents.collect { event ->
            when (event) {
                AuthEvent.LoggedOut -> currentOnLoggedOut()
            }
        }
    }
}
