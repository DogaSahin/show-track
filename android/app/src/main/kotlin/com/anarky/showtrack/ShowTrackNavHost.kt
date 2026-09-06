package com.anarky.showtrack

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.AuthEvent
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.GroupsRoute
import com.anarky.showtrack.core.navigation.ImportRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
 * `NavGraph` on EVERY recomposition, built fresh from whatever `startDestination`
 * [startDestinationFor] currently answers — `NavController.setGraph` resets the back stack to the
 * new graph's start destination whenever the incoming graph is unequal to the one already
 * installed (`NavGraph.equals` compares `startDestinationId`). `AppViewModel.markSignedIn(isNewAccount)`
 * is what turns that machinery into the fix for the `popUpTo` bug: it flips `start` from `Auth` to
 * `Library` (or, task 9b.6, to `Onboarding` for a fresh registration — the identical mechanism, one
 * more decided value; see [AppViewModel.markSignedIn]'s own KDoc), [startDestinationFor] then
 * answers the matching route, and the graph that gets re-supplied genuinely has that route as its
 * start destination from then on — for the rest of the `AppViewModel`
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
    activeGroupViewModel: ActiveGroupViewModel = hiltViewModel(),
) {
    AuthGate(authEvents = authEvents, onLoggedOut = navController::navigateToAuthClearingStack)

    when (val start = appViewModel.start.collectAsStateWithLifecycle().value) {
        AppStart.Undecided -> LoadingState(modifier = modifier)
        AppStart.Auth, AppStart.Library, AppStart.Onboarding ->
            ShowTrackGraph(
                navController = navController,
                startDestination = startDestinationFor(start),
                onSignedIn = appViewModel::markSignedIn,
                activeGroup = activeGroupViewModel.state,
                onSwitchGroup = activeGroupViewModel::selectGroup,
                onRetryGroups = activeGroupViewModel::refresh,
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
 * Both `when`s here are genuinely exhaustive over [AppStart]'s four cases, deliberately, not an
 * `if`/`else` or an `else ->` branch (task 9b.0, review round 3, finding 3: an early version used
 * both, which meant a fourth `AppStart` value would have compiled clean and silently started at
 * `AuthRoute`) — [AppStart.Onboarding] (task 9b.6 fix round) is exactly that fourth value, and
 * both `when`s below needed a real branch for it rather than compiling by accident. [ShowTrackNavHost]
 * never calls this function for [AppStart.Undecided] — that value renders [LoadingState] instead
 * of building a graph at all — so its branch here `error`s rather than silently answering
 * something: a caller that reaches it is calling this out of the one context it is meant for, and
 * should fail loudly instead of picking a default.
 */
internal fun startDestinationFor(start: AppStart): AppRoute =
    when (start) {
        AppStart.Auth -> AuthRoute
        AppStart.Library -> LibraryRoute
        AppStart.Onboarding -> ImportRoute
        AppStart.Undecided -> error("startDestinationFor is not meaningful for AppStart.Undecided")
    }

/**
 * Round 2 (task 9b.6 fix round) REMOVED the `start: AppStart` parameter this composable used to
 * thread into [routeShowTrackNavigation] — see that function's own KDoc for why `start` was the
 * wrong signal for the thing it was being used to decide, and what replaced it. Nothing here needs
 * `start` any more; [startDestination] (already derived from it, one layer up) is all this
 * composable's `NavHost` call needs.
 */
@Suppress("LongParameterList")
@Composable
private fun ShowTrackGraph(
    navController: NavHostController,
    startDestination: AppRoute,
    onSignedIn: (Boolean) -> Unit,
    activeGroup: StateFlow<ActiveGroupState>,
    onSwitchGroup: (String) -> Unit,
    onRetryGroups: () -> Unit,
    modifier: Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        showTrackDestinations(
            onNavigate = { route -> navController.routeShowTrackNavigation(route, onSignedIn) },
            activeGroup = activeGroup,
            onSwitchGroup = onSwitchGroup,
            onRetryGroups = onRetryGroups,
        )
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
 *
 * **Round 1 (task 9b.6 fix round) found:** `LibraryRoute` and `ImportRoute` are BOTH reachable from
 * a session that is already fully signed in, not only from `AuthRoute` — Profile's own door to
 * `ImportRoute` (`ProfileNavigation.kt`'s `importNavigation`), and `ImportScreen`'s own skip/Done
 * action routing back to `LibraryRoute` (`ImportNavigation.kt`'s `importFinishedNavigation`) — so
 * neither branch can unconditionally treat "I was asked to go there" as "a sign-in just completed".
 *
 * **Round 1's fix was wrong, and round 2 replaced it.** Round 1 added a `start: AppStart` parameter
 * and used `start == AppStart.Library` to mean "already signed in, this must be the Profile door".
 * That is false: [AppViewModel.markSignedIn] is documented ONE-WAY and never moves `start` back to
 * `Auth` on a runtime sign-out (`navigateToAuthClearingStack` handles sign-out entirely through
 * navigation) — so after Profile → sign out → sign back in, `start` is STILL `Library` at the exact
 * moment this function routes the fresh login's `onNavigate(LibraryRoute)`, and round 1's guard
 * read that as "the Profile door returning" and called [popBackStack] on a stack that had nothing
 * to pop TO (`[null, AuthRoute]`), leaving the app on an empty stack — a regression measurably worse
 * than the bug round 1 fixed, on a path (sign out, sign back in) that worked before this task
 * touched anything. The same premise error left a second registration after a sign-out unpromoted
 * (`start` stuck at `Library`, never reaching `Onboarding`), so a rotation mid-onboarding on that
 * second account pulled the user off `ImportRoute`.
 *
 * **Round 2's fix reads the back stack instead of `start`**, because the back stack is ground truth
 * about what screen is actually on top and what is underneath it, and `start` — a session-progress
 * value — is not a reliable proxy for that once a sign-out/sign-in cycle can leave it ahead of
 * reality:
 *
 * - `LibraryRoute`: pops (via [popBackStack]) only when [NavHostController.currentDestination] is
 *   ALREADY `ImportRoute` and [NavHostController.previousBackStackEntry] is non-null. The second
 *   condition is what makes this safe rather than merely no-longer-wrong: `previousBackStackEntry`
 *   walks the back stack skipping the top entry and returns the first entry whose OWN destination
 *   is not itself a `NavGraph` (AndroidX Navigation's own implementation, confirmed by test, not
 *   assumed) — so onboarding's stack (`[null, ImportRoute]`, nothing but the graph's own root
 *   beneath `ImportRoute`) answers `null` and takes the `else` branch below, while the Profile
 *   door's stack (`[null, LibraryRoute, ProfileRoute, ImportRoute]`) answers `ProfileRoute` and
 *   pops. Every other case — a login from `AuthRoute`, onboarding finishing from `ImportRoute` with
 *   nothing under it, a login reached AFTER a sign-out (current destination `AuthRoute`, not
 *   `ImportRoute`) — takes the `else` branch: [navigateToLibraryClearingAuth] plus
 *   `onSignedIn(false)`, restoring exactly the `5a7d536` (pre-task-9b.6) behaviour for the
 *   sign-out/sign-in path this parameter's earlier design broke.
 * - `ImportRoute`: promotes (`onSignedIn(true)`) only when [NavHostController.currentDestination]
 *   is `AuthRoute` at the moment this fires — i.e. only when the navigation is genuinely arriving
 *   FROM the login/register screen, regardless of what `start` currently holds. This is what fixes
 *   the second registration-after-sign-out case above: `start` being stuck at `Library` from a
 *   prior session no longer matters, because the check no longer looks at it. Profile's door reaches
 *   this same branch with `currentDestination == ProfileRoute`, so it never promotes — no change in
 *   OUTCOME from round 1's guard for that case, only in how it is decided.
 *
 * [AppViewModel.markSignedIn] carries NO guard of its own — an earlier version of this KDoc
 * (round 2) claimed one, "belt and braces" with the `currentDestination == AuthRoute` check above,
 * and that claim was false. Round 2 considered exactly that guard and REVERTED it: it is unsound,
 * because a second registration reached after a sign-out legitimately calls `markSignedIn(true)`
 * while `start` already reads `Library` (a sign-out never moves `start` backward), which a
 * `start`-based guard inside that function cannot tell apart from the demotion bug it would be
 * trying to prevent — see [AppViewModel.markSignedIn]'s own KDoc for the measured conflict. The
 * `currentDestination == AuthRoute` check immediately above is therefore the ONLY guard against
 * that demotion, not one half of two.
 *
 * **`GroupsRoute` (task 9c.2, revised round 1)** — `:feature:groups`' `groupDetailEntry` reaches
 * this branch via `onNavigate(GroupsRoute)` when a member leaves a group (`GroupsNavigation.kt`'s
 * `leaveNavigation`). Round 0 mirrored `LibraryRoute`/`ImportRoute`'s pop-vs-push shape exactly —
 * infer "returning" from `currentDestination == GroupDetailRoute` plus something beneath it, then
 * pop blind. Round 1 review found that shape unsound HERE specifically: `LibraryRoute`/`ImportRoute`
 * each have exactly one door (`ProfileRoute`), so "something is beneath me" and "that something is
 * the right screen" are the same fact for them. `GroupDetailRoute` does not have that guarantee —
 * 9c.4 is expected to let a feed item route into it directly — so the blind pop would land a leave
 * reached that way on Feed while reading as a return to Groups. The branch below instead asks the
 * BACK STACK directly, via `popBackStack<GroupsRoute>(inclusive = false)`: pop up to (not
 * including) the nearest real `GroupsRoute` wherever it is, or push an ordinary new one if there
 * is none. Popping back to an EXISTING `GroupsRoute` rather than pushing a second one is what keeps
 * Back from returning to the just-left group's own (now stale, and for a non-member, now 404ing)
 * detail screen — the same outcome round 0 wanted, reached by reading the stack instead of guessing
 * what is on it.
 */
internal fun NavHostController.routeShowTrackNavigation(
    route: AppRoute,
    onSignedIn: (Boolean) -> Unit = {},
) {
    when (route) {
        is LibraryRoute -> {
            val returningFromImport =
                currentDestination?.hasRoute(ImportRoute::class) == true && previousBackStackEntry != null
            if (returningFromImport) {
                // The Profile door's import screen returning (skip or Done) — see this
                // function's own KDoc. A pop, not a push: lands back on whatever screen sent the
                // user to Import (Profile), rather than stacking a second Library underneath it.
                popBackStack()
            } else {
                navigateToLibraryClearingAuth()
                onSignedIn(false)
            }
        }

        is ImportRoute -> {
            val comingFromAuth = currentDestination?.hasRoute(AuthRoute::class) == true
            navigateToImportClearingAuth()
            if (comingFromAuth) onSignedIn(true)
        }

        is GroupsRoute -> {
            // Round 1 review, minor 2: reads the STACK for an actual GroupsRoute entry rather than
            // inferring one is there from "am I on GroupDetailRoute with something beneath me" —
            // the round 0 shape mirrored LibraryRoute/ImportRoute's own precedent faithfully, but
            // that precedent's "something beneath me" is ALWAYS the door that pushed it (Profile,
            // in that pair's case); GroupDetailRoute has no such single door once 9c.4 lets a feed
            // item route into it directly, and blind-popping in that shape would land the user on
            // Feed while believing they returned to Groups. `popBackStack<GroupsRoute>(inclusive =
            // false)` pops up to (not including) the nearest actual GroupsRoute wherever it is on
            // the stack and returns `false` if none exists, so a route that never went through
            // Groups falls through to an ordinary push instead of popping to the wrong screen.
            val poppedToGroups = popBackStack<GroupsRoute>(inclusive = false)
            if (!poppedToGroups) {
                navigate(route)
            }
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
 * The reverse trip: reached via `authEntry`'s `onNavigate(LibraryRoute)` after a successful login
 * (a fresh registration goes to [navigateToImportClearingAuth] instead — see that function's
 * KDoc), and via `importFinishedNavigation`'s `onNavigate(LibraryRoute)` when onboarding finishes.
 * `popUpTo<AuthRoute>` rather than a plain push, so Back does not return to a login form that
 * already succeeded — the type-safe overload is available here (unlike [navigateToAuthClearingStack]'s
 * graph-id form) because `AuthRoute` is a real destination on the stack the first time this runs
 * (a login); it is a harmless no-op the second (onboarding finishing — `AuthRoute` was already
 * popped when the session first moved to `ImportRoute`, so there is nothing left to find, and
 * `popUpTo` on an absent destination degrades to an ordinary push rather than throwing — confirmed
 * empirically, not assumed: see `ShowTrackGraphRoutingTest`'s
 * `` `routing on to LibraryRoute after Onboarding is an ordinary push` ``).
 *
 * This function pops `AuthRoute` off the back stack — the imperative half of the fix. It does
 * NOT touch `NavGraph.startDestinationId` (an earlier version of this function did, via
 * `graph.setStartDestination(LibraryRoute)`, and a review caught that the mutation does not
 * survive an Activity recreation: `NavGraph` state lives in the composition, and a rotation right
 * after login rebuilds it from `ShowTrackNavHost`'s declared `startDestination`, silently
 * reverting to `AuthRoute` and reintroducing the exact bug the mutation existed to fix). The
 * declarative half — making `LibraryRoute` the graph's own recorded start destination, for real,
 * across recreation — is [AppViewModel.markSignedIn], called by [routeShowTrackNavigation] right
 * after this function returns, for BOTH cases above (`isNewAccount = false` either way — see that
 * function's KDoc for why one parameter value covers login and onboarding-finishing alike). See
 * [ShowTrackNavHost]'s KDoc for the full mechanism, and [routeShowTrackNavigation]'s own KDoc for
 * the THIRD case that reaches `LibraryRoute` through the routing table without calling this
 * function at all — the Profile door's `ImportRoute` returning via a plain [NavHostController.popBackStack].
 */
internal fun NavHostController.navigateToLibraryClearingAuth() {
    navigate(LibraryRoute) {
        popUpTo<AuthRoute> { inclusive = true }
    }
}

/**
 * The same trip as [navigateToLibraryClearingAuth], for a fresh registration (task 9b.6):
 * `AuthRoute` must not survive on the back stack under the import screen either, for the
 * identical reason — Back from `ImportRoute` must not return to a login form that already
 * succeeded.
 *
 * `ImportScreen`'s own skip action, and its terminal success screen's "Done" button, both then
 * navigate on to `LibraryRoute` — but NOT simply "through the ordinary `LibraryRoute` branch
 * above" the way an earlier version of this KDoc claimed. That claim was the round-0 bug: a plain
 * push onto `[null, ImportRoute]` produces `[null, ImportRoute, LibraryRoute]`, and separately
 * calling `onSignedIn` to promote `AppViewModel.start` from `Onboarding` to `Library` causes
 * `NavHost` to re-supply a graph whose declared start is now `LibraryRoute` — DIFFERENT from the
 * `ImportRoute` start the currently-installed graph has — so `NavController.setGraph`'s
 * graph-inequality branch resets the back stack to `[null, LibraryRoute]` on the very next
 * recomposition, regardless of what the explicit push just did or which of the two calls ran
 * first. The imperative push is therefore not wasted work exactly, but it is not what produces
 * the final shape either — the declarative reset is, and the two are DESIGNED to converge on the
 * same destination rather than race, the same way `navigateToLibraryClearingAuth`'s push and
 * `markSignedIn`'s `Auth`→`Library` promotion already do for an ordinary login. See
 * [routeShowTrackNavigation]'s KDoc for the full three-way split this destination is now part of,
 * and `ShowTrackGraphRebuildTest`'s `` `finishing onboarding converges on the shape a login already
 * produces` `` for the composed, end-to-end proof.
 */
internal fun NavHostController.navigateToImportClearingAuth() {
    navigate(ImportRoute) {
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
