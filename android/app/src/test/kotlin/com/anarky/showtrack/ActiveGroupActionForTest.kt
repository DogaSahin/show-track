package com.anarky.showtrack

import android.app.Application
import android.content.Context
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.FeedRoute
import com.anarky.showtrack.core.navigation.GroupsRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BLOCKING F3 (fix round 2): [activeGroupActionFor]'s own pin — `ShouldShowNavigationTabsTest`'s
 * identical reasoning and setup (a real `NavDestination`, which needs a `Context` to parse its
 * route, via a Robolectric `NavHostController`; `:app` has no Hilt harness to compose
 * `ShowTrackApp` and drive its `LaunchedEffect` directly).
 *
 * Before this function existed, the `when` lived inline in that `LaunchedEffect`, and a reviewer
 * measured directly that deleting the `AuthRoute -> reset()` branch — or swapping it for
 * `refresh()` — left the whole suite green. `ActiveGroupViewModel.reset()`/`refresh()` are each
 * well tested on their own (`ActiveGroupViewModelTest`); this file is what pins that the right ONE
 * is called for the right destination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ActiveGroupActionForTest {
    @Test
    fun `no destination yet resolves to None`() {
        assertEquals(ActiveGroupAction.None, activeGroupActionFor(null))
    }

    /**
     * The exact regression this function exists to prevent: `AuthRoute` is the one destination
     * BOTH a session-expiry logout and a user-initiated sign-out land on
     * (`navigateToAuthClearingStack`, `ShowTrackNavHost.kt`) — so this is the sole trigger for
     * [ActiveGroupViewModel.reset] clearing the previous account's state.
     */
    @Test
    fun `AuthRoute resolves to Reset`() {
        val controller = controllerWith { authOnlyGraph() }

        assertEquals(ActiveGroupAction.Reset, activeGroupActionFor(controller.currentDestination))
    }

    @Test
    fun `FeedRoute resolves to Refresh`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(FeedRoute)

        assertEquals(ActiveGroupAction.Refresh, activeGroupActionFor(controller.currentDestination))
    }

    @Test
    fun `GroupsRoute resolves to Refresh`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(GroupsRoute)

        assertEquals(ActiveGroupAction.Refresh, activeGroupActionFor(controller.currentDestination))
    }

    /** The negative control: an ordinary destination that is none of the three named above. */
    @Test
    fun `an unrelated destination resolves to None`() {
        val controller = controllerWith { defaultGraph() }

        assertEquals(ActiveGroupAction.None, activeGroupActionFor(controller.currentDestination))
    }

    private fun controllerWith(graph: NavHostController.() -> NavGraph): NavHostController =
        NavHostController(ApplicationProvider.getApplicationContext<Context>())
            .apply {
                navigatorProvider.addNavigator(ComposeNavigator())
                this.graph = graph()
            }

    private fun NavHostController.defaultGraph() =
        createGraph(startDestination = LibraryRoute) { testShowTrackDestinations(onNavigate = { }) }

    private fun NavHostController.authOnlyGraph() =
        createGraph(startDestination = AuthRoute) { testShowTrackDestinations(onNavigate = { }) }

    private fun androidx.navigation.NavGraphBuilder.testShowTrackDestinations(
        onNavigate: (com.anarky.showtrack.core.navigation.AppRoute) -> Unit,
    ) {
        showTrackDestinations(
            onNavigate = onNavigate,
            activeGroup = MutableStateFlow(ActiveGroupState.Loading),
            onSwitchGroup = {},
            onRetryGroups = {},
        )
    }
}
