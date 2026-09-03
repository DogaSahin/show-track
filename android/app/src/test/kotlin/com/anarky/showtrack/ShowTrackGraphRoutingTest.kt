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
import com.anarky.showtrack.core.navigation.GroupDetailRoute
import com.anarky.showtrack.core.navigation.GroupsRoute
import com.anarky.showtrack.core.navigation.ImportRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import com.anarky.showtrack.core.navigation.ProfileRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 *
 * **Round 1 correction (task 9b.6 fix round).** A bare `NavHostController` with a hand-assigned
 * `NavGraph` calls `NavController.setGraph` exactly once, so its graph-inequality reset branch —
 * the mechanism `AppViewModel.markSignedIn`'s promotion actually relies on to converge a navigate
 * and a `start` change onto the same final shape — is NEVER exercised here
 * (`ShowTrackGraphRebuildTest`'s own KDoc states this, and that file is where the composed,
 * end-to-end proof lives). This file can therefore only prove what `routeShowTrackNavigation` does
 * DIRECTLY: which of its own calls it makes, given a back stack shape it is handed.
 *
 * **Round 2 correction.** `routeShowTrackNavigation` no longer takes a `start: AppStart` parameter
 * at all — round 1 added one and used `start == AppStart.Library` to infer "this must be the
 * Profile door returning", which a blind review measured false: `AppViewModel.markSignedIn` never
 * moves `start` back on a sign-out, so `start` stays `Library` straight through a sign-out and the
 * next login, and round 1's guard popped a stack that had nothing to pop TO. Every test below now
 * sets up the actual BACK STACK SHAPE the branch under test reads (`currentDestination`,
 * `previousBackStackEntry`) rather than passing a `start` value — see
 * `routeShowTrackNavigation`'s own KDoc for the full reasoning. `ShowTrackGraphRebuildTest`'s
 * `` `signing out from Profile and back in leaves a working stack on LibraryRoute` `` is the
 * composed, end-to-end regression guard for the bug this correction fixes; this file covers the
 * same branches at the level it can actually prove things at.
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
    fun `routing to LibraryRoute from AuthRoute pops the auth screen off the stack`() {
        val controller = controllerWith { authOnlyGraph() }

        controller.routeShowTrackNavigation(LibraryRoute)

        assertEquals(listOf(null, LibraryRoute::class.qualifiedName), controller.backStackRoutes())
    }

    /**
     * The concrete hole a review round found by grep (task 9b.0, review round 2, finding 1): no
     * test anywhere passed a non-default `onSignedIn`, so the parameter's default (`{}`) was the
     * only thing every existing test ever exercised — deleting the single `onSignedIn()` call in
     * `routeShowTrackNavigation`'s `LibraryRoute` branch left every other test green. This is the
     * direct, minimal proof that the wiring is real: a spy in place of `AppViewModel::markSignedIn`,
     * asserted fired with `isNewAccount = false` for an ordinary login.
     */
    @Test
    fun `routing to LibraryRoute from AuthRoute fires onSignedIn with isNewAccount false`() {
        val controller = controllerWith { authOnlyGraph() }
        var receivedIsNewAccount: Boolean? = null

        controller.routeShowTrackNavigation(
            LibraryRoute,
            onSignedIn = { isNewAccount -> receivedIsNewAccount = isNewAccount },
        )

        assertEquals(false, receivedIsNewAccount)
    }

    /**
     * The Profile door's own return trip (task 9b.6, round 1 fix — M1 in this task's report),
     * re-verified against round 2's back-stack-based condition. Back stack shape:
     * `[Library, Profile, Import]`, current destination `ImportRoute`, a real screen (`Profile`)
     * underneath it — exactly the shape [routeShowTrackNavigation]'s KDoc names as the one that
     * pops rather than pushes.
     */
    @Test
    fun `routing to LibraryRoute pops back to Profile when the current screen is Import with something underneath`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(ProfileRoute)
        controller.navigate(ImportRoute)
        assertEquals(4, controller.currentBackStack.value.size)

        controller.routeShowTrackNavigation(LibraryRoute)

        assertEquals(
            listOf(null, LibraryRoute::class.qualifiedName, ProfileRoute::class.qualifiedName),
            controller.backStackRoutes(),
        )
    }

    /** The negative control for the pop above: `onSignedIn` must not fire when this is a pop, not a sign-in. */
    @Test
    fun `routing to LibraryRoute does not call onSignedIn when popping back from Import`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(ProfileRoute)
        controller.navigate(ImportRoute)
        var called = false

        controller.routeShowTrackNavigation(LibraryRoute, onSignedIn = { called = true })

        assertFalse(called)
    }

    /**
     * Round 3, task 9b.6 fix round: the `ImportRoute` half of the pop condition
     * (`currentDestination?.hasRoute(ImportRoute::class) == true`) had no test isolating it —
     * deleting that clause and popping on `previousBackStackEntry != null` alone left the whole
     * suite green, because only two doors reach `onNavigate(LibraryRoute)` today and neither
     * needs this specific clause to already behave correctly. It is what stops a future third
     * door from silently inheriting pop behaviour instead of an ordinary push. Stack
     * `[null, Library, Profile]`, current destination `ProfileRoute` — NOT `ImportRoute` — must
     * push, even though `previousBackStackEntry` (`LibraryRoute`) is exactly the shape the pop
     * condition's OTHER half would otherwise be satisfied by.
     */
    @Test
    fun `routing to LibraryRoute from Profile with Library already underneath is still an ordinary push`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(ProfileRoute)
        assertEquals(
            listOf(null, LibraryRoute::class.qualifiedName, ProfileRoute::class.qualifiedName),
            controller.backStackRoutes(),
        )

        controller.routeShowTrackNavigation(LibraryRoute)

        assertEquals(
            listOf(
                null,
                LibraryRoute::class.qualifiedName,
                ProfileRoute::class.qualifiedName,
                LibraryRoute::class.qualifiedName,
            ),
            controller.backStackRoutes(),
        )
    }

    /**
     * **Round 2's actual fix, at the bare-controller level.** Back stack shape `[null, AuthRoute]`
     * — the shape a sign-out leaves, and the shape the NEXT login reaches this branch from. Current
     * destination is `AuthRoute`, not `ImportRoute`, so the pop condition does not match REGARDLESS
     * of what any `start` value would have said — this is the direct proof that the fix no longer
     * depends on session-progress state that a sign-out can leave stale. Mirrors
     * `ShowTrackGraphRebuildTest`'s composed, end-to-end version of the identical scenario.
     */
    @Test
    fun `routing to LibraryRoute from AuthRoute pushes even when nothing is on Import`() {
        val controller = controllerWith { authOnlyGraph() }
        assertEquals(2, controller.currentBackStack.value.size)

        controller.routeShowTrackNavigation(LibraryRoute)

        assertEquals(listOf(null, LibraryRoute::class.qualifiedName), controller.backStackRoutes())
    }

    /**
     * The onboarding-finishing case: current destination IS `ImportRoute`, but there is nothing
     * underneath it (`previousBackStackEntry` is `null` — `NavHostController`'s own root graph
     * entry does not count, since AndroidX Navigation's `previousBackStackEntry` explicitly skips
     * entries whose destination is itself a `NavGraph`; confirmed by this test passing, not
     * assumed). Must push, not pop — there is nothing to return to.
     */
    @Test
    fun `routing to LibraryRoute from a bare ImportRoute with nothing underneath pushes rather than pops`() {
        val controller = controllerWith { authOnlyGraph() }
        controller.routeShowTrackNavigation(ImportRoute)
        assertEquals(listOf(null, ImportRoute::class.qualifiedName), controller.backStackRoutes())

        controller.routeShowTrackNavigation(LibraryRoute)

        assertEquals(
            listOf(null, ImportRoute::class.qualifiedName, LibraryRoute::class.qualifiedName),
            controller.backStackRoutes(),
        )
    }

    /**
     * Task 9b.6's fresh-registration path — the mirror of
     * `routing to LibraryRoute from AuthRoute pops the auth screen off the stack`.
     */
    @Test
    fun `routing to ImportRoute from AuthRoute pops the auth screen off the stack`() {
        val controller = controllerWith { authOnlyGraph() }

        controller.routeShowTrackNavigation(ImportRoute)

        assertEquals(listOf(null, ImportRoute::class.qualifiedName), controller.backStackRoutes())
    }

    @Test
    fun `routing to ImportRoute from AuthRoute fires onSignedIn with isNewAccount true`() {
        val controller = controllerWith { authOnlyGraph() }
        var receivedIsNewAccount: Boolean? = null

        controller.routeShowTrackNavigation(
            ImportRoute,
            onSignedIn = { isNewAccount -> receivedIsNewAccount = isNewAccount },
        )

        assertEquals(true, receivedIsNewAccount)
    }

    /**
     * Profile's own door to `ImportRoute` (`ProfileNavigation.kt`'s `importNavigation`) reaches
     * this exact branch with the current destination `ProfileRoute`, not `AuthRoute` — task 9b.6
     * round 1's own bug, caught before it shipped: an earlier draft called `onSignedIn(true)`
     * unconditionally here, which would silently move an already-signed-in session's `start` from
     * `Library` to `Onboarding` on every ordinary visit to Profile's import screen.
     */
    @Test
    fun `routing to ImportRoute from Profile does not call onSignedIn`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(ProfileRoute)
        var called = false

        controller.routeShowTrackNavigation(ImportRoute, onSignedIn = { called = true })

        assertFalse(called)
    }

    /**
     * What `ImportScreen`'s own skip action and its terminal "Done" button do at the
     * `routeShowTrackNavigation` level ALONE, once `AuthRoute` is already off the stack from the
     * transition above — proof that `navigateToLibraryClearingAuth`'s `popUpTo<AuthRoute>` does not
     * throw or otherwise misbehave when `AuthRoute` is a real destination in the graph but is NOT
     * currently on the back stack.
     *
     * **This is NOT the final production shape** — see this file's own class KDoc and
     * `ShowTrackGraphRebuildTest`'s `` `finishing onboarding converges on the shape a login already
     * produces` `` for why a bare `NavHostController` cannot show the graph-reset that actually
     * corrects this push into `[null, LibraryRoute]`.
     */
    @Test
    fun `routing on to LibraryRoute after Onboarding is an ordinary push`() {
        val controller = controllerWith { authOnlyGraph() }
        controller.routeShowTrackNavigation(ImportRoute)

        controller.routeShowTrackNavigation(LibraryRoute)

        assertEquals(
            listOf(null, ImportRoute::class.qualifiedName, LibraryRoute::class.qualifiedName),
            controller.backStackRoutes(),
        )
    }

    /**
     * `GroupsRoute` (task 9c.2) — a member leaving the group on screen. Stack shape
     * `[null, GroupsRoute, GroupDetailRoute]`, `GroupDetailRoute`'s own KDoc: it is reached ONLY
     * from `GroupsRoute`'s own list, so this is the ONLY shape the back stack is ever actually in
     * when `leaveNavigation` fires `onNavigate(GroupsRoute)`. Must pop back to the EXISTING
     * `GroupsRoute` rather than pushing a second one — `LibraryRoute` popping back to `ProfileRoute`
     * from `ImportRoute`'s identical shape, one screen over.
     */
    @Test
    fun `routing to GroupsRoute pops back to the existing one when returning from GroupDetailRoute`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(GroupsRoute)
        controller.navigate(GroupDetailRoute(groupId = "group-1"))
        assertEquals(4, controller.currentBackStack.value.size)

        controller.routeShowTrackNavigation(GroupsRoute)

        assertEquals(
            listOf(null, LibraryRoute::class.qualifiedName, GroupsRoute::class.qualifiedName),
            controller.backStackRoutes(),
        )
    }

    /**
     * The negative control for the pop above, mirroring `` `routing to LibraryRoute from Profile
     * with Library already underneath is still an ordinary push` ``: current destination is
     * `LibraryRoute`, not `GroupDetailRoute`, so the pop condition must not match regardless of
     * what is or is not underneath it.
     */
    @Test
    fun `routing to GroupsRoute from somewhere other than GroupDetailRoute is an ordinary push`() {
        val controller = controllerWith { defaultGraph() }

        controller.routeShowTrackNavigation(GroupsRoute)

        assertEquals(
            listOf(null, LibraryRoute::class.qualifiedName, GroupsRoute::class.qualifiedName),
            controller.backStackRoutes(),
        )
    }

    /**
     * Round 3's own precedent (task 9b.6 fix round), applied to this branch: the
     * `previousBackStackEntry != null` half of the pop condition, isolated. The graph's OWN start
     * destination is `GroupDetailRoute` here — the one way to put it on the back stack with
     * nothing real underneath it (unlike the shape every other test in this file builds by
     * `navigate`ing there from something else) — mirroring the onboarding `ImportRoute`-with-
     * nothing-underneath shape one screen over (`` `routing to LibraryRoute from a bare ImportRoute
     * with nothing underneath pushes rather than pops` ``, whose OWN shape comes from
     * `navigateToImportClearingAuth`'s `popUpTo<AuthRoute>` rather than this trick — `GroupDetailRoute`
     * has no such special-cased arrival, so a plain `navigate` never produces this shape on its
     * own). Must push, not pop — there is nothing to return to.
     */
    @Test
    fun `routing to GroupsRoute from a bare GroupDetailRoute with nothing underneath pushes rather than pops`() {
        val controller =
            NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
                graph =
                    createGraph(startDestination = GroupDetailRoute(groupId = "group-1")) {
                        showTrackDestinations(onNavigate = { })
                    }
            }
        assertEquals(listOf(null, GroupDetailRoute::class.qualifiedName), controller.backStackRoutes())

        controller.routeShowTrackNavigation(GroupsRoute)

        assertEquals(
            listOf(null, GroupDetailRoute::class.qualifiedName, GroupsRoute::class.qualifiedName),
            controller.backStackRoutes(),
        )
    }

    /**
     * Round 1 review's own scenario, reproduced directly: `GroupDetailRoute` reached from a door
     * OTHER than `GroupsRoute` (`FavoritesRoute` here stands in for the 9c.4 feed-item case the
     * review named). Round 0's blind `popBackStack()` — "am I on `GroupDetailRoute` with something
     * beneath me" — would have popped to `FavoritesRoute` here and called it a return to Groups.
     * `popBackStack<GroupsRoute>(inclusive = false)` finds no `GroupsRoute` anywhere on this stack
     * and correctly falls through to an ordinary push instead.
     */
    @Test
    fun `routing to GroupsRoute after reaching GroupDetailRoute from somewhere other than GroupsRoute still pushes`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(FavoritesRoute)
        controller.navigate(GroupDetailRoute(groupId = "group-1"))
        assertEquals(4, controller.currentBackStack.value.size)

        controller.routeShowTrackNavigation(GroupsRoute)

        assertEquals(
            listOf(
                null,
                LibraryRoute::class.qualifiedName,
                FavoritesRoute::class.qualifiedName,
                GroupDetailRoute::class.qualifiedName,
                GroupsRoute::class.qualifiedName,
            ),
            controller.backStackRoutes(),
        )
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

    /** Sanity check that the bare default (`onSignedIn = {}`) still compiles and behaves. */
    @Test
    fun `omitting onSignedIn behaves as an ordinary navigation`() {
        val controller = controllerWith { authOnlyGraph() }

        controller.routeShowTrackNavigation(LibraryRoute)

        assertTrue(controller.backStackRoutes().contains(LibraryRoute::class.qualifiedName))
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
