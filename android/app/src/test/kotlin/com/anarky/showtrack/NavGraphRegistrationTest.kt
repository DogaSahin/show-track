package com.anarky.showtrack

import android.app.Application
import android.content.Context
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass

/**
 * The regression this exists for: a route declared in `:core:navigation` and never wired into the
 * NavHost. Navigating to it throws `IllegalArgumentException: Navigation destination ... cannot be
 * found` at runtime, on a screen nobody opened during development — so it ships.
 *
 * Enumerated by reflection rather than compared against a hand-written list of nine, which is why
 * `AppRoute` is a sealed interface: a hand-written list has to be updated by the same person who
 * forgot to wire the route, on the same day.
 *
 * `kotlin-reflect` is a `testImplementation` for `sealedSubclasses` alone — without it this fails
 * with `KotlinReflectionNotSupportedError`, not with a wrong answer.
 *
 * Robolectric because a real `NavGraph` is not a JVM-only object: `NavDestination.route`'s setter
 * builds a deep link, which parses through `android.net.Uri`. `sdk = [35]` because Robolectric
 * ships no shadow jar for 36. `application = Application::class` keeps Robolectric from
 * instantiating `ShowTrackApplication`, whose `@HiltAndroidApp` component would stand up
 * DataStore and the Keystore for a test that needs neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NavGraphRegistrationTest {
    private val declaredRoutes: Set<KClass<out AppRoute>> = AppRoute::class.sealedSubclasses.toSet()

    /**
     * The positive control. Both assertions below compare the graph against `sealedSubclasses`,
     * so if reflection ever came back empty — kotlin-reflect dropped from the test classpath, or
     * a refactor that stopped the routes being subtypes of [AppRoute] — they would compare two
     * empty sets and pass while checking nothing at all.
     *
     * Deliberately not `assertEquals(9, ...)`: pinning the count would fail every legitimate
     * route addition in Phase 9 with a message about reflection, which is the wrong diagnosis
     * pointing at the wrong file. The other two tests already adapt to a tenth route by
     * demanding it be wired.
     */
    @Test
    fun `the route hierarchy is enumerable`() {
        assertTrue(
            "AppRoute::class.sealedSubclasses returned ${declaredRoutes.size} subclasses. " +
                "Either kotlin-reflect is off the test classpath or the routes are no longer " +
                "subtypes of AppRoute — every other assertion here would compare empty sets.",
            declaredRoutes.size > 1,
        )
    }

    /** The registration table names each declared route exactly once. */
    @Test
    fun `every declared route appears in appDestinations exactly once`() {
        val registered = appDestinations.map(AppDestination::route)

        assertEquals(
            "appDestinations must name every route in AppRoute's hierarchy and no others",
            declaredRoutes.mapNotNull { it.qualifiedName }.toSortedSet(),
            registered.mapNotNull { it.qualifiedName }.toSortedSet(),
        )
        // Not implied by the set comparison above: a route listed twice collapses in a set and
        // would pass it while registering two destinations for one route.
        assertEquals(
            "appDestinations has a duplicate row: ${registered.map { it.simpleName }}",
            declaredRoutes.size,
            registered.size,
        )
    }

    /**
     * The assertion that goes through the real entry functions rather than the table describing
     * them. A row whose `register` lambda calls the wrong feature's entry — plausible, since
     * eight of the nine are one-line copies of each other — leaves the table looking perfect and
     * the graph holding eight nodes, one of them registered twice.
     *
     * `NavGraph.addDestination` silently REPLACES a destination with the same id rather than
     * failing, which is why the count is compared to the number of registration calls and not
     * merely to nine: a double registration shows up here only as a node that went missing.
     */
    @Test
    fun `the built nav graph holds one destination per declared route`() {
        val graph = buildGraph()

        val graphRoutes = graph.map { destination -> destination.route?.substringBefore("/") }

        assertEquals(
            "the NavHost's graph must contain exactly one destination per registration call; " +
                "a smaller graph means two entries registered the same route",
            appDestinations.size,
            graphRoutes.size,
        )
        assertEquals(
            "the destinations the entry functions actually registered must be the declared routes",
            declaredRoutes.mapNotNull { it.qualifiedName }.toSortedSet(),
            graphRoutes.filterNotNull().toSortedSet(),
        )
    }

    /**
     * Builds the graph through `showTrackDestinations` — the same function `ShowTrackNavHost`
     * calls, rather than a re-implementation of it that could go on passing after the NavHost
     * stopped using the table.
     *
     * What it does not do is compose, so the destination content lambdas are stored and never
     * invoked; this checks the graph's SHAPE, which is all a JVM test can honestly claim about a
     * NavHost.
     */
    private fun buildGraph() =
        NavHostController(ApplicationProvider.getApplicationContext<Context>())
            .apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            .createGraph(startDestination = LibraryRoute) { showTrackDestinations(onNavigate = { }) }
}
