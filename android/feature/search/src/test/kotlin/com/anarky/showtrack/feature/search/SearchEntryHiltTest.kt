package com.anarky.showtrack.feature.search

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.data.search.RecentSearchStore
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.SearchResult
import com.anarky.showtrack.core.model.SearchResults
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.SearchRoute
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * The `:feature:library` harness rollout, applied here (task 9c.0, E-L, resolution 1: `:feature:search`
 * has an `onNavigateToDetail` binding and no entry test — the only module in the original five-module
 * list omission). `SearchNavigation.kt`'s `onNavigateToDetail = { mediaId -> onNavigate(DetailRoute(...)) }`
 * binding is defined INLINE inside `searchEntry()`, unlike `authenticatedNavigation`/`signOutNavigation`
 * — there is no named function a plain unit test could call instead, so closing this gap needs the
 * full Hilt-composed path: type a query, wait out the real 300ms debounce, tap the result, let the
 * add complete, and follow the one-shot `navigateToDetail` channel.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class SearchEntryHiltTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val mediaRepository: MediaRepository =
        EntryFakeMediaRepository(
            searchResult =
                SearchResults(
                    items = listOf(SearchResult(searchSummary(), mediaId = "media-1")),
                    hasMore = false,
                    degraded = emptyList(),
                ),
        )

    @BindValue
    @JvmField
    val libraryRepository: LibraryRepository = EntryFakeLibraryRepository(addResult = addedEntry())

    @BindValue
    @JvmField
    val recentSearchStore: RecentSearchStore =
        object : RecentSearchStore {
            override val recent: Flow<List<String>> = flowOf(emptyList())

            override suspend fun record(query: String) = Unit

            override suspend fun clear() = Unit
        }

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun `tapping a result opens DetailRoute for its id and adds nothing`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController =
                remember {
                    TestNavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            NavHost(navController = navController, startDestination = SearchRoute) {
                searchEntry(onNavigate = navController::navigate)
                composable<DetailRoute> { }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.search_field_placeholder))
            .performTextInput("frieren")

        // The real 300ms debounce (`SearchViewModel.SEARCH_DEBOUNCE_MS`) has to actually elapse:
        // it is scheduled via `Dispatchers.Main`'s Handler-based `delay()`, which Robolectric's
        // PAUSED main looper only runs once idled PAST that virtual time. Compose's own
        // `waitForIdle()` drains work that is already due but does not fast-forward a
        // future-scheduled task, so it cannot substitute for this on its own (measured: without
        // this line the query never resolves and no result row ever appears to click).
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Frieren", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        // The id the result already carried, read off the route itself rather than just its type.
        val mediaId = navController.currentBackStackEntry?.toRoute<DetailRoute>()?.mediaId
        assertEquals("media-1", mediaId)
        // Opening is not adding: the behaviour this screen flipped.
        assertEquals(0, (libraryRepository as EntryFakeLibraryRepository).addCalls)
    }

    /** The "added" snackbar waits for a tap; it must not hold up opening a title meanwhile. */
    @Test
    fun `a title opens while the added snackbar is still up`() {
        val navController = setGraph()
        typeAndSettle("frieren")

        composeRule.onNodeWithContentDescription("Add Frieren to your library").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Frieren added to Planned").assertExists()

        composeRule.onNodeWithText("Frieren", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        assertEquals("media-1", navController.currentBackStackEntry?.toRoute<DetailRoute>()?.mediaId)
    }

    private fun setGraph(): TestNavHostController {
        lateinit var navController: TestNavHostController
        composeRule.setContent {
            navController =
                remember {
                    TestNavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            NavHost(navController = navController, startDestination = SearchRoute) {
                searchEntry(onNavigate = navController::navigate)
                composable<DetailRoute> { }
            }
        }
        return navController
    }

    private fun typeAndSettle(text: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.search_field_placeholder)).performTextInput(text)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        composeRule.waitForIdle()
    }

    private companion object {
        fun searchSummary() =
            MediaSummary(
                source = MediaSource.ANILIST,
                externalId = "154587",
                type = MediaType.ANIME,
                title = "Frieren",
                year = 2023,
                genres = listOf("fantasy"),
                coverImageUrl = null,
            )

        fun addedEntry() =
            LibraryEntry(
                id = "entry-1",
                status = UserMediaStatus.PLANNED,
                score = null,
                progress = 0,
                favorite = false,
                updatedAt = Instant.parse("2026-09-01T10:00:00Z"),
                media =
                    Media(
                        id = "media-1",
                        source = MediaSource.ANILIST,
                        externalId = "154587",
                        type = MediaType.ANIME,
                        title = "Frieren",
                        year = 2023,
                        genres = listOf("fantasy"),
                        coverImageUrl = null,
                        status = MediaStatus.AIRING,
                        nextEpisodeSeason = null,
                        nextEpisodeNumber = null,
                        nextEpisodeDate = null,
                        daysUntilNextEpisode = null,
                    ),
            )
    }
}
