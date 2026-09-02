package com.anarky.showtrack.feature.discover

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
import com.anarky.showtrack.core.data.repository.RecommendationRepository
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.Recommendation
import com.anarky.showtrack.core.model.RecommendationReason
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.DiscoverRoute
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

/**
 * The `:feature:library` harness rollout, applied here (task 9c.0, E-L). `DiscoverNavigation.kt`'s
 * `onNavigate(DetailRoute(mediaId))` binding lives inline inside `discoverEntry()`, wired to
 * `DiscoverScreen`'s `onNavigateToDetail` — the same untested-binding shape `LibraryNavigation.kt`'s
 * `onSearchClick` had before `LibraryEntryHiltTest`. Unlike Search, a recommendation row's tap fires
 * directly (`Recommendation.media` already has a real id — no add-first round trip), so no debounce
 * or async add is in the way here.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class DiscoverEntryHiltTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    // Preset BEFORE injection — see EntryFakeRecommendationRepository's own KDoc: DiscoverViewModel
    // calls refresh() from init {}, the instant hiltViewModel() constructs it.
    @BindValue
    @JvmField
    val recommendationRepository: RecommendationRepository =
        EntryFakeRecommendationRepository(refreshResult = listOf(recommendation()))

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun `tapping a recommendation row navigates to DetailRoute for its media`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController =
                remember {
                    TestNavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            NavHost(navController = navController, startDestination = DiscoverRoute) {
                discoverEntry(onNavigate = navController::navigate)
                composable<DetailRoute> { }
            }
        }

        composeRule.onNodeWithText("Frieren").performClick()

        assertTrue(navController.currentDestination?.hasRoute(DetailRoute::class) == true)
    }

    private companion object {
        fun recommendation() =
            Recommendation(
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
                        status = MediaStatus.NOT_YET_AIRED,
                        nextEpisodeSeason = null,
                        nextEpisodeNumber = null,
                        nextEpisodeDate = null,
                        daysUntilNextEpisode = null,
                    ),
                reason =
                    RecommendationReason(
                        seedMediaId = "seed-1",
                        seedTitle = "Made in Abyss",
                        matchedGenres = listOf("fantasy"),
                    ),
            )
    }
}
