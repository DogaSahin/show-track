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
 * **Round 1 correction (task 9b.6 fix round), and the boundary of what this file can prove.** A
 * bare `NavHostController` with a hand-assigned `NavGraph` calls `NavController.setGraph` exactly
 * once, so its graph-inequality reset branch — the mechanism `AppViewModel.markSignedIn`'s
 * promotion actually relies on to converge a navigate and a `start` change onto the same final
 * shape — is NEVER exercised here (`ShowTrackGraphRebuildTest`'s own KDoc states this, and that
 * file is where the composed, end-to-end proof lives). This file can therefore only prove what
 * `routeShowTrackNavigation` does DIRECTLY: which of its own calls it makes, and with which
 * arguments. Round 0 of this task shipped a test here (`` `routing on to LibraryRoute after
 * ImportRoute is an ordinary push` ``) whose NAME asserted that shape was the final, correct
 * production behaviour — it was not: the actual final shape is `ShowTrackGraphRebuildTest`'s job to
 * prove, and depends on a mechanism this harness cannot see at all.
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

        controller.routeShowTrackNavigation(LibraryRoute, start = AppStart.Auth)

        assertEquals(listOf(null, LibraryRoute::class.qualifiedName), controller.backStackRoutes())
    }

    /**
     * The concrete hole a review round found by grep (task 9b.0, review round 2, finding 1): no
     * test anywhere passed a non-default `onSignedIn`, so the parameter's default (`{}`) was the
     * only thing every existing test ever exercised — deleting the single `onSignedIn()` call in
     * `routeShowTrackNavigation`'s `LibraryRoute` branch left every other test green. This is the
     * direct, minimal proof that the wiring is real: a spy in place of `AppViewModel::markSignedIn`,
     * asserted fired.
     *
     * Round 1 (task 9b.6 fix round): `start = AppStart.Auth` is now load-bearing to this test's own
     * premise — the branch only calls `onSignedIn` when `start` is NOT already `AppStart.Library`,
     * so a call that omitted `start` (leaving the `AppStart.Undecided` default) would still pass
     * this specific assertion, but for the wrong reason: `Undecided` happens to also satisfy
     * `!= Library`. `AppStart.Auth` is the actual production value at this call site (a login), and
     * is what makes this test assert the real condition rather than a coincidentally-true one.
     * `false` is asserted for the ARGUMENT, not just that `onSignedIn` fired at all — `isNewAccount`
     * must be `false` for a login, never `true`.
     */
    @Test
    fun `routing to LibraryRoute fires onSignedIn with isNewAccount false`() {
        val controller = controllerWith { authOnlyGraph() }
        var receivedIsNewAccount: Boolean? = null

        controller.routeShowTrackNavigation(
            LibraryRoute,
            start = AppStart.Auth,
            onSignedIn = { isNewAccount -> receivedIsNewAccount = isNewAccount },
        )

        assertEquals(false, receivedIsNewAccount)
    }

    /**
     * The Profile door's own return trip (task 9b.6, round 1 fix — M1 in this task's report): when
     * `start` is ALREADY `Library`, the only way `onNavigate(LibraryRoute)` can fire is
     * `ImportScreen`'s skip/Done action returning from a Profile-initiated visit — nothing else
     * routes to `LibraryRoute` from an already-`Library` session (`routeShowTrackNavigation`'s own
     * KDoc walks through why). This models exactly that back stack shape: `[Library, Profile,
     * Import]`. A `popBackStack()`, not a push, is what returns the user to Profile rather than
     * stacking a second Library underneath everything.
     */
    @Test
    fun `routing to LibraryRoute pops back to Profile when already signed in`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(ProfileRoute)
        controller.navigate(ImportRoute)
        assertEquals(4, controller.currentBackStack.value.size)

        controller.routeShowTrackNavigation(LibraryRoute, start = AppStart.Library)

        assertEquals(
            listOf(null, LibraryRoute::class.qualifiedName, ProfileRoute::class.qualifiedName),
            controller.backStackRoutes(),
        )
    }

    /**
     * The negative control for the pop above: `onSignedIn` must not fire at all when `start` is
     * already `Library` — calling it would demote `start` from `Library` back to `Onboarding`
     * (`AppViewModel.markSignedIn`'s own KDoc), breaking the "one-way" invariant on every visit to
     * Profile's import screen.
     */
    @Test
    fun `routing to LibraryRoute does not call onSignedIn when already signed in`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(ProfileRoute)
        controller.navigate(ImportRoute)
        var called = false

        controller.routeShowTrackNavigation(LibraryRoute, start = AppStart.Library, onSignedIn = { called = true })

        assertFalse(called)
    }

    /**
     * Task 9b.6's fresh-registration path — the mirror of
     * `routing to LibraryRoute pops the auth screen off the stack`.
     */
    @Test
    fun `routing to ImportRoute pops the auth screen off the stack`() {
        val controller = controllerWith { authOnlyGraph() }

        controller.routeShowTrackNavigation(ImportRoute, start = AppStart.Auth)

        assertEquals(listOf(null, ImportRoute::class.qualifiedName), controller.backStackRoutes())
    }

    @Test
    fun `routing to ImportRoute fires onSignedIn with isNewAccount true, coming from Auth`() {
        val controller = controllerWith { authOnlyGraph() }
        var receivedIsNewAccount: Boolean? = null

        controller.routeShowTrackNavigation(
            ImportRoute,
            start = AppStart.Auth,
            onSignedIn = { isNewAccount -> receivedIsNewAccount = isNewAccount },
        )

        assertEquals(true, receivedIsNewAccount)
    }

    /**
     * Profile's own door to `ImportRoute` (`ProfileNavigation.kt`'s `importNavigation`) reaches
     * this exact branch with `start` already `AppStart.Library` — task 9b.6 round 1's own bug,
     * caught before it shipped: an earlier draft of this fix called `onSignedIn(true)`
     * unconditionally here, which would silently move an already-signed-in session's `start` from
     * `Library` to `Onboarding` on every ordinary visit to Profile's import screen — invisible in
     * the same session (the user is already looking at `ImportRoute` either way), but a real
     * regression on the NEXT Activity recreation, which would then reopen on `ImportRoute` instead
     * of `Library`.
     */
    @Test
    fun `routing to ImportRoute does not call onSignedIn when already signed in`() {
        val controller = controllerWith { defaultGraph() }
        controller.navigate(ProfileRoute)
        var called = false

        controller.routeShowTrackNavigation(ImportRoute, start = AppStart.Library, onSignedIn = { called = true })

        assertFalse(called)
    }

    /**
     * What `ImportScreen`'s own skip action and its terminal "Done" button do at the
     * `routeShowTrackNavigation` level ALONE, once `AuthRoute` is already off the stack from the
     * transition above and `start` is `Onboarding` (not yet promoted to `Library`) — proof that
     * `navigateToLibraryClearingAuth`'s `popUpTo<AuthRoute>` does not throw or otherwise misbehave
     * when `AuthRoute` is a real destination in the graph but is NOT currently on the back stack.
     *
     * **This is NOT the final production shape** — see this file's own class KDoc and
     * `ShowTrackGraphRebuildTest`'s `` `finishing onboarding converges on the shape a login already
     * produces` `` for why a bare `NavHostController` cannot show the graph-reset that actually
     * corrects this push into `[null, LibraryRoute]`. Renamed from round 0's `` `routing on to
     * LibraryRoute after ImportRoute is an ordinary push` `` specifically because that name
     * asserted the wrong thing was correct.
     */
    @Test
    fun `routing on to LibraryRoute after Onboarding is an ordinary push`() {
        val controller = controllerWith { authOnlyGraph() }
        controller.routeShowTrackNavigation(ImportRoute, start = AppStart.Auth)

        controller.routeShowTrackNavigation(LibraryRoute, start = AppStart.Onboarding)

        assertEquals(
            listOf(null, ImportRoute::class.qualifiedName, LibraryRoute::class.qualifiedName),
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

    /** Sanity check that the bare defaults (`start = Undecided`, `onSignedIn = {}`) still compile and behave. */
    @Test
    fun `omitting start and onSignedIn behaves as an ordinary not-yet-signed-in navigation`() {
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
