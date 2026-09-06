package com.anarky.showtrack

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.navigation.AppRoute
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.FavoritesRoute
import com.anarky.showtrack.core.navigation.FeedRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * SF1 (whole-branch fix round). `ActiveGroupActionForTest` pins the DECISION and
 * `ActiveGroupViewModelTest` pins what [ActiveGroupViewModel.refresh]/[ActiveGroupViewModel.reset]
 * do; until this file, nothing joined the two. A reviewer measured the cost precisely: the effect
 * lived as an anonymous `LaunchedEffect` inside `ShowTrackApp`, and **deleting the whole block left
 * all 713 tests green** while the Feed tab spun on `ActiveGroupState.Loading` forever for every
 * account and sign-out silently stopped clearing the previous account's groups (walkthrough 40's
 * cross-account leak, reintroduced).
 *
 * `:app` has no Hilt test harness, so composing `ShowTrackApp` itself is not available — every
 * destination it registers resolves a `@HiltViewModel`. [ActiveGroupDestinationEffect] is the
 * largest piece of that seam a test can reach: it is composed here for real, driven by a real
 * `NavDestination` and a REAL [ActiveGroupViewModel] over the same fakes `ActiveGroupViewModelTest`
 * uses, so an assertion here can only pass if the effect actually calls the ViewModel.
 *
 * What remains unpinned, stated rather than implied: that `ShowTrackApp` calls this composable at
 * all. Deleting that one line is still invisible to the suite. The parameter is typed
 * [ActiveGroupViewModel] and the app holds exactly one, so the same-type swap this project has been
 * bitten by five times is not constructible here — only the deletion is. Closing that last step
 * needs a Hilt harness in `:app`; it is recorded in the README's known follow-ups.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ActiveGroupDestinationEffectTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The `Refresh` arm, driven from `LibraryRoute` — which is both the graph's start destination
     * and, since BLOCKING 2, the destination that carries the session's load-once. Asserts the
     * repository CALL COUNT rather than the resulting state: a count on a fake cannot pass when the
     * call never happens, which is what makes it discriminate a deleted effect from a working one.
     */
    @Test
    fun `arriving at a destination that needs the groups fetches them through the ViewModel`() {
        val repository = FakeGroupRepository(groups = listOf(ALPHA))
        val viewModel = ActiveGroupViewModel(repository, FakeActiveGroupStore(initial = null))
        val destination = destinationFor(LibraryRoute)

        composeRule.setContent {
            ActiveGroupDestinationEffect(destination = destination, viewModel = viewModel)
        }
        composeRule.waitForIdle()

        assertEquals(1, repository.groupsCallCount)
        assertEquals(
            ActiveGroupState.Success(groups = listOf(ALPHA), activeGroupId = ALPHA.id),
            viewModel.state.value,
        )
    }

    /**
     * The `Reset` arm. `AuthRoute` is the one destination BOTH a session-expiry logout and a
     * user-initiated sign-out land on, so this branch is the only thing that stops the next account
     * signing in inside the same process from inheriting the previous one's groups — the
     * Activity-scoped `ViewModelStore` survives a sign-out, which is a navigate, not a recreation.
     * Swapping this arm for `refresh()`, or deleting it, leaves the state a stale `Success`.
     */
    @Test
    fun `arriving at AuthRoute resets the previous account's groups`() {
        val repository = FakeGroupRepository(groups = listOf(ALPHA))
        val store = FakeActiveGroupStore(initial = null)
        val viewModel = ActiveGroupViewModel(repository, store)
        var destination by mutableStateOf(destinationFor(LibraryRoute))

        composeRule.setContent {
            ActiveGroupDestinationEffect(destination = destination, viewModel = viewModel)
        }
        composeRule.waitForIdle()
        assertEquals(
            ActiveGroupState.Success(groups = listOf(ALPHA), activeGroupId = ALPHA.id),
            viewModel.state.value,
        )

        destination = destinationFor(AuthRoute)
        composeRule.waitForIdle()

        assertEquals(ActiveGroupState.Loading, viewModel.state.value)
        assertEquals(null, store.setCalls.last())
    }

    /**
     * The load-once, end to end through the effect (BLOCKING 2's other consumer). Two ordinary
     * destinations in a row — the Library-to-Detail shape, standing in for any second authenticated
     * screen — must cost ONE `GET /v1/groups`, not one per screen opened. Making
     * `activeGroupActionFor`'s new branch unconditional passes the first test above and fails this
     * one.
     */
    @Test
    fun `a second ordinary destination does not fetch the groups again`() {
        val repository = FakeGroupRepository(groups = listOf(ALPHA))
        val viewModel = ActiveGroupViewModel(repository, FakeActiveGroupStore(initial = null))
        var destination by mutableStateOf(destinationFor(LibraryRoute))

        composeRule.setContent {
            ActiveGroupDestinationEffect(destination = destination, viewModel = viewModel)
        }
        composeRule.waitForIdle()
        assertEquals(1, repository.groupsCallCount)

        destination = destinationFor(FavoritesRoute)
        composeRule.waitForIdle()

        assertEquals(1, repository.groupsCallCount)
    }

    /**
     * Feed stays an explicit refresh point on EVERY arrival — that is what makes a group created or
     * left elsewhere show up in the switcher. Pinned separately from the load-once above because
     * the two branches answer the same `Refresh` for opposite reasons, and a `when` that lost the
     * `FeedRoute` arm would still pass the first test in this file.
     */
    @Test
    fun `arriving at Feed refetches even after the load-once has fired`() {
        val repository = FakeGroupRepository(groups = listOf(ALPHA))
        val viewModel = ActiveGroupViewModel(repository, FakeActiveGroupStore(initial = null))
        var destination by mutableStateOf(destinationFor(LibraryRoute))

        composeRule.setContent {
            ActiveGroupDestinationEffect(destination = destination, viewModel = viewModel)
        }
        composeRule.waitForIdle()
        assertEquals(1, repository.groupsCallCount)

        destination = destinationFor(FeedRoute)
        composeRule.waitForIdle()

        assertEquals(2, repository.groupsCallCount)
    }

    /**
     * A real [NavDestination], built the way `ActiveGroupActionForTest`/`ShouldShowNavigationTabsTest`
     * build theirs: `hasRoute` parses the destination's route into a deep link, which needs a
     * `Context`, so a hand-rolled stub would not answer the question production asks.
     */
    private fun destinationFor(route: AppRoute): NavDestination {
        val controller = controllerWith(startDestination = route)
        return checkNotNull(controller.currentDestination)
    }

    private fun controllerWith(startDestination: AppRoute): NavHostController =
        NavHostController(ApplicationProvider.getApplicationContext<Context>())
            .apply {
                navigatorProvider.addNavigator(ComposeNavigator())
                graph = buildGraph(startDestination)
            }

    private fun NavHostController.buildGraph(startDestination: AppRoute): NavGraph =
        createGraph(startDestination = startDestination) {
            showTrackDestinations(
                onNavigate = { },
                activeGroup = MutableStateFlow(ActiveGroupState.Loading),
                onSwitchGroup = { },
                onRetryGroups = { },
            )
        }

    private companion object {
        val ALPHA =
            Group(id = "group-alpha", name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T10:15:30Z"))
    }
}
