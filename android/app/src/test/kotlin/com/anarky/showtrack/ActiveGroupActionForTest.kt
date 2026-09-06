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
 *
 * The `hasRequestedGroups` half (whole-branch fix round, BLOCKING 2) is what makes the ordinary
 * Library -> Detail path load the group list at all, and the two cases below that drive the SAME
 * destination with both flag values are the pin: with the `!hasRequestedGroups -> Refresh` branch
 * deleted, `` `an ordinary destination loads the groups once` `` fails; with the branch made
 * unconditional, `` `an ordinary destination does not re-load once a fetch has been requested` ``
 * fails. One test alone would pass against either mutation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ActiveGroupActionForTest {
    @Test
    fun `no destination yet resolves to None`() {
        assertEquals(ActiveGroupAction.None, activeGroupActionFor(null, hasRequestedGroups = false))
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

        assertEquals(
            ActiveGroupAction.Reset,
            activeGroupActionFor(controller.currentDestination, hasRequestedGroups = true),
        )
    }

    @Test
    fun `FeedRoute resolves to Refresh`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(FeedRoute)

        // hasRequestedGroups = true: Feed re-fetches on EVERY arrival, which is what makes a group
        // created or left on another surface show up in the switcher. The load-once branch below
        // must not be what this case depends on.
        assertEquals(
            ActiveGroupAction.Refresh,
            activeGroupActionFor(controller.currentDestination, hasRequestedGroups = true),
        )
    }

    @Test
    fun `GroupsRoute resolves to Refresh`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(GroupsRoute)

        assertEquals(
            ActiveGroupAction.Refresh,
            activeGroupActionFor(controller.currentDestination, hasRequestedGroups = true),
        )
    }

    /**
     * BLOCKING 2's own pin. `LibraryRoute` is the graph's start destination and the beginning of the
     * ordinary path into Detail; before this branch it answered `None` unconditionally, so an
     * account that never opened Feed or Groups issued no `GET /v1/groups` at all and Detail's group
     * section rendered nothing for the life of the Activity.
     */
    @Test
    fun `an ordinary destination loads the groups once`() {
        val controller = controllerWith { defaultGraph() }

        assertEquals(
            ActiveGroupAction.Refresh,
            activeGroupActionFor(controller.currentDestination, hasRequestedGroups = false),
        )
    }

    /**
     * The other half, and the reason the flag exists rather than a bare `DetailRoute -> Refresh`
     * branch: `ActiveGroupViewModel.refresh` fetches unconditionally, and this effect re-evaluates
     * on EVERY destination change, so an unguarded branch would be a `GET /v1/groups` per screen
     * opened.
     */
    @Test
    fun `an ordinary destination does not re-load once a fetch has been requested`() {
        val controller = controllerWith { defaultGraph() }

        assertEquals(
            ActiveGroupAction.None,
            activeGroupActionFor(controller.currentDestination, hasRequestedGroups = true),
        )
    }

    /**
     * `AuthRoute` outranks the load-once branch: a signed-out destination must Reset, never fetch.
     * Ordering pin — moving the `!hasRequestedGroups` branch above the `AuthRoute` one would fire
     * an authenticated `GET /v1/groups` at the login screen, which is the exact regression fix
     * round 1's BLOCKING B4 removed `init { refresh() }` to stop.
     */
    @Test
    fun `AuthRoute resolves to Reset even when no fetch has been requested`() {
        val controller = controllerWith { authOnlyGraph() }

        assertEquals(
            ActiveGroupAction.Reset,
            activeGroupActionFor(controller.currentDestination, hasRequestedGroups = false),
        )
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
