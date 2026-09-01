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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The regression guard `AuthNavigationTest` and `NavGraphRegistrationTest` cannot provide between
 * them. `AuthNavigationTest` pins that `navigateToAuthClearingStack()` itself clears the stack;
 * `NavGraphRegistrationTest` pins the graph's SHAPE. Neither pins that `routeShowTrackNavigation`
 * actually DISPATCHES `AuthRoute` to `navigateToAuthClearingStack()` rather than a plain push —
 * the exact line Gap 2 added. Composing `ShowTrackNavHost` to observe that dispatch would need
 * Hilt (every registered screen resolves a `@HiltViewModel`), and `:app` has no Hilt test harness,
 * so this calls `routeShowTrackNavigation` directly on a bare `NavHostController` instead — the
 * same Robolectric setup `AuthNavigationTest` already uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShowTrackGraphRoutingTest {
    @Test
    fun `routing to AuthRoute clears the back stack`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(FavoritesRoute)
        controller.navigate(DetailRoute(mediaId = "abc"))
        assertEquals(4, controller.currentBackStack.value.size)

        controller.routeShowTrackNavigation(AuthRoute)

        assertEquals(listOf(null, AuthRoute::class.qualifiedName), controller.backStackRoutes())
    }

    @Test
    fun `routing to LibraryRoute pops the auth screen off the stack`() {
        val controller = controllerWith { authOnlyGraph() }

        controller.routeShowTrackNavigation(LibraryRoute)

        assertEquals(listOf(null, LibraryRoute::class.qualifiedName), controller.backStackRoutes())
    }

    @Test
    fun `routing to any other route is an ordinary push`() {
        val controller = controllerWith { defaultGraph() }

        controller.routeShowTrackNavigation(DetailRoute(mediaId = "abc"))

        // A plain push GROWS the stack rather than replacing anything on it — the graph's own
        // root entry, the start destination (LibraryRoute), and the pushed DetailRoute.
        assertEquals(3, controller.currentBackStack.value.size)
        assertEquals(
            listOf(null, LibraryRoute::class.qualifiedName, DetailRoute::class.qualifiedName),
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

    private fun NavHostController.authOnlyGraph() =
        createGraph(startDestination = AuthRoute) { showTrackDestinations(onNavigate = { }) }

    private fun NavHostController.backStackRoutes() =
        currentBackStack.value.map { entry -> entry.destination.route?.substringBefore('/') }
}
