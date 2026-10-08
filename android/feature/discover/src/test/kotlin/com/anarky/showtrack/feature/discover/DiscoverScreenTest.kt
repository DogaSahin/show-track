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
import org.robolectric.annotation.Config
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
 *
 * `@Config(qualifiers = ...)` enlarges Robolectric's virtual display, and it is a consequence of the
 * layout rather than a stylistic pick. `createComposeRule()`'s default root measures a fixed
 * 320x470px in this project — it does NOT auto-size to content — and the large title, the top pick
 * card and a shelf (header, poster, title, caption) already fill a phone-sized screen. A second
 * shelf below them would measure to zero height and be neither displayed nor clickable: Compose
 * cannot route a synthetic tap to a zero-area node, so `performClick()` silently finds nothing
 * rather than throwing. Hence a display taller than any phone. `ProfileScreenTest` records the same
 * finding, for the same reason, one screen over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1400dp")
class DiscoverScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tapping the add action invokes onAdd for that row, not onRowClick`() {
        var added: Recommendation? = null
        var rowClicked = false

        composeRule.setContent {
            DiscoverScreen(
                state = DiscoverUiState.Success(items = listOf(TOP, FRIEREN, BEBOP)),
                onRetry = {},
                onLoadMore = {},
                onAdd = { added = it },
                onRowClick = { rowClicked = true },
                onSearch = {},
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
                state = DiscoverUiState.Success(items = listOf(TOP, FRIEREN, BEBOP)),
                onRetry = {},
                onLoadMore = {},
                onAdd = { added = it },
                onRowClick = { clicked = it },
                onSearch = {},
            )
        }

        composeRule.onNodeWithText(BEBOP.media.title).performClick()

        assertEquals(BEBOP, clicked)
        assertEquals("tapping the row must not also trigger an add", null, added)
    }

    /**
     * The shelf layout's whole claim: a seed is named ONCE over its titles, not repeated under
     * every one of them. Two recommendations sharing a seed must therefore produce exactly one
     * header, and a third with a different seed a second header.
     *
     * This replaces a test that guarded a dangling em dash in the per-row reason line
     * ("Because you watched Frieren — " when matchedGenres was empty). That line no longer exists:
     * the genres left the reason when it became a shelf header, so the defect it guarded is
     * unreachable rather than merely untested.
     *
     * Asserts on the seed TITLES rather than counting header nodes, because the title is what a
     * reader actually uses to tell two shelves apart — a count would still pass if both headers
     * rendered the same seed.
     */
    @Test
    fun `recommendations sharing a seed are gathered under one header`() {
        val alsoFromAbyss = FRIEREN.copy(media = FRIEREN.media.copy(id = "media-other", title = "Sousou"))

        composeRule.setContent {
            DiscoverScreen(
                state = DiscoverUiState.Success(items = listOf(FRIEREN, alsoFromAbyss, BEBOP)),
                onRetry = {},
                onLoadMore = {},
                onAdd = {},
                onRowClick = {},
                onSearch = {},
            )
        }

        composeRule.onNodeWithText(FRIEREN.reason.seedTitle).assertIsDisplayed()
        composeRule.onNodeWithText(BEBOP.reason.seedTitle).assertIsDisplayed()
        composeRule.onNode(hasTitle(FRIEREN.media.title)).assertIsDisplayed()
        composeRule.onNodeWithText(alsoFromAbyss.media.title).assertIsDisplayed()
    }

    /**
     * Grouping is by seed ID, never by seed TITLE. Two seeds can share a display string — a remake
     * and its original routinely do — and grouping on the string would silently fuse two unrelated
     * shelves into one, which is invisible in the rendering and wrong in the data.
     */
    @Test
    fun `two seeds sharing a title stay two shelves`() {
        val sameTitleDifferentSeed =
            BEBOP.copy(reason = BEBOP.reason.copy(seedMediaId = "seed-3", seedTitle = FRIEREN.reason.seedTitle))

        val shelves = listOf(FRIEREN, sameTitleDifferentSeed).toShelves()

        assertEquals(2, shelves.size)
        assertEquals(listOf("seed-1", "seed-3"), shelves.map { it.seedMediaId })
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
                onSearch = {},
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
                onSearch = {},
            )
        }

        composeRule.onNode(hasTitle(FRIEREN.media.title)).assertIsDisplayed()

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
                onSearch = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithText(context.getString(DesignSystemR.string.stale_data_notice))
            .assertDoesNotExist()
    }

    @Test
    fun `the first recommendation is the top pick and is not repeated in its shelf`() {
        val alsoFromAbyss = FRIEREN.copy(media = FRIEREN.media.copy(id = "media-other", title = "Sousou"))

        composeRule.setContent {
            DiscoverScreen(
                state = DiscoverUiState.Success(items = listOf(FRIEREN, alsoFromAbyss)),
                onRetry = {},
                onLoadMore = {},
                onAdd = {},
                onRowClick = {},
                onSearch = {},
            )
        }

        composeRule.onNodeWithContentDescription(FRIEREN.media.title, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(FRIEREN.media.title).assertDoesNotExist()
        composeRule.onNodeWithText(alsoFromAbyss.media.title).assertIsDisplayed()
    }

    @Test
    fun `the top pick's buttons add it and open it`() {
        var added: Recommendation? = null
        var opened: Recommendation? = null

        composeRule.setContent {
            DiscoverScreen(
                state = DiscoverUiState.Success(items = listOf(FRIEREN, BEBOP)),
                onRetry = {},
                onLoadMore = {},
                onAdd = { added = it },
                onRowClick = { opened = it },
                onSearch = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.discover_add_button)).performClick()
        assertEquals(FRIEREN, added)
        assertEquals(null, opened)

        composeRule.onNodeWithText(context.getString(R.string.discover_details_button)).performClick()
        assertEquals(FRIEREN, opened)
    }

    @Test
    fun `a failed add of the top pick shows the error on the card`() {
        composeRule.setContent {
            DiscoverScreen(
                state =
                    DiscoverUiState.Success(
                        items = listOf(FRIEREN, BEBOP),
                        addError = AddFailure(FRIEREN.media.id, IllegalStateException("offline")),
                    ),
                onRetry = {},
                onLoadMore = {},
                onAdd = {},
                onRowClick = {},
                onSearch = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.discover_add_error), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `no recommendations offers a way to search`() {
        var searched = false

        composeRule.setContent {
            DiscoverScreen(
                state = DiscoverUiState.Success(items = emptyList()),
                onRetry = {},
                onLoadMore = {},
                onAdd = {},
                onRowClick = {},
                onSearch = { searched = true },
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(context.getString(R.string.discover_empty_search_button)).performClick()

        assertTrue(searched)
    }

    @Test
    fun `genre chips put matched genres first and stop at four`() {
        val pick =
            FRIEREN.copy(
                media = FRIEREN.media.copy(genres = listOf("Drama", "Comedy", "Fantasy", "Romance", "Adventure")),
                reason = FRIEREN.reason.copy(matchedGenres = listOf("adventure", "fantasy")),
            )

        val chips = pick.genreChips()

        assertEquals(listOf("Fantasy", "Adventure", "Drama", "Comedy"), chips.map { it.name })
        assertEquals(listOf(true, true, false, false), chips.map { it.matched })
    }

    @Test
    fun `a shelf names the two genres its items share most`() {
        fun rec(
            id: String,
            genres: List<String>,
        ) = FRIEREN.copy(
            media = FRIEREN.media.copy(id = id),
            reason = FRIEREN.reason.copy(matchedGenres = genres),
        )
        val shelf =
            listOf(
                rec("a", listOf("Drama", "Fantasy")),
                rec("b", listOf("Fantasy", "Adventure")),
                rec("c", listOf("Adventure", "Fantasy")),
            ).toShelves().single()

        assertEquals(listOf("Fantasy", "Adventure"), shelf.sharedGenres())
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

        val TOP =
            Recommendation(
                media = media(id = "media-top", title = "Mushoku Tensei", externalId = "108465"),
                reason =
                    RecommendationReason(
                        seedMediaId = "seed-9",
                        seedTitle = "Re:Zero",
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
