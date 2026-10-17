package com.anarky.showtrack.feature.discover

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
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
import org.robolectric.annotation.Config

/**
 * Discover asks for the next page only when a user scroll ends near an end. A trigger keyed on
 * "the end is visible" fires on open whenever there are few shelves, and again after every page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1400dp")
class DiscoverPagingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var loadMoreCalls = 0

    private fun show(items: List<Recommendation>) {
        composeRule.setContent {
            DiscoverScreen(
                state = DiscoverUiState.Success(items = items),
                onRetry = {},
                onLoadMore = { loadMoreCalls++ },
                onAdd = {},
                onRowClick = {},
                onSearch = {},
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `opening Discover loads nothing more`() {
        show(feed(shelves = 2, perShelf = 3))

        assertEquals(0, loadMoreCalls)
    }

    @Test
    fun `a page arriving without a scroll loads nothing more`() {
        var items by mutableStateOf(feed(shelves = 2, perShelf = 3))
        composeRule.setContent {
            DiscoverScreen(
                state = DiscoverUiState.Success(items = items),
                onRetry = {},
                onLoadMore = { loadMoreCalls++ },
                onAdd = {},
                onRowClick = {},
                onSearch = {},
            )
        }
        composeRule.waitForIdle()

        items = feed(shelves = 2, perShelf = 6)
        composeRule.waitForIdle()

        assertEquals(0, loadMoreCalls)
    }

    @Test
    fun `a scroll that ends far from the end loads nothing more`() {
        show(feed(shelves = 14, perShelf = 3))

        composeRule
            .onNodeWithTag(DISCOVER_LIST_TAG)
            .performTouchInput { swipeUp(startY = centerY, endY = centerY - 150f) }
        composeRule.waitForIdle()

        assertEquals(0, loadMoreCalls)
    }

    @Test
    fun `scrolling down to the last shelves asks for more`() {
        show(feed(shelves = 14, perShelf = 3))

        repeat(SWIPES_TO_THE_END) {
            composeRule.onNodeWithTag(DISCOVER_LIST_TAG).performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }

        assertTrue("a scroll ending near the end must ask for the next page", loadMoreCalls >= 1)
    }

    @Test
    fun `scrolling a shelf to its end asks for more`() {
        show(feed(shelves = 2, perShelf = 15))

        repeat(SWIPES_TO_THE_END) {
            composeRule.onNodeWithTag(discoverShelfTag("seed-1")).performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
        }

        assertTrue("a shelf scrolled to its end must ask for the next page", loadMoreCalls >= 1)
    }

    private companion object {
        const val SWIPES_TO_THE_END = 8

        /** The first item becomes the top pick; the rest group into one shelf per seed. */
        fun feed(
            shelves: Int,
            perShelf: Int,
        ): List<Recommendation> =
            (0 until shelves).flatMap { s ->
                (0 until perShelf).map { i -> recommendation(seed = s, index = i) }
            }

        fun recommendation(
            seed: Int,
            index: Int,
        ) = Recommendation(
            media =
                Media(
                    id = "media-$seed-$index",
                    source = MediaSource.ANILIST,
                    externalId = "$seed$index",
                    type = MediaType.ANIME,
                    title = "Title $seed-$index",
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
                    seedMediaId = "seed-$seed",
                    seedTitle = "Seed $seed",
                    matchedGenres = listOf("fantasy"),
                ),
        )
    }
}
