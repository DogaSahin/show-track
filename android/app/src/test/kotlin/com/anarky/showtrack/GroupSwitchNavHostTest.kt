package com.anarky.showtrack

import android.app.Application
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.navigation.FeedRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The composed-`NavHost` proof task 9c.5's brief demands, modelled on `ShowTrackGraphRebuildTest`
 * (that file's own KDoc, lines 29-37): a bare `NavHostController` assigns a hand-built graph
 * directly, so `NavController.setGraph`'s graph-inequality branch never runs and its three routing
 * tests could never have seen 9b.6's broken onboarding door. Switching the active group changes what
 * a destination's OWN content composes with — the identical class of behaviour, one layer lower
 * (a route ARGUMENT rather than the route itself) — so this composes a REAL `NavHost` too.
 *
 * No Hilt harness exists for `:app` (`ShowTrackGraphRebuildTest`'s own note, still true), so
 * [markerFeedEntry] below stands in for the real, Hilt-gated `feedEntry`/`FeedScreen` — but it
 * reproduces their EXACT shape for the one thing this file is proving: `activeGroupId` arrives as a
 * `StateFlow<String?>` and is read via `collectAsStateWithLifecycle` INSIDE the destination's own
 * content lambda, never as a plain value captured by `appDestinations`/`showTrackDestinations` at
 * registration time. `AppDestination.kt`'s own KDoc explains why that distinction is load-bearing:
 * `NavHost`'s `builder` lambda — the thing that actually calls `feedEntry` — is only re-invoked when
 * `remember(startDestination, ...)`'s keys change, which for a signed-in session on the Feed tab
 * never happens again. A plain `String?` parameter baked in at that one registration would freeze
 * forever; a `StateFlow` read reactively inside the content lambda does not, because Navigation
 * re-composes that content on every `NavBackStackEntry` visit regardless of how rarely the graph
 * itself rebuilds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GroupSwitchNavHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The brief's own named test, verbatim, and §9.11's acceptance criterion. A REAL
     * [ActiveGroupViewModel] (constructed directly — no Hilt needed, `ShowTrackGraphRebuildTest`'s
     * own technique for [AppViewModel]) drives the switch; the composed `NavHost` never navigates
     * anywhere — the back stack shape is asserted unchanged before and after — proving the
     * re-scoping happens by the active group's OWN state changing, not by any navigation event.
     */
    @Test
    fun `switching groups re-scopes the feed without restarting the app`() {
        val store = FakeActiveGroupStore(initial = null)
        val repository = FakeGroupRepository(groups = listOf(ALPHA, BETA))
        val activeGroupViewModel = ActiveGroupViewModel(repository, store)
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = FeedRoute) {
                markerFeedEntry(activeGroupId = activeGroupViewModel.activeGroupId)
            }
        }
        composeRule.waitForIdle()

        // E-K: nothing stored, two groups — the FIRST group from the server is active.
        composeRule.onNodeWithText("feed:${ALPHA.id}").assertIsDisplayed()
        assertEquals(listOf(null, FeedRoute::class.qualifiedName), navController.backStackRoutes())

        composeRule.runOnIdle { activeGroupViewModel.selectGroup(BETA.id) }
        composeRule.waitForIdle()

        // Re-scoped to the new group, with NO navigation having happened at all.
        composeRule.onNodeWithText("feed:${BETA.id}").assertIsDisplayed()
        assertEquals(listOf(null, FeedRoute::class.qualifiedName), navController.backStackRoutes())
    }

    /**
     * **The tab-swap round trip Global Constraints and the task brief both flag as never yet
     * exercised.** `FeedViewModel` is tab-scoped (resolved via `hiltViewModel()` against `FeedRoute`'s
     * own `NavBackStackEntry`), and a tab swap uses `popUpTo(startDestination) { saveState = true }`
     * plus `restoreState = true` (`navigateToTopLevelDestination`, production code, called directly
     * below) — which is DOCUMENTED to retain the `ViewModelStore` but destroy and recreate the entry,
     * never proven against a real `NavigationSuiteScaffold`-style round trip until now.
     *
     * [MarkerFeedViewModel] stands in for `FeedViewModel` — plain `viewModel()`, no Hilt, the same
     * substitution [markerFeedEntry] makes for `FeedScreen` — and its own `resumeCount` field stands
     * in for whatever state a real resume-driven reload would leave behind. Two facts are proven
     * together: the SAME instance answers both visits (`assertSame` — the `ViewModelStore` survived
     * the round trip, so `FeedViewModel`'s in-memory rows would too), and the resume effect fires
     * again on the second visit (`resumeCount` reaches 2, not stuck at 1) — proving `FeedScreen`'s
     * own `LifecycleResumeEffect` genuinely re-fires on a tab return, which is what makes a resumed
     * Feed tab re-check for new activity rather than silently going stale.
     */
    @Test
    fun `a tab swap away from Feed and back retains the ViewModel and re-fires its resume effect`() {
        val observedInstances = mutableListOf<MarkerFeedViewModel>()
        val observedResumeCounts = mutableListOf<Int>()
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = LibraryRoute) {
                composable<LibraryRoute> { Text(text = "library") }
                composable<FeedRoute> {
                    val marker: MarkerFeedViewModel = viewModel()
                    LifecycleResumeEffect(marker) {
                        marker.resumeCount++
                        observedInstances += marker
                        observedResumeCounts += marker.resumeCount
                        onPauseOrDispose { }
                    }
                    Text(text = "feed")
                }
            }
        }
        composeRule.waitForIdle()

        // The first visit to Feed — navigateToTopLevelDestination is PRODUCTION code, the tab bar's
        // own onClick handler (MainActivity.kt).
        composeRule.runOnIdle { navController.navigateToTopLevelDestination(FeedRoute) }
        composeRule.waitForIdle()
        assertEquals(1, observedInstances.size)
        assertEquals(1, observedResumeCounts.last())

        // Swap to another tab, then back — the exact round trip a NavigationSuiteScaffold tab tap
        // performs. saveState/restoreState is what this whole test exists to prove actually holds.
        composeRule.runOnIdle { navController.navigateToTopLevelDestination(LibraryRoute) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { navController.navigateToTopLevelDestination(FeedRoute) }
        composeRule.waitForIdle()

        assertEquals(2, observedInstances.size)
        assertSame(
            "the ViewModelStore must survive a tab swap — a NEW instance means FeedViewModel's " +
                "in-memory rows would be lost on every ordinary tab switch, not just a real restart",
            observedInstances[0],
            observedInstances[1],
        )
        assertEquals(
            "the resume effect must re-fire on the SAME instance, not leave it stuck at its first count",
            2,
            observedResumeCounts.last(),
        )
    }

    @Composable
    private fun rememberTestNavController(): TestNavHostController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return remember {
            TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
        }
    }

    private fun NavGraphBuilder.markerFeedEntry(activeGroupId: StateFlow<String?>) {
        composable<FeedRoute> {
            val currentGroupId by activeGroupId.collectAsStateWithLifecycle()
            Text(text = "feed:${currentGroupId ?: "none"}")
        }
    }

    private fun NavHostController.backStackRoutes() =
        currentBackStack.value.map { entry -> entry.destination.route?.substringBefore('/') }

    private companion object {
        val ALPHA =
            Group(id = "group-alpha", name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T10:15:30Z"))
        val BETA =
            Group(id = "group-beta", name = "Beta Watchers", createdAt = Instant.parse("2026-08-29T09:00:00Z"))
    }
}

/**
 * A TOP-LEVEL class, not nested inside [GroupSwitchNavHostTest] — a nested `private class` here
 * compiles to a JVM-private static nested class, which `viewModel()`'s default
 * `ViewModelProvider.NewInstanceFactory` cannot reflectively construct (measured:
 * `IllegalAccessException`, wrapped as a `RuntimeException`, the moment `viewModel()` tried to
 * instantiate it). `internal`, not `private`, for the identical reason.
 */
internal class MarkerFeedViewModel : ViewModel() {
    var resumeCount = 0
}
