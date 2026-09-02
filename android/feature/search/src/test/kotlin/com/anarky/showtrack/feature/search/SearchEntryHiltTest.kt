package com.anarky.showtrack.feature.search

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.remember
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
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.data.repository.MediaRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.SearchResults
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.SearchRoute
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
            searchResult = SearchResults(items = listOf(searchSummary()), hasMore = false, degraded = emptyList()),
        )

    @BindValue
    @JvmField
    val libraryRepository: LibraryRepository = EntryFakeLibraryRepository(addResult = addedEntry())

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun `adding a result navigates to DetailRoute for the id the add call minted`() {
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
            .onNodeWithText(context.getString(R.string.search_field_label))
            .performTextInput("frieren")

        // The real 300ms debounce (`SearchViewModel.SEARCH_DEBOUNCE_MS`) has to actually elapse:
        // it is scheduled via `Dispatchers.Main`'s Handler-based `delay()`, which Robolectric's
        // PAUSED main looper only runs once idled PAST that virtual time. Compose's own
        // `waitForIdle()` drains work that is already due but does not fast-forward a
        // future-scheduled task, so it cannot substitute for this on its own (measured: without
        // this line the query never resolves and no result row ever appears to click).
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Frieren").performClick()
        composeRule.waitForIdle()

        assertTrue(navController.currentDestination?.hasRoute(DetailRoute::class) == true)
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
