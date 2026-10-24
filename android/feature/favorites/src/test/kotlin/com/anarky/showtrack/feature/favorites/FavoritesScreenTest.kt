package com.anarky.showtrack.feature.favorites

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.anarky.showtrack.core.model.LibraryEntry
import com.anarky.showtrack.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.anarky.showtrack.core.designsystem.R as DesignSystemR

/**
 * What the tab renders for each state. The stateless overload, no ViewModel. The display is taller
 * than a phone so the podium and both shelves are laid out at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1400dp")
class FavoritesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `no favourites shows the favourites copy and how to add one`() {
        setScreen(FavoritesUiState.Success(podium = emptyList(), anime = FavoriteShelf(), tv = FavoriteShelf()))

        composeRule.onNodeWithText(context.getString(R.string.favorites_empty_message)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.favorites_empty_hint)).assertIsDisplayed()
    }

    @Test
    fun `the podium reads in rank order and asks for scores when it is not full`() {
        setScreen(SUCCESS)

        // Drawn #2, #1, #3; read #1, #2, #3. The order lives in each place's traversal index.
        val first = composeRule.onNodeWithContentDescription("Number 1, Frieren, score 9.5").fetchSemanticsNode()
        val second = composeRule.onNodeWithContentDescription("Number 2, Severance, score 9.0").fetchSemanticsNode()
        assertTrue(first.config[SemanticsProperties.TraversalIndex] < second.config[SemanticsProperties.TraversalIndex])
        assertTrue(first.boundsInRoot.left > second.boundsInRoot.left)
        composeRule.onNodeWithText(context.getString(R.string.favorites_podium_hint)).assertIsDisplayed()
    }

    @Test
    fun `see all opens that shelf's grid, and a shelf with no favourites is hidden`() {
        var seeAll: MediaType? = null
        setScreen(SUCCESS.copy(tv = FavoriteShelf()), onSeeAll = { seeAll = it })

        composeRule.onNodeWithText(context.getString(R.string.favorites_row_tv)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.favorites_see_all)).performClick()

        assertEquals(MediaType.ANIME, seeAll)
    }

    @Test
    fun `tapping a shelf poster opens it`() {
        var opened: LibraryEntry? = null
        setScreen(SUCCESS, onOpen = { opened = it })

        composeRule.onNodeWithText(UNSCORED.media.title).performClick()

        assertEquals(UNSCORED, opened)
    }

    @Test
    fun `a stale screen keeps its rows under the banner, and the banner retries`() {
        var retried = false
        setScreen(SUCCESS.copy(isStale = true), onRetry = { retried = true })

        composeRule.onNodeWithText(UNSCORED.media.title).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(DesignSystemR.string.action_retry)).performClick()

        assertTrue(retried)
    }

    @Test
    fun `an error offers retry`() {
        var retried = false
        setScreen(FavoritesUiState.Error(IllegalStateException("offline")), onRetry = { retried = true })

        composeRule.onNodeWithText(context.getString(R.string.favorites_error_message)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(DesignSystemR.string.action_retry)).performClick()

        assertTrue(retried)
    }

    private fun setScreen(
        state: FavoritesUiState,
        onRetry: () -> Unit = {},
        onOpen: (LibraryEntry) -> Unit = {},
        onSeeAll: (MediaType) -> Unit = {},
    ) {
        composeRule.setContent {
            FavoritesScreen(
                state = state,
                onRetry = onRetry,
                onLoadMore = {},
                onOpen = onOpen,
                onRemove = {},
                onSeeAll = onSeeAll,
            )
        }
    }

    private companion object {
        val FRIEREN = favourite(id = "frieren", title = "Frieren: Beyond Journey's End", score = "9.5")
        val SEVERANCE = favourite(id = "severance", title = "Severance", type = MediaType.TV, score = "9.0")
        val UNSCORED = favourite(id = "unscored", title = "Mushishi")
        val SUCCESS =
            FavoritesUiState.Success(
                podium = listOf(FRIEREN, SEVERANCE),
                anime = FavoriteShelf(entries = listOf(FRIEREN, UNSCORED)),
                tv = FavoriteShelf(entries = listOf(SEVERANCE)),
            )
    }
}
