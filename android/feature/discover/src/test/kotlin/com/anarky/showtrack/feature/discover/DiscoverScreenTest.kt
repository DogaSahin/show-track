package com.anarky.showtrack.feature.discover

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.Recommendation
import com.anarky.showtrack.core.model.RecommendationReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.anarky.showtrack.core.designsystem.R as DesignSystemR

/**
 * The regression guard for the one UX inversion this screen makes on purpose: unlike
 * `LibraryScreen`/`SearchScreen`, tapping a ROW here navigates rather than acts — the recommendation
 * carries a real media id (unlike a search result), so there is no add-first workaround, and the
 * separate ADD affordance is what decision D-I's one-tap add actually fires. A regression that wired
 * the row's own `onClick` to `onAdd` (or vice versa) would compile clean and read correctly at a
 * glance; only exercising both taps independently catches it.
 *
 * `createComposeRule`, not `createAndroidComposeRule`: no Activity is needed to render the
 * stateless overload in isolation — `LibraryScreenTest`'s own reasoning.
 */
@RunWith(RobolectricTestRunner::class)
class DiscoverScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tapping the add action invokes onAdd for that row, not onRowClick`() {
        var added: Recommendation? = null
        var rowClicked = false

        composeRule.setContent {
            DiscoverScreen(
                state = DiscoverUiState.Success(items = listOf(FRIEREN, BEBOP)),
                onRetry = {},
                onLoadMore = {},
                onAdd = { added = it },
                onRowClick = { rowClicked = true },
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.discover_add_content_description, FRIEREN.media.title),
            ).performClick()

        assertEquals(FRIEREN, added)
        assertTrue("tapping the add action must not also count as a row click", !rowClicked)
    }

    @Test
    fun `tapping the row invokes onRowClick for that row, not onAdd`() {
        var added: Recommendation? = null
        var clicked: Recommendation? = null

        composeRule.setContent {
            DiscoverScreen(
                state = DiscoverUiState.Success(items = listOf(FRIEREN, BEBOP)),
                onRetry = {},
                onLoadMore = {},
                onAdd = { added = it },
                onRowClick = { clicked = it },
            )
        }

        composeRule.onNodeWithText(BEBOP.media.title).performClick()

        assertEquals(BEBOP, clicked)
        assertEquals("tapping the row must not also trigger an add", null, added)
    }

    /**
     * Fix round 1, finding 4: a row whose reason carries no matched genres used to render "Because
     * you watched Frieren — " — a dangling em dash with nothing after it. The subtitle line
     * immediately above already guards `media.genres` with `takeIf { it.isNotEmpty() }`; the reason
     * line needed the same guard and did not have it.
     */
    @Test
    fun `a reason with no matched genres renders without a dangling em dash`() {
        val noGenres = FRIEREN.copy(reason = FRIEREN.reason.copy(matchedGenres = emptyList()))

        composeRule.setContent {
            DiscoverScreen(
                state = DiscoverUiState.Success(items = listOf(noGenres)),
                onRetry = {},
                onLoadMore = {},
                onAdd = {},
                onRowClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(R.string.discover_reason, noGenres.reason.seedTitle))
            .assertIsDisplayed()
    }

    @Test
    fun `an error state's retry action invokes onRetry`() {
        var retried = false

        composeRule.setContent {
            DiscoverScreen(
                state = DiscoverUiState.Error(IllegalStateException("offline")),
                onRetry = { retried = true },
                onLoadMore = {},
                onAdd = {},
                onRowClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.action_retry))
            .performClick()

        assertTrue(retried)
    }

    /**
     * Task 9c.8, E-M: a stale [DiscoverUiState.Success] must show the `StaleDataBanner` ABOVE the
     * rows, not replace them and not render them silently unmarked — `FavoritesScreenTest`'s
     * identical regression guard for `FavoritesUiState.Success.isStale`, applied here now that
     * Discover has the same field.
     */
    @Test
    fun `a stale success shows the stale banner above the rows, and its retry invokes onRetry`() {
        var retried = false

        composeRule.setContent {
            DiscoverScreen(
                state = DiscoverUiState.Success(items = listOf(FRIEREN), isStale = true),
                onRetry = { retried = true },
                onLoadMore = {},
                onAdd = {},
                onRowClick = {},
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
            DiscoverScreen(
                state = DiscoverUiState.Success(items = listOf(FRIEREN), isStale = false),
                onRetry = {},
                onLoadMore = {},
                onAdd = {},
                onRowClick = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.stale_data_notice))
            .assertDoesNotExist()
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
