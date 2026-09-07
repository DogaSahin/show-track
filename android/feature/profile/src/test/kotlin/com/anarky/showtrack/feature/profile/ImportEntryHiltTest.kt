package com.anarky.showtrack.feature.profile

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.navigation.ImportRoute
import com.anarky.showtrack.core.navigation.LibraryRoute
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `:feature:library` harness rollout, applied here (task 9c.0, E-L). One of the four bindings
 * the brief named by name (round 1 fix — the original submission closed `authEntry`'s
 * `onAuthenticated` and Discover's/Favorites' Detail bindings but left this one open, with
 * `ImportNavigation.kt`'s own KDoc still claiming "this module has no Hilt harness for" it after
 * `:feature:profile` had in fact adopted one for `ProfileEntryHiltTest`).
 *
 * `importEntry`'s `onFinished = importFinishedNavigation(onNavigate)` binding lives inline inside
 * the `composable<ImportRoute> { }` lambda, which composes `ImportScreen` WITHOUT passing
 * `viewModel` — evaluating its `hiltViewModel()` default, the same untested-binding shape
 * `LibraryNavigation.kt`'s `onSearchClick` had before `LibraryEntryHiltTest`. The Skip action is
 * the cheapest way to fire `onFinished`: it requires no repository call at all
 * (`onFinishedOnce` → `onFinished()` runs synchronously off the tap), unlike the terminal
 * "Done" button, which would need a completed import first.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ImportEntryHiltTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun `tapping skip navigates to LibraryRoute`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController =
                remember {
                    TestNavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            NavHost(navController = navController, startDestination = ImportRoute) {
                importEntry(onNavigate = navController::navigate)
                composable<LibraryRoute> { }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.import_skip)).performClick()

        assertTrue(navController.currentDestination?.hasRoute(LibraryRoute::class) == true)
    }
}
