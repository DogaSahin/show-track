package com.anarky.showtrack

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.FavoritesRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The regression guard `TopLevelNavigationTest` cannot provide (task 9b.0, review finding 1): that
 * file assigns a hand-built `NavGraph` to a bare `NavHostController` directly, so
 * `NavController.setGraph` runs exactly once and its graph-inequality branch — `if
 * (!areEqual(_graph, graph)) { clearBackStackInternal(...); ... }`, where `NavGraph.equals`
 * compares `startDestinationId` — is never exercised. `NavHost` re-supplies a graph on EVERY
 * recomposition, built fresh from whatever `startDestination` the call site currently declares;
 * production's real mechanism (`ShowTrackNavHost`'s `when (start) { … }`) is a REACTIVE
 * `startDestination`, not a one-off `NavGraph` assignment, and only composing a real `NavHost`
 * puts that branch under test at all.
 *
 * [MarkerGraph] mirrors [ShowTrackGraph]'s shape exactly — a `NavHost` whose `startDestination` is
 * an external parameter — but registers bare marker destinations instead of the real
 * `libraryEntry()`/`authEntry()`: those resolve `@HiltViewModel`s through `hiltViewModel()`, and
 * `:app` has no Hilt test harness (only `:feature:library` gained one this task — see
 * `LibraryEntryHiltTest`). What's under test here is `NavController.setGraph`'s own behaviour when
 * the DECLARED start destination changes, which needs no ViewModel at all to observe.
 *
 * No Hilt, so no `HiltTestApplication` either: `application = Application::class` below
 * overrides the manifest's `ShowTrackApplication` (`@HiltAndroidApp`, with `@Inject lateinit var`
 * fields Robolectric cannot satisfy outside a real Hilt component) with the plain Android one —
 * same as `AuthNavigationTest`/`ShowTrackGraphRoutingTest`/`ShouldShowNavigationTabsTest`.
 * `:app` has no `robolectric.properties`, so `sdk` is pinned per class here too, matching the
 * rest of this module.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShowTrackGraphRebuildTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The mechanism itself: what `AppViewModel.markSignedIn()` now triggers in production. The
     * imperative half (`navigateToLibraryClearingAuth`'s `navigate(LibraryRoute) {
     * popUpTo<AuthRoute>(inclusive = true) }`) and the declarative half (flipping the composed
     * `startDestination` from `AuthRoute` to `LibraryRoute`, which is what `markSignedIn()` does
     * one layer up) both run here, in the order production runs them, against a REAL `NavHost`.
     *
     * The claim under test: the two converge to the SAME shape — `[graph, LibraryRoute]` — that
     * the imperative navigate alone already produces, i.e. the declarative re-point does not
     * fight or duplicate what the navigate already did. That is the reviewer's own ruling on the
     * cost of this fix: "the cost is a stack reset at the exact moment the code already resets
     * the stack."
     */
    @Test
    fun `promoting the declared start destination converges on the shape login already produces`() {
        lateinit var navController: TestNavHostController
        lateinit var promoteToLibrary: () -> Unit

        composeRule.setContent {
            var startDestination by remember { mutableStateOf<AppRoute>(AuthRoute) }
            promoteToLibrary = { startDestination = LibraryRoute }
            navController = rememberTestNavController()
            MarkerGraph(navController = navController, startDestination = startDestination)
        }

        composeRule.runOnIdle {
            navController.navigate(LibraryRoute) {
                popUpTo<AuthRoute> { inclusive = true }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { promoteToLibrary() }
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
     * The case the earlier `graph.setStartDestination(...)` mutation silently failed on
     * (review finding 1's "certain" consequence): an Activity recreation composes EVERYTHING
     * fresh — a new `NavHostController`, a new `NavGraph`, nothing carried over from the graph
     * object that existed before. What DOES survive recreation, in production, is
     * `AppViewModel.start`'s value — it is `viewModelScope`-held, not composition-held.
     * `composeRule.setContent` a second time tears down the whole composition and builds a new
     * one from scratch, the closest a Robolectric JVM test gets to that recreation without an
     * actual configuration change. This proves the fix no longer depends on anything surviving
     * that a real rotation would in fact discard.
     */
    @Test
    fun `a fresh composition that already knows the session is signed in declares Library from the start`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController = rememberTestNavController()
            MarkerGraph(navController = navController, startDestination = LibraryRoute)
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

    @Composable
    private fun MarkerGraph(
        navController: TestNavHostController,
        startDestination: AppRoute,
    ) {
        NavHost(navController = navController, startDestination = startDestination) {
            composable<AuthRoute> { }
            composable<LibraryRoute> { }
            composable<FavoritesRoute> { }
        }
    }

    private fun NavHostController.backStackRoutes() =
        currentBackStack.value.map { entry -> entry.destination.route?.substringBefore('/') }
}
