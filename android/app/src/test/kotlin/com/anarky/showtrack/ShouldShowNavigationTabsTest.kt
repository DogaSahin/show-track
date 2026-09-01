package com.anarky.showtrack

import android.app.Application
import android.content.Context
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.FavoritesRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard for the bug where the bottom nav bar stayed hidden for an entire process after
 * a logged-out cold start: `shouldShowNavigationTabs` used to compare `start == AppStart.Library`,
 * which is true only for a `Library`-started session and stays false forever once a session starts
 * on `AuthRoute` and then logs in — `start` is a one-shot emission (`AppViewModel`'s KDoc) and is
 * never re-evaluated. This pins the fixed condition directly, without composing `ShowTrackApp`
 * (which needs a Hilt harness this module does not have) — same Robolectric-NavController setup
 * `ShowTrackGraphRoutingTest`/`AuthNavigationTest` already use, so a real `NavDestination` (which
 * needs a `Context` to parse its route) is available to pass in.
 *
 * **Pinned:** the truth table `shouldShowNavigationTabs` is built from — `Undecided`/decided
 * crossed with on-`AuthRoute`/elsewhere/`null` — including the exact regression case (decided +
 * not-`AuthRoute`, reached via an `Auth`-started session that navigated to `Library`) AND the
 * `null`-destination case: `currentBackStackEntryAsState()` seeds at `null`
 * (`collectAsState(null)`) before the back-stack flow's first emission, a window every cold start
 * passes through, `Library`-started ones included. A condition using `!= true` instead of
 * `== false` reads `null?.hasRoute(...) != true` as `true` and shows the tab bar over the login
 * screen for that window — a real regression this test file did not catch the first time round
 * because nothing here passed `null` at all.
 *
 * **Not pinned:** the `popUpTo` back-stack-stacking follow-on noted in `shouldShowNavigationTabs`'s
 * KDoc — that needs an on-device check, not a Robolectric one. Nor, more fundamentally, can this
 * file catch the class of bug `shouldShowNavigationTabs` has actually had TWICE: it drives static
 * cells of a truth table, one call at a time, and both regressions here were ordering/staleness
 * bugs — a value that was correct in isolation but wrong at the moment it was read relative to
 * something else changing. A full truth table passes trivially against a bug like that; only
 * composing `ShowTrackApp` across real recompositions would catch it, and `:app` has no Hilt
 * harness to do so. Treat every green run of this file as "the function is internally consistent",
 * never as "the bug class is covered".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShouldShowNavigationTabsTest {
    @Test
    fun `hidden while start is undecided, even off AuthRoute`() {
        val destination = controllerWith { defaultGraph() }.currentDestination

        assertFalse(shouldShowNavigationTabs(AppStart.Undecided, destination))
    }

    /**
     * `(Undecided, null)` is the MOST-executed cell of the nine: it's the real first composition
     * pass of every single launch, cold or warm — `start` reads `Undecided` before `AppViewModel`'s
     * one-shot flow has emitted, and no graph exists yet for `currentBackStackEntryAsState()` to
     * read a destination from, so it's still at its `collectAsState(null)` seed. Every other cell
     * in this file is driven off a real, already-built `NavHostController`; this one — despite being
     * the one everything else starts from — had no test at all before this was added. `(Undecided,
     * AuthRoute)` alongside it costs nothing extra and rules out the other plausible early value.
     */
    @Test
    fun `hidden on the real first composition pass — Undecided with no destination yet`() {
        assertFalse(shouldShowNavigationTabs(AppStart.Undecided, null))

        val authDestination = controllerWith { authOnlyGraph() }.currentDestination
        assertFalse(shouldShowNavigationTabs(AppStart.Undecided, authDestination))
    }

    @Test
    fun `hidden on AuthRoute once start has resolved`() {
        val destination = controllerWith { authOnlyGraph() }.currentDestination

        assertFalse(shouldShowNavigationTabs(AppStart.Auth, destination))
        assertFalse(shouldShowNavigationTabs(AppStart.Library, destination))
    }

    /**
     * The boundary a review round found live: `currentBackStackEntryAsState()` is
     * `currentBackStackEntryFlow.collectAsState(null)`, so it SEEDS at `null` — before any graph
     * exists — and stays `null` through the composition pass where `start` first flips off
     * `Undecided`. `null` must read the same as `Undecided`: "we don't know where we are yet" is
     * not "provably not `AuthRoute`". The original `!= true` phrasing got this wrong (`null !=
     * true` is `true`); `== false` requires an actual, known, non-`AuthRoute` destination.
     */
    @Test
    fun `hidden when the destination is null, even once start has resolved`() {
        assertFalse(shouldShowNavigationTabs(AppStart.Auth, null))
        assertFalse(shouldShowNavigationTabs(AppStart.Library, null))
    }

    @Test
    fun `shown once start has resolved and the current destination is not AuthRoute`() {
        val destination = controllerWith { defaultGraph() }.currentDestination

        assertTrue(shouldShowNavigationTabs(AppStart.Library, destination))
    }

    @Test
    fun `shown after logging in from an Auth-started session — the fixed regression`() {
        val controller = controllerWith { authOnlyGraph() }
        controller.routeShowTrackNavigation(LibraryRoute)

        // `start` never flips off `Auth` for the rest of the process (it is one-shot), yet the
        // tabs must now be visible: the current destination is what changed.
        assertTrue(shouldShowNavigationTabs(AppStart.Auth, controller.currentDestination))
    }

    @Test
    fun `shown as soon as navigation leaves AuthRoute, regardless of which route follows`() {
        val controller = controllerWith { authOnlyGraph() }

        assertFalse(shouldShowNavigationTabs(AppStart.Auth, controller.currentDestination))

        controller.navigate(FavoritesRoute)

        // Unreachable in practice (Favorites is behind the auth gate), but confirms the guard is
        // driven by the destination, not an assumption about which routes follow AuthRoute.
        assertTrue(shouldShowNavigationTabs(AppStart.Auth, controller.currentDestination))
    }

    private fun controllerWith(graph: NavHostController.() -> NavGraph): NavHostController =
        NavHostController(ApplicationProvider.getApplicationContext<Context>())
            .apply {
                navigatorProvider.addNavigator(ComposeNavigator())
                this.graph = graph()
            }

    private fun NavHostController.defaultGraph() =
        createGraph(startDestination = LibraryRoute) { showTrackDestinations(onNavigate = { }) }

    private fun NavHostController.authOnlyGraph() =
        createGraph(startDestination = AuthRoute) { showTrackDestinations(onNavigate = { }) }
}
