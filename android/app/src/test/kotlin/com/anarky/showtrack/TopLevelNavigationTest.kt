package com.anarky.showtrack

import android.app.Application
import android.content.Context
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.navigation.FavoritesRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `navigateToTopLevelDestination`'s own mechanics — `popUpTo(findStartDestination().id)`,
 * `launchSingleTop`, `restoreState` — pinned against a graph that is ALREADY correctly shaped
 * (`startDestinationId` genuinely on the stack), which is what a bare `NavHostController` with a
 * hand-built `NavGraph` assigned directly can represent.
 *
 * What this file does NOT, and cannot, cover: the `Auth`-started-then-promoted case. A review
 * round (task 9b.0, finding 1) caught that assigning a `NavGraph` to a bare controller only calls
 * `NavController.setGraph` ONCE — the branch that matters for that regression
 * (`!areEqual(_graph, graph)`, comparing `NavGraph.startDestinationId`, firing when a DIFFERENT
 * graph is re-supplied) is never exercised here, because nothing here ever re-supplies a second
 * graph. `ShowTrackGraphRebuildTest` composes a real `NavHost` — the only way to make `setGraph`
 * run more than once — and is the actual regression guard for that case; a version of this file
 * that tried to fake it by calling `navigateToLibraryClearingAuth()` against a bare controller
 * would be pinning code that no longer does what it used to (see that function's KDoc).
 *
 * Same Robolectric-`NavHostController` setup `AuthNavigationTest`/`ShowTrackGraphRoutingTest`
 * already use: composing `ShowTrackApp` itself would need Hilt, which `:app` still has no harness
 * for (only `:feature:library` gained one this task).
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

    private fun controllerWith(graph: NavHostController.() -> NavGraph): NavHostController =
        NavHostController(ApplicationProvider.getApplicationContext<Context>())
            .apply {
                navigatorProvider.addNavigator(ComposeNavigator())
                this.graph = graph()
            }

    private fun NavHostController.defaultGraph() =
        createGraph(startDestination = LibraryRoute) { showTrackDestinations(onNavigate = { }) }

    private fun NavHostController.backStackRoutes() =
        currentBackStack.value.map { entry -> entry.destination.route?.substringBefore('/') }
}
