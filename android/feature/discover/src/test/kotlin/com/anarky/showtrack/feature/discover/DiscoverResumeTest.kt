package com.anarky.showtrack.feature.discover

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.Recommendation
import com.anarky.showtrack.core.model.RecommendationReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The user-path half of task 9c.8's (E-M) resume decision: `DiscoverViewModelTest`'s resume-shape
 * tests prove [DiscoverViewModel.refresh] behaves correctly IF called a second time; this test
 * proves it actually GETS called, on the one signal available (`DiscoverScreen`'s
 * `LifecycleResumeEffect`) — the thing no ViewModel-only test can see. Modelled directly on
 * `FavoritesResumeTest`/`ProfileResumeTest`, including their own reasoning for why this needs no
 * Hilt harness.
 *
 * This is the direct regression guard for the acceptance criterion named in this task's own brief:
 * add a title from Detail or Search, return to Discover, and — because this effect fires — the
 * next resume's fetch excludes it, since the backend's own contract already excludes anything in
 * the library. A ViewModel-only test can pin that a SECOND [DiscoverViewModel.refresh] call drops
 * an item the fake no longer returns (mirroring `FavoritesViewModelTest`'s "refresh drops an entry
 * the server no longer returns"), but not that anything in production actually calls it a second
 * time at all — that is exactly this test's job.
 *
 * `createAndroidComposeRule<ComponentActivity>()`, not `createComposeRule()`: only the explicit
 * form exposes `.activityRule.scenario`, which is what lets this test drive the Activity through
 * CREATED -> RESUMED via [androidx.test.core.app.ActivityScenario.moveToState] — the same mechanism
 * Android itself uses under a real "returned from another screen" navigation, rather than reaching
 * into [DiscoverViewModel] directly.
 *
 * No Hilt harness needed: `DiscoverScreen`'s stateful overload takes `viewModel` as an ordinary
 * parameter with a `hiltViewModel()` DEFAULT — passing one explicitly here (a plain
 * [DiscoverViewModel] built against [FakeRecommendationRepository]/[FakeLibraryRepository]) never
 * evaluates that default, so a bare `ComponentActivity` is enough.
 */
@RunWith(RobolectricTestRunner::class)
class DiscoverResumeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `resuming the screen re-fetches recommendations, the way returning from Search actually does`() {
        val recommendations = FakeRecommendationRepository(refreshResult = listOf(FRIEREN, BEBOP))
        val viewModel = DiscoverViewModel(recommendations, FakeLibraryRepository())

        composeRule.setContent {
            DiscoverScreen(onNavigateToDetail = {}, viewModel = viewModel)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(FRIEREN.media.title).assertIsDisplayed()
        composeRule.onNodeWithText(BEBOP.media.title).assertIsDisplayed()
        // ONE call, not two: DiscoverViewModel has no `init { refresh() }` any more (this task,
        // round 1 — see that class's own KDoc), so `LifecycleResumeEffect` firing on first
        // composition (`createAndroidComposeRule` launches its Activity straight to RESUMED, and
        // `Lifecycle` replays ON_CREATE/ON_START/ON_RESUME to a freshly-registered observer) is the
        // ONLY thing that loads this screen at all.
        val callsAfterInitialCompose = recommendations.refreshCalls
        assertEquals(1, callsAfterInitialCompose)

        // FRIEREN was added to the library from Search while this screen sat backgrounded on the
        // back stack — the backend's own contract excludes anything already in the library, so the
        // next fetch no longer returns it.
        recommendations.refreshResult = listOf(BEBOP)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(BEBOP.media.title).assertIsDisplayed()
        composeRule.onNodeWithText(FRIEREN.media.title).assertDoesNotExist()
        // The one call this test actually exists to pin: a REAL pause/resume cycle, the shape
        // Android dispatches for an actual Discover -> Search -> add -> Back trip, triggers exactly
        // one more `refresh()` — proving `LifecycleResumeEffect` is wired to a live Lifecycle, not
        // merely present in the source.
        assertEquals(callsAfterInitialCompose + 1, recommendations.refreshCalls)
    }

    private companion object {
        fun media(
            id: String,
            title: String,
            externalId: String,
        ) = Media(
            id = id,
            source = MediaSource.ANILIST,
            externalId = externalId,
            type = MediaType.ANIME,
            title = title,
            year = 2023,
            genres = listOf("fantasy"),
            coverImageUrl = null,
            status = MediaStatus.NOT_YET_AIRED,
            nextEpisodeSeason = null,
            nextEpisodeNumber = null,
            nextEpisodeDate = null,
            daysUntilNextEpisode = null,
        )

        val FRIEREN =
            Recommendation(
                media = media(id = "media-frieren", title = "Frieren", externalId = "154587"),
                reason =
                    RecommendationReason(
                        seedMediaId = "seed-1",
                        seedTitle = "Made in Abyss",
                        matchedGenres = listOf("fantasy"),
                    ),
            )

        val BEBOP =
            Recommendation(
                media = media(id = "media-bebop", title = "Cowboy Bebop", externalId = "1"),
                reason =
                    RecommendationReason(
                        seedMediaId = "seed-2",
                        seedTitle = "Trigun",
                        matchedGenres = listOf("action"),
                    ),
            )
    }
}
