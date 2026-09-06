package com.anarky.showtrack.feature.favorites

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
import com.anarky.showtrack.core.data.repository.LibraryRepository
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.navigation.DetailRoute
import com.anarky.showtrack.core.navigation.FavoritesRoute
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
 * The `:feature:library` harness rollout, applied here (task 9c.0, E-L). `FavoritesNavigation.kt`'s
 * `onEntryClick = { entry -> onNavigate(DetailRoute(mediaId = entry.media.id)) }` binding lives
 * inline inside `favoritesEntry()`, the same untested shape `LibraryNavigation.kt`'s
 * `onSearchClick` binding had before `LibraryEntryHiltTest` — a plain unit test could reach
 * `FavoritesScreen`'s stateless overload directly, but never THIS binding, since composing
 * `favoritesEntry()` resolves `FavoritesViewModel` through `hiltViewModel()`.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class FavoritesEntryHiltTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    // Preset with one entry BEFORE injection: FavoritesViewModel's LifecycleResumeEffect calls
    // `refreshFavorites()` on the very first composition (`FavoritesViewModel`'s own KDoc), and by
    // then this field must already hold what that call publishes — a `@Provides` method in
    // `TestDataModule` would construct a fresh, empty-by-default fake with no way for this test to
    // reach in and set `refreshResult` afterward.
    @BindValue
    @JvmField
    val libraryRepository: LibraryRepository = FakeLibraryRepository(refreshResult = listOf(favouriteEntry()))

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun `tapping a favourite entry navigates to DetailRoute for its media`() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            navController =
                remember {
                    TestNavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
            NavHost(navController = navController, startDestination = FavoritesRoute) {
                favoritesEntry(onNavigate = navController::navigate)
                composable<DetailRoute> { }
            }
        }

        composeRule.onNodeWithText("Frieren").performClick()

        // NOT just `hasRoute(DetailRoute::class)`: `FavoritesNavigation.kt`'s binding reads
        // `entry.media.id`, but `LibraryEntry` ALSO carries its own, different `id` in scope at the
        // same call site — `entry.id` (the library ROW's id, "entry-1") vs. `entry.media.id` (the
        // TITLE's id, "media-1"). Both are `String`, so swapping one for the other is a real,
        // type-checked, silently-wrong-navigation bug a route-type-only assertion cannot see
        // (measured, round 1 fix: it did not).
        val mediaId = navController.currentBackStackEntry?.toRoute<DetailRoute>()?.mediaId
        assertEquals("media-1", mediaId)
    }

    private companion object {
        fun favouriteEntry() =
            LibraryEntry(
                id = "entry-1",
                status = UserMediaStatus.WATCHING,
                score = null,
                progress = 4,
                favorite = true,
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
