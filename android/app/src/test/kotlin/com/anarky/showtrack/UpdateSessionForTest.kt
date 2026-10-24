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
import com.anarky.showtrack.core.navigation.ImportRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import com.anarky.showtrack.update.UpdateSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `updateSessionFor` decides when the update check may run and when its state is thrown away, so
 * each cell here is a prompt that would otherwise cover the sign-in screen or survive into the next
 * account. Same Robolectric NavController setup as `ShouldShowNavigationTabsTest`, for a real
 * `NavDestination`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateSessionForTest {
    @Test
    fun `no destination yet is unknown, whatever start says`() {
        assertEquals(UpdateSession.Unknown, updateSessionFor(AppStart.Library, null))
        assertEquals(UpdateSession.Unknown, updateSessionFor(AppStart.Undecided, null))
    }

    @Test
    fun `the sign-in screen is signed out, even for a session that started signed in`() {
        val destination = controllerWith { graphStartingAt(AuthRoute) }.currentDestination

        assertEquals(UpdateSession.SignedOut, updateSessionFor(AppStart.Library, destination))
        assertEquals(UpdateSession.SignedOut, updateSessionFor(AppStart.Auth, destination))
    }

    @Test
    fun `a Library or Onboarding start off the sign-in screen is signed in`() {
        val library = controllerWith { graphStartingAt(LibraryRoute) }.currentDestination
        val import = controllerWith { graphStartingAt(ImportRoute) }.currentDestination

        assertEquals(UpdateSession.SignedIn, updateSessionFor(AppStart.Library, library))
        assertEquals(UpdateSession.SignedIn, updateSessionFor(AppStart.Onboarding, import))
    }

    @Test
    fun `an undecided start off the sign-in screen is still unknown`() {
        val destination = controllerWith { graphStartingAt(LibraryRoute) }.currentDestination

        assertEquals(UpdateSession.Unknown, updateSessionFor(AppStart.Undecided, destination))
    }

    private fun controllerWith(graph: NavHostController.() -> NavGraph): NavHostController =
        NavHostController(ApplicationProvider.getApplicationContext<Context>())
            .apply {
                navigatorProvider.addNavigator(ComposeNavigator())
                this.graph = graph()
            }

    private fun NavHostController.graphStartingAt(start: Any) =
        createGraph(startDestination = start) {
            showTrackDestinations(
                onNavigate = { },
                activeGroup = MutableStateFlow(ActiveGroupState.Loading),
                onSwitchGroup = {},
                onRetryGroups = {},
            )
        }
}
