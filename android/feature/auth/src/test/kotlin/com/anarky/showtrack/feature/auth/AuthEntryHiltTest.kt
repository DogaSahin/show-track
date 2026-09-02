package com.anarky.showtrack.feature.auth

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.navigation.AuthRoute
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
 * The `:feature:library` harness rollout, applied here (task 9c.0, E-L): Phase 9b's whole-branch
 * review found that setting `authEntry`'s `onAuthenticated = authenticatedNavigation(onNavigate)`
 * binding to `{}` leaves the entire suite green, because `authenticatedNavigation` itself is only
 * ever unit-tested directly (`AuthNavigationTest`), never through a composed `authEntry`. This test
 * closes that — see [AuthEntryHiltTest]'s own mutation note in the task report.
 *
 * `createAndroidComposeRule<HiltTestActivity>()`, not `createComposeRule()` — see
 * `HiltTestActivity`'s own KDoc for why a plain `ComponentActivity` cannot host a `hiltViewModel()`
 * call. `TestNavHostController`, real `authEntry()`, and a bare marker `composable<LibraryRoute>` —
 * only whether navigation LANDED on `LibraryRoute` is under test, the same shape
 * `LibraryEntryHiltTest` uses for `SearchRoute`.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AuthEntryHiltTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun `a successful login navigates to LibraryRoute`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController =
                remember {
                    TestNavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            NavHost(navController = navController, startDestination = AuthRoute) {
                authEntry(onNavigate = navController::navigate)
                composable<LibraryRoute> { }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.auth_field_email))
            .performTextInput("someone@example.test")
        composeRule
            .onNodeWithText(context.getString(R.string.auth_field_password))
            .performTextInput("a-strong-password")
        // NOT onNodeWithText: `auth_mode_login` ("Log in", the mode FilterChip's own label) and
        // `auth_submit_login` (the submit Button) are the SAME string resource — measured, this
        // ambiguity is real, not hypothetical: `onNodeWithText` failed here with "found '2' nodes"
        // before this Role filter was added. Role.Button, not Role.Checkbox (the FilterChip's own
        // role), disambiguates them.
        composeRule
            .onNode(hasText(context.getString(R.string.auth_submit_login)) and isButtonRole())
            .performClick()
        // The submit -> login() -> Authenticated state -> LaunchedEffect -> onAuthenticated chain
        // spans more than one recomposition; performClick()'s own idle-wait does not guarantee the
        // LaunchedEffect has fired yet, unlike LibraryEntryHiltTest's direct, synchronous click
        // handler.
        composeRule.waitForIdle()

        assertTrue(navController.currentDestination?.hasRoute(LibraryRoute::class) == true)
    }

    private fun isButtonRole(): SemanticsMatcher =
        SemanticsMatcher("Role = '${Role.Button}'") { node ->
            node.config.getOrNull(SemanticsProperties.Role) == Role.Button
        }
}
