package com.anarky.showtrack

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.FavoritesRoute
import com.anarky.showtrack.core.navigation.ImportRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The regression guard `TopLevelNavigationTest` cannot provide (task 9b.0, review round 1, finding
 * 1): that file assigns a hand-built `NavGraph` to a bare `NavHostController` directly, so
 * `NavController.setGraph` runs exactly once and its graph-inequality branch — `if
 * (!areEqual(_graph, graph)) { clearBackStackInternal(...); ... }`, where `NavGraph.equals`
 * compares `startDestinationId` — is never exercised. `NavHost` re-supplies a graph on EVERY
 * recomposition, built fresh from whatever `startDestination` the call site currently declares;
 * production's real mechanism (`ShowTrackNavHost`'s single call to `ShowTrackGraph`) is a REACTIVE
 * `startDestination`, not a one-off `NavGraph` assignment, and only composing a real `NavHost`
 * puts that branch under test at all.
 *
 * **Round 2 correction.** The first version of this file composed a real `NavHost`, which was the
 * point, but referenced NO production code the fix actually changed: it drove `startDestination`
 * from its own local `mutableStateOf`, inlined `navigate(LibraryRoute) { popUpTo<AuthRoute>(...)
 * }` instead of calling [navigateToLibraryClearingAuth], and never touched [AppViewModel],
 * [AppViewModel.markSignedIn], or [routeShowTrackNavigation]. Deleting `markSignedIn`/`onSignedIn`
 * entirely left that version compiling and passing — it pinned a navigation-library property, not
 * this fix. This version drives a REAL [AppViewModel] (constructed directly — no Hilt needed, same
 * technique `AppViewModelTest` uses) and calls [routeShowTrackNavigation] and [startDestinationFor]
 * directly, so every non-Hilt-gated piece of the actual fix is exercised for real. Only the
 * Hilt-resolved screens themselves ([libraryEntry]/[authEntry], via `hiltViewModel()`) are stood in
 * for by [MarkerGraph]'s bare destinations — `:app` still has no Hilt test harness (only
 * `:feature:library` gained one, in this task's first commit), and `ShowTrackGraphRoutingTest`'s
 * `` `routing to LibraryRoute fires onSignedIn` `` spy test is the direct, fast proof that
 * [routeShowTrackNavigation] actually invokes what it's handed — this file's job is the REACTION to
 * that invocation, at the `NavHost` level, which a spy cannot see.
 *
 * [MarkerGraph] mirrors [ShowTrackGraph]'s (now single) call-site shape exactly — a `NavHost` whose
 * `startDestination` is an external, reactive parameter.
 *
 * One more gap worth naming rather than papering over: this file reads `appViewModel.start` via
 * plain `collectAsState()`, where production ([ShowTrackNavHost]) uses
 * `collectAsStateWithLifecycle()`. A reasonable simplification for a test with no real Activity
 * lifecycle to gate on, but it does mean this file cannot see a failure that is specifically
 * lifecycle-shaped — a recomposition that `collectAsStateWithLifecycle()` suppresses or defers
 * while the host is `STOPPED`, for instance, would look identical here to one that fires
 * immediately. Nothing in this fix depends on that distinction, but a future change to how
 * [ShowTrackNavHost] collects `start` would not be caught by this file.
 *
 * No Hilt, so no `HiltTestApplication` either: `application = Application::class` below overrides
 * the manifest's `ShowTrackApplication` (`@HiltAndroidApp`, with `@Inject lateinit var` fields
 * Robolectric cannot satisfy outside a real Hilt component) with the plain Android one — same as
 * `AuthNavigationTest`/`ShowTrackGraphRoutingTest`/`ShouldShowNavigationTabsTest`. `:app` has no
 * `robolectric.properties`, so `sdk` is pinned per class here too, matching the rest of this module.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShowTrackGraphRebuildTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The mechanism itself, driven end to end through production code: [routeShowTrackNavigation]
     * dispatches `LibraryRoute` to [navigateToLibraryClearingAuth] (the imperative pop-and-push)
     * and then to `onSignedIn` — here, a REAL `AppViewModel.markSignedIn`, not a spy — which flips
     * a REAL `AppViewModel.start` from `Auth` to `Library`. That StateFlow change recomposes this
     * test's content, [startDestinationFor] answers `LibraryRoute`, `NavHost` re-supplies a graph
     * that genuinely declares it, and `NavController.setGraph`'s graph-inequality branch fires.
     *
     * The claim under test: the imperative navigate and the declarative re-point converge on the
     * SAME shape — `[graph, LibraryRoute]` — rather than fighting or duplicating each other. That
     * is the reviewer's own ruling on the cost of this fix: "the cost is a stack reset at the exact
     * moment the code already resets the stack."
     */
    @Test
    fun `logging in from an Auth-started session converges on the shape login already produces`() {
        val appViewModel = AppViewModel(FakeAuthRepository(hasSession = false))
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            val start by appViewModel.start.collectAsState()
            navController = rememberTestNavController()
            if (start != AppStart.Undecided) {
                MarkerGraph(navController = navController, startDestination = startDestinationFor(start))
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            navController.routeShowTrackNavigation(LibraryRoute, AppStart.Auth, appViewModel::markSignedIn)
        }
        composeRule.waitForIdle()

        assertEquals(listOf(null, LibraryRoute::class.qualifiedName), navController.backStackRoutes())

        // Now that the graph genuinely declares LibraryRoute as its start destination,
        // navigateToTopLevelDestination's popUpTo(findStartDestination().id) has something real
        // to find — the exact bug this whole fix exists to close.
        navController.navigateToTopLevelDestination(FavoritesRoute)
        navController.navigateToTopLevelDestination(LibraryRoute)

        assertEquals(listOf(null, LibraryRoute::class.qualifiedName), navController.backStackRoutes())
    }

    /**
     * **B1/B2, task 9b.6 fix round.** This is the test the blind review demanded, and the one
     * round 0 shipped without: `ShowTrackGraphRoutingTest`'s bare-`NavHostController` harness
     * calls `NavController.setGraph` exactly once and can never exercise the graph-inequality reset
     * this whole mechanism depends on (see that file's own class KDoc). Only a composed `NavHost`,
     * recomposing across a REAL `start` change, can prove the onboarding door actually lands the
     * user on `ImportRoute` and STAYS there.
     *
     * Two moves, both through the real `routeShowTrackNavigation`/`AppViewModel.markSignedIn`:
     * a fresh registration (`ImportRoute`, `start = Auth` → `onSignedIn(true)` → `Onboarding`),
     * then `ImportScreen`'s own skip/Done action finishing onboarding (`LibraryRoute`,
     * `start = Onboarding` → `onSignedIn(false)` → `Library`). The claim under test for the FIRST
     * move is the one the blind review's probe measured failing before this fix: navigating to
     * `ImportRoute` must not be wiped by the very `onSignedIn` call that promotes the session,
     * because `start` now agrees with `ImportRoute` as its destination rather than jumping straight
     * to `Library`. The SECOND move is the mirror of `logging in from an Auth-started session
     * converges on the shape login already produces` above — the exact convergence that test
     * documents, reached from `Onboarding` instead of `Auth`.
     */
    @Test
    fun `finishing onboarding converges on the shape a login already produces`() {
        val appViewModel = AppViewModel(FakeAuthRepository(hasSession = false))
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            val start by appViewModel.start.collectAsState()
            navController = rememberTestNavController()
            if (start != AppStart.Undecided) {
                MarkerGraph(navController = navController, startDestination = startDestinationFor(start))
            }
        }
        composeRule.waitForIdle()

        // A fresh registration: AuthNavigation's onAuthenticated routes here with isNewAccount = true.
        composeRule.runOnIdle {
            navController.routeShowTrackNavigation(ImportRoute, AppStart.Auth, appViewModel::markSignedIn)
        }
        composeRule.waitForIdle()

        // The exact assertion the blind review's probe found false before this fix: navigating to
        // ImportRoute must SURVIVE the onSignedIn call that just promoted the session, not be
        // wiped by it on the very next recomposition.
        assertEquals(listOf(null, ImportRoute::class.qualifiedName), navController.backStackRoutes())
        assertEquals(AppStart.Onboarding, appViewModel.start.value)

        // ImportScreen's own skip/Done action routes on to LibraryRoute.
        composeRule.runOnIdle {
            navController.routeShowTrackNavigation(LibraryRoute, AppStart.Onboarding, appViewModel::markSignedIn)
        }
        composeRule.waitForIdle()

        assertEquals(listOf(null, LibraryRoute::class.qualifiedName), navController.backStackRoutes())
        assertEquals(AppStart.Library, appViewModel.start.value)
    }

    /**
     * The case the earlier `graph.setStartDestination(...)` mutation silently failed on (review
     * round 1, finding 1's "certain" consequence): an Activity recreation composes EVERYTHING
     * fresh — a new `NavHostController`, a new `NavGraph`, nothing carried over from what existed
     * before. What DOES survive recreation, in production, is `AppViewModel.start`'s value — it is
     * `viewModelScope`-held, not composition-held. Simulated here not by calling `setContent`
     * twice within one test (`ComposeContentTestRule.setContent` allows exactly one call per test
     * and throws on a second) — the fresh composition instead comes from this being a SEPARATE
     * `@Test` method: JUnit builds a new instance of this class, and therefore a new `composeRule`
     * and a new `AppViewModel`, for every test. A `Library`-signed-in `FakeAuthRepository` here
     * stands in for "the ViewModel survived recreation already knowing `Library`", and the
     * assertion is that a fresh composition declaring `startDestination = LibraryRoute` from its
     * first frame needs no help from any prior mutation to work correctly.
     */
    @Test
    fun `a fresh composition that already knows the session is signed in declares Library from the start`() {
        val appViewModel = AppViewModel(FakeAuthRepository(hasSession = true))
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            val start by appViewModel.start.collectAsState()
            navController = rememberTestNavController()
            if (start != AppStart.Undecided) {
                MarkerGraph(navController = navController, startDestination = startDestinationFor(start))
            }
        }
        composeRule.waitForIdle()

        navController.navigateToTopLevelDestination(FavoritesRoute)
        navController.navigateToTopLevelDestination(LibraryRoute)

        assertEquals(listOf(null, LibraryRoute::class.qualifiedName), navController.backStackRoutes())
    }

    @Composable
    private fun rememberTestNavController(): TestNavHostController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return remember {
            TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
        }
    }

    /**
     * [ShowTrackGraph]'s (private, unreachable from here) shape, reproduced with bare marker
     * destinations instead of the real `libraryEntry()`/`authEntry()` — those resolve
     * `@HiltViewModel`s through `hiltViewModel()`, and `:app` has no Hilt test harness. Registers
     * no `onNavigate` of its own: nothing inside an empty marker composable would ever call it: the
     * tests above drive [routeShowTrackNavigation] directly on [navController] instead, the same
     * way `ShowTrackGraphRoutingTest` already does.
     */
    @Composable
    private fun MarkerGraph(
        navController: TestNavHostController,
        startDestination: AppRoute,
    ) {
        NavHost(navController = navController, startDestination = startDestination) {
            composable<AuthRoute> { }
            composable<LibraryRoute> { }
            composable<FavoritesRoute> { }
            composable<ImportRoute> { }
        }
    }

    private fun NavHostController.backStackRoutes() =
        currentBackStack.value.map { entry -> entry.destination.route?.substringBefore('/') }

    private class FakeAuthRepository(
        private val hasSession: Boolean,
    ) : AuthRepository {
        override suspend fun hasSession(): Boolean = hasSession

        override suspend fun login(
            email: String,
            password: String,
        ): Unit = error("not exercised by ShowTrackGraphRebuildTest")

        override suspend fun register(
            username: String,
            email: String,
            password: String,
            inviteCode: String,
        ): Unit = error("not exercised by ShowTrackGraphRebuildTest")

        override suspend fun logout(): Unit = error("not exercised by ShowTrackGraphRebuildTest")
    }
}
