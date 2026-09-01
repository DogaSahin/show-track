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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The regression guard for the bug `navigateToLibraryClearingAuth`'s KDoc documents in full: an
 * `Auth`-started graph's `NavGraph.startDestinationId` stayed `AuthRoute`'s even after `AuthRoute`
 * was popped off by a successful login, so every tab's `popUpTo(findStartDestination().id)` —
 * `navigateToTopLevelDestination`, the tab bar's `onClick` pulled out of `ShowTrackApp` for
 * exactly this reason — matched nothing on the stack and popped nothing. Tabs stacked instead of
 * swapping, without bound, on every session that started logged out.
 *
 * Same Robolectric-`NavHostController` setup `AuthNavigationTest`/`ShowTrackGraphRoutingTest`
 * already use: composing `ShowTrackApp` itself would need Hilt, which `:app` still has no harness
 * for (only `:feature:library` gained one this task), so this drives the extracted navigation
 * functions directly on a bare controller instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TopLevelNavigationTest {
    @Test
    fun `tapping Home after Favorites swaps rather than stacks on a Library-started graph`() {
        val controller = controllerWith { defaultGraph() }

        controller.navigateToTopLevelDestination(FavoritesRoute)
        controller.navigateToTopLevelDestination(LibraryRoute)

        assertEquals(listOf(null, LibraryRoute::class.qualifiedName), controller.backStackRoutes())
    }

    /**
     * The regression itself. Before `navigateToLibraryClearingAuth` re-pointed the graph's start
     * destination at `LibraryRoute`, this exact sequence left a FOUR-entry stack —
     * `[root, Library, Favorites, Library]` — because `popUpTo(findStartDestination().id)` kept
     * naming `AuthRoute`, which was no longer anywhere on the stack, on both tab taps.
     */
    @Test
    fun `tapping Home after Favorites swaps rather than stacks once an Auth-started session has logged in`() {
        val controller = controllerWith { authOnlyGraph() }
        controller.navigateToLibraryClearingAuth()

        controller.navigateToTopLevelDestination(FavoritesRoute)
        controller.navigateToTopLevelDestination(LibraryRoute)

        assertEquals(listOf(null, LibraryRoute::class.qualifiedName), controller.backStackRoutes())
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

    private fun NavHostController.backStackRoutes() =
        currentBackStack.value.map { entry -> entry.destination.route?.substringBefore('/') }
}
