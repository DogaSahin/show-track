package com.anarky.showtrack.feature.feed

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.data.repository.FeedPage
import com.anarky.showtrack.core.data.repository.GroupRepository
import com.anarky.showtrack.core.model.ActiveGroupState
import com.anarky.showtrack.core.model.ActivityKind
import com.anarky.showtrack.core.model.FeedEntry
import com.anarky.showtrack.core.model.GroupActor
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.FeedRoute
import com.anarky.showtrack.core.navigation.GroupsRoute
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * `:feature:feed` E-L harness rollout, this task's own entry binding — Ruling 3 (`progress.md`'s
 * pre-flight conflict scan): "`:feature:groups` and `:feature:feed` are built in 9c.1 and 9c.4, so
 * each of those tasks carries its own entry test rather than deferring to 9c.0, which cannot test
 * an entry that does not exist yet." `GroupDetailEntryHiltTest`'s pattern is the model this task's
 * own brief names.
 *
 * Pins the WHOLE chain a tap on a media-bearing row has to go through to reach `DetailRoute`:
 * [FeedViewModel] (real, resolved through `hiltViewModel()` — nothing here constructs a ViewModel
 * by hand) fetching the group's feed → the stateful [FeedScreen]'s real `onEntryClick` binding →
 * [feedEntry]'s real `onNavigate(DetailRoute(mediaId = ...))` call. A `feedEntry` mutated to
 * `onEntryClick = {}` — the exact BLOCKING-3-shaped gap `GroupsEntryHiltTest`'s own KDoc documents
 * for `onGroupClick` — would leave every OTHER test in this module green while this tap did
 * nothing at all; see this task's own report for the mutation evidence.
 *
 * Asserts WHICH route the navigation reached AND which `mediaId` it carried
 * (`toRoute<DetailRoute>().mediaId`), not merely that navigation happened —
 * `GroupDetailEntryHiltTest`'s identical discipline, this task's own carried-forward instruction.
 *
 * A TWO-element fixture, tapping the SECOND row — `GroupsScreenTest`'s "not the first one" lesson,
 * applied at the entry-binding layer too: a one-item fixture cannot tell "the tapped row's own
 * `mediaId`" apart from "always the first row's `mediaId`".
 *
 * **Fix round 1, BLOCKING B1.** Two new tests below compose the REAL `feedEntry` — not a hand-rolled
 * marker — through Hilt, and are what actually pin the reactive `StateFlow<ActiveGroupState>`
 * wiring `AppDestination.kt`/`ShowTrackNavHost.kt` depend on. Review measured that mutating
 * `ShowTrackNavHost.kt`'s real `ActiveGroupViewModel` wiring down to inert `MutableStateFlow`
 * defaults left all 558 pre-fix-round-1 tests green — the composed-`NavHost` test this task
 * shipped for that (`GroupSwitchNavHostTest`) never referenced `feedEntry`/`appDestinations`/
 * `showTrackDestinations` at all, so it could not have caught it either. These two do: they call
 * `feedEntry` itself, and a regression in `AppDestination.kt`'s wiring cannot make them pass, only
 * a regression here that this file does not exercise could hide.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class FeedEntryHiltTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    // feedPages must be set BEFORE injection — FeedViewModel's own fetch fires from FeedScreen's
    // LifecycleResumeEffect the moment it first composes, and by then this field must already
    // hold what that call publishes (GroupDetailEntryHiltTest's identical setup note).
    @BindValue
    @JvmField
    val groupRepository: GroupRepository =
        FakeGroupRepository(
            feedPages =
                mutableMapOf(
                    (GROUP_ID to null) to FeedPage(items = listOf(ADDED, RATED), nextCursor = null),
                    (OTHER_GROUP_ID to null) to FeedPage(items = listOf(ADDED), nextCursor = null),
                ),
        )

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun `tapping a feed entry with media navigates to DetailRoute with that entry's mediaId`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = FeedRoute) {
                feedEntry(
                    activeGroup =
                        MutableStateFlow(
                            ActiveGroupState.Success(groups = emptyList(), activeGroupId = GROUP_ID),
                        ),
                    onSwitchGroup = {},
                    onRetryGroups = {},
                    onNavigate = navController::navigate,
                )
                composable<DetailRoute> { }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.feed_entry_rated, ACTOR.username, MEDIA_RATED.title))
            .performClick()
        composeRule.waitForIdle()

        assertTrue(navController.currentDestination?.hasRoute(DetailRoute::class) == true)
        assertTrue(navController.currentBackStackEntry?.toRoute<DetailRoute>()?.mediaId == RATED.mediaId)
    }

    /**
     * BLOCKING B1's actual proof: the REAL `feedEntry`, driven by a `StateFlow<ActiveGroupState>`
     * this test itself mutates — no navigation happens (the back stack shape is asserted unchanged
     * before and after), so the re-fetch can only be explained by the reactive collection this
     * task's design decision depends on.
     */
    @Test
    fun `changing the activeGroup flow re-scopes the fetch to the new group, without navigating`() {
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(ActiveGroupState.Success(groups = emptyList(), activeGroupId = GROUP_ID))
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = FeedRoute) {
                feedEntry(
                    activeGroup = activeGroup,
                    onSwitchGroup = {},
                    onRetryGroups = {},
                    onNavigate = navController::navigate,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf(GROUP_ID to null), (groupRepository as FakeGroupRepository).feedCalls)

        activeGroup.value = ActiveGroupState.Success(groups = emptyList(), activeGroupId = OTHER_GROUP_ID)
        composeRule.waitForIdle()

        val expectedCalls = listOf(GROUP_ID to null, OTHER_GROUP_ID to null)
        assertEquals(expectedCalls, (groupRepository as FakeGroupRepository).feedCalls)
        // No navigation happened at all — the back stack is exactly what it was at start.
        assertEquals(listOf(null, FeedRoute::class.qualifiedName), navController.backStackRoutes())
    }

    /** BLOCKING B2's real-binding proof: the create-or-join action reaches a genuine `GroupsRoute`. */
    @Test
    fun `tapping the no-groups action navigates to GroupsRoute`() {
        val activeGroup =
            MutableStateFlow<ActiveGroupState>(ActiveGroupState.Success(groups = emptyList(), activeGroupId = null))
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = FeedRoute) {
                feedEntry(
                    activeGroup = activeGroup,
                    onSwitchGroup = {},
                    onRetryGroups = {},
                    onNavigate = navController::navigate,
                )
                composable<GroupsRoute> { }
            }
        }
        composeRule.waitForIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.feed_no_group_action)).performClick()
        composeRule.waitForIdle()

        assertTrue(navController.currentDestination?.hasRoute(GroupsRoute::class) == true)
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
        const val GROUP_ID = "group-1"
        const val OTHER_GROUP_ID = "group-2"
        val ACTOR = GroupActor(id = "user-1", username = "alex")
        val MEDIA_ADDED =
            MediaSummary(
                source = MediaSource.ANILIST,
                externalId = "Frieren",
                type = MediaType.ANIME,
                title = "Frieren",
                year = 2024,
                genres = emptyList(),
                coverImageUrl = null,
            )
        val MEDIA_RATED =
            MediaSummary(
                source = MediaSource.ANILIST,
                externalId = "Dandadan",
                type = MediaType.ANIME,
                title = "Dandadan",
                year = 2024,
                genres = emptyList(),
                coverImageUrl = null,
            )
        val ADDED =
            FeedEntry(
                id = "entry-added",
                actor = ACTOR,
                kind = ActivityKind.ADDED,
                media = MEDIA_ADDED,
                mediaId = "media-added",
                payload = emptyMap(),
                createdAt = Instant.parse("2026-08-28T10:15:30Z"),
            )
        val RATED =
            FeedEntry(
                id = "entry-rated",
                actor = ACTOR,
                kind = ActivityKind.RATED,
                media = MEDIA_RATED,
                mediaId = "media-rated",
                payload = mapOf("score" to "9.0"),
                createdAt = Instant.parse("2026-08-28T11:00:00Z"),
            )
    }
}
