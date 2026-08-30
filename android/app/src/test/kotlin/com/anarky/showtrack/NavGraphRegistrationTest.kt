package com.anarky.showtrack

import android.app.Application
import android.content.Context
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import com.anarky.showtrack.core.navigation.detailDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Enumerated to the LEAVES, not to `sealedSubclasses` — see [leafRoutes]. `sealedSubclasses` is
 * one level deep, so the first nested sub-graph would make this suite demand a destination for an
 * abstract interface that can never have one, while saying nothing about the concrete unwired
 * route underneath it that would actually crash. A misleading diagnosis and a coverage hole in
 * the same move.
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
    private val declaredRoutes: Set<KClass<*>> = AppRoute::class.leafRoutes()

    /**
     * The positive control. Both assertions below compare the graph against the reflected route
     * set, so if reflection ever came back empty — kotlin-reflect dropped from the test
     * classpath, or a refactor that stopped the routes being subtypes of [AppRoute] — they would
     * compare two empty sets and pass while checking nothing at all.
     *
     * Deliberately not `assertEquals(9, ...)`: pinning the count would fail every legitimate
     * route addition in Phase 9 with a message about reflection, which is the wrong diagnosis
     * pointing at the wrong file. The other two tests already adapt to a tenth route by
     * demanding it be wired.
     */
    @Test
    fun `the route hierarchy is enumerable`() {
        assertTrue(
            "AppRoute::class.leafRoutes() returned ${declaredRoutes.size} routes. " +
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

        // Strip BOTH argument forms: a required argument renders as `…DetailRoute/{mediaId}`
        // and an optional one as `…FooRoute?x={x}`, and comparing either against a class name
        // would fail about the wrong thing.
        val graphRoutes =
            graph.map { destination ->
                destination.route?.substringBefore('/')?.substringBefore('?')
            }

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
     * The graph half of the notification deep link.
     *
     * `PushNotifier` builds `showtrack://detail/<id>` and hands it to the system; `:app`'s
     * manifest lets it in; this asserts the graph then has somewhere to put it. All three must
     * agree, and the failure when they do not is SILENT — the tap opens the launcher screen, no
     * exception, nothing in logcat. This is the one of the three a JVM test can reach.
     *
     * `hasDeepLink` is asked of the GRAPH, not of a destination looked up by hand, so it
     * exercises the same matching a real `NavController.handleDeepLink` performs.
     */
    @Test
    fun `the nav graph answers the deep link a push notification opens`() {
        val graph = buildGraph()

        assertTrue(
            "the graph must answer showtrack://detail/<id>; a push notification's tap resolves " +
                "to exactly this URI and would otherwise open the start destination silently",
            graph.hasDeepLink(detailDeepLink("abc-123").toUri()),
        )
    }

    /**
     * The negative control, without which the assertion above would pass on a graph that matched
     * everything. A route that does not exist must NOT match.
     */
    @Test
    fun `an unregistered deep link host is not answered`() {
        assertFalse(buildGraph().hasDeepLink("showtrack://nosuchscreen/abc-123".toUri()))
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

    /**
     * Proves [leafRoutes] recurses, using a hierarchy shaped like the one Phase 9 will introduce
     * the first time a feature nests: a sealed sub-interface with a concrete route under it.
     *
     * Written because the flat hierarchy in `:core:navigation` cannot distinguish a recursive
     * implementation from `sealedSubclasses` today — the two agree on every input that currently
     * exists, so the recursion would be untested until the day it mattered.
     */
    @Test
    fun `leafRoutes descends through a nested sealed route`() {
        assertEquals(
            setOf(FlatLeaf::class, NestedLeaf::class, DeeperLeaf::class),
            Nestable::class.leafRoutes(),
        )
    }

    private sealed interface Nestable

    private data object FlatLeaf : Nestable

    private sealed interface NestedGroup : Nestable

    private data object NestedLeaf : NestedGroup

    private sealed interface DeeperGroup : NestedGroup

    private data object DeeperLeaf : DeeperGroup
}

/**
 * Every concrete route in a sealed hierarchy, however deeply nested.
 *
 * `sealedSubclasses` alone stops at the first level. A `sealed interface GroupsSubRoute :
 * AppRoute` would come back as a "route" needing a destination — which it can never have, since
 * only a concrete class is `@Serializable` and registrable — while the concrete leaf beneath it,
 * the one whose absence actually crashes, would not be checked at all.
 */
private fun KClass<*>.leafRoutes(): Set<KClass<*>> =
    if (isSealed) sealedSubclasses.flatMap { it.leafRoutes() }.toSet() else setOf(this)
