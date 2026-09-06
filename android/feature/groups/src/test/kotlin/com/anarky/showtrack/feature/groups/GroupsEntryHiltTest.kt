package com.anarky.showtrack.feature.groups

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.data.repository.GroupWithInvite
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.Group
import com.anarky.showtrack.core.navigation.GroupDetailRoute
import com.anarky.showtrack.core.navigation.GroupsRoute
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.MutableStateFlow
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
 *
 * **Fix round 2 (task 9c.5), BLOCKING B1.** A new test below composes the REAL `groupsEntry`
 * through Hilt, driven by a `StateFlow<ActiveGroupState>` this test mutates directly — the same
 * discipline `FeedEntryHiltTest`'s own two new tests apply, for the identical reason: a
 * hand-rolled marker (`GroupSwitchNavHostTest`'s original, now-removed test) can prove a navigation
 * library property, never this module's own wiring.
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
    // Declared as the CONCRETE fake, not `GroupRepository`: two tests below reconfigure
    // `groupsResult`/`joinResult` per case. `@BindValue` binds by the DECLARED type, so this still
    // has to name the interface somewhere — the explicit `: GroupRepository` on the binding is
    // provided by `@BindValue`'s own inspection of the supertype, and Hilt resolves it because the
    // fake implements exactly one repository interface.
    @BindValue
    @JvmField
    val groupRepository: GroupRepository = FakeGroupRepository(groupsResult = listOf(ALPHA, BETA))

    private val fakeGroups: FakeGroupRepository get() = groupRepository as FakeGroupRepository

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
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = GroupsRoute) {
                // activeGroup = no selection (a static, never-emitting-again MutableStateFlow):
                // this test is about the navigation binding, not the switcher — see
                // GroupsScreenTest's own switcher tests, and this class's own switcher test below.
                // Loading resolves to no active group, so the switcher never renders (GroupsScreen's
                // own null check), keeping BETA.name unambiguous — the plain list row only.
                groupsEntry(
                    activeGroup = MutableStateFlow(ActiveGroupState.Loading),
                    onSwitchGroup = {},
                    onGroupsChanged = {},
                    onNavigate = navController::navigate,
                )
                composable<GroupDetailRoute> { }
            }
        }

        composeRule.onNodeWithText(BETA.name).performClick()

        val groupId = navController.currentBackStackEntry?.toRoute<GroupDetailRoute>()?.groupId
        assertEquals(BETA.id, groupId)
    }

    /**
     * BLOCKING B1's proof for `groupsEntry`: the REAL screen, through Hilt, reacts to the
     * `activeGroup` flow changing — no navigation involved — by moving which switcher tab reports
     * itself selected.
     */
    @Test
    fun `changing the activeGroup flow moves which switcher tab is selected, without navigating`() {
        // groupsResult stays [ALPHA, BETA] from the field above: this test is about the flow moving
        // the SELECTION. Which list the tabs come from is the next test's job.
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(
                ActiveGroupState.Success(groups = listOf(ALPHA, BETA), activeGroupId = ALPHA.id),
            )
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = GroupsRoute) {
                groupsEntry(
                    activeGroup = activeGroup,
                    onSwitchGroup = {},
                    onGroupsChanged = {},
                    onNavigate = navController::navigate,
                )
                composable<GroupDetailRoute> { }
            }
        }
        composeRule.waitForIdle()

        // ALPHA.name is genuinely ambiguous with two groups active (the switcher tab AND the list
        // row both render it) — .onFirst() is the switcher's own tab, GroupsScreen's own ordering
        // (the switcher renders above GroupsContent).
        composeRule.onAllNodesWithText(ALPHA.name).onFirst().assertIsSelected()

        activeGroup.value = ActiveGroupState.Success(groups = listOf(ALPHA, BETA), activeGroupId = BETA.id)
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(BETA.name).onFirst().assertIsSelected()
        // No navigation happened — still on GroupsRoute alone.
        assertEquals(listOf(null, GroupsRoute::class.qualifiedName), navController.backStackRoutes())
    }

    /**
     * BLOCKING 3 at the `groupsEntry` seam (whole-branch fix round). `GroupsScreenTest` pins the
     * stateless overload's use of `switcherGroups`; this pins that the REAL entry actually feeds it
     * from `activeGroup` rather than from `GroupsViewModel`'s own list, through Hilt, with the two
     * deliberately disagreeing: the repository (and therefore `GroupsUiState.Success.groups`) has
     * ALPHA alone, while the active-group flow has ALPHA and BETA. That is not a contrived state —
     * it is exactly what a create or join produces on the OTHER side, and the reverse of it is what
     * shipped: a tab offered from a list `ActiveGroupViewModel.recompute` would then reject.
     *
     * `BETA.name` can only come from a switcher tab here, since no list row exists for it.
     */
    @Test
    fun `the switcher's tabs come from the active-group flow, not this screen's own list`() {
        fakeGroups.groupsResult = listOf(ALPHA)
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(
                ActiveGroupState.Success(groups = listOf(ALPHA, BETA), activeGroupId = ALPHA.id),
            )
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = GroupsRoute) {
                groupsEntry(
                    activeGroup = activeGroup,
                    onSwitchGroup = {},
                    onGroupsChanged = {},
                    onNavigate = navController::navigate,
                )
                composable<GroupDetailRoute> { }
            }
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(BETA.name).assertCountEquals(1)
    }

    /**
     * The other half of BLOCKING 3's fix: with one owner of "which groups exist", a create or join
     * made on THIS screen has to tell that owner, or the group the user just joined does not reach
     * the switcher until they navigate away and back. `:app` binds `onGroupsChanged` to
     * `ActiveGroupViewModel::refresh`; this pins that `groupsEntry` actually invokes it on a
     * successful join. Mutating the binding in `GroupsNavigation.kt` to `onGroupsChanged = {}`, or
     * deleting the `LaunchedEffect` in `GroupsScreen`'s stateful overload, fails only this test.
     */
    @Test
    fun `a successful join reports that the group set changed`() {
        fakeGroups.joinResult =
            GroupWithInvite(
                group = BETA,
                inviteCode = "ABCDEFGHIJ1234567890",
                expiresAt = Instant.parse("2026-09-10T00:00:00Z"),
            )
        var changed = 0
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = GroupsRoute) {
                groupsEntry(
                    activeGroup = MutableStateFlow(ActiveGroupState.Loading),
                    onSwitchGroup = {},
                    onGroupsChanged = { changed++ },
                    onNavigate = navController::navigate,
                )
                composable<GroupDetailRoute> { }
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, changed)

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.groups_join_action)).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.groups_join_code_label))
            .performTextInput("ABCDEFGHIJ1234567890")
        composeRule.onNodeWithText(context.getString(R.string.groups_join_submit)).performClick()
        composeRule.waitForIdle()

        assertEquals(1, changed)
    }

    @Composable
    private fun rememberTestNavController(): TestNavHostController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return remember {
            TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
        }
    }

    private fun TestNavHostController.backStackRoutes() =
        currentBackStack.value.map { entry -> entry.destination.route?.substringBefore('/') }

    private companion object {
        val ALPHA =
            Group(id = "group-alpha", name = "Alpha Watchers", createdAt = Instant.parse("2026-08-28T10:15:30Z"))
        val BETA =
            Group(id = "group-beta", name = "Beta Watchers", createdAt = Instant.parse("2026-08-29T09:00:00Z"))
    }
}
