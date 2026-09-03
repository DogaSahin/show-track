package com.anarky.showtrack.feature.groups

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.navigation.GroupDetailRoute
import com.anarky.showtrack.core.navigation.GroupsRoute
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The `:feature:library`/`:feature:favorites` harness rollout, applied here (task 9c.1 — this
 * module was excluded from task 9c.0's own rollout because `groupsEntry()` took no `onNavigate`
 * parameter at all until this task gave the screen somewhere real to go; see ruling 3 in the
 * plan's `progress.md`). `GroupsNavigation.kt`'s `onGroupClick = { group -> onNavigate(GroupDetailRoute(groupId
 * = group.id)) }` binding lives inline inside `groupsEntry()` — a plain unit test can reach
 * `GroupsScreen`'s stateless overload directly, but never THIS binding, since composing
 * `groupsEntry()` resolves [GroupsViewModel] through `hiltViewModel()`.
 *
 * Asserts WHICH route the navigation reached, not merely that navigation happened at all — a
 * plain `hasRoute(GroupDetailRoute::class)` check would pass even if the binding read the wrong
 * field.
 *
 * **Fix round 1 (BLOCKING 3):** the previous version of this test used a ONE-group fixture, which
 * cannot tell "the tapped row's own id" apart from "always the first group's id" — a
 * `GroupsList`/`GroupRow` bug that hands every row's click straight to `groups.first()` regardless
 * of which one was tapped would have passed this test AND every `GroupsScreenTest` assertion that
 * existed at the time (measured: it did). A TWO-group fixture, tapping the SECOND one, is what
 * actually discriminates "reads the tapped group" from "reads some fixed group" — the same failure
 * shape the dispatch named for `entry.media.id` vs `entry.id`, one layer lower (the row itself,
 * not just the navigation binding above it).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class GroupsEntryHiltTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    // Preset with TWO groups BEFORE injection (fix round 1 — see this class's own KDoc for why
    // one is not enough to discriminate this test): GroupsViewModel's LifecycleResumeEffect calls
    // `refresh()` on the very first composition (GroupsViewModel's own KDoc), and by then this
    // field must already hold what that call publishes — FavoritesEntryHiltTest's identical setup.
    @BindValue
    @JvmField
    val groupRepository: GroupRepository = FakeGroupRepository(groupsResult = listOf(ALPHA, BETA))

    // GroupsViewModel never reads this — it exists only so this test's Hilt component can resolve
    // GroupDetailViewModel's own AuthRepository dependency for the whole-component validation Hilt
    // performs on every @HiltViewModel in the module, `TestDataModule`'s own KDoc explains why this
    // lives here (one `@BindValue` per test class) rather than as a module-wide `@Provides` default.
    @BindValue
    @JvmField
    val authRepository: AuthRepository = FakeAuthRepository()

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun `tapping a group navigates to GroupDetailRoute for that group's id, not the first one`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController =
                remember {
                    TestNavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            NavHost(navController = navController, startDestination = GroupsRoute) {
                groupsEntry(onNavigate = navController::navigate)
                composable<GroupDetailRoute> { }
            }
        }

        composeRule.onNodeWithText(BETA.name).performClick()

        val groupId = navController.currentBackStackEntry?.toRoute<GroupDetailRoute>()?.groupId
        assertEquals(BETA.id, groupId)
    }

    private companion object {
        val ALPHA =
            Group(id = "group-alpha", name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T10:15:30Z"))
        val BETA =
            Group(id = "group-beta", name = "Beta Watchers", createdAt = Instant.parse("2026-08-29T09:00:00Z"))
    }
}
