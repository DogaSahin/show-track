package com.anarky.showtrack

import android.app.Application
import android.content.Context
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.navigation.DiscoverRoute
import com.anarky.showtrack.core.navigation.FavoritesRoute
import com.anarky.showtrack.core.navigation.FeedRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * Narrower than it might read: this only pins that `DISCOVER` exists in the TAB set at all —
     * a plain enum read, nothing about the nav graph. `TopLevelDestination` gained this entry in
     * task 9b.3 (`MainActivity.kt`'s own KDoc explains why); if a future edit dropped it from the
     * enum, the tab would simply not render and nothing else here would notice.
     *
     * Whether tapping Discover actually LANDS on `DiscoverRoute` — the "registered in the enum but
     * unreachable" failure mode, the Gap 1/Gap 2 shape this file's Favorites test also guards
     * against — is NOT this test's job: that is the sibling test directly below (drives a real
     * `NavHostController` through `navigateToTopLevelDestination(DiscoverRoute)` and asserts the
     * resulting back stack), together with `NavGraphRegistrationTest` (asserts
     * `showTrackDestinations` actually registers a `DiscoverRoute` destination in the graph at
     * all). Fix round 1, finding 5: an earlier version of this KDoc attributed that coverage to
     * this test instead of naming the tests that actually provide it.
     */
    @Test
    fun `Discover is registered as a top-level destination`() {
        assertTrue(TopLevelDestination.entries.any { it.route == DiscoverRoute })
    }

    /**
     * `popUpTo(findStartDestination().id)` targets the graph's START destination (`LibraryRoute`)
     * without `inclusive`, so it pops everything ABOVE that entry and leaves the start destination
     * itself in place — swapping Favorites for Discover collapses to [Library, Discover], not to
     * [Discover] alone the way the sibling test's [LibraryRoute, LibraryRoute] case does. That
     * sibling test's two-entry result is specific to its FINAL tap landing back on the start
     * destination itself, where `launchSingleTop`/`restoreState` dedup it into the existing entry
     * rather than pushing a second one — not "any tab swap collapses to one entry". A first draft
     * of this test asserted the wrong shape (`[Discover]`) by generalising from that one case; this
     * is the actual failure message that caught it: `expected:<[null, DiscoverRoute]> but
     * was:<[null, LibraryRoute, DiscoverRoute]>`.
     */
    @Test
    fun `tapping Discover after Favorites lands on Discover above the start destination`() {
        val controller = controllerWith { defaultGraph() }

        controller.navigateToTopLevelDestination(FavoritesRoute)
        controller.navigateToTopLevelDestination(DiscoverRoute)

        assertEquals(
            listOf(null, LibraryRoute::class.qualifiedName, DiscoverRoute::class.qualifiedName),
            controller.backStackRoutes(),
        )
    }

    /**
     * `FeedRoute` is registered and unreachable today — this task's own brief, verbatim (task
     * 9c.4). `TopLevelDestination.FEED` is what makes it reachable: a plain enum read, the
     * `DISCOVER` sibling test's identical narrow scope — see that test's own KDoc for what this
     * does and does not cover.
     */
    @Test
    fun `Feed is registered as a top-level destination`() {
        assertTrue(TopLevelDestination.entries.any { it.route == FeedRoute })
    }

    /**
     * The actual reachability pin `Feed is registered as a top-level destination` cannot provide
     * on its own — `AppDestination.kt`'s own KDoc calls this exact failure mode out: "a route
     * wired into the graph with no door in." Drives a real `NavHostController` through
     * `navigateToTopLevelDestination(FeedRoute)` via `showTrackDestinations` (which calls
     * `feedEntry`, defaulting `activeGroupId`/`groups` here since this test does not care about
     * groups), so a broken registration — the
     * `AppDestination` entry pointing at the wrong route, or `feedEntry` never actually composing
     * `FeedRoute` — fails here, not silently. `Discover`'s sibling test's identical shape one tab
     * over.
     */
    @Test
    fun `tapping Feed after Favorites lands on Feed above the start destination`() {
        val controller = controllerWith { defaultGraph() }

        controller.navigateToTopLevelDestination(FavoritesRoute)
        controller.navigateToTopLevelDestination(FeedRoute)

        assertEquals(
            listOf(null, LibraryRoute::class.qualifiedName, FeedRoute::class.qualifiedName),
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

    private fun NavHostController.backStackRoutes() =
        currentBackStack.value.map { entry -> entry.destination.route?.substringBefore('/') }
}
