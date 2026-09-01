package com.anarky.showtrack

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
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
 *
 * `start` is not merely read once, either. `NavHost` (inside [ShowTrackGraph]) re-supplies a
 * `NavGraph` on EVERY recomposition, built fresh from whatever `startDestination` this `when`
 * currently declares — `NavController.setGraph` resets the back stack to the new graph's start
 * destination whenever the incoming graph is unequal to the one already installed
 * (`NavGraph.equals` compares `startDestinationId`). `AppViewModel.markSignedIn()` is what turns
 * that machinery into the fix for the `popUpTo` bug: it flips `start` from `Auth` to `Library`,
 * [startDestinationFor] then answers `LibraryRoute`, and the graph that gets re-supplied genuinely
 * has `LibraryRoute` as its start destination from then on — for the rest of the `AppViewModel`
 * instance's life, Activity recreation included, since `start` is `viewModelScope`-held and
 * survives it while any composed `NavGraph` does not. A prior version of this fix mutated the
 * already-built graph's `startDestinationId` directly (`graph.setStartDestination(...)`) instead
 * of moving `start` — that mutation was invisible to a FRESH composition, so a rotation right
 * after login silently regressed to the exact bug this exists to fix. Moving `start` is what makes
 * the graph's declared shape, not a graph object's mutable state, the source of truth.
 *
 * ONE call to [ShowTrackGraph] below, not one per decided [AppStart] value — a review round (task
 * 9b.0, review round 2, finding 1) caught that two call sites meant the `Auth`→`Library` promotion
 * disposed an entire `NavHost` subtree and composed a fresh one (Compose treats each `when` branch
 * as a distinct call site for positional-memoization purposes), which is a bigger, less predictable
 * operation than the one `NavController.setGraph`'s own graph-inequality check already performs on
 * its own, and made the mechanism harder to test honestly — `ShowTrackGraphRebuildTest` can only
 * faithfully model what production actually does when production itself has one reactive call
 * site, not two static ones.
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
        else ->
            ShowTrackGraph(
                navController = navController,
                startDestination = startDestinationFor(start),
                onSignedIn = appViewModel::markSignedIn,
                modifier = modifier,
            )
    }
}

/**
 * The single source of truth for which route [ShowTrackGraph]'s `NavHost` declares as its start
 * destination. Pulled out to a plain, non-`@Composable` function — rather than left inline in the
 * `when` above — specifically so `ShowTrackGraphRebuildTest` can call the EXACT function production
 * uses instead of a hand-rolled stand-in for it (task 9b.0, review round 2, finding 1's "secondary"
 * point: the test used to model a simpler mechanism than production actually has).
 *
 * [AppStart.Undecided] is a valid input only in the sense that the compiler requires
 * exhaustiveness; [ShowTrackNavHost] never actually calls this for it — that value renders
 * [LoadingState] instead of building a graph at all.
 */
internal fun startDestinationFor(start: AppStart): AppRoute = if (start == AppStart.Library) LibraryRoute else AuthRoute

@Composable
private fun ShowTrackGraph(
    navController: NavHostController,
    startDestination: AppRoute,
    onSignedIn: () -> Unit,
    modifier: Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        showTrackDestinations(onNavigate = { route -> navController.routeShowTrackNavigation(route, onSignedIn) })
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
 *
 * [onSignedIn] defaults to a no-op so every existing bare-`NavHostController` test call site keeps
 * compiling unchanged; only [ShowTrackGraph] passes a real one (`AppViewModel::markSignedIn`).
 */
internal fun NavHostController.routeShowTrackNavigation(
    route: AppRoute,
    onSignedIn: () -> Unit = {},
) {
    when (route) {
        // Navigating TO LibraryRoute through this table only ever happens once, from
        // AuthNavigation on a successful login/register — nothing else in the app reaches Library
        // through onNavigate (it is a start destination, not a target other screens link to).
        // popUpTo<AuthRoute> there is load-bearing, not incidental: a plain push leaves Auth on
        // the back stack and Back returns to a login form that already succeeded. onSignedIn()
        // fires right after: it's what promotes AppViewModel.start to Library, which is what
        // makes the NEXT recomposition declare LibraryRoute as the graph's own start destination
        // (see ShowTrackNavHost's KDoc) — the actual fix for the popUpTo bug this comment used to
        // describe as fixed by a graph mutation one line below instead.
        is LibraryRoute -> {
            navigateToLibraryClearingAuth()
            onSignedIn()
        }

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
 *
 * This function pops `AuthRoute` off the back stack — the imperative half of the fix. It does
 * NOT touch `NavGraph.startDestinationId` (an earlier version of this function did, via
 * `graph.setStartDestination(LibraryRoute)`, and a review caught that the mutation does not
 * survive an Activity recreation: `NavGraph` state lives in the composition, and a rotation right
 * after login rebuilds it from `ShowTrackNavHost`'s declared `startDestination`, silently
 * reverting to `AuthRoute` and reintroducing the exact bug the mutation existed to fix). The
 * declarative half — making `LibraryRoute` the graph's own recorded start destination, for real,
 * across recreation — is [AppViewModel.markSignedIn], called by
 * [routeShowTrackNavigation] right after this function returns. See [ShowTrackNavHost]'s KDoc for
 * the full mechanism.
 */
internal fun NavHostController.navigateToLibraryClearingAuth() {
    navigate(LibraryRoute) {
        popUpTo<AuthRoute> { inclusive = true }
    }
}

/**
 * The tab bar's `onClick` navigation, pulled out of `ShowTrackApp` (`:app`'s `MainActivity.kt`) so
 * it is reachable by a plain `NavHostController` test — the same reason [navigateToAuthClearingStack]
 * and [navigateToLibraryClearingAuth] are named extensions rather than inline lambdas.
 * `popUpTo(findStartDestination().id)`, `launchSingleTop` and `restoreState` are the standard
 * top-level-destination options: they save/restore each tab's own back stack and scroll position,
 * and stop re-tapping the current tab from stacking a duplicate of itself.
 *
 * `findStartDestination().id` is trustworthy here once `AppViewModel.markSignedIn()` has run for
 * an `Auth`-started session (see [ShowTrackNavHost]'s KDoc for the mechanism) — this function
 * itself does nothing to guarantee that; it only reads whatever the currently-installed graph's
 * start destination is.
 */
internal fun NavHostController.navigateToTopLevelDestination(route: AppRoute) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
