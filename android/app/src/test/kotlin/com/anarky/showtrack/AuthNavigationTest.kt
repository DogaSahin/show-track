package com.anarky.showtrack

import android.app.Application
import android.content.Context
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.FavoritesRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `navigateToAuthClearingStack` is the one piece of navigation behaviour the brief calls
 * load-bearing: without a cleared back stack, *back* from the login screen returns to a screen
 * whose every request 401s, and the app looks broken rather than logged out.
 *
 * It had no coverage, and it was fragile in a way that fails silently. `popUpTo(0)` pops the whole
 * stack only because a graph built from a `startDestination` with no `route` class has id 0. Give
 * the graph a route and 0 matches no destination, `popBackStackInternal` pops nothing, and the
 * navigation still happens — so the app compiles, navigates, and quietly keeps the stack it was
 * supposed to clear. [a graph with a route] is that exact configuration, and it is why the
 * production code reads the id off the graph.
 *
 * Robolectric for the same reason as `NavGraphRegistrationTest`: a real `NavController` needs a
 * `Context` and routes parse through `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AuthNavigationTest {
    @Test
    fun `logging out clears the back stack on the graph the app builds today`() {
        val controller = controllerWith { defaultGraph() }

        controller.navigate(FavoritesRoute)
        controller.navigate(DetailRoute(mediaId = "abc"))
        assertEquals(4, controller.currentBackStack.value.size)

        controller.navigateToAuthClearingStack()

        assertEquals(listOf(null, AuthRoute::class.qualifiedName), controller.backStackRoutes())
    }

    /**
     * The regression guard for the refactor that breaks `popUpTo(0)`: a graph with a `route`, as
     * `NavHost(route = …)` produces the first time Phase 9 nests. Its id is the route's hash, not
     * 0 — asserted here rather than assumed, because if it ever WERE 0 this test would pass while
     * proving nothing.
     */
    @Test
    fun `logging out clears the back stack on a graph that has a route`() {
        val controller = controllerWith { routedGraph() }
        assertNotEquals(0, controller.graph.id)

        controller.navigate(FavoritesRoute)
        controller.navigate(DetailRoute(mediaId = "abc"))

        controller.navigateToAuthClearingStack()

        assertEquals(
            listOf(ROOT_GRAPH_ROUTE, AuthRoute::class.qualifiedName),
            controller.backStackRoutes(),
        )
    }

    private fun controllerWith(graph: NavHostController.() -> NavGraph): NavHostController =
        NavHostController(ApplicationProvider.getApplicationContext<Context>())
            .apply {
                navigatorProvider.addNavigator(ComposeNavigator())
                this.graph = graph()
            }

    private fun NavHostController.defaultGraph() =
        createGraph(startDestination = LibraryRoute) { showTrackDestinations(onNavigate = { }) }

    // The String overload, because the typed one pairs `startDestination: Any` with
    // `route: KClass<*>`, and giving the graph a KClass route would need a @Serializable marker
    // type — which needs the serialization compiler plugin, which :app does not apply. What is
    // under test is only that the graph's id is non-zero, and a String route produces that.
    private fun NavHostController.routedGraph() =
        createGraph(
            startDestination = requireNotNull(LibraryRoute::class.qualifiedName),
            route = ROOT_GRAPH_ROUTE,
        ) { showTrackDestinations(onNavigate = { }) }

    // The root NavGraph is itself the first back-stack entry, so the expected lists below start
    // with it: null when the graph has no route, the route string when it has one.
    private fun NavHostController.backStackRoutes() =
        currentBackStack.value.map { entry -> entry.destination.route?.substringBefore('/') }

    private companion object {
        const val ROOT_GRAPH_ROUTE = "showtrack_root"
    }
}
