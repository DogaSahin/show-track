package com.anarky.showtrack.feature.profile

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.navigation.AuthRoute
import com.anarky.showtrack.core.navigation.GroupsRoute
import com.anarky.showtrack.core.navigation.ImportRoute
import com.anarky.showtrack.core.navigation.ProfileRoute
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
 * The `:feature:library` harness rollout, applied here (task 9c.0, E-L). `ProfileNavigation.kt`'s
 * own KDoc names this exact gap: `profileEntry`'s `onSignedOut = signOutNavigation(onNavigate)` and
 * `onImportClick = importNavigation(onNavigate)` bindings are the ones a Hilt-composed test is
 * needed to close — `signOutNavigation`/`importNavigation` themselves are already unit-tested
 * directly (`ProfileNavigationTest`), but the BINDING that wires either of them into the real
 * `profileEntry()` composable was not, until this test.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ProfileEntryHiltTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun `tapping import navigates to ImportRoute`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController =
                remember {
                    TestNavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            NavHost(navController = navController, startDestination = ProfileRoute) {
                profileEntry(onNavigate = navController::navigate)
                composable<ImportRoute> { }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        // `performScrollTo()` first — see `confirming sign-out navigates to AuthRoute`'s own note:
        // Robolectric's default root does not auto-size to this screen's content.
        composeRule
            .onNodeWithText(context.getString(R.string.profile_import_action))
            .performScrollTo()
            .performClick()

        assertTrue(navController.currentDestination?.hasRoute(ImportRoute::class) == true)
    }

    /**
     * Decision E-A's Profile -> Groups door (whole-branch fix round). Composes the REAL
     * [profileEntry], so the only thing that can make this pass is the binding
     * `onGroupsClick = groupsNavigation(onNavigate)` actually being there — mutating it to
     * `onGroupsClick = {}` leaves `ProfileNavigationTest`'s mapping test and every other test in
     * this module green while the door goes back to not existing, which is precisely the failure
     * mode `ProfileNavigation.kt`'s gap 3 documents and the reason this test was written with the
     * feature rather than after it.
     */
    @Test
    fun `tapping manage groups navigates to GroupsRoute`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController =
                remember {
                    TestNavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            NavHost(navController = navController, startDestination = ProfileRoute) {
                profileEntry(onNavigate = navController::navigate)
                composable<GroupsRoute> { }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        // `performScrollTo()` first, for the reason the other two tests in this class document:
        // Robolectric's default root does not auto-size to this screen's content, and a click on a
        // node scrolled out of view succeeds at the semantics-tree level while doing nothing.
        composeRule
            .onNodeWithText(context.getString(R.string.profile_groups_action))
            .performScrollTo()
            .performClick()

        assertTrue(navController.currentDestination?.hasRoute(GroupsRoute::class) == true)
    }

    @Test
    fun `confirming sign-out navigates to AuthRoute`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController =
                remember {
                    TestNavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            NavHost(navController = navController, startDestination = ProfileRoute) {
                profileEntry(onNavigate = navController::navigate)
                composable<AuthRoute> { }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val signOutText = context.getString(R.string.profile_sign_out)
        // `performScrollTo()` before the click, matching `ProfileScreenTest`'s own
        // "sign-out is reachable by scrolling" test: Robolectric's default root does not auto-size
        // to content, so the sign-out button is genuinely off the fold at this screen's real
        // height — a `performClick()` on a node scrolled out of view lands nowhere, succeeding at
        // the semantics-tree level while doing nothing (measured: the dialog never opened without
        // this).
        //
        // Unambiguous: the confirm dialog is not yet on screen, so exactly one "Sign out" node
        // exists at this point — `profile_sign_out` and `profile_sign_out_confirm_action` are the
        // SAME string resource, but only after this tap does a second one appear.
        composeRule.onNodeWithText(signOutText).performScrollTo().performClick()
        composeRule.waitForIdle()

        // The confirm dialog's button is the SECOND "Sign out" node in the merged tree — the
        // screen's own TextButton is composed first, the AlertDialog's confirm TextButton second
        // (measured).
        composeRule.onAllNodesWithText(signOutText).get(1).performClick()
        composeRule.waitForIdle()

        assertTrue(navController.currentDestination?.hasRoute(AuthRoute::class) == true)
    }
}
