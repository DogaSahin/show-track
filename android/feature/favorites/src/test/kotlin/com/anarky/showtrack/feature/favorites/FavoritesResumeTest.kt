package com.anarky.showtrack.feature.favorites

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The user-path half of the acceptance criterion (review finding, round 1): favouriting and
 * unfavouriting happen exclusively on Detail or Library (task 9b.4's brief — "no add here"), and
 * [FavoritesViewModel] is scoped to this destination's `NavBackStackEntry`, so `init` never runs
 * again after Favorites -> Detail -> Back. `FavoritesViewModelTest`'s
 * `refresh drops an entry the server no longer returns, once called` proves `refresh()` behaves
 * correctly IF called; this test proves it actually GETS called, on the one signal available
 * (`FavoritesScreen`'s `LifecycleResumeEffect`) — the thing no ViewModel-only test can see.
 *
 * `createAndroidComposeRule<ComponentActivity>()`, not `createComposeRule()`: they launch the
 * identical bare `ComponentActivity` under the hood (`HiltTestActivity`'s own KDoc says so), but
 * only the explicit form exposes `.activityRule.scenario`, which is what lets this test drive the
 * Activity through CREATED -> RESUMED via [androidx.test.core.app.ActivityScenario.moveToState] —
 * the same mechanism Android itself uses under a real "returned from another screen" navigation,
 * rather than reaching into `FavoritesViewModel` directly.
 *
 * No Hilt harness needed, unlike `LibraryEntryHiltTest`: `FavoritesScreen`'s stateful overload
 * takes `viewModel` as an ordinary parameter with a `hiltViewModel()` DEFAULT — passing one
 * explicitly here (a plain [FavoritesViewModel] built against [FakeLibraryRepository]) never
 * evaluates that default, so a bare `ComponentActivity` is enough.
 */
@RunWith(RobolectricTestRunner::class)
class FavoritesResumeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `resuming the screen re-fetches favourites, the way returning from Detail actually does`() {
        val repository = FakeLibraryRepository(refreshResult = listOf(FRIEREN, BEBOP))
        val viewModel = FavoritesViewModel(repository)

        composeRule.setContent {
            FavoritesScreen(onEntryClick = {}, viewModel = viewModel)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(FRIEREN.media.title).assertIsDisplayed()
        composeRule.onNodeWithText(BEBOP.media.title).assertIsDisplayed()
        // ONE call, not two: `FavoritesViewModel` has no `init { refresh() }` (review finding,
        // round 2 — see that class's own KDoc), so `LifecycleResumeEffect` firing on first
        // composition (`createAndroidComposeRule` launches its Activity straight to RESUMED, and
        // `Lifecycle` replays ON_CREATE/ON_START/ON_RESUME to a freshly-registered observer) is
        // the ONLY thing that loads this screen at all — there is no second, redundant call to
        // land on top of it any more.
        val callsAfterInitialCompose = repository.refreshCalls
        assertEquals(1, callsAfterInitialCompose)

        // FRIEREN was unfavourited from Detail while this screen sat backgrounded on the back
        // stack — the server no longer returns it under favorite=true.
        repository.refreshResult = listOf(BEBOP)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(BEBOP.media.title).assertIsDisplayed()
        composeRule.onNodeWithText(FRIEREN.media.title).assertDoesNotExist()
        // The one call this test actually exists to pin: a REAL pause/resume cycle, the shape
        // Android dispatches for an actual Favorites -> Detail -> Back trip, triggers exactly one
        // more `refresh()` — proving `LifecycleResumeEffect` is wired to a live Lifecycle, not
        // merely present in the source.
        assertEquals(callsAfterInitialCompose + 1, repository.refreshCalls)
    }

    private companion object {
        fun entry(
            id: String,
            title: String,
        ) = LibraryEntry(
            id = id,
            status = UserMediaStatus.WATCHING,
            score = null,
            progress = 0,
            favorite = true,
            updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
            media =
                Media(
                    id = "media-$id",
                    source = MediaSource.ANILIST,
                    externalId = id,
                    type = MediaType.ANIME,
                    title = title,
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

        val FRIEREN = entry(id = "frieren", title = "Frieren")
        val BEBOP = entry(id = "bebop", title = "Cowboy Bebop")
    }
}
