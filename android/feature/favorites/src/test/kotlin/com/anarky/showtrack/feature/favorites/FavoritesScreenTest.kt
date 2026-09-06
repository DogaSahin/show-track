package com.anarky.showtrack.feature.favorites

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import com.anarky.showtrack.core.designsystem.R as DesignSystemR

/**
 * The one user-visible thing this screen can get wrong that no ViewModel test sees (task 9b.4's
 * brief): the empty-state copy. `FavoritesViewModelTest` can only pin that [FavoritesUiState.Success]
 * carries an empty [FavoritesUiState.Success.entries] list; only a composed screen can pin which
 * STRING renders for it.
 *
 * Drives the stateless overload directly, the same shape `LibraryScreenTest`/`DiscoverScreenTest`
 * use. `createComposeRule`, not `createAndroidComposeRule`: no Activity is needed. Robolectric
 * supplies the Android runtime `stringResource` needs; `sdk = 35` is pinned in
 * `src/test/resources/robolectric.properties`.
 */
@RunWith(RobolectricTestRunner::class)
class FavoritesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The regression this guards: "Nothing in your library yet" (`LibraryScreen`'s default-filter
     * empty copy, `library_empty_default`) is FALSE on this screen — a user with a full library
     * and zero favourites would read it as data loss. [LIBRARY_EMPTY_DEFAULT_COPY] is a literal
     * snapshot of that string rather than a dependency on `:feature:library`'s `R` class:
     * `:feature:favorites` does not depend on `:feature:library` even in test sources
     * (architecture rule 1's whole point — decision D-H duplicates the layout precisely so these
     * two modules stay independent), so the comparison value has to be copied in, not imported.
     */
    @Test
    fun `the empty state shows the favourites copy, not the library copy`() {
        composeRule.setContent {
            FavoritesScreen(
                state = FavoritesUiState.Success(entries = emptyList()),
                onRetry = {},
                onLoadMore = {},
                onEntryClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.favorites_empty_message))
            .assertIsDisplayed()

        assertTrue(
            "favorites_empty_message must not read the same as library's default-filter empty copy",
            context.getString(R.string.favorites_empty_message) != LIBRARY_EMPTY_DEFAULT_COPY,
        )
    }

    @Test
    fun `tapping an entry invokes onEntryClick for that entry`() {
        var clicked: LibraryEntry? = null

        composeRule.setContent {
            FavoritesScreen(
                state = FavoritesUiState.Success(entries = listOf(FRIEREN)),
                onRetry = {},
                onLoadMore = {},
                onEntryClick = { clicked = it },
            )
        }

        composeRule.onNodeWithText(FRIEREN.media.title).performClick()

        assertEquals(FRIEREN, clicked)
    }

    /**
     * Decision C-B made real (review finding, round 3): a stale [FavoritesUiState.Success] must
     * show the `StaleDataBanner` ABOVE the rows, not replace them and not render them silently
     * unmarked — either of those was the actual bug this state exists to fix. `StaleDataBanner`'s
     * own retry action is wired straight to [onRetry], the same button `ErrorState`'s uses.
     */
    @Test
    fun `a stale success shows the stale banner above the entries, and its retry invokes onRetry`() {
        var retried = false

        composeRule.setContent {
            FavoritesScreen(
                state = FavoritesUiState.Success(entries = listOf(FRIEREN), isStale = true),
                onRetry = { retried = true },
                onLoadMore = {},
                onEntryClick = {},
            )
        }

        composeRule.onNodeWithText(FRIEREN.media.title).assertIsDisplayed()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.stale_data_notice))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.action_retry))
            .performClick()

        assertTrue(retried)
    }

    @Test
    fun `a non-stale success shows no stale banner`() {
        composeRule.setContent {
            FavoritesScreen(
                state = FavoritesUiState.Success(entries = listOf(FRIEREN), isStale = false),
                onRetry = {},
                onLoadMore = {},
                onEntryClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.stale_data_notice))
            .assertDoesNotExist()
    }

    @Test
    fun `an error state's retry action invokes onRetry`() {
        var retried = false

        composeRule.setContent {
            FavoritesScreen(
                state = FavoritesUiState.Error(IllegalStateException("offline")),
                onRetry = { retried = true },
                onLoadMore = {},
                onEntryClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.action_retry))
            .performClick()

        assertTrue(retried)
    }

    private companion object {
        // A literal snapshot of `feature/library/src/main/res/values/strings.xml`'s
        // `library_empty_default` — see the test above's KDoc for why this is copied rather than
        // imported.
        const val LIBRARY_EMPTY_DEFAULT_COPY = "Nothing in your library yet"

        val FRIEREN =
            LibraryEntry(
                id = "entry-frieren",
                status = UserMediaStatus.WATCHING,
                score = null,
                progress = 3,
                favorite = true,
                updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
                media =
                    Media(
                        id = "media-frieren",
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
