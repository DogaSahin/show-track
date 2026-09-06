package com.anarky.showtrack.feature.library

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.navigation.LibraryRoute
import com.anarky.showtrack.core.navigation.SearchRoute
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
 * The harness Phase 9a could not build, proved on the exact line it could not pin.
 *
 * `libraryEntry`'s `onSearchClick = searchNavigation(onNavigate)` binding was documented as
 * untestable because composing `LibraryScreen`'s stateful overload resolves a `LibraryViewModel`
 * through `hiltViewModel()`, and no Hilt test harness existed. Change that binding to `{}` and
 * this test must fail — that is the acceptance criterion for this whole task (see the task's own
 * report for the discrimination check this file was run through both ways).
 *
 * `createAndroidComposeRule<HiltTestActivity>()`, not the brief's original `createComposeRule()`:
 * the latter is exactly `createAndroidComposeRule<ComponentActivity>()` under the hood, and a
 * plain `ComponentActivity` is not a Hilt entry point — `hiltViewModel()` needs one. See
 * `HiltTestActivity`'s KDoc for the failure this avoids.
 *
 * `TestNavHostController`, real `libraryEntry()`, and a bare marker `composable<SearchRoute>` —
 * not the real `:feature:search` screen, which this module may never depend on (architecture
 * rule 1). Only whether navigation LANDED on `SearchRoute` is under test here.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class LibraryEntryHiltTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun `tapping the search action navigates to SearchRoute`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController =
                remember {
                    TestNavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            NavHost(navController = navController, startDestination = LibraryRoute) {
                libraryEntry(onNavigate = navController::navigate)
                composable<SearchRoute> { }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.library_search_content_description))
            .performClick()

        assertTrue(navController.currentDestination?.hasRoute(SearchRoute::class) == true)
    }
}
