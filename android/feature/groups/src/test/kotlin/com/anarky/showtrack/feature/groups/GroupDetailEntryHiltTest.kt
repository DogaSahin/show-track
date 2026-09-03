package com.anarky.showtrack.feature.groups

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
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.model.GroupMember
import com.anarky.showtrack.core.model.GroupRole
import com.anarky.showtrack.core.navigation.GroupDetailRoute
import com.anarky.showtrack.core.navigation.GroupsRoute
import dagger.hilt.android.testing.BindValue
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
import java.time.Instant

/**
 * `:feature:groups` E-L harness rollout, this task's own entry binding
 * (`GroupsEntryHiltTest`'s pattern, task 9c.1 — this task's own brief names it as the model).
 *
 * Pins the WHOLE chain a confirmed leave has to go through to reach [GroupsRoute]:
 * [GroupDetailViewModel.leaveGroup] (real, resolved through `hiltViewModel()` — nothing here
 * constructs a ViewModel by hand) → [GroupDetailViewModel.left] flipping `true` → the stateful
 * [GroupDetailScreen]'s `LaunchedEffect(left)` reacting to it → [groupDetailEntry]'s real
 * `leaveNavigation` binding → `onNavigate(GroupsRoute)`. A `leaveNavigation` mutated to `onLeft = {}`
 * — the exact BLOCKING-3-shaped gap `GroupsEntryHiltTest`'s own KDoc documents for `onGroupClick`
 * one screen over — would leave every OTHER test in this module green while this tap did nothing at
 * all; see this task's own report for the mutation evidence.
 *
 * Asserts WHICH route the navigation reached (`hasRoute(GroupsRoute::class)`), not merely that some
 * navigation happened — `GroupsEntryHiltTest`'s identical discipline.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class GroupDetailEntryHiltTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    // membersResult must be set BEFORE injection — GroupDetailViewModel's own `init { refresh() }`
    // fires on construction, resolved through hiltViewModel() the moment GroupDetailScreen first
    // composes, and by then this field must already hold what that call publishes
    // (GroupsEntryHiltTest's identical setup note for GroupsViewModel's resume load).
    @BindValue
    @JvmField
    val groupRepository: GroupRepository = FakeGroupRepository(membersResult = listOf(SELF))

    // Round 1 review moved identity to AuthRepository (that method's own KDoc) — GroupDetailViewModel
    // now names it as a second constructor dependency, so the Hilt graph needs a binding for it too.
    // SELF is a MEMBER, not the OWNER, deliberately: round 1 review's own minor 1 measured that
    // "Leave group" gated on `isOwner` left every screen test green except THIS one, whose fixture
    // happened to be a member — kept that way here so this test keeps covering that exact case.
    @BindValue
    @JvmField
    val authRepository: AuthRepository = FakeAuthRepository(currentUserIdResult = SELF.userId)

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun `confirming leave navigates to GroupsRoute`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController =
                remember {
                    TestNavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            NavHost(navController = navController, startDestination = GroupDetailRoute(groupId = GROUP_ID)) {
                groupDetailEntry(onNavigate = navController::navigate)
                composable<GroupsRoute> { }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_action)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.groups_detail_leave_confirm_button)).performClick()
        composeRule.waitForIdle()

        assertTrue(navController.currentDestination?.hasRoute(GroupsRoute::class) == true)
    }

    private companion object {
        const val GROUP_ID = "group-1"
        val SELF =
            GroupMember(
                userId = "user-self",
                username = "alex",
                role = GroupRole.MEMBER,
                joinedAt = Instant.parse("2026-08-28T10:15:30Z"),
            )
    }
}
